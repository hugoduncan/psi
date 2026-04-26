(ns psi.agent-session.workflow-progression
  "Namespace removed in task 058.

   Active execution must use `workflow-runtime`, `workflow-statechart-runtime`,
   and `workflow-progression-recording` directly. Sequential compatibility proofs
   now live in test-only support namespaces."
  (:refer-clojure :exclude [compile]))

(defn- removed!
  [surface]
  (throw (ex-info "workflow-progression has been removed; use canonical runtime/recording namespaces directly"
                  {:surface surface
                   :replacements ['psi.agent-session.workflow-runtime
                                  'psi.agent-session.workflow-statechart-runtime
                                  'psi.agent-session.workflow-progression-recording]})))

(defn submit-result-envelope
  [& _]
  (removed! 'submit-result-envelope))

(defn record-execution-failure
  [& _]
  (removed! 'record-execution-failure))

(defn submit-judged-result
  [& _]
  (removed! 'submit-judged-result))