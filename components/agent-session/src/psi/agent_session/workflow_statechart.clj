(ns psi.agent-session.workflow-statechart
  "Workflow execution statechart + compilation boundary for deterministic workflows.

   Two chart models:
   1. **Status-tracker** (`workflow-run-chart`) — flat :pending/:running/:validating
      states used by Phase B imperative execution. Still used by `compile-definition`.
   2. **Hierarchical** (`compile-hierarchical-chart`) — per-step states compiled from
      workflow definitions. Entry actions drive execution; event-queue drain loop
      replaces the imperative `execute-run!` loop. Phase A target architecture.

   Public surface:
   - workflow-facing definitions remain data in `workflow-model`
   - `compile-definition` produces Phase B execution metadata (flat chart)
   - `compile-hierarchical-chart` produces Phase A statechart (per-step states)"
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-session.workflow-model :as workflow-model]))

(def run-events
  [{:event :workflow/start
    :from #{:pending}
    :to :running
    :meaning "Begin workflow execution."}
   {:event :workflow/attempt-started
    :from #{:running}
    :to :running
    :meaning "Record that the current step attempt has started."}
   {:event :workflow/result-received
    :from #{:running}
    :to :validating
    :meaning "Structured result envelope was received for the current attempt."}
   {:event :workflow/step-succeeded
    :from #{:validating}
    :to :running
    :meaning "Validated result accepted and workflow should continue to the next step."}
   {:event :workflow/block
    :from #{:validating :running}
    :to :blocked
    :meaning "Workflow is blocked on a structured user decision/request."}
   {:event :workflow/resume
    :from #{:blocked}
    :to :running
    :meaning "Workflow resumes from blocked state via a new attempt."}
   {:event :workflow/retry
    :from #{:validating :running}
    :to :running
    :meaning "Current step should be retried with a new attempt."}
   {:event :workflow/fail
    :from #{:validating :running :blocked}
    :to :failed
    :meaning "Workflow reached unrecoverable terminal failure."}
   {:event :workflow/complete
    :from #{:validating :running}
    :to :completed
    :meaning "Workflow finished all steps successfully."}
   {:event :workflow/cancel
    :from #{:pending :running :validating :blocked}
    :to :cancelled
    :meaning "Workflow was externally cancelled."}])

(def run-event->spec
  (into {} (map (juxt :event identity) run-events)))

(def run-status->phase
  {:pending :pending
   :running :running
   :blocked :blocked
   :completed :completed
   :failed :failed
   :cancelled :cancelled})

(def terminal-run-statuses
  #{:completed :failed :cancelled})

(defn terminal-run-status?
  [status]
  (contains? terminal-run-statuses status))

(defn supported-run-event?
  [event]
  (contains? run-event->spec event))

(defn next-step-id
  "Return the next step id after `step-id` in workflow definition order, or nil."
  [definition step-id]
  (let [step-order (:step-order definition)
        idx        (.indexOf ^java.util.List step-order step-id)]
    (when (<= 0 idx)
      (nth step-order (inc idx) nil))))

(defn initial-step-id
  "Return the first step id in definition order, or nil for empty definitions."
  [definition]
  (first (:step-order definition)))

(def workflow-run-chart
  (chart/statechart
   {:id :workflow-run}
   (ele/state {:id :pending}
              (ele/transition {:event :workflow/start :target :running})
              (ele/transition {:event :workflow/cancel :target :cancelled}))
   (ele/state {:id :running}
              (ele/transition {:event :workflow/attempt-started :target :running})
              (ele/transition {:event :workflow/result-received :target :validating})
              (ele/transition {:event :workflow/retry :target :running})
              (ele/transition {:event :workflow/block :target :blocked})
              (ele/transition {:event :workflow/complete :target :completed})
              (ele/transition {:event :workflow/fail :target :failed})
              (ele/transition {:event :workflow/cancel :target :cancelled}))
   (ele/state {:id :validating}
              (ele/transition {:event :workflow/step-succeeded :target :running})
              (ele/transition {:event :workflow/retry :target :running})
              (ele/transition {:event :workflow/block :target :blocked})
              (ele/transition {:event :workflow/complete :target :completed})
              (ele/transition {:event :workflow/fail :target :failed})
              (ele/transition {:event :workflow/cancel :target :cancelled}))
   (ele/state {:id :blocked}
              (ele/transition {:event :workflow/resume :target :running})
              (ele/transition {:event :workflow/fail :target :failed})
              (ele/transition {:event :workflow/cancel :target :cancelled}))
   (ele/state {:id :completed})
   (ele/state {:id :failed})
   (ele/state {:id :cancelled})))

(defn compile-definition
  "Compile a sequential workflow definition into Phase B execution metadata.

   The compiled artifact deliberately keeps workflow-facing authoring data intact,
   while attaching the generic run chart and derived sequential helpers needed by
   runtime orchestration."
  [definition]
  (when-not (workflow-model/valid-workflow-definition? definition)
    (throw (ex-info "Invalid workflow definition"
                    {:explanation (workflow-model/explain-workflow-definition definition)})))
  {:execution-model :sequential
   :chart workflow-run-chart
   :run-events run-events
   :initial-step-id (initial-step-id definition)
   :step-order (:step-order definition)
   :steps (:steps definition)
   :next-step-id-fn (fn [step-id] (next-step-id definition step-id))})

;;; ============================================================
;;; Phase A — Hierarchical chart compiler
;;; ============================================================

(defn- step-state-id
  "Canonical statechart state id for a step."
  [step-id]
  (keyword (str "step/" step-id)))

(defn- step-acting-state-id
  "Canonical statechart state id for the acting sub-state of a judged step."
  [step-id]
  (keyword (str "step/" step-id ".acting")))

(defn- step-judging-state-id
  "Canonical statechart state id for the judging sub-state of a judged step."
  [step-id]
  (keyword (str "step/" step-id ".judging")))

(defn- judged-step?
  "True if a step definition has a judge."
  [step-def]
  (some? (:judge step-def)))

(defn- dispatch-action
  "Create a script element that calls the actions-fn with the given action keyword
   and step-id merged into the data model."
  [action-kw step-id]
  (ele/script {:expr (fn [_env data]
                       (when-let [af (:actions-fn data)]
                         (af action-kw (assoc data :step-id step-id))))}))

(defn- make-cancel-transition
  "Create a :workflow/cancel transition to :cancelled."
  []
  (ele/transition {:event :workflow/cancel :target :cancelled}))

(defn- make-fail-transition
  "Create an :actor/failed transition to :failed (no retry guard)."
  []
  (ele/transition {:event :actor/failed :target :failed}))

(defn- next-step-target
  "Resolve the target state id for the step after `step-id`, or :completed if last."
  [step-order step-id]
  (let [idx (.indexOf ^java.util.List step-order step-id)]
    (if (>= idx (dec (count step-order)))
      :completed
      (step-state-id (nth step-order (inc idx))))))

(defn- compile-routing-transitions
  "Compile judge routing table `:on` into statechart transitions with guards.

   Each signal in the routing table becomes a guarded transition on `:judge/signal`.
   The guard checks that the signal string in the event data matches."
  [routing-table step-order current-step-id]
  (let [transitions
        (mapv (fn [[signal directive]]
                (let [{:keys [goto max-iterations]} directive
                      target (case goto
                               :next (next-step-target step-order current-step-id)
                               :done :completed
                               :previous (let [idx (.indexOf ^java.util.List step-order current-step-id)]
                                           (if (<= idx 0)
                                             :failed
                                             (step-state-id (nth step-order (dec idx)))))
                               ;; string step-id
                               (step-state-id goto))]
                  (if max-iterations
                    ;; Guarded: check iteration limit
                    (ele/transition {:event :judge/signal
                                     :target target
                                     :cond (fn [_env data]
                                             (let [signal-str (:signal data)
                                                   iter-counts (:iteration-counts data)
                                                   target-step (case goto
                                                                 :next (let [idx (.indexOf ^java.util.List step-order current-step-id)]
                                                                         (when (< idx (dec (count step-order)))
                                                                           (nth step-order (inc idx))))
                                                                 :done nil
                                                                 :previous (let [idx (.indexOf ^java.util.List step-order current-step-id)]
                                                                             (when (> idx 0)
                                                                               (nth step-order (dec idx))))
                                                                ;; string
                                                                 goto)
                                                   iter-count (get iter-counts target-step 0)]
                                               (and (= signal-str signal)
                                                    (< iter-count max-iterations))))})
                    ;; Unguarded: just match signal
                    (ele/transition {:event :judge/signal
                                     :target target
                                     :cond (fn [_env data]
                                             (= (:signal data) signal))}))))
              routing-table)]
    transitions))

(defn- compile-leaf-step
  "Compile a non-judged step into a leaf statechart state."
  [step-id _step-def step-order]
  (let [next-target (next-step-target step-order step-id)]
    (ele/state {:id (step-state-id step-id)}
               (ele/on-entry {}
                             (dispatch-action :step/enter step-id))
               (ele/on-exit {}
                            (dispatch-action :step/exit step-id))
               (ele/transition {:event :actor/done :target next-target})
               (ele/transition {:event :actor/failed :target (step-state-id step-id)
                                :cond (fn [_env data]
                                        (let [af (:actions-fn data)]
                                          (when af (af :retry-available? (assoc data :step-id step-id)))))})
               (make-fail-transition)
               (make-cancel-transition))))

(defn- compile-judged-step
  "Compile a judged step into a compound statechart state with .acting and .judging sub-states."
  [step-id step-def step-order]
  (let [routing-table (or (:on step-def) {})
        routing-transitions (compile-routing-transitions routing-table step-order step-id)
        ;; Fallback: if no signal matches and judge retries exhausted → fail
        no-match-fail (ele/transition {:event :judge/no-match :target :failed})]
    (ele/state {:id (step-state-id step-id)}
               ;; Acting sub-state
               (ele/state {:id (step-acting-state-id step-id)}
                          (ele/on-entry {}
                                        (dispatch-action :step/enter step-id))
                          (ele/on-exit {}
                                       (dispatch-action :step/exit step-id))
                          (ele/transition {:event :actor/done :target (step-judging-state-id step-id)})
                          (ele/transition {:event :actor/failed :target (step-acting-state-id step-id)
                                           :cond (fn [_env data]
                                                   (let [af (:actions-fn data)]
                                                     (when af (af :retry-available? (assoc data :step-id step-id)))))})
                          (make-fail-transition)
                          (make-cancel-transition))
               ;; Judging sub-state
               (apply ele/state {:id (step-judging-state-id step-id)}
                      (ele/on-entry {}
                                    (dispatch-action :judge/enter step-id))
                      (ele/on-exit {}
                                   (dispatch-action :judge/exit step-id))
                      (concat routing-transitions
                              [no-match-fail
                               (make-cancel-transition)])))))

(defn- compile-step
  "Compile a single step into its statechart state(s)."
  [step-id step-def step-order]
  (if (judged-step? step-def)
    (compile-judged-step step-id step-def step-order)
    (compile-leaf-step step-id step-def step-order)))

(defn compile-hierarchical-chart
  "Compile a workflow definition into a hierarchical statechart.

   Each step becomes a state (leaf for non-judged, compound for judged).
   Entry actions dispatch to an actions-fn for side-effects.
   Guards read from external workflow context atoms.

   Returns a fulcrologic statechart definition suitable for `simple/register!`."
  [definition]
  (when-not (workflow-model/valid-workflow-definition? definition)
    (throw (ex-info "Invalid workflow definition"
                    {:explanation (workflow-model/explain-workflow-definition definition)})))
  (let [step-order (:step-order definition)
        steps      (:steps definition)
        step-states (mapv (fn [step-id]
                            (compile-step step-id (get steps step-id) step-order))
                          step-order)
        first-step (first step-order)]
    (apply chart/statechart {:id :workflow-run}
           ;; Pending state
           (ele/state {:id :pending}
                      (ele/transition {:event :workflow/start
                                       :target (step-state-id first-step)})
                      (make-cancel-transition))
           ;; Terminal states
           (ele/state {:id :completed}
                      (ele/on-entry {}
                                    (dispatch-action :terminal/enter "completed")))
           (ele/state {:id :failed}
                      (ele/on-entry {}
                                    (dispatch-action :terminal/enter "failed")))
           (ele/state {:id :cancelled}
                      (ele/on-entry {}
                                    (dispatch-action :terminal/enter "cancelled")))
           ;; Step states
           step-states)))
