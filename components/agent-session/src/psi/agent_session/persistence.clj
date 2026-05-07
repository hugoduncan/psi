(ns psi.agent-session.persistence
  "Session entry journal — append-only in-memory log with disk write.

   Format: NDEDN — one EDN map per line (newline-delimited EDN).

   In-memory journal
   ─────────────────
   The canonical journal lives in ctx `:state*` at the per-session path
   [:agent-session :sessions sid :persistence :journal].
   Low-level atom-oriented helpers remain for focused persistence tests.
   Runtime code should prefer the ctx-based `*-in` functions.

   Disk write (lazy flush)
   ───────────────────────
   Entries are held in memory until the first assistant message arrives.
   At that point the full journal (header + all entries) is written at
   once.  Subsequent entries are appended one line at a time.

   Cross-process safety
   ────────────────────
   Every write path acquires an exclusive sidecar file lock
   (<session-file>.lock) before mutating the session file.

   The flush state is tracked in a separate atom returned by
   `create-flush-state`.  Both atoms live in the session context.

   Session directory layout
   ────────────────────────
   ~/.psi/agent/sessions/--<encoded-cwd>--/<timestamp>_<uuid>.ndedn

   cwd encoding: strip leading slash, replace / and : with -.

   Migration
   ─────────
   v1 — no :version, no :id/:parent-id on entries
        → add linear :id/:parent-id chain
   v2 — :version 2, entries have :id/:parent-id
        → rename :hook-message role to :custom in message entries
   v3 — header has :parent-session path hint only
        → add :parent-session-id in header
   v4 — current

   Public API
   ──────────
   Journal (in-memory):
     create-journal, append-entry!, all-entries, entries-of-kind,
     entries-up-to, last-entry-of-kind, messages-from-entries,
     messages-up-to

   Flush state:
     create-flush-state

   Disk write:
     session-dir-for, new-session-file-path,
     write-header!, append-entry-to-disk!, flush-journal!

   Disk read:
     load-session-file, find-most-recent-session,
     list-sessions, list-all-sessions

   Entry constructors:
     message-entry, thinking-level-entry, model-entry, compaction-entry,
     branch-summary-entry, custom-message-entry, label-entry,
     session-info-entry"
  (:require
   [psi.session-journal.store :as journal-store]
   [psi.session-state.model :as session-model]
   [psi.session-state.state :as session-state]))

;;; ============================================================
;;; Session-facing state helpers
;;; ============================================================

(def ^:dynamic *session-file-lock-retry-ms*
  journal-store/*session-file-lock-retry-ms*)
(def ^:dynamic *session-file-lock-max-attempts*
  journal-store/*session-file-lock-max-attempts*)

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

(defn- journal-path
  "Build the per-session journal path for `session-id` in ctx."
  [_ctx session-id]
  (session-state/state-path :journal session-id))

(defn- flush-state-path
  "Build the per-session flush-state path for `session-id` in ctx."
  [_ctx session-id]
  (session-state/state-path :flush-state session-id))

(defn- state*
  [ctx]
  (:state* ctx))

(defn- get-state-in
  [ctx path]
  (get-in @(state* ctx) path))

(defn- assoc-state-in!
  [ctx path value]
  (swap! (state* ctx) assoc-in path value))

;;; ============================================================
;;; Journal operations (in-memory atom)
;;; ============================================================

(defn create-journal
  "Create a fresh journal atom (vector of SessionEntry maps)."
  []
  (atom []))

(defn append-entry!
  "Append `entry` to `journal-atom` atomically. Returns `entry`."
  [journal-atom entry]
  (swap! journal-atom conj entry)
  entry)

(defn all-entries
  "Return all entries from `journal-atom` as a vector."
  [journal-atom]
  @journal-atom)

(defn entries-of-kind
  "Return all entries of `kind` keyword from `journal-atom`."
  [journal-atom kind]
  (filterv #(= (:kind %) kind) @journal-atom))

(defn entries-up-to
  "Return all entries up to and including the entry with `entry-id`.
  Returns the full journal if `entry-id` is nil or not found."
  [journal-atom entry-id]
  (if (nil? entry-id)
    @journal-atom
    (let [entries @journal-atom
          idx     (first (keep-indexed #(when (= (:id %2) entry-id) %1) entries))]
      (if idx
        (subvec entries 0 (inc idx))
        entries))))

(defn last-entry-of-kind
  "Return the most recent entry of `kind` from `journal-atom`, or nil."
  [journal-atom kind]
  (last (entries-of-kind journal-atom kind)))

(defn messages-from-entries
  "Extract agent message maps from all :message entries in the journal."
  [journal-atom]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        @journal-atom))

(defn messages-up-to
  "Extract agent messages from journal entries up to `entry-id`."
  [journal-atom entry-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (entries-up-to journal-atom entry-id)))

(declare persist-entry-in!)

(defn append-entry-in!
  "Append `entry` to the canonical journal for `session-id` in ctx and preserve
   existing persistence semantics. Returns `entry`."
  [ctx session-id entry]
  (session-state/append-journal-entry-in! ctx session-id entry)
  (let [sd (session-state/get-session-data-in ctx session-id)]
    (persist-entry-in! ctx
                       session-id
                       (:worktree-path sd)
                       (:parent-session-id sd)
                       (:parent-session-path sd)))
  entry)

(defn- entry-coll
  [x]
  (cond
    (vector? x)     x
    (sequential? x) x
    :else           []))

(defn all-entries-in
  "Return all canonical journal entries for `session-id` from ctx as a vector."
  [ctx session-id]
  (-> (get-state-in ctx (journal-path ctx session-id))
      entry-coll
      vec))

(defn entries-of-kind-in
  "Return canonical journal entries of `kind` for `session-id` from ctx."
  [ctx session-id kind]
  (filterv #(= (:kind %) kind) (all-entries-in ctx session-id)))

(defn entries-up-to-in
  "Return canonical journal entries for `session-id` up to and including `entry-id`.
   Returns full journal if `entry-id` is nil or not found."
  [ctx session-id entry-id]
  (let [entries (all-entries-in ctx session-id)]
    (if (nil? entry-id)
      entries
      (let [idx (first (keep-indexed #(when (= (:id %2) entry-id) %1) entries))]
        (if idx
          (subvec entries 0 (inc idx))
          entries)))))

(defn last-entry-of-kind-in
  "Return the most recent canonical journal entry of `kind` for `session-id`, or nil."
  [ctx session-id kind]
  (last (entries-of-kind-in ctx session-id kind)))

(defn messages-from-entries-in
  "Extract agent messages from canonical journal message entries for `session-id` in ctx."
  [ctx session-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (all-entries-in ctx session-id)))

(defn messages-up-to-in
  "Extract agent messages from canonical journal entries for `session-id` up to `entry-id`."
  [ctx session-id entry-id]
  (keep (fn [entry]
          (when (= (:kind entry) :message)
            (get-in entry [:data :message])))
        (entries-up-to-in ctx session-id entry-id)))

;;; ============================================================
;;; Flush state
;;; ============================================================

(defn create-flush-state
  "Create a flush-state atom.
  {:flushed? false :session-file nil}
  :flushed? — true once the initial bulk write has happened
  :session-file — java.io.File for the session file, or nil"
  []
  (atom {:flushed? false :session-file nil}))

(defn persist-state-entry!
  "Persist journal `entries` using plain flush-state data.
   Calls `save-flush-state!` with the updated flush-state when it changes."
  ([entries flush-state session-id cwd parent-session-path save-flush-state!]
   (persist-state-entry! entries flush-state session-id cwd nil parent-session-path save-flush-state!))
  ([entries flush-state session-id cwd parent-session-id parent-session-path save-flush-state!]
   (let [{:keys [flushed? session-file]} flush-state]
     (when session-file
       (let [has-assistant (some (fn [e]
                                   (and (= :message (:kind e))
                                        (= "assistant" (get-in e [:data :message :role]))))
                                 entries)]
         (when has-assistant
           (if flushed?
             (append-entry-to-disk! session-file (last entries))
             (do
               (flush-journal! session-file session-id cwd parent-session-id parent-session-path entries)
               (when save-flush-state!
                 (save-flush-state! (assoc flush-state :flushed? true)))))))))))

;;; ============================================================
;;; Persist-on-append (called by core.clj after append-entry!)
;;; ============================================================

(defn persist-entry!
  "Conditionally write `entry` to disk.

  Rules:
    - If :session-file is nil → no-op (in-memory only mode).
    - If the journal has no assistant message yet → no-op (lazy flush).
    - If not yet :flushed? → bulk-write header + all entries, set :flushed? true.
    - Otherwise → append single entry line.

  `journal-atom`   — the session journal atom
  `flush-state-atom` — atom from create-flush-state
  `session-id`     — string
  `cwd`            — string
  `parent-session-id` — string or nil
  `parent-session-path` — string or nil"
  ([journal-atom flush-state-atom session-id cwd parent-session-path]
   (persist-entry! journal-atom flush-state-atom session-id cwd nil parent-session-path))
  ([journal-atom flush-state-atom session-id cwd parent-session-id parent-session-path]
   (let [{:keys [flushed? session-file]} @flush-state-atom]
     (when session-file
       (let [entries       @journal-atom
             has-assistant (some (fn [e]
                                   (and (= :message (:kind e))
                                        (= "assistant" (get-in e [:data :message :role]))))
                                 entries)]
         (when has-assistant
           (if flushed?
             (append-entry-to-disk! session-file (last entries))
             (do
               (flush-journal! session-file session-id cwd parent-session-id parent-session-path entries)
               (swap! flush-state-atom assoc :flushed? true)))))))))

(defn persist-entry-in!
  "Ctx-based runtime persistence helper.

   Reads journal + flush-state from canonical ctx state and writes any flush-state
   transition back to canonical state."
  ([ctx session-id cwd parent-session-path]
   (persist-entry-in! ctx session-id cwd nil parent-session-path))
  ([ctx session-id cwd parent-session-id parent-session-path]
   (let [fp           (flush-state-path ctx session-id)
         flush-state  (get-state-in ctx fp)
         session-file (:session-file flush-state)
         entries      (all-entries-in ctx session-id)]
     (when session-file
       (let [has-assistant (some (fn [e]
                                   (and (= :message (:kind e))
                                        (= "assistant" (get-in e [:data :message :role]))))
                                 entries)]
         (when has-assistant
           (if (:flushed? flush-state)
             (append-entry-to-disk! session-file (last entries))
             (do
               (flush-journal! session-file session-id cwd parent-session-id parent-session-path entries)
               (assoc-state-in! ctx fp (assoc flush-state :flushed? true))))))))))

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
                            {:summary             (:summary result)
                             :first-kept-entry-id (:first-kept-entry-id result)
                             :tokens-before       (:tokens-before result)
                             :details             (:details result)
                             :from-hook           (boolean from-hook?)}))

(defn branch-summary-entry [from-id summary details label from-hook?]
  (session-model/make-entry :branch-summary
                            {:from-id   from-id
                             :summary   summary
                             :details   details
                             :label     label
                             :from-hook (boolean from-hook?)}))

(defn custom-message-entry [custom-type content details display?]
  (session-model/make-entry :custom-message
                            {:custom-type custom-type
                             :content     content
                             :details     details
                             :display     (boolean display?)}))

(defn label-entry [target-id label]
  (session-model/make-entry :label {:target-id target-id :label label}))

(defn session-info-entry [name]
  (session-model/make-entry :session-info {:name name}))
