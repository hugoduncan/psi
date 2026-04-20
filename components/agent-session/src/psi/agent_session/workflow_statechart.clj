(ns psi.agent-session.workflow-statechart
  "Workflow execution statechart + compilation boundary for slice-one deterministic workflows.

   Public surface:
   - workflow-facing definitions remain data in `workflow-model`
   - execution is normalized onto a generic workflow-run statechart
   - sequential workflow compilation derives execution metadata from the definition

   Slice-one execution phases:
   :pending -> :running -> :validating -> (:running | :blocked | :completed | :failed | :cancelled)

   The statechart owns legal transition structure.
   Runtime code will later decide when to emit each event based on step execution,
   validation, retry exhaustion, resume, and cancellation semantics."
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
  "Compile a slice-one sequential workflow definition into execution metadata.

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
