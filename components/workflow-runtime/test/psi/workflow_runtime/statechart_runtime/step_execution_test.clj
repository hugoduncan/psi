(ns psi.workflow-runtime.statechart-runtime.step-execution-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.statechart-runtime.step-execution :as step-execution]
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]))

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

;; NOTE: `:session-id` is included in session-step raw outputs (execute-session-step!)
;; but requires full runtime context (ctx, parent session, execution session) to test.
;; This surface is integration-tested via workflow execution (e.g. local-logprobs workflow
;; exercises {:from {:step "run" :output :session-id}} in its invoke step).
;; A unit-level assertion is impractical here without substantial test infrastructure.

(deftest execute-session-step-invalid-structured-output-blocks-with-envelope-test
  (testing "invalid structured output records raw output and validation errors instead of escaping surface resolution"
    (let [working-memory* (atom {:current-step-id "classify"})
          event-queue* (atom [])
          raw-output "not json"
          turn-opts* (atom nil)]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx session-id prompt opts]
                      (is (= "child-session" session-id))
                      (is (= "Classify" prompt))
                      (reset! turn-opts* opts)
                      {:status :ok
                       :assistant-text raw-output
                       :execution-result nil
                       :assistant-message nil})]
        (step-execution/execute-session-step!
         {}
         {:session-id "child-session"}
         {:name "classify"
          :type :session
          :outputs {:classification {:source :session/structured-output
                                     :mode :structured
                                     :schema-id :psi.workflow/test-classification
                                     :schema-version 1
                                     :schema [:map
                                              [:decision [:enum :pass :fail]]]
                                     :json-schema {:type "object"
                                                   :required ["decision"]
                                                   :properties {"decision" {:type "string"}}}}}}
         "classify"
         "attempt-1"
         working-memory*
         event-queue*
         "Classify"))
      (let [pending (:pending-actor-result @working-memory*)
            payload (:payload pending)
            classification (get-in payload [:outputs :classification])]
        (is (= :blocked (:kind pending)))
        (is (= :blocked (:outcome payload)))
        (is (= :invalid-structured-output (get-in payload [:blocked :reason])))
        (is (= raw-output (:raw-output classification)))
        (is (= :invalid (get-in classification [:structured-output :status])))
        (is (seq (get-in classification [:structured-output :errors])))
        (is (= raw-output (get-in payload [:outputs :final-llm-reply])))
        (is (= {:structured-output {:schema-id :psi.workflow/test-classification
                                    :schema-version 1
                                    :json-schema {:type "object"
                                                  :required ["decision"]
                                                  :properties {"decision" {:type "string"}}}
                                    :strategy-preference :provider-native
                                    :fallback-allowed? true
                                    :strict? true}}
               @turn-opts*))
        (is (= :actor/blocked (:event (first @event-queue*))))))))

(deftest execute-session-step-text-output-remains-compatible-test
  (testing "session steps without structured outputs still accept text outputs unchanged"
    (let [working-memory* (atom {:current-step-id "summarize"})
          event-queue* (atom [])
          raw-output "plain human summary"]
      (with-redefs [turn-execution/execute-actor-turn!
                    (fn [_ctx session-id prompt]
                      (is (= "child-session" session-id))
                      (is (= "Summarize" prompt))
                      {:status :ok
                       :assistant-text raw-output
                       :execution-result nil
                       :assistant-message {:role "assistant"
                                           :content [{:type :text :text raw-output}]}})]
        (step-execution/execute-session-step!
         {}
         {:session-id "child-session"}
         {:name "summarize"
          :type :session
          :outputs {:final-llm-reply {:source :session/final-llm-reply}
                    :transcript {:source :session/transcript}}
          :yields {:type :text :text :final-llm-reply}}
         "summarize"
         "attempt-1"
         working-memory*
         event-queue*
         "Summarize"))
      (let [pending (:pending-actor-result @working-memory*)
            payload (:payload pending)]
        (is (= :success (:kind pending)))
        (is (= :ok (:outcome payload)))
        (is (= raw-output (get-in payload [:outputs :final-llm-reply])))
        (is (= raw-output (get-in payload [:outputs :text])))
        (is (= [{:role "assistant" :content [{:type :text :text raw-output}]}]
               (get-in payload [:outputs :transcript])))
        (is (string? (get-in payload [:outputs :final-llm-reply])))
        (is (= :actor/done (:event (first @event-queue*))))))))

(deftest assistant-message-text-test
  (testing "assistant-message-text delegates to turn-execution-contract"
    (is (= "hello world"
           (step-execution/assistant-message-text
            {:role "assistant"
             :content [{:type :text :text "hello world"}]})))))
