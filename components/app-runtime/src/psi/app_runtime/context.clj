(ns psi.app-runtime.context
  "Adapter-neutral context snapshot projection shared across interactive UIs."
  (:require
   [psi.agent-session.message-text :as message-text]
   [psi.session-state.state :as ss]))

(defn phase->runtime-state
  [phase]
  (case phase
    :idle "waiting"
    :streaming "running"
    :retrying "retrying"
    :compacting "compacting"
    nil))

(defn- session-runtime-state
  [ctx session-id]
  (let [phase (ss/sc-phase-in ctx session-id)
        sd    (ss/get-session-data-in ctx session-id)]
    (cond
      (:is-streaming sd)  "running"
      (:is-compacting sd) "compacting"
      :else               (phase->runtime-state phase))))

(defn context-snapshot
  "Build a canonical context snapshot for `session-id`.

   Includes a synthetic single-session snapshot before the runtime context index
   has any real entries, so handshake/bootstrap surfaces still reflect the
   current live session.

   Deliberately excludes per-prompt fork rows so lightweight context snapshots
   stay cheap even for large session journals. Fork-point expansion is provided
   by explicit selector payloads instead."
  [ctx active-session-id session-id]
  (let [active-session-id (or active-session-id session-id)
        sd               (ss/get-session-data-in ctx session-id)
        current-id       (:session-id sd)
        indexed-sessions (or (seq (ss/list-context-sessions-in ctx)) [])
        sessions*        (if (seq indexed-sessions)
                           (->> indexed-sessions
                                (sort-by (juxt :updated-at :session-id))
                                vec)
                           [(select-keys sd [:session-id :session-name :worktree-path :parent-session-id :created-at :updated-at])])
        active-id*       (or active-session-id current-id)
        slots            (mapv (fn [m]
                                 (let [sid (:session-id m)]
                                   {:id                sid
                                    :item-kind         "session"
                                    :name              (:session-name m)
                                    :display-name      (or (:display-name m)
                                                           (message-text/short-display-text (:session-name m)))
                                    :worktree-path     (:worktree-path m)
                                    :runtime-state     (session-runtime-state ctx sid)
                                    :is-streaming      (= "running" (session-runtime-state ctx sid))
                                    :is-active         (= sid active-id*)
                                    :parent-session-id (:parent-session-id m)
                                    :created-at        (some-> (:created-at m) str)
                                    :updated-at        (some-> (:updated-at m) str)}))
                               sessions*)]
    {:active-session-id active-id*
     :sessions          slots}))
