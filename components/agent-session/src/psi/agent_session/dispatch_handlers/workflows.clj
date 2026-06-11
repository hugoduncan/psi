(ns psi.agent-session.dispatch-handlers.workflows
  "Dispatch handlers for canonical workflow run terminal requests."
  (:require
   [psi.state-kernel.dispatch :as kernel]
   [psi.workflow-runtime.core :as workflow-runtime]))

(def ^:private terminal-statuses #{:completed :failed :cancelled})
(def ^:private live-statuses #{:pending :running :blocked})

(defn- register-core-handler! [event handler]
  (kernel/register-handler! event handler))

(defn- terminal-status? [status]
  (contains? terminal-statuses status))

(defn- live-status? [status]
  (contains? live-statuses status))

(defn- top-level-run? [run]
  (nil? (:delegating-run-id run)))

(defn- workflow-run [state run-id]
  (workflow-runtime/workflow-run-in state run-id))

(defn- ordered-runs [state]
  (filterv some? (workflow-runtime/list-workflow-runs state)))

(defn- child-runs [runs parent-run-id seen-run-ids]
  (->> runs
       (filter #(= parent-run-id (:delegating-run-id %)))
       (remove #(contains? seen-run-ids (:run-id %)))
       vec))

(defn- live-descendant-runs
  "Return non-terminal descendant runs of root-run-id in canonical run-order."
  [state root-run-id]
  (let [runs (ordered-runs state)]
    (loop [frontier [root-run-id]
           seen #{root-run-id}
           descendants []]
      (if (empty? frontier)
        descendants
        (let [children (mapcat #(child-runs runs % seen) frontier)
              child-ids (mapv :run-id children)]
          (recur child-ids
                 (into seen child-ids)
                 (into descendants (filter #(live-status? (:status %)) children))))))))

(defn- cancellation-cascade-runs
  [state run-id]
  (let [run (workflow-run state run-id)]
    (cond-> []
      run (conj run)
      run (into (live-descendant-runs state run-id)))))

(defn- cancel-cascade-state-update
  [run-ids reason]
  (fn [state]
    (reduce (fn [state' run-id]
              (let [run (workflow-run state' run-id)]
                (if (live-status? (:status run))
                  (first (workflow-runtime/cancel-run state' run-id reason))
                  state')))
            state
            run-ids)))

(defn- remove-state-update
  [run-id]
  (fn [state]
    (if (workflow-run state run-id)
      (first (workflow-runtime/remove-run state run-id))
      state)))

(defn- cancel-inflight-run-effect [run-id]
  {:effect/type :runtime/cancel-inflight-run
   :run-id run-id})

(defn- drop-inflight-run-effect [run-id]
  {:effect/type :runtime/drop-inflight-run
   :run-id run-id})

(defn- handle-cleanup-effects
  [run-id run]
  (cond
    (nil? run)
    [(cancel-inflight-run-effect run-id)
     (drop-inflight-run-effect run-id)]

    (top-level-run? run)
    [(cancel-inflight-run-effect run-id)
     (drop-inflight-run-effect run-id)]

    :else
    [(drop-inflight-run-effect run-id)]))

(defn- live-attempt-abort-effects
  [run]
  (let [step-id (:current-step-id run)
        attempt (last (get-in run [:step-runs step-id :attempts]))
        attempt-id (:attempt-id attempt)
        attempt-status (:status attempt)]
    (when (and step-id attempt-id)
      (cond-> []
        (and (:execution-session-id attempt)
             (contains? #{:running :validating} attempt-status))
        (conj {:effect/type :runtime/agent-abort
               :session-id (:execution-session-id attempt)
               :workflow-run-id (:run-id run)
               :workflow-step-id step-id
               :workflow-attempt-id attempt-id
               :expected-session-id (:execution-session-id attempt)
               :workflow-session-kind :attempt})

        (and (:judge-session-id attempt)
             (contains? #{:running :validating :succeeded} attempt-status))
        (conj {:effect/type :runtime/agent-abort
               :session-id (:judge-session-id attempt)
               :workflow-run-id (:run-id run)
               :workflow-step-id step-id
               :workflow-attempt-id attempt-id
               :expected-session-id (:judge-session-id attempt)
               :workflow-session-kind :judge})))))

(defn- cancellation-effects
  [run cascade-runs]
  (let [abort-effects (mapcat live-attempt-abort-effects cascade-runs)]
    (cond-> [{:effect/type :runtime/mark-workflow-jobs-terminal}]
      (top-level-run? run)
      (conj (cancel-inflight-run-effect (:run-id run)))
      true
      (into abort-effects))))

(defn- remove-dispatch-effect
  [run-id reason session-id]
  {:effect/type :runtime/dispatch-event
   :event-type :psi.workflow/remove-run
   :event-data (cond-> {:run-id run-id}
                 reason (assoc :reason reason)
                 session-id (assoc :session-id session-id))
   :origin :core})

(defn- cancel-result
  [run-id run status noop?]
  (cond-> {:psi.workflow/run-id run-id
           :psi.workflow/status status
           :psi.workflow/cancelled? (= :cancelled status)
           :psi.workflow/noop? noop?
           :psi.workflow/error nil}
    (nil? run) (assoc :psi.workflow/found? false)))

(defn- remove-result
  [run-id {:keys [found? removed? cancelled? noop?]}]
  (cond-> {:psi.workflow/run-id run-id
           :psi.workflow/removed? removed?
           :psi.workflow/noop? noop?
           :psi.workflow/error nil}
    (some? found?) (assoc :psi.workflow/found? found?)
    (some? cancelled?) (assoc :psi.workflow/cancelled? cancelled?)))

(defn- cancel-run-handler
  [ctx {:keys [run-id reason]}]
  (let [reason' (or reason "cancelled")
        state @(:state* ctx)
        run (workflow-run state run-id)
        status (:status run)
        cascade-runs (when (live-status? status)
                       (cancellation-cascade-runs state run-id))]
    (cond
      (nil? run)
      {:return (cancel-result run-id nil nil true)}

      (terminal-status? status)
      {:root-state-update identity
       :effects []
       :return (cancel-result run-id run status true)}

      :else
      {:root-state-update (cancel-cascade-state-update (mapv :run-id cascade-runs) reason')
       :effects (cancellation-effects run cascade-runs)
       :return (cancel-result run-id run :cancelled false)})))

(defn- remove-run-handler
  [ctx {:keys [run-id reason session-id]}]
  (let [reason' (or reason "cancelled by remove")
        state @(:state* ctx)
        run (workflow-run state run-id)
        status (:status run)
        cascade-runs (when (live-status? status)
                       (cancellation-cascade-runs state run-id))]
    (cond
      (nil? run)
      {:root-state-update identity
       :effects (handle-cleanup-effects run-id nil)
       :return (remove-result run-id {:found? false
                                      :removed? false
                                      :noop? true})}

      (terminal-status? status)
      {:root-state-update (remove-state-update run-id)
       :effects (handle-cleanup-effects run-id run)
       :return (remove-result run-id {:found? true
                                      :removed? true
                                      :noop? false})}

      :else
      {:root-state-update (cancel-cascade-state-update (mapv :run-id cascade-runs) reason')
       :effects (conj (cancellation-effects run cascade-runs)
                      (remove-dispatch-effect run-id reason' session-id))
       :return (remove-result run-id {:found? true
                                      :removed? true
                                      :cancelled? true
                                      :noop? false})})))

(defn register!
  "Register canonical workflow terminal event handlers."
  [_ctx]
  (register-core-handler! :psi.workflow/cancel-run cancel-run-handler)
  (register-core-handler! :psi.workflow/remove-run remove-run-handler))
