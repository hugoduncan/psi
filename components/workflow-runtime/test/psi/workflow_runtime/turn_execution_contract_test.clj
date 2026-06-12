(ns psi.workflow-runtime.turn-execution-contract-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]
   [psi.workflow-runtime.turn-execution-contract :as turn-execution]))

(defn- cancelled-workflow-turn-ctx
  [prompted* session-data]
  {execution-adapter/adapter-key
   (execution-adapter/create
    {:get-session-data (fn [_ctx session-id]
                         (is (= (:session-id session-data) session-id))
                         session-data)
     :prompt-execution-result! (fn [& _]
                                 (swap! prompted* inc)
                                 {:execution-result/assistant-message {:role "assistant"
                                                                       :content [{:type :text :text "started"}]}})})
   :state* (atom {:workflows {:runs {(:workflow-run-id session-data)
                                     {:run-id (:workflow-run-id session-data)
                                      :status :cancelled}}}})})

(deftest workflow-owned-actor-turn-start-honors-canonical-cancellation-test
  ;; Regression for task 225 implementation review pass 6: if cancellation wins
  ;; after workflow pre-start checks, the turn-start boundary must still consume
  ;; the canonical workflow stop marker and avoid starting the ordinary actor turn.
  (testing "actor turn start is blocked by cancelled workflow run"
    (let [prompted* (atom 0)
          session-data {:session-id "actor-child"
                        :workflow-owned? true
                        :workflow-run-id "run-cancelled"
                        :workflow-step-id "plan"
                        :workflow-attempt-id "attempt-1"}
          ctx (cancelled-workflow-turn-ctx prompted* session-data)
          result (turn-execution/execute-actor-turn! ctx "actor-child" "Plan")]
      (is (= 0 @prompted*)
          "the lower prompt adapter must not be invoked after cancellation")
      (is (= :error (:status result)))
      (is (= "Workflow execution stopped before turn start"
             (get-in result [:failure :message])))
      (is (= :cancelled (get-in result [:assistant-message :workflow-stop-reason]))))))

(deftest workflow-owned-judge-turn-start-honors-canonical-cancellation-test
  ;; Regression for task 225 implementation review pass 6: initial judge turns
  ;; use the same turn-start boundary guard, so a cancellation that lands after a
  ;; judge pre-start checkpoint cannot start the ordinary judge prompt.
  (testing "judge turn start is blocked by cancelled workflow run"
    (let [prompted* (atom 0)
          session-data {:session-id "judge-child"
                        :workflow-owned? true
                        :workflow-run-id "run-cancelled"
                        :workflow-step-id "review"
                        :workflow-attempt-id "attempt-1"}
          ctx (cancelled-workflow-turn-ctx prompted* session-data)
          result (turn-execution/execute-judge-turn! ctx "judge-child" "APPROVED?")]
      (is (= 0 @prompted*)
          "the lower prompt adapter must not be invoked after cancellation")
      (is (= :error (:status result)))
      (is (= "Workflow execution stopped before turn start"
             (get-in result [:failure :message])))
      (is (= :cancelled (get-in result [:assistant-message :workflow-stop-reason]))))))
