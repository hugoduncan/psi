(ns psi.turn-runtime.recording-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.turn-runtime.recording :as recording]))

(deftest classify-assistant-message-test
  (testing "text-only assistant message is stop"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :text :text "done"}]
                         :stop-reason :stop}
          outcome       (recording/classify-assistant-message assistant-msg)]
      (is (= :turn.outcome/stop (:turn/outcome outcome)))
      (is (= [] (:tool-calls outcome)))))

  (testing "assistant tool-call message is tool-use"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :text :text "checking"}
                                   {:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                         :stop-reason :tool_use}
          outcome       (recording/classify-assistant-message assistant-msg)]
      (is (= :turn.outcome/tool-use (:turn/outcome outcome)))
      (is (= [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
             (:tool-calls outcome)))))

  (testing "error assistant message is terminal error"
    (let [assistant-msg {:role "assistant"
                         :content [{:type :error :text "boom"}
                                   {:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                         :stop-reason :error}
          outcome       (recording/classify-assistant-message assistant-msg)]
      (is (= :turn.outcome/error (:turn/outcome outcome)))
      (is (= [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
             (:tool-calls outcome))))))

(deftest build-recording-decision-and-usage-tokens-test
  (let [execution-result {:execution-result/turn-id "turn-1"
                          :execution-result/assistant-message {:role "assistant"
                                                               :content [{:type :tool-call :id "call-1" :name "read" :arguments "{}"}]
                                                               :stop-reason :tool_use}
                          :execution-result/usage {:input-tokens 10
                                                   :output-tokens 20
                                                   :cache-read-tokens 3
                                                   :cache-write-tokens 2}}
        decision         (recording/build-recording-decision execution-result)]
    (is (= "turn-1" (:turn-id decision)))
    (is (= :turn.outcome/tool-use (:turn-outcome decision)))
    (is (= :session/prompt-continue (:next-event decision)))
    (is (= 35 (recording/execution-usage-tokens execution-result)))))
