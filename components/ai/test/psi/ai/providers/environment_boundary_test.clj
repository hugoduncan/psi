(ns psi.ai.providers.environment-boundary-test
  (:require [clojure.test :refer [deftest is testing]]
            [psi.ai.providers.environment-boundary :as environment-boundary]))

(deftest nullable-function-lookup-records-ordered-reads-test
  ;; Proves the nullable's function-backed lookup and ordered read-state contract.
  (testing "lookups pass variable names through and retain repeated reads"
    (let [boundary (environment-boundary/nullable
                    (fn [variable]
                      (str "value-for-" variable)))]
      (is (= "value-for-FIRST_KEY"
             (environment-boundary/lookup boundary "FIRST_KEY")))
      (is (= "value-for-SECOND_KEY"
             (environment-boundary/lookup boundary "SECOND_KEY")))
      (is (= "value-for-FIRST_KEY"
             (environment-boundary/lookup boundary "FIRST_KEY")))
      (is (= ["FIRST_KEY" "SECOND_KEY" "FIRST_KEY"]
             (environment-boundary/reads boundary))))))
