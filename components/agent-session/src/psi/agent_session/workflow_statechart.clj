(ns psi.agent-session.workflow-statechart
  "Canonical Phase A workflow execution chart compiler for deterministic workflows.

   Public canonical surfaces:
   - workflow-facing definitions remain data in `workflow-model`
   - `compile-hierarchical-chart` produces the Phase A execution chart
   - `initial-step-id` and `next-step-id` expose canonical workflow definition order helpers
   - `workflow-run-chart` and run event/status helpers describe the canonical run lifecycle surface."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-session.workflow-model :as workflow-model]))

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

;;; ============================================================
;;; Phase A — Hierarchical chart compiler
;;; ============================================================

(defn- step-state-id
  "Canonical statechart step namespace id for a step. Used as a naming base only."
  [step-id]
  (keyword (str "step/" step-id)))

(defn- step-acting-state-id
  "Canonical statechart state id for the acting sub-state of a step."
  [step-id]
  (keyword (str "step/" step-id ".acting")))

(defn- step-blocked-state-id
  "Canonical statechart state id for the blocked sub-state of a step."
  [step-id]
  (keyword (str "step/" step-id ".blocked")))

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
   and step-id merged into the data model.

   The callback return value is intentionally discarded; statechart script expressions
   are for side-effects here, not working-memory operations."
  [action-kw step-id]
  (ele/script {:expr (fn [_env data]
                       (when-let [af (:actions-fn data)]
                         (af action-kw (assoc data :step-id step-id)))
                       nil)}))

(defn- make-cancel-transition
  "Create a :workflow/cancel transition to :cancelled."
  []
  (ele/transition {:event :workflow/cancel :target :cancelled}))

(defn- next-step-target
  "Resolve the target acting-state id for the step after `step-id`, or :completed if last."
  [step-order step-id]
  (let [idx (.indexOf ^java.util.List step-order step-id)]
    (if (>= idx (dec (count step-order)))
      :completed
      (step-acting-state-id (nth step-order (inc idx))))))

(defn- routing-target-step-id
  [step-order current-step-id goto]
  (case goto
    :next (let [idx (.indexOf ^java.util.List step-order current-step-id)]
            (when (< idx (dec (count step-order)))
              (nth step-order (inc idx))))
    :done nil
    :previous (let [idx (.indexOf ^java.util.List step-order current-step-id)]
                (when (> idx 0)
                  (nth step-order (dec idx))))
    goto))

(defn- compile-routing-transitions
  "Compile judge routing table `:on` into statechart transitions with guards.

   Each signal in the routing table becomes one or more guarded transitions on
   `:judge/signal`. Iteration-limited routes compile both the success path and the
   exhaustion-to-failed path so the chart remains quiescent only for truly unmatched
   signals."
  [routing-table step-order current-step-id]
  (vec
   (mapcat
    (fn [[signal directive]]
      (let [{:keys [goto max-iterations]} directive
            target-step (routing-target-step-id step-order current-step-id goto)
            target (case goto
                     :next (next-step-target step-order current-step-id)
                     :done :completed
                     :previous (let [idx (.indexOf ^java.util.List step-order current-step-id)]
                                 (if (<= idx 0)
                                   :failed
                                   (step-acting-state-id (nth step-order (dec idx)))))
                     ;; string step-id
                     (step-acting-state-id goto))]
        (if max-iterations
          [(ele/transition {:event :judge/signal
                            :target target
                            :cond (fn [_env data]
                                    (let [signal-str (:signal data)
                                          iter-counts (:iteration-counts data)
                                          iter-count (get iter-counts target-step 0)]
                                      (and (= signal-str signal)
                                           (< iter-count max-iterations))))})
           (ele/transition {:event :judge/signal
                            :target :failed
                            :cond (fn [_env data]
                                    (let [signal-str (:signal data)
                                          iter-counts (:iteration-counts data)
                                          iter-count (get iter-counts target-step 0)]
                                      (and (= signal-str signal)
                                           (>= iter-count max-iterations))))})]
          [(ele/transition {:event :judge/signal
                            :target target
                            :cond (fn [_env data]
                                    (= (:signal data) signal))})])))
    routing-table)))

(defn- actor-retry-available-guard
  "Pure guard: retry is available when working-memory snapshot says the current
   step attempt count is still below the configured max-attempts."
  [step-id]
  (fn [_env data]
    (let [attempt-count (get-in data [:attempt-counts step-id] 0)
          max-attempts (get-in data [:actor-retry-limits step-id] 1)]
      (< attempt-count max-attempts))))

(defn- compile-step-shell
  "Compile the canonical Phase A step shell.

   Every executable step gets:
   - `.acting`
   - `.blocked`
   - optional `.judging`

   `acting-children` are inserted into the `.acting` state and may add judged-step
   behavior such as `.acting -> .judging`.
   `extra-children` are appended at the step shell level (for example `.judging`)."
  [step-id acting-children extra-children]
  (apply ele/state {:id (step-state-id step-id)}
         (concat
          [(apply ele/state {:id (step-acting-state-id step-id)}
                  acting-children)
           (ele/state {:id (step-blocked-state-id step-id)}
                      (ele/on-entry {}
                                    (dispatch-action :step/block step-id))
                      (ele/transition {:event :workflow/resume
                                       :target (step-acting-state-id step-id)})
                      (make-cancel-transition))]
          extra-children)))

(defn- compile-leaf-step
  "Compile a non-judged step into the canonical step shell with `.acting` and `.blocked`."
  [step-id _step-def step-order]
  (let [next-target (next-step-target step-order step-id)]
    (compile-step-shell
     step-id
     [(ele/on-entry {}
                    (dispatch-action :step/enter step-id))
      (ele/transition {:event :actor/done :target next-target}
                      (dispatch-action :step/record-result step-id))
      (ele/transition {:event :actor/failed
                       :target (step-acting-state-id step-id)
                       :cond (actor-retry-available-guard step-id)}
                      (dispatch-action :step/record-failure step-id))
      (ele/transition {:event :actor/blocked
                       :target (step-blocked-state-id step-id)})
      (ele/transition {:event :actor/failed :target :failed}
                      (dispatch-action :step/record-failure step-id))
      (make-cancel-transition)]
     [])))

(defn- judged-routing-transition
  [transition step-id]
  (let [target (:target transition)]
    (if (= target :failed)
      (ele/transition (cond-> {:event :judge/signal
                               :target :failed}
                        (:cond transition) (assoc :cond (:cond transition))))
      (ele/transition (cond-> {:event :judge/signal
                               :target target}
                        (:cond transition) (assoc :cond (:cond transition)))
                      (dispatch-action :judge/record step-id)))))

(defn- compile-judged-step
  "Compile a judged step into the canonical step shell with `.acting`, `.blocked`, and `.judging`."
  [step-id step-def step-order]
  (let [routing-table (or (:on step-def) {})
        routing-transitions (mapv #(judged-routing-transition % step-id)
                                  (compile-routing-transitions routing-table step-order step-id))
        ;; Fallback: if no signal matches and judge retries exhausted → fail
        no-match-fail (ele/transition {:event :judge/no-match :target :failed})]
    (compile-step-shell
     step-id
     [(ele/on-entry {}
                    (dispatch-action :step/enter step-id))
      (ele/transition {:event :actor/done :target (step-judging-state-id step-id)}
                      (dispatch-action :step/record-result step-id))
      (ele/transition {:event :actor/failed
                       :target (step-acting-state-id step-id)
                       :cond (actor-retry-available-guard step-id)}
                      (dispatch-action :step/record-failure step-id))
      (ele/transition {:event :actor/blocked
                       :target (step-blocked-state-id step-id)})
      (ele/transition {:event :actor/failed :target :failed}
                      (dispatch-action :step/record-failure step-id))
      (make-cancel-transition)]
     [(apply ele/state {:id (step-judging-state-id step-id)}
             (ele/on-entry {}
                           (dispatch-action :judge/enter step-id))
             (concat routing-transitions
                     [no-match-fail
                      (make-cancel-transition)]))])))

(defn- compile-step
  "Compile a single step into its statechart state(s)."
  [step-id step-def step-order]
  (if (judged-step? step-def)
    (compile-judged-step step-id step-def step-order)
    (compile-leaf-step step-id step-def step-order)))

(defn compile-hierarchical-chart
  "Compile a workflow definition into a hierarchical statechart.

   Each step becomes a canonical step shell with `.acting` and `.blocked`, and
   judged steps additionally get `.judging`.
   Entry actions dispatch to an actions-fn for side-effects.
   Guards read from working-memory snapshots in the flat data model.

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
                                       :target (step-acting-state-id first-step)})
                      (make-cancel-transition))
           ;; Terminal states
           (ele/state {:id :completed}
                      (ele/on-entry {}
                                    (dispatch-action :terminal/record "completed")))
           (ele/state {:id :failed}
                      (ele/on-entry {}
                                    (dispatch-action :terminal/record "failed")))
           (ele/state {:id :cancelled}
                      (ele/on-entry {}
                                    (dispatch-action :terminal/record "cancelled")))
           ;; Step states
           step-states)))
