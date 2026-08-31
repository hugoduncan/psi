(ns psi.workflow-runtime.terminal-contract-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.terminal-contract :as terminal-contract]))

(deftest cancelled-run-suppresses-accepted-terminal-yield-test
  ;; Tests cancellation prevents a previously accepted terminal result from
  ;; becoming the workflow's yielded text.
  (testing "cancelled terminal outcomes suppress accepted terminal-step text"
    (let [accepted-result {:outputs {:final-llm-reply "accepted terminal text"}}
          workflow-run {:status :cancelled
                        :effective-definition
                        {:step-order ["summary"]
                         :steps {"summary" {:name "summary"
                                            :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                                            :yields {:type :text
                                                     :text :final-llm-reply}}}}
                        :step-runs {"summary" {:accepted-result accepted-result}}
                        :terminal-outcome {:outcome :cancelled
                                           :step-id "summary"
                                           :reason "operator request"}}]
      (is (= accepted-result
             (get-in workflow-run [:step-runs "summary" :accepted-result])))
      (is (nil? (terminal-contract/terminal-yielded-text workflow-run))))))
