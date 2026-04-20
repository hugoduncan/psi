(ns psi.agent-session.session-state
  "Canonical session state infrastructure.
   Owns the state atom paths, read/write primitives, session-update mechanics,
   journal append, session registry helpers, and statechart phase queries.

   This is a leaf module — dispatch-handlers, session-lifecycle, mutations,
   and core all require it without creating cycles.

   Atom shape
   ──────────
   {:agent-session {:sessions {sid {:data {session-map...}
                                    :telemetry {...}
                                    :persistence {:journal [] :flush-state {...}}
                                    :turn {:ctx nil}}}}
    :runtime ...
    :background-jobs ...
    :ui ...
    :recursion ...
    :oauth ...}

   Session targeting
   ─────────────────
   Session targeting is explicit at call sites and on dispatch/query payloads.
   Adapters no longer bake session-id into persistent ctx closures."
  (:require
   [clojure.string :as str]
   [psi.agent-session.message-text :as message-text]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.statechart :as sc]))

;;; Per-session runtime handle accessors


(defn agent-ctx-in
  "Return the agent-core context for `session-id`."
  [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :agent-ctx]))

(defn sc-session-id-in
  "Return the statechart session id for `session-id`."
  [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :sc-session-id]))

;;; State path builders

(defn- session-data-path
  "Build the path to session data for a given session-id."
  [sid]
  [:agent-session :sessions sid :data])

(defn- session-telemetry-path
  "Build the path to a telemetry key within a session."
  [sid k]
  [:agent-session :sessions sid :telemetry k])

(defn- session-journal-path
  "Build the path to the journal within a session."
  [sid]
  [:agent-session :sessions sid :persistence :journal])

(defn- session-flush-state-path
  "Build the path to flush-state within a session."
  [sid]
  [:agent-session :sessions sid :persistence :flush-state])

(defn- session-turn-ctx-path
  "Build the path to turn ctx within a session."
  [sid]
  [:agent-session :sessions sid :turn :ctx])

(def ^:private static-state-paths
  {:workflow-state  [:workflows]
   :workflow-definitions [:workflows :definitions]
   :workflow-runs   [:workflows :runs]
   :workflow-run-order [:workflows :run-order]
   :nrepl-runtime   [:runtime :nrepl]
   :background-jobs [:background-jobs :store]
   :ui-state        [:ui :extension-ui]
   :recursion       [:recursion]
   :oauth           [:oauth]
   :rpc-trace       [:runtime :rpc-trace]})

(def ^:private session-state-path-builders
  {:session-data             session-data-path
   :provider-error-replies   #(conj (session-data-path %) :provider-error-replies)
   :tool-output-stats        #(session-telemetry-path % :tool-output-stats)
   :tool-call-attempts       #(session-telemetry-path % :tool-call-attempts)
   :tool-lifecycle-events    #(session-telemetry-path % :tool-lifecycle-events)
   :provider-requests        #(session-telemetry-path % :provider-requests)
   :provider-replies         #(session-telemetry-path % :provider-replies)
   :journal                  session-journal-path
   :flush-state              session-flush-state-path
   :turn-ctx                 session-turn-ctx-path})

(defn state-path
  "Return the canonical root-state path vector for the named state key.
   Session-scoped keys (:session-data, :provider-error-replies, :journal,
   :flush-state, :turn-ctx, :tool-output-stats, :tool-call-attempts,
   :tool-lifecycle-events, :provider-requests, :provider-replies) require
   a session-id `sid` and return nil when none is provided.
   Non-session keys work with one arg.
   Returns nil for unknown keys."
  ([k] (state-path k nil))
  ([k sid]
   (if-let [build-path (get session-state-path-builders k)]
     (when sid (build-path sid))
     (get static-state-paths k))))

;;; Private state primitives

(defn- get-state-in*
  [ctx path]
  (when-let [state* (:state* ctx)]
    (get-in @state* path)))

(defn- assoc-state-in!*
  [ctx path value]
  (swap! (:state* ctx) assoc-in path value))

(defn- update-state-in!*
  [ctx path f & args]
  (apply swap! (:state* ctx) update-in path f args))

;;; Public state accessors

(defn get-state-value-in
  "Low-level canonical root-state read helper.
   Prefer named session APIs or dispatch-routed mutations for production flows.
   Retained as intentional infrastructure for resolvers, compatibility views,
   and focused test/harness usage."
  [ctx path]
  (get-state-in* ctx path))

(defn assoc-state-value-in!
  "Low-level canonical root-state write helper.
   Prefer named session APIs or dispatch-routed mutations in production code.
   Retained primarily for focused test/harness setup and deliberate low-level
   infrastructure boundaries."
  [ctx path value]
  (assoc-state-in!* ctx path value))

(defn update-state-value-in!
  "Low-level canonical root-state update helper.
   Prefer named session APIs or dispatch-routed mutations in production code.
   Retained primarily for focused test/harness setup and deliberate low-level
   infrastructure boundaries."
  [ctx path f & args]
  (apply update-state-in!* ctx path f args))

(defn get-session-data-in
  "Return the AgentSession data map for `session-id` from `ctx`."
  [ctx session-id]
  (get-state-in* ctx (session-data-path session-id)))

;;; Session update mechanics

(defn session-update
  "Wrap a session-data transform into a root-state transform for session `sid`.
   Use in dispatch handler results:
     {:root-state-update (session-update sid #(assoc % :session-name name))}"
  [sid f]
  (fn [state]
    (update-in state (session-data-path sid) f)))

(defn apply-root-state-update-in!
  "Apply a pure root-state update function to canonical state in `ctx`.
   `f` is (fn [root-state] → new-root-state). Used by the dispatch pipeline
   for pure handlers that intentionally operate on non-session root slices."
  [ctx f]
  (swap! (:state* ctx) f)
  @(:state* ctx))

;;; Worktree path

(defn session-worktree-path-in
  "Return the required worktree-path for `session-id`.
   Sessions must always carry an explicit :worktree-path."
  [ctx session-id]
  (or (:worktree-path (get-session-data-in ctx session-id))
      (throw (ex-info "session is missing required :worktree-path"
                      {:session-id session-id
                       :callback :session-worktree-path-in}))))

;;; Journal

(defn- journal-append!
  "Append `entry` to the journal for `session-id` and conditionally persist to disk."
  [ctx session-id entry]
  (persist/append-entry-in! ctx session-id entry)
  (when (:persist? ctx)
    (let [sd (get-session-data-in ctx session-id)
          session-file (:session-file sd)]
      (when session-file
        (persist/persist-entry-in!
         ctx
         session-id
         (session-worktree-path-in ctx session-id)
         (:parent-session-id sd)
         session-file)))))

(defn journal-append-in!
  "Append `entry` to the journal for `session-id` and persist.
   Use `persist/message-entry` et al. to build entries."
  [ctx session-id entry]
  (journal-append! ctx session-id entry))

;;; Session registry helpers

(defn get-sessions-map-in
  "Return the sessions map {sid -> session-entry} from the atom."
  [ctx]
  (get-state-in* ctx [:agent-session :sessions]))

(defn- latest-message-timestamp
  "Return the latest journaled user/assistant/tool-result message timestamp.

   Falls back to nil when the session has no recorded message entries yet."
  [entries]
  (reduce (fn [latest entry]
            (let [timestamp (when (= :message (:kind entry))
                              (get-in entry [:data :message :timestamp]))]
              (cond
                (nil? timestamp) latest
                (nil? latest)    timestamp
                (pos? (compare timestamp latest)) timestamp
                :else latest)))
          nil
          (or entries [])))

(defn list-context-sessions-in
  "Return valid session metadata entries sorted by updated-at.

   `:display-name` is canonicalized here so all adapters can render the same
   explicit-or-inferred label for a session.

   `:updated-at` prefers explicit session metadata when present, otherwise
   derives from the latest journaled message timestamp so adapters can render a
   real last-message clock instead of repeating session creation time.

   Defensive filter: ignore malformed runtime slots that do not carry a
   non-blank :session-id in :data. Adapters should only render real sessions."
  [ctx]
  (let [sessions (get-sessions-map-in ctx)]
    (->> (vals sessions)
         (keep (fn [{:keys [data persistence]}]
                 (when-let [sid (:session-id data)]
                   (when-not (str/blank? sid)
                     (let [journal       (:journal persistence)
                           messages      (keep (fn [entry]
                                                 (when (= (:kind entry) :message)
                                                   (get-in entry [:data :message])))
                                               journal)
                           updated-at    (or (:updated-at data)
                                             (latest-message-timestamp journal))
                           display-name  (message-text/session-display-name
                                          (:session-name data)
                                          messages)]
                       (assoc (select-keys data [:session-id :session-file :session-name
                                                 :worktree-path :parent-session-id
                                                 :parent-session-path :created-at :updated-at])
                              :display-name display-name
                              :updated-at updated-at))))))
         (sort-by (juxt :updated-at :session-id))
         vec)))

;; set-context-active-session-in! removed — adapters own focus locally.
;; Session targeting is explicit at call sites.

;;; Statechart phase queries

(defn sc-phase-in
  "Return the statechart phase for `session-id`."
  [ctx session-id]
  (sc/sc-phase (:sc-env ctx) (sc-session-id-in ctx session-id)))

(defn idle-in?
  "True when the session phase for `session-id` is :idle."
  [ctx session-id]
  (= :idle (sc-phase-in ctx session-id)))

;;; Prompt contribution helpers

(defn sorted-prompt-contributions
  "Return prompt contributions sorted by deterministic render order."
  [coll]
  (->> (or coll [])
       (filter map?)
       (sort-by (fn [{:keys [priority ext-path id]}]
                  [(or priority 1000)
                   (or ext-path "")
                   (or id "")]))
       vec))

(defn list-prompt-contributions-in
  "Return prompt contributions for `session-id` sorted by deterministic render order."
  [ctx session-id]
  (sorted-prompt-contributions (:prompt-contributions (get-session-data-in ctx session-id))))

