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

(deftest transcript-with-logprobs-appends-synthetic-logprob-context-test
  (testing "workflow session transcript includes synthetic logprob context after assistant message"
    (is (= [{:role "assistant"
             :content [{:type :text :text "done"}]}
            {:role "user"
             :content "[logprob context — previous response]\nUncertain tokens (p < 0.90):\n  \"done\" 0.82  |  \"nope\" 0.18\nAll other tokens: p ≥ 0.90"}]
           (#'step-execution/transcript-with-logprobs
            {:role "assistant"
             :content [{:type :text :text "done"}]}
            [{:token "done"
              :logprob (Math/log 0.82)
              :top [{:token "done" :logprob (Math/log 0.82)}
                    {:token "nope" :logprob (Math/log 0.18)}]}]))))

  (testing "workflow session transcript stays assistant-only when no logprobs were collected"
    (is (= [{:role "assistant"
             :content [{:type :text :text "done"}]}]
           (#'step-execution/transcript-with-logprobs
            {:role "assistant"
             :content [{:type :text :text "done"}]}
            nil)))))
