(ns psi.workflow-step-materialization.structured-output
  "Canonical lower workflow structured-output contract predicates.

   This namespace owns source identity and validity predicates shared by runtime
   structured-output parsing and step materialization. Runtime namespaces may
   build richer envelopes, but they should not duplicate these contract rules.")

(def structured-output-sources #{:session/structured-output :judge/structured-output})

(defn structured-output-spec?
  [output-spec]
  (contains? structured-output-sources (:source output-spec)))

(defn structured-output-entries
  [outputs]
  (filter (fn [[_ output-spec]]
            (structured-output-spec? output-spec))
          outputs))

(defn single-structured-output-entry
  [outputs]
  (first (structured-output-entries outputs)))

(defn valid-output-result?
  [result]
  (= :valid (get-in result [:structured-output :status])))
