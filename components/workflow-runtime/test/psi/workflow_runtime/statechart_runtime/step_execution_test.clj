(ns psi.workflow-runtime.statechart-runtime.step-execution-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.statechart-runtime.step-execution :as step-execution]))

(deftest operation-result->invoke-step-result-test
  (testing "ok deterministic operation results become accepted workflow step results"
    (is (= {:kind :accepted-result
            :accepted-result {:outcome :ok
                              :outputs {:data {:value 42}
                                        :result {:status :ok
                                                 :data {:value 42}
                                                 :summary "done"}
                                        :summary "done"}}}
           (step-execution/operation-result->invoke-step-result
            {:status :ok
             :data {:value 42}
             :summary "done"}))))

  (testing "error deterministic operation results become execution failures"
    (is (= {:kind :execution-error
            :execution-error {:reason :bad-input
                              :message "No repo"
                              :operation-result {:status :error
                                                 :reason :bad-input
                                                 :message "No repo"
                                                 :details {:path "/tmp"}}
                              :operation-details {:path "/tmp"}}}
           (step-execution/operation-result->invoke-step-result
            {:status :error
             :reason :bad-input
             :message "No repo"
             :details {:path "/tmp"}})))))

(deftest assistant-message-text-test
  (testing "assistant-message-text delegates to turn-execution-contract"
    (is (= "hello world"
           (step-execution/assistant-message-text
            {:role "assistant"
             :content [{:type :text :text "hello world"}]})))))
