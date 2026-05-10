(ns psi.workflow-step-materialization.semantics
  "Shared lower workflow step materialization semantics.

   Owns canonical effective-step lookup plus normalized output/yield resolution
   used by workflow step materialization and source-resolution surfaces without
   re-expanding workflow-runtime ownership."
  (:require
   [psi.workflow-judge :as workflow-judge]))

(defn effective-steps
  [definition]
  (or (some->> (get-in definition [:canonical-ir :steps])
               (mapv (juxt :name identity))
               (into {})
               not-empty)
      (:steps definition)))

(defn effective-step-def
  [workflow-run step-id]
  (get (effective-steps (:effective-definition workflow-run)) step-id))

(defn step-output-value
  [accepted-result output-key]
  (let [raw-outputs (:outputs accepted-result)]
    (case output-key
      :result accepted-result
      :final-llm-reply (or (get raw-outputs :final-llm-reply)
                           (get raw-outputs :text))
      :handoff (get raw-outputs :handoff)
      (get raw-outputs output-key))))

(defn step-yield-field-value
  [step accepted-result yield-field]
  (let [yield-spec (:yields step)]
    (case (:type yield-spec)
      :data (when (= :data yield-field)
              (step-output-value accepted-result (:data yield-spec)))
      :text (when (= :text yield-field)
              (step-output-value accepted-result (:text yield-spec)))
      :error (get-in accepted-result [:blocked yield-field])
      :delegated (when (= :text yield-field)
                   (step-output-value accepted-result :final-llm-reply))
      nil)))

(defn project-source-value
  [base projection]
  (if (= :full projection)
    base
    (workflow-judge/project-messages base projection)))
