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

(defn agent-ctx-in
  [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :agent-ctx]))

(defn sc-session-id-in
  [ctx session-id]
  (get-in @(:state* ctx) [:agent-session :sessions session-id :sc-session-id]))

(defn- session-data-path [sid] [:agent-session :sessions sid :data])
(defn- session-telemetry-path [sid k] [:agent-session :sessions sid :telemetry k])
(defn- session-journal-path [sid] [:agent-session :sessions sid :persistence :journal])
(defn- session-flush-state-path [sid] [:agent-session :sessions sid :persistence :flush-state])
(defn- session-turn-ctx-path [sid] [:agent-session :sessions sid :turn :ctx])
(defn- session-scheduler-path [sid] [:agent-session :sessions sid :data :scheduler])
(defn- session-scheduler-schedules-path [sid] [:agent-session :sessions sid :data :scheduler :schedules])
(defn- session-scheduler-queue-path [sid] [:agent-session :sessions sid :data :scheduler :queue])

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
   :rpc-trace       [:runtime :rpc-trace]
   :extension-installs [:runtime :extension-installs]})

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

(defn- journal-append! [ctx session-id entry]
  (persist/append-entry-in! ctx session-id entry)
  (when (:persist? ctx)
    (let [sd (get-session-data-in ctx session-id)
          session-file (:session-file sd)]
      (when session-file
        (persist/persist-entry-in!
         ctx session-id (session-worktree-path-in ctx session-id)
         (:parent-session-id sd) session-file)))))

(defn journal-append-in! [ctx session-id entry] (journal-append! ctx session-id entry))
(defn get-sessions-map-in [ctx] (get-state-in* ctx [:agent-session :sessions]))

(defn- latest-message-timestamp [entries]
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

(defn sc-phase-in [ctx session-id]
  (sc/sc-phase (:sc-env ctx) (sc-session-id-in ctx session-id)))

(defn idle-in? [ctx session-id]
  (= :idle (sc-phase-in ctx session-id)))

(defn sorted-prompt-contributions [coll]
  (->> (or coll [])
       (filter map?)
       (sort-by (fn [{:keys [priority ext-path id]}]
                  [(or priority 1000)
                   (or ext-path "")
                   (or id "")]))
       vec))

(defn list-prompt-contributions-in [ctx session-id]
  (sorted-prompt-contributions (:prompt-contributions (get-session-data-in ctx session-id))))

(defn children-of-in
  "Return a vec of session-ids whose :parent-session-id equals `parent-id`.
   Pure scan of the sessions map — O(n) in number of sessions."
  [ctx parent-id]
  (->> (vals (get-sessions-map-in ctx))
       (keep (fn [{:keys [data]}]
               (when (= parent-id (:parent-session-id data))
                 (:session-id data))))
       vec))

(defn descendants-of-in
  "Return a vec of all descendant session-ids of `root-id` in bottom-up
   (leaf-first) order, suitable for sequential close without orphaning.
   `root-id` itself is not included."
  [ctx root-id]
  (letfn [(post-order [id]
            (let [children (children-of-in ctx id)]
              (conj (into [] (mapcat post-order children)) id)))]
    (into [] (mapcat post-order (children-of-in ctx root-id)))))
