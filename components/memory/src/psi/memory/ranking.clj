(ns psi.memory.ranking
  "Ranking defaults and validation for memory recovery.")

(def default-weights
  "Step 10 fixed default ranking weights.

   Values are percentages and must sum to 100."
  {:text-relevance 50
   :recency 25
   :capability-proximity 25})

(defn weights-valid?
  "True when ranking weights sum to 100."
  [weights]
  (= 100 (reduce + 0 (vals weights))))
