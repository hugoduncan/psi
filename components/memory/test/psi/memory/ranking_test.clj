(ns psi.memory.ranking-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.ranking :as ranking]))

(deftest default-ranking-weights-sum-to-100
  (testing "Step 10 defaults validate"
    (is (true? (ranking/weights-valid? ranking/default-weights)))))
