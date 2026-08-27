(ns psi.agent-session.workflow-implementation-routing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.turn]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.deterministic-operation-registry.registry]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.terminal-contract :as terminal-contract]))

(defn- register-review-routing-ops!
  [ctx]
  (workflow-core/init {:register-operation (fn [operation]
                                             (psi.deterministic-operation-registry.registry/register-operation-in!
                                              (:deterministic-operation-registry ctx)
                                              operation))
                       :register-tool (fn [_] nil)
                       :register-command (fn [& _] nil)
                       :on (fn [& _] nil)
                       :query (fn [& _] nil)
                       :query-session (fn [& _] nil)
                       :mutate (fn [& _] nil)
                       :mutate-session (fn [& _] nil)}))

(def implement-task-definition
  {:definition-id "implement-task-proof"
   :name "implement-task-proof"
   :steps [{:name "implement-pass"
            :type :session
            :contributions [{:type :template
                             :text "Implement {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]
            :judge {:type :invoke
                    :operation "workflow/exact-marker-routing"
                    :args {:text {:from {:step "implement-pass" :output :final-llm-reply}}
                           :marker-label "PASS_STATUS"
                           :allowed-routes ["MORE_WORK_REMAINS"
                                            "IMPLEMENTATION_COMPLETE"
                                            "IMPLEMENTATION_BLOCKED"]}}
            :on {"MORE_WORK_REMAINS" {:goto "implement-pass" :max-iterations 20}
                 "IMPLEMENTATION_COMPLETE" {:goto "final-summary-complete"}
                 "IMPLEMENTATION_BLOCKED" {:goto "final-summary-blocked"}}}
           ;; Deliberately before the complete summary: terminal projection must
           ;; use the executed terminal step rather than declaration order.
           {:name "final-summary-blocked"
            :type :session
            :contributions [{:type :template :text "Blocked summary"}]
            :judge {:type :invoke
                    :operation "workflow/constant-routing"
                    :args {:route "DONE"}}
            :on {"DONE" {:goto :done}}}
           {:name "final-summary-complete"
            :type :session
            :contributions [{:type :template :text "Complete summary"}]
            :judge {:type :invoke
                    :operation "workflow/constant-routing"
                    :args {:route "DONE"}}
            :on {"DONE" {:goto :done}}}]})
(defn- create-implement-task-run!
  [ctx run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[s _ _] (workflow-runtime/create-run state {:definition implement-task-definition
                                                             :run-id run-id
                                                             :workflow-input {:input "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"}})]
             s))))

(deftest implement-task-implementation-complete-routes-to-final-summary-test
  (testing "IMPLEMENTATION_COMPLETE terminates the implementation loop deterministically"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-implement-task-run! ctx "run-implement-complete")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text
                                   :text (case prompt
                                           "Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                                           "No work remains\n\nPASS_STATUS: IMPLEMENTATION_COMPLETE"

                                           "Complete summary"
                                           "complete summary")}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-implement-complete")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-implement-complete")]
          (is (= :completed (:status result)))
          (is (= :completed (:status run)))
          (is (= 1 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (= 1 (count (get-in run [:step-runs "final-summary-complete" :attempts]))))
          (is (= {:status :ok :data "IMPLEMENTATION_COMPLETE" :summary "IMPLEMENTATION_COMPLETE"}
                 (get-in run [:step-runs "implement-pass" :attempts 0 :judge-output :routing-result])))
          (is (= ["Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                  "Complete summary"]
                 (mapv :prompt @prompts*))))))))

(deftest implement-task-implementation-blocked-routes-to-blocked-summary-test
  ;; Tests the authored blocked route terminates without another pass and that
  ;; terminal projection selects this earlier-declared terminal branch.
  (testing "IMPLEMENTATION_BLOCKED reaches the blocked handback"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-implement-task-run! ctx "run-implement-blocked")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _child-session-id prompt]
                      (swap! prompts* conj prompt)
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text
                                   :text (case prompt
                                           "Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                                           "Need a human decision\nPASS_STATUS: IMPLEMENTATION_BLOCKED"
                                           "Blocked summary"
                                           "blocked handback")}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-implement-blocked")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-implement-blocked")]
          (is (= :completed (:status result)))
          (is (= 1 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (= 1 (count (get-in run [:step-runs "final-summary-blocked" :attempts]))))
          (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))))
          (is (= "final-summary-blocked" (get-in run [:terminal-outcome :step-id])))
          (is (= "blocked handback" (terminal-contract/terminal-yielded-text run)))
          (is (= ["Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                  "Blocked summary"]
                 @prompts*)))))))

(deftest implement-task-invalid-statuses-and-repeat-limit-fail-test
  ;; Tests invalid implementation markers fail at the authored exact-marker gate
  ;; and the existing repeat bound remains twenty passes.
  (testing "malformed, duplicate, and unsupported PASS_STATUS markers fail without a summary"
    (doseq [[label reply reason]
            [["malformed" "PASS_STATUS:IMPLEMENTATION_BLOCKED" :malformed-route-marker]
             ["duplicate" "PASS_STATUS: IMPLEMENTATION_COMPLETE\nPASS_STATUS: IMPLEMENTATION_BLOCKED" :ambiguous-route-marker]
             ["unsupported" "PASS_STATUS: UNKNOWN_OUTCOME" :unsupported-route-marker]]]
      (let [[ctx session-id] (support/create-session-context {:persist? false})
            run-id (str "run-implement-" label)]
        (register-review-routing-ops! ctx)
        (create-implement-task-run! ctx run-id)
        (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                      (fn [_ctx _child-session-id _prompt]
                        {:execution-result/assistant-message
                         {:role "assistant"
                          :content [{:type :text :text reply}]
                          :stop-reason :stop}})]
          (let [result (workflow-execution/execute-run! ctx session-id run-id)
                run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
            (is (= :failed (:status result)) label)
            (is (= reason (get-in run [:terminal-outcome :reason])) label)
            (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))) label)
            (is (zero? (count (get-in run [:step-runs "final-summary-blocked" :attempts]))) label))))))
  (testing "the twenty-pass MORE_WORK_REMAINS bound still fails before pass twenty-one"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          run-id "run-implement-repeat-limit"]
      (register-review-routing-ops! ctx)
      (create-implement-task-run! ctx run-id)
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _child-session-id _prompt]
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text :text "PASS_STATUS: MORE_WORK_REMAINS"}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id run-id)
              run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
          (is (= :failed (:status result)))
          (is (= :iteration-exhausted (get-in run [:terminal-outcome :reason])))
          (is (= 20 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))))
          (is (zero? (count (get-in run [:step-runs "final-summary-blocked" :attempts])))))))))

(deftest implement-task-more-work-repeats-then-completes-test
  ;; Tests the implementation loop retains its authored repeat behavior.
  (testing "implementation loop routing continues accepting implementation PASS_STATUS tokens"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-implement-task-run! ctx "run-implement-more-work")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text
                                   :text (case (count @prompts*)
                                           1 "PASS_STATUS: MORE_WORK_REMAINS"
                                           2 "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                                           "Complete summary")}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-implement-more-work")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-implement-more-work")]
          (is (= :completed (:status result)))
          (is (= :completed (:status run)))
          (is (= 2 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (= {:status :ok :data "MORE_WORK_REMAINS" :summary "MORE_WORK_REMAINS"}
                 (get-in run [:step-runs "implement-pass" :attempts 0 :judge-output :routing-result])))
          (is (= {:status :ok :data "IMPLEMENTATION_COMPLETE" :summary "IMPLEMENTATION_COMPLETE"}
                 (get-in run [:step-runs "implement-pass" :attempts 1 :judge-output :routing-result]))))))))
