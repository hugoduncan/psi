(ns psi.agent-session.psi-tool-scheduler
  "Scheduler action handler for psi-tool: parse, validate, and execute scheduler ops."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.scheduler-runtime :as scheduler-runtime]))

(defn- psi-tool-error-summary
  ([e] (psi-tool-error-summary nil e))
  ([default-phase e]
   {:message (or (ex-message e) (str e))
    :class   (.getName (class e))
    :phase   (or (:phase (ex-data e)) default-phase :execute)
    :data    (ex-data e)}))

(defn- require-session-id!
  [session-id op]
  (or session-id
      (throw (ex-info "psi-tool scheduler action requires invoking or explicit `session-id`"
                      {:phase :validate :action "scheduler" :op op}))))

(defn- parse-utc-instant!
  [s]
  (try
    (java.time.Instant/parse s)
    (catch Exception e
      (throw (ex-info "scheduler `at` must be an ISO-8601 UTC instant"
                      {:phase :validate :action "scheduler" :field :at :input s}
                      e)))))

(defn- now [] (java.time.Instant/now))

(defn- millis-until
  [target now*]
  (max 0 (.toMillis (java.time.Duration/between now* target))))

(defn- resolve-fire-time!
  [{:keys [delay-ms at]}]
  (cond
    (and delay-ms at)
    (throw (ex-info "scheduler create accepts either `delay-ms` or `at`, not both"
                    {:phase :validate :action "scheduler" :op "create"}))

    delay-ms
    (let [validated (scheduler/validate-delay-ms! delay-ms)
          created-at (now)]
      {:created-at created-at
       :delay-ms validated
       :fire-at (.plusMillis created-at validated)})

    at
    (let [created-at (now)
          fire-at (parse-utc-instant! at)
          delay (millis-until fire-at created-at)]
      (when (pos? delay)
        (scheduler/validate-delay-ms! (int delay)))
      {:created-at created-at
       :delay-ms (int delay)
       :fire-at fire-at})

    :else
    (throw (ex-info "scheduler create requires `delay-ms` or `at`"
                    {:phase :validate :action "scheduler" :op "create"}))))

(def ^:private supported-kinds #{:message :session})

(def ^:private session-config-supported-keys
  #{:session-name
    :system-prompt
    :model
    :thinking-level
    :skills
    :tool-defs
    :developer-prompt
    :developer-prompt-source
    :preloaded-messages
    :cache-breakpoints
    :prompt-component-selection})

(defn- parse-kind! [kind]
  (let [parsed (cond
                 (keyword? kind) kind
                 (string? kind) (keyword kind)
                 :else nil)]
    (when-not (contains? supported-kinds parsed)
      (throw (ex-info "scheduler create requires valid `kind`"
                      {:phase :validate :action "scheduler" :op "create" :kind kind :supported-kinds (vec (sort supported-kinds))})))
    parsed))

(defn- parse-session-config! [session-config]
  (let [cfg (cond
              (map? session-config) session-config
              (string? session-config) (try
                                         (binding [*read-eval* false]
                                           (edn/read-string session-config))
                                         (catch Exception e
                                           (throw (ex-info "scheduler session-config must be valid EDN map"
                                                           {:phase :validate :action "scheduler" :op "create" :field :session-config}
                                                           e))))
              (nil? session-config) nil
              :else session-config)]
    (when (some? cfg)
      (when-not (map? cfg)
        (throw (ex-info "scheduler session-config must be a map"
                        {:phase :validate :action "scheduler" :op "create" :field :session-config})))
      (let [unsupported (->> (keys cfg)
                             (remove session-config-supported-keys)
                             sort
                             vec)]
        (when (seq unsupported)
          (throw (ex-info "scheduler session-config contains unsupported keys"
                          {:phase :validate
                           :action "scheduler"
                           :op "create"
                           :field :session-config
                           :unsupported-keys unsupported})))))
    cfg))

(defn execute-psi-tool-scheduler-report
  [{:keys [ctx session-id]} {:keys [op delay-ms at label message schedule-id kind session-config]}]
  (let [started-at (System/nanoTime)]
    (try
      (when-not ctx
        (throw (ex-info "psi-tool scheduler action requires live runtime ctx"
                        {:phase :validate :action "scheduler" :op op})))
      (let [session-id (require-session-id! session-id op)
            result     (case op
                         "create"
                         (do
                           (when (str/blank? (str message))
                             (throw (ex-info "scheduler create requires `message`"
                                             {:phase :validate :action "scheduler" :op op})))
                           (let [kind           (parse-kind! kind)
                                 session-config (parse-session-config! session-config)
                                 _              (case kind
                                                  :message (when (some? session-config)
                                                             (throw (ex-info "scheduler create kind :message does not accept `session-config`"
                                                                             {:phase :validate :action "scheduler" :op op :kind kind})))
                                                  :session (when (nil? session-config)
                                                             (throw (ex-info "scheduler create kind :session requires `session-config`"
                                                                             {:phase :validate :action "scheduler" :op op :kind kind}))))
                                 state          (scheduler-runtime/scheduler-state-in ctx session-id)]
                             (when (>= (scheduler/pending-count state) scheduler/default-max-pending-per-session)
                               (throw (ex-info "scheduler pending cap exceeded"
                                               {:phase :validate
                                                :action "scheduler"
                                                :op op
                                                :cap scheduler/default-max-pending-per-session})))
                             (let [{:keys [created-at fire-at delay-ms]} (resolve-fire-time! {:delay-ms delay-ms :at at})
                                   new-schedule-id (str "sch-" (java.util.UUID/randomUUID))
                                   schedule (dispatch/dispatch! ctx
                                                                :scheduler/create
                                                                {:session-id session-id
                                                                 :schedule-id new-schedule-id
                                                                 :kind kind
                                                                 :label label
                                                                 :message message
                                                                 :session-config session-config
                                                                 :created-at created-at
                                                                 :fire-at fire-at
                                                                 :delay-ms delay-ms}
                                                                {:origin :core})]
                               {:psi-tool/action :scheduler
                                :psi-tool/scheduler-op :create
                                :psi-tool/overall-status :ok
                                :psi-tool/scheduler {:schedule (scheduler-runtime/schedule->psi-tool-summary schedule)}})))

                         "list"
                         (let [schedules (scheduler-runtime/scheduler-schedules-in ctx session-id [:pending :queued])]
                           {:psi-tool/action :scheduler
                            :psi-tool/scheduler-op :list
                            :psi-tool/overall-status :ok
                            :psi-tool/scheduler (scheduler-runtime/scheduler-list->psi-tool-summary schedules)})

                         "cancel"
                         (do
                           (when-not (string? schedule-id)
                             (throw (ex-info "scheduler cancel requires `schedule-id`"
                                             {:phase :validate :action "scheduler" :op op})))
                           (let [schedule (dispatch/dispatch! ctx
                                                              :scheduler/cancel
                                                              {:session-id session-id
                                                               :schedule-id schedule-id}
                                                              {:origin :core})]
                             {:psi-tool/action :scheduler
                              :psi-tool/scheduler-op :cancel
                              :psi-tool/overall-status :ok
                              :psi-tool/scheduler {:cancelled? true
                                                   :schedule (scheduler-runtime/schedule->psi-tool-summary schedule)}})))]
        (assoc result :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))))
      (catch Exception e
        {:psi-tool/action :scheduler
         :psi-tool/scheduler-op (some-> op keyword)
         :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))
         :psi-tool/overall-status :error
         :psi-tool/error (psi-tool-error-summary :scheduler e)}))))
