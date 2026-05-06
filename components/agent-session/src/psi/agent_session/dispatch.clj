(ns psi.agent-session.dispatch
  "Compatibility wrapper over the extracted state-kernel dispatch pipeline."
  (:require
   [psi.agent-session.dispatch-schema :as schema]
   [psi.state-kernel.dispatch :as kernel]))

(declare dispatch! ->dispatch-env ->kernel-contract-env apply-interceptor)

(def effect-schema schema/effect-schema)
(def pure-result-schema schema/pure-result-schema)
(def valid-effect? schema/valid-effect?)
(def explain-effect schema/explain-effect)
(def valid-pure-result?* schema/valid-pure-result?*)
(def explain-pure-result schema/explain-pure-result)
(def validate-dispatch-schemas schema/validate-dispatch-schemas)

(def handler-entry kernel/handler-entry)
(def register-handler! kernel/register-handler!)
(def registered-event-types kernel/registered-event-types)
(def registered-handler-entries kernel/registered-handler-entries)
(def clear-handlers! kernel/clear-handlers!)
(def ->interceptor kernel/->interceptor)
(def run-interceptor-chain kernel/run-interceptor-chain)
(def normalize-event kernel/normalize-event)
(def event-log-entries kernel/event-log-entries)
(def dispatch-trace-entries kernel/dispatch-trace-entries)
(def clear-event-log! kernel/clear-event-log!)
(def clear-dispatch-trace! kernel/clear-dispatch-trace!)
(def next-dispatch-id kernel/next-dispatch-id)
(defn append-trace-entry!
  ([entry]
   (kernel/append-trace-entry! nil entry))
  ([ctx entry]
   (kernel/append-trace-entry! (->dispatch-env ctx) entry)))
(def assoc-dispatch-id kernel/assoc-dispatch-id)
(def dispatch-id-of kernel/dispatch-id-of)
(def replay-event-entry! (fn [ctx entry] (kernel/replay-event-entry! dispatch! ctx entry)))
(def replay-event-log! (fn [ctx entries] (kernel/replay-event-log! dispatch! ctx entries)))
(def log-interceptor kernel/log-interceptor)
(def pure-result? kernel/pure-result?)
(def handler-interceptor kernel/handler-interceptor)
(def effect-interceptor kernel/effect-interceptor)
(def validate-interceptor kernel/validate-interceptor)
(def trim-effects-on-replay kernel/trim-effects-on-replay)

(def permission-interceptor
  (->interceptor
   {:id :permission
    :before
    (fn [ictx]
      (if (= :extension (or (:origin ictx)
                            (get-in ictx [:event :event/origin])
                            :core))
        (let [ext-id (or (:ext-id ictx)
                         (get-in ictx [:event :event/ext-id]))
              event-type (or (:event-type ictx)
                             (get-in ictx [:event :event/type]))
              ctx (:env ictx)
              reg (:extension-registry ctx)
              state (when reg @(:state reg))
              ext-record (when (and ext-id state)
                           (get-in state [:extensions ext-id]))
              known? (some? ext-record)
              allowed-set (:allowed-events ext-record)]
          (cond
            (not known?)
            (assoc ictx :blocked? true :block-reason :unknown-extension)

            (and (set? allowed-set)
                 (contains? allowed-set event-type))
            ictx

            :else
            (assoc ictx :blocked? true
                   :block-reason {:reason :permission-denied
                                  :event-type event-type
                                  :ext-id ext-id})))
        ictx))}))

(def statechart-interceptor
  (->interceptor
   {:id :statechart
    :before
    (fn [ictx]
      (if (:blocked? ictx)
        ictx
        (let [ctx (:env ictx)
              dispatch-fn (:dispatch-statechart-event-fn ctx)
              event-type (or (:event-type ictx)
                             (get-in ictx [:event :event/type]))
              event-data (if (contains? ictx :event-data)
                           (:event-data ictx)
                           (get-in ictx [:event :event/data]))]
          (if-not (fn? dispatch-fn)
            ictx
            (let [result (dispatch-fn ctx event-type event-data ictx)
                  claimed? (cond
                             (map? result) (boolean (:claimed? result))
                             :else (boolean result))]
              (if claimed?
                (let [claimed-ictx (assoc ictx :blocked? true)]
                  (if (map? result)
                    (cond-> claimed-ictx
                      (contains? result :result) (assoc :result (:result result))
                      (contains? result :blocked?) (assoc :blocked? (boolean (:blocked? result)))
                      (contains? result :block-reason) (assoc :block-reason (:block-reason result)))
                    claimed-ictx))
                ictx))))))}))

(defonce ^:private interceptor-chain-override (atom nil))

(defn set-interceptors!
  "Override the agent-session interceptor chain. Pass nil to restore defaults."
  [interceptors]
  (reset! interceptor-chain-override interceptors)
  nil)

(defn apply-root-state-update!
  [ctx root-update-fn]
  (cond
    (and (fn? root-update-fn) (fn? (:apply-root-state-update-fn ctx)))
    ((:apply-root-state-update-fn ctx) ctx root-update-fn)

    (and (fn? root-update-fn) (:state* ctx))
    (swap! (:state* ctx) root-update-fn)

    :else nil)
  nil)

(defn read-root-state-value
  [ctx return-key]
  (cond
    (fn? (:read-session-state-fn ctx))
    ((:read-session-state-fn ctx) ctx return-key)

    (:state* ctx)
    (if (vector? return-key)
      (get-in @(:state* ctx) return-key)
      (get @(:state* ctx) return-key))

    :else nil))

(def apply-interceptor
  (->interceptor
   {:id :apply
    :after
    (fn [ictx]
      (kernel/apply-pure-result
       ictx
       {:apply-root-state-update-fn apply-root-state-update!
        :read-root-state-value-fn read-root-state-value
        :session-id-fn (fn [ictx]
                         (or (:session-id ictx)
                             (get-in ictx [:event :event/session-id])))
        :event-type-fn (fn [ictx]
                         (or (:event-type ictx)
                             (get-in ictx [:event :event/type])))
        :append-trace-entry-fn append-trace-entry!}))}))

(def default-interceptors
  [permission-interceptor
   log-interceptor
   statechart-interceptor
   handler-interceptor
   effect-interceptor
   trim-effects-on-replay
   validate-interceptor
   apply-interceptor])

(defn current-interceptors []
  (or @interceptor-chain-override default-interceptors))

(defn- ->kernel-contract-env [ctx]
  (cond-> ctx
    (and (not (:execute-effect-fn ctx)) (:execute-dispatch-effect-fn ctx))
    (assoc :execute-effect-fn (:execute-dispatch-effect-fn ctx))

    (and (not (:validate-result-fn ctx)) (:validate-dispatch-result-fn ctx))
    (assoc :validate-result-fn (:validate-dispatch-result-fn ctx))))

(defn- ->dispatch-env
  [ctx]
  (cond-> (->kernel-contract-env ctx)
    (:apply-root-state-update-fn ctx)
    (assoc :apply-root-state-update-fn (:apply-root-state-update-fn ctx))

    (:read-session-state-fn ctx)
    (assoc :read-session-state-fn (:read-session-state-fn ctx))))

(defn dispatch!
  ([ctx event-type]
   (kernel/dispatch! (->dispatch-env ctx) event-type nil nil (current-interceptors)))
  ([ctx event-type event-data]
   (kernel/dispatch! (->dispatch-env ctx) event-type event-data nil (current-interceptors)))
  ([ctx event-type event-data opts]
   (kernel/dispatch! (->dispatch-env ctx) event-type event-data opts (current-interceptors))))
