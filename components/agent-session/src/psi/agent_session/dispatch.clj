(ns psi.agent-session.dispatch
  "Compatibility wrapper over the extracted state-kernel dispatch pipeline."
  (:require
   [psi.agent-session.dispatch-schema :as schema]
   [psi.state-kernel.dispatch :as kernel]))

(declare dispatch! ->kernel-env)

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
   (kernel/append-trace-entry! (->kernel-env ctx) entry)))
(def assoc-dispatch-id kernel/assoc-dispatch-id)
(def dispatch-id-of kernel/dispatch-id-of)
(def replay-event-entry! (fn [ctx entry] (kernel/replay-event-entry! dispatch! ctx entry)))
(def replay-event-log! (fn [ctx entries] (kernel/replay-event-log! dispatch! ctx entries)))
(def log-interceptor kernel/log-interceptor)
(def permission-interceptor kernel/permission-interceptor)
(def statechart-interceptor kernel/statechart-interceptor)
(def pure-result? kernel/pure-result?)
(def handler-interceptor kernel/handler-interceptor)
(def apply-interceptor kernel/apply-interceptor)
(def effect-interceptor kernel/effect-interceptor)
(def validate-interceptor kernel/validate-interceptor)
(def trim-effects-on-replay kernel/trim-effects-on-replay)
(def default-interceptors kernel/default-interceptors)
(def set-interceptors! kernel/set-interceptors!)
(def current-interceptors kernel/current-interceptors)
(def apply-root-state-update! kernel/apply-root-state-update!)
(def read-root-state-value kernel/read-root-state-value)

(defn- ->kernel-env [ctx]
  (cond-> ctx
    (and (not (:execute-effect-fn ctx)) (:execute-dispatch-effect-fn ctx))
    (assoc :execute-effect-fn (:execute-dispatch-effect-fn ctx))

    (and (not (:validate-result-fn ctx)) (:validate-dispatch-result-fn ctx))
    (assoc :validate-result-fn (:validate-dispatch-result-fn ctx))))

(defn dispatch!
  ([ctx event-type]
   (kernel/dispatch! (->kernel-env ctx) event-type nil nil))
  ([ctx event-type event-data]
   (kernel/dispatch! (->kernel-env ctx) event-type event-data nil))
  ([ctx event-type event-data opts]
   (kernel/dispatch! (->kernel-env ctx) event-type event-data opts)))
