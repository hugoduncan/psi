(ns psi.app-runtime.transcript-test
  "Tests for psi.app-runtime.transcript/agent-messages->tui-resume-state."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.app-runtime.transcript]))

(deftest agent-messages->tui-resume-state-rehydrates-tool-rows-test
  (let [messages [{:role "user"
                   :content [{:type :text :text "read file"}]}
                  {:role "assistant"
                   :content [{:type :text :text "Sure"}
                             {:type :tool-call :id "call-1" :name "read"
                              :arguments "{\"path\":\"a.txt\"}"}]}
                  {:role "toolResult"
                   :tool-call-id "call-1"
                   :tool-name "read"
                   :content [{:type :text :text "hello"}
                             {:type :image :mime-type "image/png" :data "<base64>"}]
                   :details {:full-output-path "/tmp/all.log"}
                   :is-error false}
                  {:role "assistant"
                   :content [{:type :text :text "done"}]}]
        {:keys [messages tool-calls tool-order]}
        (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
    ;; tool-call block emits a :tool message before the assistant text summary
    (is (= [{:role :user :text "read file"}
            {:role :tool :tool-id "call-1"}
            {:role :assistant :text "Sure"}
            {:role :assistant :text "done"}]
           messages))
    (is (= ["call-1"] tool-order))
    (is (= "read" (get-in tool-calls ["call-1" :name])))
    (is (= :success (get-in tool-calls ["call-1" :status])))
    (is (= "hello" (get-in tool-calls ["call-1" :result])))
    (is (= {:full-output-path "/tmp/all.log"}
           (get-in tool-calls ["call-1" :details])))))

(deftest agent-messages->tui-resume-state-supports-structured-content-test
  (let [messages [{:role "assistant"
                   :content {:kind :structured
                             :blocks [{:kind :text :text "planning"}
                                      {:kind :tool-call :id "call-2" :name "read" :input {:path "README.md"}}]}}
                  {:role "toolResult"
                   :tool-call-id "call-2"
                   :tool-name "read"
                   :content [{:type :text :text "ok"}]
                   :is-error false}
                  {:role "assistant"
                   :content {:kind :structured
                             :blocks [{:kind :text :text "done"}]}}]
        {:keys [messages tool-calls tool-order]}
        (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
    ;; tool-call block emits a :tool message before the assistant text summary
    (is (= [{:role :tool :tool-id "call-2"}
            {:role :assistant :text "planning"}
            {:role :assistant :text "done"}]
           messages))
    (is (= ["call-2"] tool-order))
    (is (= "read" (get-in tool-calls ["call-2" :name])))
    (is (= "{:path \"README.md\"}"
           (get-in tool-calls ["call-2" :args])))))

(deftest agent-messages->tui-resume-state-rehydrates-thinking-blocks-test
  (testing "thinking blocks in assistant content are emitted as :thinking messages before the assistant reply"
    (let [messages [{:role "user"
                     :content [{:type :text :text "explain recursion"}]}
                    {:role "assistant"
                     :content [{:type :thinking :text "Let me think about this carefully."}
                               {:type :text :text "Recursion is when a function calls itself."}]}]
          {:keys [messages]}
          (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
      (is (= [{:role :user :text "explain recursion"}
              {:role :thinking :text "Let me think about this carefully."}
              {:role :assistant :text "Recursion is when a function calls itself."}]
             messages)))))

(deftest agent-messages->tui-resume-state-thinking-before-tool-in-block-order-test
  (testing "thinking and tool-call blocks are emitted in block order; assistant text follows"
    (let [messages [{:role "assistant"
                     :content [{:type :thinking :text "Plan A"}
                               {:type :tool-call :id "call-3" :name "bash"
                                :arguments "{\"cmd\":\"ls\"}"}
                               {:type :thinking :text "Plan B"}
                               {:type :text :text "Done."}]}
                    {:role "toolResult"
                     :tool-call-id "call-3"
                     :tool-name "bash"
                     :content [{:type :text :text "file1 file2"}]
                     :is-error false}]
          {:keys [messages tool-order]}
          (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
      ;; thinking A, :tool message, thinking B, then assistant text — in block order
      (is (= {:role :thinking :text "Plan A"} (nth messages 0)))
      (is (= {:role :tool :tool-id "call-3"} (nth messages 1)))
      (is (= {:role :thinking :text "Plan B"} (nth messages 2)))
      (is (= {:role :assistant :text "Done."} (nth messages 3)))
      (is (= ["call-3"] tool-order)))))

(deftest agent-messages->tui-resume-state-structured-content-with-thinking-test
  (testing "structured content map with thinking blocks is rehydrated correctly"
    (let [messages [{:role "assistant"
                     :content {:kind :structured
                               :blocks [{:kind :thinking :text "Structured thinking."}
                                        {:kind :text :text "Structured answer."}]}}]
          {:keys [messages]}
          (#'psi.app-runtime.transcript/agent-messages->tui-resume-state messages)]
      (is (= [{:role :thinking :text "Structured thinking."}
              {:role :assistant :text "Structured answer."}]
             messages)))))
