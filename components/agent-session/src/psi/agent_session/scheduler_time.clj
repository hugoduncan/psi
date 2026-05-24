(ns psi.agent-session.scheduler-time
  "Mandatory time-source boundary for scheduler-owned current-time reads.

   A scheduler time source is a zero-arity function that returns a
   java.time.Instant. Scheduler boundary code must receive one explicitly and
   call `now`; only `system-time-source` reads wall-clock time directly."
  (:import
   (java.time Instant)))

(defn system-time-source
  "Return the production scheduler time source backed by wall-clock time."
  []
  (fn [] (Instant/now)))

(defn now
  "Return the current scheduler Instant from `time-source`.

   Throws an ex-info error at the scheduler time-source boundary when the
   source is absent, not callable, or does not return a java.time.Instant."
  [time-source]
  (when-not (fn? time-source)
    (throw (ex-info "scheduler time-source must be a zero-arity function"
                    {:boundary :scheduler-time-source
                     :time-source time-source})))
  (let [instant (time-source)]
    (when-not (instance? Instant instant)
      (throw (ex-info "scheduler time-source must return a java.time.Instant"
                      {:boundary :scheduler-time-source
                       :value instant})))
    instant))
