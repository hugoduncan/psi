(ns psi.state-kernel.dispatch
  "Application-independent event dispatch pipeline.

   Kernel environment contract
   - :state*               root state atom
   - :execute-effect-fn    optional callback (fn [env effect]) -> any
   - :validate-result-fn   optional callback (fn [env ictx]) -> truthy | {:valid? ...}
   - :publish-change-fn    optional callback reserved for higher-layer projection publication
   - :dispatch-trace-fn    optional callback (fn [entry]) for external trace sinks

   Domain-specific keys may exist on the caller environment map, but generic
   kernel machinery in this namespace depends only on the contract above plus
   optional injection points passed directly to helpers/interceptors."
  (:require
   [psi.state-kernel.dispatch-schema :as schema]
   [taoensso.timbre :as timbre]))

(def effect-schema schema/effect-schema)
(def pure-result-schema schema/pure-result-schema)
(def valid-effect? schema/valid-effect?)
(def explain-effect schema/explain-effect)
(def valid-pure-result?* schema/valid-pure-result?*)
(def explain-pure-result schema/explain-pure-result)
(def validate-dispatch-schemas schema/validate-dispatch-schemas)

(defonce ^:private handler-registry (atom {}))

(defn handler-entry [event-type] (get @handler-registry event-type))
(defn register-handler! [event-type handler-fn]
  (swap! handler-registry assoc event-type {:fn handler-fn})
  nil)
(defn registered-event-types [] (set (keys @handler-registry)))
(defn registered-handler-entries [] @handler-registry)
(defn clear-handlers! [] (reset! handler-registry {}) nil)

(defn ->interceptor [{:keys [id before after]}]
  {:id id :before (or before identity) :after (or after identity)})

(declare next-dispatch-id append-trace-entry! pure-result? append-interceptor-trace!)

(defn run-interceptor-chain [ictx interceptors]
  (let [after-before (reduce (fn [ctx i]
                               (if (:blocked? ctx)
                                 ctx
                                 (let [_ (append-interceptor-trace! :dispatch/interceptor-enter ctx i)]
                                   ((:before i) ctx))))
                             ictx
                             interceptors)]
    (reduce (fn [ctx i]
              (let [after-ctx ((:after i) ctx)]
                (append-interceptor-trace! :dispatch/interceptor-exit after-ctx i)
                after-ctx))
            after-before
            (reverse interceptors))))

(defn normalize-event [event-type event-data opts]
  {:event/type event-type
   :event/data event-data
   :event/session-id (:session-id event-data)
   :event/origin (or (:origin opts) :core)
   :event/ext-id (:ext-id opts)
   :event/replaying? (boolean (:replaying? opts))
   :event/dispatch-id (or (:dispatch-id opts) (next-dispatch-id))})

(defn- event-type-of [ictx]
  (or (:event-type ictx)
      (get-in ictx [:event :event/type])))
(defn- event-data-of [ictx]
  (if (contains? ictx :event-data)
    (:event-data ictx)
    (get-in ictx [:event :event/data])))
(defn- event-origin-of [ictx]
  (or (:origin ictx)
      (get-in ictx [:event :event/origin])
      :core))
(defn- event-ext-id-of [ictx]
  (or (:ext-id ictx)
      (get-in ictx [:event :event/ext-id])))
(defn- event-replaying?-of [ictx]
  (or (:replaying? ictx)
      (boolean (get-in ictx [:event :event/replaying?]))))
(defn- event-session-id-of [ictx]
  (or (:session-id ictx)
      (get-in ictx [:event :event/session-id])))
(defn dispatch-id-of [ictx]
  (or (:dispatch-id ictx)
      (get-in ictx [:event :event/dispatch-id])))

(defn- append-interceptor-trace! [trace-kind ictx interceptor]
  (append-trace-entry! (:env ictx)
                       {:trace/kind trace-kind
                        :dispatch-id (dispatch-id-of ictx)
                        :session-id (event-session-id-of ictx)
                        :event-type (event-type-of ictx)
                        :interceptor-id (:id interceptor)}))

(defn- summarize-handler-result [result]
  {:kind :pure-result
   :effect-count (count (:effects result))
   :has-root-state-update (boolean (:root-state-update result))
   :has-return (contains? result :return)
   :return-key (:return-key result)
   :return-effect-result? (boolean (:return-effect-result? result))})

(defonce ^:private event-log (atom []))
(defonce ^:private dispatch-trace (atom []))
(def ^:private max-event-log-size 1000)
(def ^:private max-dispatch-trace-size 1000)

(defn- trim-bounded-log [entries max-size]
  (let [xs (vec entries)
        n (count xs)]
    (if (> n max-size)
      (subvec xs (- n max-size))
      xs)))

(defn event-log-entries [] @event-log)
(defn dispatch-trace-entries [] @dispatch-trace)
(defn clear-event-log! [] (reset! event-log []) nil)
(defn clear-dispatch-trace! [] (reset! dispatch-trace []) nil)
(defn next-dispatch-id [] (str (java.util.UUID/randomUUID)))

(defn append-trace-entry! [env entry]
  (let [entry* (assoc entry :timestamp (System/currentTimeMillis))]
    (swap! dispatch-trace
           (fn [log]
             (trim-bounded-log (conj log entry*) max-dispatch-trace-size)))
    (when-let [trace-fn (:dispatch-trace-fn env)]
      (try
        (trace-fn entry*)
        (catch Throwable t
          (timbre/debug t "dispatch-trace-fn failed"))))
    entry*))

(defn assoc-dispatch-id [m dispatch-id]
  (cond-> (or m {})
    dispatch-id (assoc :dispatch-id dispatch-id)))

(defn replay-event-entry! [dispatch-fn env entry]
  (dispatch-fn env
               (:event-type entry)
               (:event-data entry)
               {:origin (:origin entry)
                :ext-id (:ext-id entry)
                :replaying? true}))

(defn replay-event-log! [dispatch-fn env entries]
  (mapv #(replay-event-entry! dispatch-fn env %) entries))

(defn- summarize-dispatch-db [db]
  (when (map? db)
    {:root-keys (-> db keys vec sort)
     :root-key-count (count db)}))

(defn- dispatch-log-entry [ictx]
  (cond-> {:event-type (event-type-of ictx)
           :event-data (event-data-of ictx)
           :origin (event-origin-of ictx)
           :blocked? (boolean (:blocked? ictx))
           :timestamp (::log-timestamp ictx)
           :duration-ms (- (System/currentTimeMillis) (or (::log-timestamp ictx) 0))
           :replaying? (boolean (event-replaying?-of ictx))
           :validation-error (:validation-error ictx)
           :declared-effects (or (some-> ictx :pure-result :effects vec) [])
           :applied-effects (or (:applied-effects ictx) [])
           :pure-result-kind (cond
                               (contains? (:pure-result ictx) :root-state-update) :root-state-update
                               (some? (:pure-result ictx)) :pure
                               :else nil)
           :db-summary-before (or (::db-summary-before ictx)
                                  (some-> ictx :env :state* deref summarize-dispatch-db))
           :db-summary-after (some-> ictx :env :state* deref summarize-dispatch-db)}
    (event-ext-id-of ictx) (assoc :ext-id (event-ext-id-of ictx))
    (:block-reason ictx) (assoc :block-reason (:block-reason ictx))))

(def log-interceptor
  (->interceptor
   {:id :log
    :before (fn [ictx]
              (assoc ictx
                     ::log-timestamp (System/currentTimeMillis)
                     ::db-summary-before (some-> ictx :env :state* deref summarize-dispatch-db)))
    :after (fn [ictx]
             (let [entry (dispatch-log-entry ictx)]
               (swap! event-log (fn [log]
                                  (trim-bounded-log (conj log entry) max-event-log-size)))
               (dissoc ictx ::log-timestamp ::db-summary-before)))}))

(defn pure-result? [x]
  (and (map? x)
       (or (contains? x :root-state-update)
           (contains? x :effects)
           (contains? x :return)
           (contains? x :return-key)
           (contains? x :return-effect-result?))))

(defn- normalize-handler-result [result]
  (if (pure-result? result) result {:return result}))

(def handler-interceptor
  (->interceptor
   {:id :handler
    :before
    (fn [ictx]
      (if (:blocked? ictx)
        ictx
        (if-let [entry (handler-entry (event-type-of ictx))]
          (let [handler-fn (:fn entry)
                env (:env ictx)
                raw-data (event-data-of ictx)
                eff-sid (:session-id ictx)
                handler-data (cond-> (or raw-data {})
                               (and eff-sid (not (:session-id raw-data)))
                               (assoc :session-id eff-sid)

                               (and (dispatch-id-of ictx)
                                    (not (:dispatch-id raw-data)))
                               (assoc :dispatch-id (dispatch-id-of ictx))

                               (:replaying? ictx)
                               (assoc :replaying? true))
                result (try
                         (handler-fn env handler-data)
                         (catch Exception e
                           (timbre/warn "Dispatch handler error" (event-type-of ictx) (ex-message e))
                           nil))
                pure-result (normalize-handler-result result)]
            (append-trace-entry! env
                                 {:trace/kind :dispatch/handler-result
                                  :dispatch-id (dispatch-id-of ictx)
                                  :session-id (event-session-id-of ictx)
                                  :event-type (event-type-of ictx)
                                  :result (summarize-handler-result pure-result)})
            (assoc ictx :pure-result pure-result))
          ictx)))}))

(defn apply-root-state-update! [env root-update-fn]
  (when (and (fn? root-update-fn) (:state* env))
    (swap! (:state* env) root-update-fn))
  nil)

(defn read-root-state-value [env return-key]
  (when (:state* env)
    (if (vector? return-key)
      (get-in @(:state* env) return-key)
      (get @(:state* env) return-key))))

(defn apply-pure-result
  [ictx {:keys [apply-root-state-update-fn read-root-state-value-fn session-id-fn event-type-fn append-trace-entry-fn]
         :or {apply-root-state-update-fn apply-root-state-update!
              read-root-state-value-fn read-root-state-value
              session-id-fn event-session-id-of
              event-type-fn event-type-of
              append-trace-entry-fn append-trace-entry!}}]
  (if-let [pure-result (:pure-result ictx)]
    (let [env (:env ictx)
          root-update-fn (:root-state-update pure-result)
          return-key (:return-key pure-result)
          return-effect-result? (:return-effect-result? pure-result)]
      (when (fn? root-update-fn)
        (apply-root-state-update-fn env root-update-fn))
      (when (contains? pure-result :effects)
        (append-trace-entry-fn env
                               {:trace/kind :dispatch/effects-emitted
                                :dispatch-id (dispatch-id-of ictx)
                                :session-id (session-id-fn ictx)
                                :event-type (event-type-fn ictx)
                                :effects (vec (:effects pure-result))}))
      (cond-> ictx
        (contains? pure-result :effects)
        (assoc :applied-effects (:effects pure-result))
        return-effect-result?
        (assoc :return-effect-result? true)
        (contains? pure-result :return)
        (assoc :result (:return pure-result))
        return-key
        (assoc :result (read-root-state-value-fn env return-key))))
    ictx))

(def apply-interceptor
  (->interceptor
   {:id :apply
    :after (fn [ictx]
             (apply-pure-result ictx {}))}))

(def effect-interceptor
  (->interceptor
   {:id :effects
    :after
    (fn [ictx]
      (let [env (:env ictx)
            execute-fn (:execute-effect-fn env)
            effects (:applied-effects ictx)
            dispatch-id (dispatch-id-of ictx)
            eff-sid (:session-id ictx)
            effects* (mapv (fn [e]
                             (if eff-sid
                               (if (:session-id e) e (assoc e :session-id eff-sid))
                               e))
                           effects)]
        (if (and (fn? execute-fn) (seq effects*))
          (let [results (mapv (fn [effect]
                                (append-trace-entry! env
                                                     {:trace/kind :dispatch/effect-start
                                                      :dispatch-id dispatch-id
                                                      :session-id (:session-id effect)
                                                      :event-type (event-type-of ictx)
                                                      :effect-type (:effect/type effect)
                                                      :effect effect})
                                (try
                                  (let [result (execute-fn env effect)]
                                    (append-trace-entry! env
                                                         {:trace/kind :dispatch/effect-finish
                                                          :dispatch-id dispatch-id
                                                          :session-id (:session-id effect)
                                                          :event-type (event-type-of ictx)
                                                          :effect-type (:effect/type effect)
                                                          :effect effect
                                                          :result result})
                                    result)
                                  (catch Throwable t
                                    (append-trace-entry! env
                                                         {:trace/kind :dispatch/effect-finish
                                                          :dispatch-id dispatch-id
                                                          :session-id (:session-id effect)
                                                          :event-type (event-type-of ictx)
                                                          :effect-type (:effect/type effect)
                                                          :effect effect
                                                          :error-message (ex-message t)})
                                    (throw t))))
                              effects*)]
            (cond-> ictx
              (and (:return-effect-result? ictx)
                   (nil? (:result ictx))
                   (seq results))
              (assoc :result (first results))))
          ictx)))}))

(def validate-interceptor
  (->interceptor
   {:id :validate
    :after
    (fn [ictx]
      (if (:blocked? ictx)
        ictx
        (let [env (:env ictx)
              validate-fn (:validate-result-fn env)]
          (if-not (fn? validate-fn)
            ictx
            (let [result (try
                           (validate-fn env ictx)
                           (catch Exception e
                             (timbre/warn "Dispatch validation error" (event-type-of ictx) (ex-message e))
                             {:valid? false
                              :reason {:type :validator-exception
                                       :message (ex-message e)}}))
                  valid? (cond
                           (map? result) (not= false (:valid? result true))
                           :else (boolean result))]
              (if valid?
                ictx
                (let [reason (if (map? result) (:reason result :validation-failed) :validation-failed)]
                  (assoc (dissoc ictx :applied-effects)
                         :blocked? true
                         :block-reason reason
                         :validation-error (if (map? result)
                                             (or (:reason result) result)
                                             :validation-failed)))))))))}))

(def trim-effects-on-replay
  (->interceptor
   {:id :trim-effects-on-replay
    :after (fn [ictx]
             (if (:replaying? ictx)
               (dissoc ictx :applied-effects)
               ictx))}))

(def default-interceptors
  [log-interceptor
   handler-interceptor
   effect-interceptor
   trim-effects-on-replay
   validate-interceptor
   apply-interceptor])

(defonce ^:private interceptor-chain-override (atom nil))
(defn set-interceptors! [interceptors] (reset! interceptor-chain-override interceptors) nil)
(defn current-interceptors [] (or @interceptor-chain-override default-interceptors))

(defn dispatch!
  ([env event-type]
   (dispatch! env event-type nil nil (current-interceptors)))
  ([env event-type event-data]
   (dispatch! env event-type event-data nil (current-interceptors)))
  ([env event-type event-data opts]
   (dispatch! env event-type event-data opts (current-interceptors)))
  ([env event-type event-data opts interceptors]
   (let [event (normalize-event event-type event-data opts)
         session-id (event-session-id-of {:event event})
         dispatch-id (:event/dispatch-id event)
         ictx {:env env
               :session-id session-id
               :dispatch-id dispatch-id
               :event event
               :event-type (:event/type event)
               :event-data (:event/data event)
               :origin (:event/origin event)
               :ext-id (:event/ext-id event)
               :replaying? (:event/replaying? event)
               :result nil
               :blocked? false}]
     (append-trace-entry! env
                          {:trace/kind :dispatch/received
                           :dispatch-id dispatch-id
                           :session-id session-id
                           :event-type (:event/type event)
                           :event-data (:event/data event)
                           :origin (:event/origin event)
                           :replaying? (:event/replaying? event)})
     (try
       (let [result-ictx (run-interceptor-chain ictx interceptors)
             failed? (boolean (and (:blocked? result-ictx)
                                   (:validation-error result-ictx)))]
         (append-trace-entry! env
                              (cond-> {:trace/kind (if failed? :dispatch/failed :dispatch/completed)
                                       :dispatch-id dispatch-id
                                       :session-id (event-session-id-of result-ictx)
                                       :event-type (event-type-of result-ictx)
                                       :blocked? (boolean (:blocked? result-ictx))
                                       :result (:result result-ictx)}
                                (:validation-error result-ictx)
                                (assoc :validation-error (:validation-error result-ictx))
                                (:block-reason result-ictx)
                                (assoc :block-reason (:block-reason result-ictx))
                                (:applied-effects result-ictx)
                                (assoc :effects (:applied-effects result-ictx))))
         (when-let [publish-fn (:publish-change-fn env)]
           (try
             (publish-fn {:dispatch-id dispatch-id
                          :event-type (event-type-of result-ictx)
                          :session-id (event-session-id-of result-ictx)
                          :blocked? (boolean (:blocked? result-ictx))})
             (catch Throwable t
               (timbre/debug t "publish-change-fn failed"))))
         (:result result-ictx))
       (catch Throwable t
         (append-trace-entry! env
                              {:trace/kind :dispatch/failed
                               :dispatch-id dispatch-id
                               :session-id session-id
                               :event-type (:event/type event)
                               :error-message (ex-message t)})
         (throw t))))))
