(ns psi.agent-session.workflow-statechart-compat
  "Compatibility compiler surfaces retained from the Phase B workflow model.

   New execution paths should prefer `psi.agent-session.workflow-statechart`
   hierarchical compilation and `workflow-statechart-runtime`."
  (:require
   [psi.agent-session.workflow-statechart :as workflow-statechart]
   [psi.agent-session.workflow-model :as workflow-model]))

(def workflow-run-chart workflow-statechart/workflow-run-chart)
(def run-events workflow-statechart/run-events)
(def run-event->spec workflow-statechart/run-event->spec)
(def run-status->phase workflow-statechart/run-status->phase)
(def terminal-run-statuses workflow-statechart/terminal-run-statuses)
(def terminal-run-status? workflow-statechart/terminal-run-status?)
(def supported-run-event? workflow-statechart/supported-run-event?)
(def initial-step-id workflow-statechart/initial-step-id)
(def next-step-id workflow-statechart/next-step-id)

(defn compile-definition
  "Compatibility compiler for the legacy Phase B sequential execution metadata."
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
