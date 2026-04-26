(ns psi.agent-session.workflow-statechart-compat
  "Compatibility namespace removed in task 058.

   Retained temporarily as a failing load boundary so any unexpected caller is
   discovered explicitly during migration cleanup."
  (:refer-clojure :exclude [compile]))

(defn- removed!
  [surface]
  (throw (ex-info "workflow-statechart-compat has been removed; use psi.agent-session.workflow-statechart directly"
                  {:surface surface
                   :replacement 'psi.agent-session.workflow-statechart})))

(def workflow-run-chart (removed! 'workflow-run-chart))
(def run-events (removed! 'run-events))
(def run-event->spec (removed! 'run-event->spec))
(def run-status->phase (removed! 'run-status->phase))
(def terminal-run-statuses (removed! 'terminal-run-statuses))
(def terminal-run-status? (removed! 'terminal-run-status?))
(def supported-run-event? (removed! 'supported-run-event?))
(def initial-step-id (removed! 'initial-step-id))
(def next-step-id (removed! 'next-step-id))

(defn compile-definition
  [& _]
  (removed! 'compile-definition))
