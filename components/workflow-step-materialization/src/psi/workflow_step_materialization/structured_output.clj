(ns psi.workflow-step-materialization.structured-output
  "Lower structured-output result predicates for source materialization.")

(def structured-output-sources #{:session/structured-output :judge/structured-output})

(defn structured-output-spec?
  [output-spec]
  (contains? structured-output-sources (:source output-spec)))

(defn valid-output-result?
  [result]
  (= :valid (get-in result [:structured-output :status])))
