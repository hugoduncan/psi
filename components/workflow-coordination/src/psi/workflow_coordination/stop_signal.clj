(ns psi.workflow-coordination.stop-signal
  "Canonical workflow cancellation/removal stop-signal predicates.

   Cancellation is authoritative in canonical workflow state. A missing workflow
   run record is also a stop signal for workers that were woken after removal.
   Runtime handles/futures are intentionally not consulted here.")

(defn workflow-stop-signal-in-state
  "Return the cooperative workflow stop signal for run-id in state-map, if any.

   Signals:
   - nil run record => :removed
   - :status :cancelled => :cancelled
   - otherwise nil"
  [state-map run-id]
  (when run-id
    (let [workflow-run (get-in state-map [:workflows :runs run-id])]
      (cond
        (nil? workflow-run) :removed
        (= :cancelled (:status workflow-run)) :cancelled
        :else nil))))

(defn workflow-stopped-in-state?
  [state-map run-id]
  (boolean (workflow-stop-signal-in-state state-map run-id)))

(defn workflow-stop-signal
  "Return the cooperative workflow stop signal for run-id from ctx's :state*, if any."
  [ctx run-id]
  (when-let [state* (:state* ctx)]
    (workflow-stop-signal-in-state @state* run-id)))

(defn workflow-stopped?
  [ctx run-id]
  (boolean (workflow-stop-signal ctx run-id)))
