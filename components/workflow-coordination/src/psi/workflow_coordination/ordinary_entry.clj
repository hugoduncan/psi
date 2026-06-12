(ns psi.workflow-coordination.ordinary-entry
  "Shared cancellation-safe phase transitions for workflow ordinary-work entry.

   Workflow actor/judge turns and deterministic-operation invokes both cross from
   workflow state into ordinary side effects through a sequence of small CAS
   phases. This namespace owns the common read/guard/update shape so D31
   cancellation boundaries stay consistent across those entry points."
  (:require
   [psi.workflow-coordination.stop-signal :as stop-signal])
  (:import
   (java.time Instant)))

(defn workflow-attempts-path
  [workflow-run-id workflow-step-id]
  [:workflows :runs workflow-run-id :step-runs workflow-step-id :attempts])

(defn latest-attempt-index
  [attempts]
  (when (seq attempts)
    (dec (count attempts))))

(defn- phase-mismatch-reason
  [attempt {:keys [key value reason]}]
  (when (not= value (get attempt key))
    reason))

(defn- blocked-phase-reason
  [attempt phase-key blocked-states]
  (get blocked-states (get attempt phase-key)))

(defn- phase-already-ok?
  [attempt phase-key ok-states]
  (contains? ok-states (get attempt phase-key)))

(defn- apply-phase-update
  [attempt {:keys [phase-key phase-value timestamp-key count-key]}]
  (cond-> (assoc attempt phase-key phase-value)
    timestamp-key (assoc timestamp-key (Instant/now))
    count-key (update count-key (fnil inc 0))))

(defn transition-latest-attempt-in-state
  "Apply one cancellation-safe ordinary-entry phase transition to the latest
   attempt of `workflow-run-id`/`workflow-step-id` in `state-map`.

   Returns `{:state state-map' :ok? true}` on success. If the workflow is already
   stopped, the latest attempt is missing/mismatched, or a required phase does not
   match, returns `{:state state-map :ok? false :reason reason}`.

   Options:
   - `:workflow-run-id`, `:workflow-step-id`, `:workflow-attempt-id`
   - `:attempt-id-required?` defaults true; false preserves optional operation ids
   - `:missing-attempt-reason`, `:attempt-mismatch-reason`
   - `:required-phases` seq of `{:key k :value v :reason r}` guards
   - `:phase-key`, `:phase-value`, `:timestamp-key`, optional `:count-key`
   - `:ok-states` phase-key values that make the transition an idempotent success
   - `:blocked-states` map of phase-key value -> failure reason"
  [state-map {:keys [workflow-run-id workflow-step-id workflow-attempt-id
                     attempt-id-required? missing-attempt-reason
                     attempt-mismatch-reason required-phases phase-key ok-states
                     blocked-states]
              :or {attempt-id-required? true
                   missing-attempt-reason :attempt-missing
                   attempt-mismatch-reason :attempt-mismatch}
              :as opts}]
  (let [stop-reason (stop-signal/workflow-stop-signal-in-state state-map workflow-run-id)
        attempts-path (workflow-attempts-path workflow-run-id workflow-step-id)
        attempts (get-in state-map attempts-path)
        latest-idx (latest-attempt-index attempts)
        latest-attempt (when latest-idx (nth attempts latest-idx))]
    (cond
      stop-reason
      {:state state-map :ok? false :reason stop-reason}

      (nil? latest-idx)
      {:state state-map :ok? false :reason missing-attempt-reason}

      (and (or attempt-id-required? workflow-attempt-id)
           (not= workflow-attempt-id (:attempt-id latest-attempt)))
      {:state state-map :ok? false :reason attempt-mismatch-reason}

      (some #(phase-mismatch-reason latest-attempt %) required-phases)
      {:state state-map
       :ok? false
       :reason (some #(phase-mismatch-reason latest-attempt %) required-phases)}

      (blocked-phase-reason latest-attempt phase-key blocked-states)
      {:state state-map
       :ok? false
       :reason (blocked-phase-reason latest-attempt phase-key blocked-states)}

      (phase-already-ok? latest-attempt phase-key ok-states)
      {:state state-map :ok? true}

      :else
      {:state (update-in state-map (conj attempts-path latest-idx)
                         #(apply-phase-update % opts))
       :ok? true})))

(defn transition-latest-attempt!
  "CAS `state*` through `transition-latest-attempt-in-state`.

   The workflow stop-signal and attempt guards are evaluated inside each CAS
   attempt, closing the D31 cancel/read/update race for ordinary-entry phases."
  [state* opts]
  (loop []
    (let [state-map @state*
          {:keys [state ok? reason]} (transition-latest-attempt-in-state state-map opts)]
      (cond
        (not ok?) {:ok? false :reason reason}
        (identical? state state-map) {:ok? true}
        (compare-and-set! state* state-map state) {:ok? true}
        :else (recur)))))

(defn keyed-result
  [result success-key]
  (if (:ok? result)
    {success-key true}
    {success-key false :reason (:reason result)}))
