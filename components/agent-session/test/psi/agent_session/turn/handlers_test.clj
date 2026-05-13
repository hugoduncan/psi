(ns psi.agent-session.turn.handlers-test
  "Focused tests for turn handler helpers."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.turn.handlers :as handlers]))

(deftest prompt-finish-base-result-logprobs-in-event-payload-test
  (let [logprobs [{:token "hello" :logprob -0.023 :top []}]
        assistant-msg {:role "assistant" :content [{:type :text :text "hello"}]}
        terminal-result {:execution-result/turn-id "t1"
                         :execution-result/logprobs logprobs
                         :execution-result/assistant-message assistant-msg
                         :execution-result/turn-outcome :response}
        result (handlers/prompt-finish-base-result "s1" "t1" terminal-result nil nil nil)
        ext-effect (first (filter #(= :notify/extension-dispatch (:effect/type %))
                                  (:effects result)))
        payload (:payload ext-effect)]
    (testing "logprobs carried in event payload when present"
      (is (= logprobs (:logprobs payload))))
    (testing "assistant-message carried in event payload"
      (is (= assistant-msg (:assistant-message payload))))
    (testing "session-id and turn-id always present"
      (is (= "s1" (:session-id payload)))
      (is (= "t1" (:turn-id payload))))))

(deftest prompt-finish-base-result-no-logprobs-test
  (let [terminal-result {:execution-result/turn-id "t2"
                         :execution-result/logprobs nil
                         :execution-result/assistant-message {:role "assistant" :content [{:type :text :text "hi"}]}
                         :execution-result/turn-outcome :response}
        result (handlers/prompt-finish-base-result "s1" "t2" terminal-result nil nil nil)
        ext-effect (first (filter #(= :notify/extension-dispatch (:effect/type %))
                                  (:effects result)))
        payload (:payload ext-effect)]
    (testing "logprobs key absent when logprobs nil"
      (is (not (contains? payload :logprobs))))
    (testing "assistant-message still present"
      (is (some? (:assistant-message payload))))))

(deftest prompt-finish-base-result-empty-logprobs-test
  (let [terminal-result {:execution-result/turn-id "t3"
                         :execution-result/logprobs []
                         :execution-result/assistant-message nil
                         :execution-result/turn-outcome :response}
        result (handlers/prompt-finish-base-result "s1" "t3" terminal-result nil nil nil)
        ext-effect (first (filter #(= :notify/extension-dispatch (:effect/type %))
                                  (:effects result)))
        payload (:payload ext-effect)]
    (testing "logprobs key absent when logprobs empty"
      (is (not (contains? payload :logprobs))))
    (testing "assistant-message key absent when nil"
      (is (not (contains? payload :assistant-message))))))
