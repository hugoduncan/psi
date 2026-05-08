(ns psi.session-persistence.core
  "Canonical session-facing journal and persistence ownership.

   Owns the persistence subtree shape, persistence-specific session paths,
   ctx-based append/persist semantics, semantic journal-entry constructors,
   and session-file store wrappers over `psi.session-journal.store`."
  (:require
   [psi.session-journal.store :as journal-store]
   [psi.session-state.model :as session-model]))

;;; ============================================================
;;; Persistence subtree paths + constructors
;;; ============================================================

(defn session-journal-path
  [session-id]
  [:agent-session :sessions session-id :persistence :journal])

(defn session-flush-state-path
  [session-id]
  [:agent-session :sessions session-id :persistence :flush-state])

(defn flush-state
  ([]
   (flush-state nil false))
  ([session-file flushed?]
   {:flushed? flushed?
    :session-file session-file}))

(defn create-flush-state
  "Compatibility/testing helper that returns the canonical flush-state in an atom."
  []
  (atom (flush-state)))

(defn persistence-state
  ([]
   (persistence-state {}))
  ([{:keys [journal session-file flushed?]
     :or {journal []
          session-file nil
          flushed? false}}]
   {:journal (vec (or journal []))
    :flush-state (flush-state session-file flushed?)}))

(defn assoc-persistence-state
  [state session-id persistence]
  (assoc-in state [:agent-session :sessions session-id :persistence] persistence))

(defn initialize-persistence-state
  [state session-id opts]
  (assoc-persistence-state state session-id (persistence-state opts)))

(defn- state*
  [ctx]
  (:state* ctx))

(defn- get-state-in
  [ctx path]
  (get-in @(state* ctx) path))

(defn- assoc-state-in!
  [ctx path value]
  (swap! (state* ctx) assoc-in path value))

(defn append-journal-entry-root-update
  [session-id entry]
  (fn [state]
    (update-in state (session-journal-path session-id) (fnil conj []) entry)))

(defn mark-flushed-root-update
  [session-id]
  (fn [state]
    (assoc-in state (conj (session-flush-state-path session-id) :flushed?) true)))

(defn append-journal-entry-in!
  [ctx session-id entry]
  (swap! (state* ctx) (append-journal-entry-root-update session-id entry))
  entry)

;;; ============================================================
;;; Session-facing state helpers
;;; ============================================================

(defn session-dir-for
  [& args]
  (apply journal-store/session-dir-for args))

(defn new-session-file-path
  [session-dir session-id]
  (journal-store/new-session-file-path session-dir session-id))

(defn write-header!
  [& args]
  (apply journal-store/write-header! args))

(defn append-entry-to-disk!
  [file entry]
  (journal-store/append-entry-to-disk! file entry))

(defn flush-journal!
  [& args]
  (apply journal-store/flush-journal! args))

(defn load-session-file
  [file]
  (journal-store/load-session-file file))

(defn find-most-recent-session
  [session-dir]
  (journal-store/find-most-recent-session session-dir))

(defn list-sessions
  [session-dir]
  (journal-store/list-sessions session-dir))

(defn list-all-sessions
  ([]
   (journal-store/list-all-sessions))
  ([root]
   (journal-store/list-all-sessions root)))

;;; ============================================================
;;; Journal operations (in-memory atom)
;;; ============================================================

(defn create-journal
  []
  (atom []))

(defn append-entry!
  [journal-atom entry]
  (swap! journal-atom conj entry)
  entry)

(defn all-entries
  [journal-atom]
  @journal-atom)

(defn entries-of-kind
  [journal-atom kind]
  (filterv #(= (:kind %) kind) @journal-atom))

(defn entries-up-to
  [journal-atom entry-id]
  (if (nil? entry-id)
    @journal-atom
    (let [entries @journal-atom
          idx (first (keep-indexed #(when (= (:id %2) entry-id) %1) entries))]
      (if idx
        (subvec entries 0 (inc idx))
        entries))))

(defn last-entry-of-kind
  [journal-atom kind]
  (last (entries-of-kind journal-atom kind)))

(defn messages-from-entries
  [journal-atom]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        @journal-atom))

(defn messages-up-to
  [journal-atom entry-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (entries-up-to journal-atom entry-id)))

(declare persist-journal-in!)

(defn append-entry-in!
  "Compatibility alias. Prefer handler-owned append + explicit persistence IO effects."
  [ctx session-id entry]
  (append-journal-entry-in! ctx session-id entry)
  (let [sd (get-state-in ctx [:agent-session :sessions session-id :data])]
    (persist-journal-in! ctx
                         session-id
                         (:worktree-path sd)
                         (:parent-session-id sd)
                         (:parent-session-path sd)))
  entry)

(defn- entry-coll
  [x]
  (cond
    (vector? x) x
    (sequential? x) x
    :else []))

(defn all-entries-in
  [ctx session-id]
  (-> (get-state-in ctx (session-journal-path session-id))
      entry-coll
      vec))

(defn entries-of-kind-in
  [ctx session-id kind]
  (filterv #(= (:kind %) kind) (all-entries-in ctx session-id)))

(defn entries-up-to-in
  [ctx session-id entry-id]
  (let [entries (all-entries-in ctx session-id)]
    (if (nil? entry-id)
      entries
      (let [idx (first (keep-indexed #(when (= (:id %2) entry-id) %1) entries))]
        (if idx
          (subvec entries 0 (inc idx))
          entries)))))

(defn last-entry-of-kind-in
  [ctx session-id kind]
  (last (entries-of-kind-in ctx session-id kind)))

(defn messages-from-entries-in
  [ctx session-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (all-entries-in ctx session-id)))

(defn messages-up-to-in
  [ctx session-id entry-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (entries-up-to-in ctx session-id entry-id)))

(defn- assistant-message-entry?
  [entry]
  (and (= :message (:kind entry))
       (= "assistant" (get-in entry [:data :message :role]))))

(defn- has-assistant-message?
  [entries]
  (some assistant-message-entry? entries))

(defn persistence-io-request
  [{:keys [entries flush-state session-id worktree-path parent-session-id parent-session-path]
    :as _request}]
  (let [{:keys [flushed? session-file]} flush-state
        entries (vec (or entries []))]
    (when (and session-file (has-assistant-message? entries))
      (if flushed?
        {:op :append-entry
         :session-id session-id
         :session-file session-file
         :worktree-path worktree-path
         :parent-session-id parent-session-id
         :parent-session-path parent-session-path
         :entry (last entries)}
        {:op :flush-journal
         :session-id session-id
         :session-file session-file
         :worktree-path worktree-path
         :parent-session-id parent-session-id
         :parent-session-path parent-session-path
         :entries entries}))))

;;; ============================================================
;;; Flush + persist semantics
;;; ============================================================

(defn persist-state-entry!
  ([entries flush-state session-id cwd parent-session-path save-flush-state!]
   (persist-state-entry! entries flush-state session-id cwd nil parent-session-path save-flush-state!))
  ([entries flush-state session-id cwd parent-session-id parent-session-path save-flush-state!]
   (when-let [{:keys [op session-file entry entries]}
              (persistence-io-request {:entries entries
                                       :flush-state flush-state
                                       :session-id session-id
                                       :worktree-path cwd
                                       :parent-session-id parent-session-id
                                       :parent-session-path parent-session-path})]
     (case op
       :append-entry
       (append-entry-to-disk! session-file entry)

       :flush-journal
       (do
         (flush-journal! session-file session-id cwd parent-session-id parent-session-path entries)
         (when save-flush-state!
           (save-flush-state! (assoc flush-state :flushed? true))))))))

(defn persist-entry!
  ([journal-atom flush-state-atom session-id cwd parent-session-path]
   (persist-entry! journal-atom flush-state-atom session-id cwd nil parent-session-path))
  ([journal-atom flush-state-atom session-id cwd parent-session-id parent-session-path]
   (persist-state-entry! @journal-atom
                         @flush-state-atom
                         session-id
                         cwd
                         parent-session-id
                         parent-session-path
                         #(reset! flush-state-atom %))))

(defn persist-journal-in!
  ([ctx session-id cwd parent-session-path]
   (persist-journal-in! ctx session-id cwd nil parent-session-path))
  ([ctx session-id cwd parent-session-id parent-session-path]
   (let [fp (session-flush-state-path session-id)
         flush-state (get-state-in ctx fp)
         entries (all-entries-in ctx session-id)]
     (persist-state-entry! entries
                           flush-state
                           session-id
                           cwd
                           parent-session-id
                           parent-session-path
                           #(assoc-state-in! ctx fp %)))))

(defn persist-entry-in!
  "Compatibility alias. Prefer `persist-journal-in!`."
  ([ctx session-id cwd parent-session-path]
   (persist-journal-in! ctx session-id cwd parent-session-path))
  ([ctx session-id cwd parent-session-id parent-session-path]
   (persist-journal-in! ctx session-id cwd parent-session-id parent-session-path)))

;;; ============================================================
;;; Convenience entry constructors
;;; ============================================================

(defn message-entry [message]
  (session-model/make-entry :message {:message message}))

(defn thinking-level-entry [level]
  (session-model/make-entry :thinking-level {:thinking-level level}))

(defn model-entry [provider model-id]
  (session-model/make-entry :model {:provider provider :model-id model-id}))

(defn compaction-entry [result from-hook?]
  (session-model/make-entry :compaction
                            {:summary (:summary result)
                             :first-kept-entry-id (:first-kept-entry-id result)
                             :tokens-before (:tokens-before result)
                             :details (:details result)
                             :from-hook (boolean from-hook?)}))

(defn branch-summary-entry [from-id summary details label from-hook?]
  (session-model/make-entry :branch-summary
                            {:from-id from-id
                             :summary summary
                             :details details
                             :label label
                             :from-hook (boolean from-hook?)}))

(defn custom-message-entry [custom-type content details display?]
  (session-model/make-entry :custom-message
                            {:custom-type custom-type
                             :content content
                             :details details
                             :display (boolean display?)}))

(defn label-entry [target-id label]
  (session-model/make-entry :label {:target-id target-id :label label}))

(defn session-info-entry [name]
  (session-model/make-entry :session-info {:name name}))
