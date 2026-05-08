(ns psi.agent-session.deterministic-operation-registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-statechart-runtime :as workflow-statechart-runtime]))

(deftest invoke-step-wrapping-test
  (testing "successful operation result wraps into canonical invoke outputs"
    (is (= {:kind :accepted-result
            :accepted-result {:outcome :ok
                              :outputs {:data {:issues [1]}
                                        :summary "1 issue"
                                        :result {:status :ok
                                                 :data {:issues [1]}
                                                 :summary "1 issue"}}}}
           (workflow-statechart-runtime/operation-result->invoke-step-result
            {:status :ok :data {:issues [1]} :summary "1 issue"}))))

  (testing "error operation result wraps into canonical attempt execution failure input"
    (is (= {:kind :execution-error
            :execution-error {:reason :not-found
                              :message "repo missing"
                              :operation-result {:status :error
                                                 :reason :not-found
                                                 :message "repo missing"}}}
           (workflow-statechart-runtime/operation-result->invoke-step-result
            {:status :error :reason :not-found :message "repo missing"})))))
