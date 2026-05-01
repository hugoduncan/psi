(ns psi.agent-session.session-lifecycle
  "Session lifecycle operations — create, resume, fork, switch, close."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.agent-session.compaction :as compaction]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session-close :as session-close]
   [psi.agent-session.session-runtime :as runtime]
   [psi.agent-session.session-state :as session]
   [psi.agent-session.workflows :as wf]))

(defn- initialize-top-level-session!
  [ctx source-session-id {:keys [dispatch-event session-name worktree-path scheduled-origin-session-id scheduled-from-schedule-id scheduled-from-label]}]
  (let [new-session-id (str (java.util.UUID/randomUUID))
        source-sd      (session/get-session-data-in ctx source-session-id)
        worktree-path  (or worktree-path
                           (:worktree-path source-sd)
                           (:worktree-path (:session-defaults ctx))
                           (:cwd ctx))
        session-file   (when (:persist? ctx)
                         (let [session-dir (persist/session-dir-for worktree-path)
                               file        (persist/new-session-file-path session-dir new-session-id)]
                           (str file)))]
    (dispatch/dispatch! ctx
                        dispatch-event
                        {:session-id source-session-id
                         :new-session-id new-session-id
                         :worktree-path worktree-path
                         :session-name session-name
                         :session-file session-file
                         :scheduled-origin-session-id scheduled-origin-session-id
                         :scheduled-from-schedule-id scheduled-from-schedule-id
                         :scheduled-from-label scheduled-from-label}
                        {:origin :core})
    (let [sd    (session/get-session-data-in ctx new-session-id)
          fresh (runtime/create-runtime!
                 ctx new-session-id
                 {:session-data  sd
                  :messages      []
                  :agent-initial (:agent-initial ctx)})]
      (swap! (:state* ctx)
             (fn [state]
               (-> state
                   (assoc-in [:agent-session :sessions new-session-id :agent-ctx] (:agent-ctx fresh))
                   (assoc-in [:agent-session :sessions new-session-id :sc-session-id] (:sc-session-id fresh))))))
    (dispatch/dispatch! ctx :session/ensure-base-system-prompt {:session-id new-session-id} {:origin :core})
    (dispatch/dispatch! ctx :session/retarget-runtime-prompt-metadata {:session-id new-session-id} {:origin :core})
    (when session-name
      (session/journal-append-in! ctx new-session-id (persist/session-info-entry session-name)))
    (session/journal-append-in! ctx new-session-id
                                (persist/thinking-level-entry
                                 (:thinking-level (session/get-session-data-in ctx new-session-id))))
    (when-let [model (:model (session/get-session-data-in ctx new-session-id))]
      (session/journal-append-in! ctx new-session-id (persist/model-entry (:provider model) (:id model))))
    (session/get-session-data-in ctx new-session-id)))

(defn new-session-in!
  "Start a fresh session (resets agent and session data).
  Dispatches session_before_switch / session_switch extension events.

   opts:
   - :spawn-mode (keyword) default :new-root"
  [ctx source-session-id opts]
  (let [reg                  (:extension-registry ctx)
        {:keys [cancelled?]} (ext/dispatch-in reg "session_before_switch" {:reason :new})]
    (when-not cancelled?
      (wf/clear-all-in! (:workflow-registry ctx))
      (let [sd (initialize-top-level-session! ctx source-session-id {:dispatch-event :session/new-initialize
                                                                     :session-name (:session-name opts)
                                                                     :worktree-path (:worktree-path opts)})]
        (ext/dispatch-in reg "session_switch" {:reason :new})
        sd))))

(defn create-top-level-session-in!
  "Create a fresh top-level session without changing active/focus semantics.
   Reuses canonical top-level session initialization but does not dispatch switch-oriented extension events."
  [ctx source-session-id {:keys [session-name worktree-path scheduled-origin-session-id scheduled-from-schedule-id scheduled-from-label]}]
  (initialize-top-level-session! ctx source-session-id {:dispatch-event :session/create-top-level
                                                        :session-name session-name
                                                        :worktree-path worktree-path
                                                        :scheduled-origin-session-id scheduled-origin-session-id
                                                        :scheduled-from-schedule-id scheduled-from-schedule-id
                                                        :scheduled-from-label scheduled-from-label}))

(defn resume-session-in!
  "Load and resume a session from `session-path`.
  Parses the NDEDN file, migrates if needed, rebuilds the agent message list,
  and restores model/thinking-level from the journal."
  [ctx source-session-id session-path]
  (let [reg                  (:extension-registry ctx)
        {:keys [cancelled?]} (ext/dispatch-in reg "session_before_switch" {:reason :resume})]
    (when-not cancelled?
      (wf/clear-all-in! (:workflow-registry ctx))
      (let [loaded (persist/load-session-file session-path)]
        (if-not loaded
          (do
            (dispatch/dispatch! ctx
                                :session/resume-missing-initialize
                                {:session-id   source-session-id
                                 :session-path session-path}
                                {:origin :core})
            (ext/dispatch-in reg "session_switch" {:reason :resume})
            (session/get-session-data-in ctx source-session-id))
          (let [{:keys [header entries]} loaded
                session-id             (:id header)
                source-sd              (session/get-session-data-in ctx source-session-id)
                source-model           (:model source-sd)
                source-thinking-level  (:thinking-level source-sd)
                model-entry            (last (filter #(= :model (:kind %)) entries))
                thinking-entry         (last (filter #(= :thinking-level (:kind %)) entries))
                model-from-entry       (let [provider (get-in model-entry [:data :provider])
                                             model-id (get-in model-entry [:data :model-id])]
                                         (when (and provider model-id)
                                           {:provider provider :id model-id}))
                model                  (or model-from-entry source-model)
                thinking-level         (or (get-in thinking-entry [:data :thinking-level])
                                           source-thinking-level
                                           :off)
                messages               (compaction/rebuild-messages-from-journal-entries (vec entries))]
            (dispatch/dispatch! ctx
                                :session/resume-loaded
                                {:session-id        session-id
                                 :source-session-id source-session-id
                                 :session-path      session-path
                                 :header            header
                                 :entries           entries
                                 :worktree-path     (:worktree-path header)
                                 :entry-count       (count entries)
                                 :thinking-level    thinking-level
                                 :model             model
                                 :messages          (vec messages)}
                                {:origin :core})
            ;; Replace runtime handles with fresh per-session instances, preserving
            ;; current in-memory defaults when the journal does not specify them.
            (let [sd*   (session/get-session-data-in ctx session-id)
                  sd    (cond-> sd*
                          (nil? model) (assoc :model source-model)
                          (nil? (some-> thinking-entry :data :thinking-level))
                          (assoc :thinking-level source-thinking-level))
                  _     (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data] sd)
                  fresh (runtime/create-runtime!
                         ctx session-id
                         {:session-data  sd
                          :messages      messages
                          :agent-initial (:agent-initial ctx)})]
              (swap! (:state* ctx)
                     (fn [state]
                       (-> state
                           (assoc-in [:agent-session :sessions session-id :agent-ctx] (:agent-ctx fresh))
                           (assoc-in [:agent-session :sessions session-id :sc-session-id] (:sc-session-id fresh))))))
            (dispatch/dispatch! ctx :session/ensure-base-system-prompt {:session-id session-id} {:origin :core})
            (dispatch/dispatch! ctx :session/retarget-runtime-prompt-metadata {:session-id session-id} {:origin :core})
            (ext/dispatch-in reg "session_switch" {:reason :resume})
            (session/get-session-data-in ctx session-id)))))))

(defn- fork-branch-entries
  "Return branch entries for a fork rooted at `entry-id`.

   Includes the selected entry and, when that entry is a user message, the
   immediately following contiguous non-user turn entries until the next user
   message. This preserves the selected prompt together with its reply."
  [ctx parent-session-id entry-id]
  (let [entries    (vec (persist/all-entries-in ctx parent-session-id))
        idx        (first (keep-indexed #(when (= (:id %2) entry-id) %1) entries))
        selected   (when (some? idx) (nth entries idx))
        msg        (get-in selected [:data :message])
        user-fork? (and selected
                        (= :message (:kind selected))
                        (= "user" (:role msg)))]
    (if (and user-fork? (some? idx))
      (let [prefix (subvec entries 0 (inc idx))
            tail   (loop [i   (inc idx)
                          acc []]
                     (if (>= i (count entries))
                       acc
                       (let [entry     (nth entries i)
                             entry-msg (get-in entry [:data :message])]
                         (if (and (= :message (:kind entry))
                                  (= "user" (:role entry-msg)))
                           acc
                           (recur (inc i) (conj acc entry))))))]
        (vec (concat prefix tail)))
      (vec (persist/entries-up-to-in ctx parent-session-id entry-id)))))

(defn fork-session-in!
  "Fork the session from `entry-id`, creating a new session branch.

  Persistence semantics:
  - child session gets a new session id and its own session file
  - child journal contains entries/messages through the selected turn
  - child session file is written immediately with `:parent-session`
    pointing at the parent session file (when available)"
  [ctx parent-session-id entry-id]
  (let [reg (:extension-registry ctx)]
    (ext/dispatch-in reg "session_before_fork" {:entry-id entry-id})
    (let [parent-sd           (session/get-session-data-in ctx parent-session-id)
          parent-session-file (:session-file parent-sd)
          new-session-id      (str (java.util.UUID/randomUUID))
          branch-entries      (fork-branch-entries ctx parent-session-id entry-id)
          messages            (compaction/rebuild-messages-from-journal-entries branch-entries)
          session-file        (when (:persist? ctx)
                                (let [session-dir (persist/session-dir-for (session/session-worktree-path-in ctx parent-session-id))
                                      file        (persist/new-session-file-path session-dir new-session-id)]
                                  (str file)))]
      (dispatch/dispatch! ctx
                          :session/fork-initialize
                          {:session-id          parent-session-id
                           :new-session-id      new-session-id
                           :branch-entries      branch-entries
                           :entry-id            entry-id
                           :entry-count         (count branch-entries)
                           :parent-session-id   parent-session-id
                           :parent-session-path parent-session-file
                           :session-file        session-file
                           :messages            messages}
                          {:origin :core})
      ;; Replace runtime handles with fresh per-session instances.
      (let [sd    (session/get-session-data-in ctx new-session-id)
            fresh (runtime/create-runtime!
                   ctx new-session-id
                   {:session-data  sd
                    :messages      messages
                    :agent-initial (:agent-initial ctx)})]
        (swap! (:state* ctx)
               (fn [state]
                 (-> state
                     (assoc-in [:agent-session :sessions new-session-id :agent-ctx] (:agent-ctx fresh))
                     (assoc-in [:agent-session :sessions new-session-id :sc-session-id] (:sc-session-id fresh))))))
      ;; Fork persistence: create/write child file immediately with lineage header.
      (when session-file
        (let [file (io/file session-file)]
          (persist/flush-journal! file
                                  new-session-id
                                  (session/session-worktree-path-in ctx new-session-id)
                                  parent-session-id
                                  parent-session-file
                                  branch-entries)))

      (ext/dispatch-in reg "session_fork" {})
      (session/get-session-data-in ctx new-session-id))))

(defn ensure-session-loaded-in!
  "Ensure `session-id` is available in this context.

   Behavior:
   - if session-id is nil, no-op
   - if already present in the context registry, returns its data
   - if known only by persisted session-file path, resumes that file
   - otherwise throws ex-info with :error-code request/not-found

   Returns session-data for the available session."
  [ctx _source-session-id session-id]
  (when session-id
    (let [sessions (session/get-sessions-map-in ctx)
          target   (get sessions session-id)
          path     (get-in target [:data :session-file])]
      (cond
        target
        (session/get-session-data-in ctx session-id)

        (and path (not (str/blank? path)))
        (resume-session-in! ctx session-id path)

        :else
        (throw (ex-info "session id not found in context session index"
                        {:error-code "request/not-found"
                         :session-id session-id}))))))

(defn close-session-in!
  "Detach a single session from the in-memory context.
   Returns {:closed? bool :session-id sid :active-session-id sid-or-nil}."
  [ctx session-id]
  (session-close/close-session-in! ctx session-id))

(defn close-session-tree-in!
  "Close a session and all its descendants.
   Returns {:closed-count N :closed-session-ids [...]}."
  [ctx root-id]
  (session-close/close-session-tree-in! ctx root-id))
