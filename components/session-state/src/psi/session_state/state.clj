(ns psi.session-state.state
  "Canonical session state infrastructure.
   Owns session root-state paths, read/write primitives, journal append,
   session registry helpers, hierarchy traversal, and worktree invariants."
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [psi.prompt-registry.contributions :as prompt-contributions]
   [psi.prompt-registry.root-storage :as prompt-storage]
   [psi.session-persistence.core :as session-persistence]
   [psi.session-state.display-name :as display-name]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn agent-ctx-in
  [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :agent-ctx]))

(defn agent-tool-source-in
  "Return the tool-source (all known tool definition maps) for a session's agent.
   The agent data-atom holds the merged base+extension tool set after startup."
  [ctx session-id]
  (some-> (agent-ctx-in ctx session-id) :data-atom deref :tools))

(defn sc-session-id-in
  [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :sc-session-id]))

(defn session-data-path [sid] [:agent-session :sessions sid :data])
(defn session-telemetry-path [sid k] [:agent-session :sessions sid :telemetry k])
(defn session-journal-path [sid] (session-persistence/session-journal-path sid))
(defn session-flush-state-path [sid] (session-persistence/session-flush-state-path sid))
(defn session-turn-ctx-path [sid] [:agent-session :sessions sid :turn :ctx])
(defn session-scheduler-path [sid] [:agent-session :sessions sid :data :scheduler])
(defn session-scheduler-schedules-path [sid] [:agent-session :sessions sid :data :scheduler :schedules])
(defn session-scheduler-queue-path [sid] [:agent-session :sessions sid :data :scheduler :queue])

(def ^:private static-state-paths
  {:workflow-state        [:workflows]
   :workflow-definitions  (workflow-registry/definitions-path)
   :workflow-runs         [:workflows :runs]
   :workflow-run-order    [:workflows :run-order]
   :nrepl-runtime         [:runtime :nrepl]
   :background-jobs       [:background-jobs :store]
   :ui-state              [:ui :extension-ui]
   :recursion             [:recursion]
   :oauth                 [:oauth]
   :rpc-trace             [:runtime :rpc-trace]
   :extension-installs    [:runtime :extension-installs]})

(def ^:private session-state-path-builders
  {:session-data             session-data-path
   :provider-error-replies   #(conj (session-data-path %) :provider-error-replies)
   :tool-output-stats        #(session-telemetry-path % :tool-output-stats)
   :tool-call-attempts       #(session-telemetry-path % :tool-call-attempts)
   :tool-lifecycle-events    #(session-telemetry-path % :tool-lifecycle-events)
   :provider-requests        #(session-telemetry-path % :provider-requests)
   :provider-replies         #(session-telemetry-path % :provider-replies)
   :provider-events          #(session-telemetry-path % :provider-events)
   :journal                  session-journal-path
   :flush-state              session-flush-state-path
   :turn-ctx                 session-turn-ctx-path
   :scheduler                session-scheduler-path
   :scheduler-schedules      session-scheduler-schedules-path
   :scheduler-queue          session-scheduler-queue-path})

(defn state-path
  ([k] (state-path k nil))
  ([k sid]
   (if-let [build-path (get session-state-path-builders k)]
     (when sid (build-path sid))
     (get static-state-paths k))))

(defn- get-state-in* [ctx path]
  (when-let [state* (:state* ctx)]
    (get-in @state* path)))

(defn- assoc-state-in!* [ctx path value]
  (swap! (:state* ctx) assoc-in path value))

(defn- update-state-in!* [ctx path f & args]
  (apply swap! (:state* ctx) update-in path f args))

(defn get-state-value-in [ctx path] (get-state-in* ctx path))
(defn assoc-state-value-in! [ctx path value] (assoc-state-in!* ctx path value))
(defn update-state-value-in! [ctx path f & args] (apply update-state-in!* ctx path f args))
(defn get-session-data-in [ctx session-id] (get-state-in* ctx (session-data-path session-id)))

(defn session-update
  [sid f]
  (fn [state]
    (update-in state (session-data-path sid) f)))

(defn apply-root-state-update-in! [ctx f]
  (swap! (:state* ctx) f)
  @(:state* ctx))

(defn session-worktree-path-in
  [ctx session-id]
  (or (:worktree-path (get-session-data-in ctx session-id))
      (throw (ex-info "session is missing required :worktree-path"
                      {:session-id session-id
                       :callback :session-worktree-path-in}))))

(defn append-journal-entry-root-update
  [session-id entry]
  (fn [state]
    (update-in state (session-journal-path session-id) (fnil conj []) entry)))

(defn append-journal-entry-in!
  [ctx session-id entry]
  (session-persistence/append-journal-entry-in! ctx session-id entry))

(defn get-sessions-map-in [ctx] (get-state-in* ctx [:agent-session :sessions]))

(defn- timestamp-sort-key [timestamp]
  (cond
    (nil? timestamp)                     [0 nil]
    (instance? java.time.Instant timestamp) [1 (.toEpochMilli ^java.time.Instant timestamp)]
    (number? timestamp)                  [1 timestamp]
    (string? timestamp)                  [2 timestamp]
    :else                                [3 (str timestamp)]))

(defn- latest-message-timestamp [entries]
  (reduce (fn [latest entry]
            (let [timestamp (when (= :message (:kind entry))
                              (get-in entry [:data :message :timestamp]))]
              (cond
                (nil? timestamp) latest
                (nil? latest)    timestamp
                (pos? (compare (timestamp-sort-key timestamp)
                               (timestamp-sort-key latest))) timestamp
                :else latest)))
          nil
          (or entries [])))

(defn list-context-sessions-in
  [ctx]
  (let [sessions (get-sessions-map-in ctx)]
    (->> (vals sessions)
         (keep (fn [{:keys [data persistence]}]
                 (when-let [sid (:session-id data)]
                   (when-not (str/blank? sid)
                     (let [journal       (:journal persistence)
                           messages      (keep (fn [entry]
                                                 (when (= :message (:kind entry))
                                                   (get-in entry [:data :message])))
                                               journal)
                           updated-at    (or (:updated-at data)
                                             (latest-message-timestamp journal))
                           display-name  (display-name/session-display-name (:session-name data) messages)]
                       (assoc (select-keys data [:session-id :session-file :session-name
                                                 :worktree-path :parent-session-id
                                                 :parent-session-path :created-at :updated-at])
                              :display-name display-name
                              :updated-at updated-at))))))
         (sort-by (juxt (comp timestamp-sort-key :updated-at) :session-id))
         vec)))

(defn- sc-working-memory [sc-env session-id]
  (sp/get-working-memory (::sc/working-memory-store sc-env) sc-env session-id))

(defn sc-phase-in [ctx session-id]
  (let [session-data (get-session-data-in ctx session-id)]
    (if (:retry session-data)
      :retrying
      (when-let [wm (sc-working-memory (:sc-env ctx) (sc-session-id-in ctx session-id))]
        (first (::sc/configuration wm))))))

(defn idle-in? [ctx session-id]
  (= :idle (sc-phase-in ctx session-id)))

(defn sorted-prompt-contributions [coll]
  (prompt-contributions/sort-contributions coll))

(defn list-prompt-contributions-in [ctx session-id]
  (prompt-storage/list-contributions @(:state* ctx) (get-session-data-in ctx session-id)))

(defn children-of-in
  [ctx parent-id]
  (->> (vals (get-sessions-map-in ctx))
       (keep (fn [{:keys [data]}]
               (when (= parent-id (:parent-session-id data))
                 (:session-id data))))
       vec))

(defn descendants-of-in
  [ctx root-id]
  (letfn [(post-order [id]
            (let [children (children-of-in ctx id)]
              (conj (into [] (mapcat post-order children)) id)))]
    (into [] (mapcat post-order (children-of-in ctx root-id)))))
