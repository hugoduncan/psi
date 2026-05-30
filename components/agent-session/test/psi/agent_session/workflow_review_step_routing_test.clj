(ns psi.agent-session.workflow-review-step-routing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.turn]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.deterministic-operation-registry.registry]
   [psi.workflow-runtime.core :as workflow-runtime]))

(def review-step-definition
  {:definition-id "review-step-proof"
   :name "review-step-proof"
   :steps [{:name "review"
            :type :session
            :tools ["read" "bash" "edit" "write"]
            :skills ["work-independently"]
            :contributions [{:type :source :from :workflow-original}
                            {:type :template
                             :text "Review {{input}} with {{skill}}"
                             :vars {"input" {:from :workflow-input :path [:input]}
                                    "skill" {:from :workflow-input :path [:skill]}}}]
            :judge {:type :invoke
                    :operation "workflow/pass-status-routing"
                    :args {:text {:from {:step "review" :output :final-llm-reply}}}}
            :on {"DONE" {:goto :done}
                 "REPEAT" {:goto "follow-up"}}}
           {:name "follow-up"
            :type :session
            :tools ["read" "bash" "edit" "write"]
            :skills ["work-independently"]
            :contributions [{:type :source :from :workflow-original}
                            {:type :source :from {:step "review" :yield :text}}
                            {:type :template
                             :text "Execute follow-up for {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]
            :judge {:type :invoke
                    :operation "workflow/constant-routing"
                    :args {:route "REPEAT"}}
            :on {"REPEAT" {:goto "review" :max-iterations 6}}}]})

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

(defn- create-review-run!
  [ctx run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[s _ _] (workflow-runtime/create-run state {:definition review-step-definition
                                                             :run-id run-id
                                                             :workflow-input {:input "munera/open/189-deterministic-review-step-routing"
                                                                              :skill "task-implementation-review"}})]
             s))))

(deftest review-step-definition-now-validates-with-same-step-invoke-judge-output-ref-test
  (testing "the authored deterministic review-step shape now compiles"
    (let [[ctx _session-id] (support/create-session-context {:persist? false})]
      (register-review-routing-ops! ctx)
      (is (some? (create-review-run! ctx "run-review-complete"))))))

(deftest review-step-actionable-feedback-runs-follow-up-and-loops-back-via-constant-routing-test
  (testing "actionable review output executes follow-up and returns to review via invoke routing"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])
          review-count* (atom 0)]
      (register-review-routing-ops! ctx)
      (create-review-run! ctx "run-review-repeat")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      (let [reply (cond
                                    (= prompt "Review munera/open/189-deterministic-review-step-routing with task-implementation-review")
                                    (if (= 1 (swap! review-count* inc))
                                      "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                      "PASS_STATUS: REVIEW_COMPLETE")

                                    (= prompt "Execute follow-up for munera/open/189-deterministic-review-step-routing")
                                    "follow-up complete"

                                    :else
                                    (throw (ex-info "Unexpected prompt" {:prompt prompt :session-id child-session-id})))]
                        {:execution-result/assistant-message
                         {:role "assistant"
                          :content [{:type :text :text reply}]
                          :stop-reason :stop}}))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-review-repeat")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-review-repeat")]
          (is (= :completed (:status result)))
          (is (= :completed (:status run)))
          (is (= 2 (count (get-in run [:step-runs "review" :attempts]))))
          (is (= 1 (count (get-in run [:step-runs "follow-up" :attempts]))))
          (is (= {:status :ok :data "REPEAT" :summary "REPEAT"}
                 (get-in run [:step-runs "review" :attempts 0 :judge-output :routing-result])))
          (is (= {:status :ok :data "REPEAT" :summary "REPEAT"}
                 (get-in run [:step-runs "follow-up" :attempts 0 :judge-output :routing-result])))
          (is (= {:status :ok :data "DONE" :summary "DONE"}
                 (get-in run [:step-runs "review" :attempts 1 :judge-output :routing-result])))
          (is (= ["Review munera/open/189-deterministic-review-step-routing with task-implementation-review"
                  "Execute follow-up for munera/open/189-deterministic-review-step-routing"
                  "Review munera/open/189-deterministic-review-step-routing with task-implementation-review"]
                 (mapv :prompt @prompts*))))))))

(deftest review-step-pass-status-operation-error-fails-before-follow-up-test
  (testing "missing PASS_STATUS fails with invoke-judge diagnostics and does not execute follow-up"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-review-run! ctx "run-review-error")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text :text "review output without token"}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-review-error")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-review-error")
              attempt (get-in run [:step-runs "review" :attempts 0])]
          (is (= :failed (:status result)))
          (is (= :failed (:status run)))
          (is (= ["Review munera/open/189-deterministic-review-step-routing with task-implementation-review"]
                 (mapv :prompt @prompts*)))
          (is (= {:status :error
                  :reason :missing-pass-status
                  :message "PASS_STATUS missing"
                  :details {:text "review output without token"}}
                 (get-in attempt [:judge-output :routing-result])))
          (is (= {:outcome :failed
                  :reason :missing-pass-status
                  :step-id "review"
                  :attempt-id nil
                  :judge-output {:routing-result {:status :error
                                                  :reason :missing-pass-status
                                                  :message "PASS_STATUS missing"
                                                  :details {:text "review output without token"}}}}
                 (:terminal-outcome run))))))))

(deftest review-step-malformed-pass-status-fails-before-follow-up-test
  (testing "malformed PASS_STATUS fails terminally and does not execute follow-up"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-review-run! ctx "run-review-malformed")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text :text "notes\nPASS_STATUS: MAYBE"}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-review-malformed")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-review-malformed")
              attempt (get-in run [:step-runs "review" :attempts 0])]
          (is (= :failed (:status result)))
          (is (= :failed (:status run)))
          (is (= ["Review munera/open/189-deterministic-review-step-routing with task-implementation-review"]
                 (mapv :prompt @prompts*)))
          (is (= {:status :error
                  :reason :malformed-pass-status
                  :message "PASS_STATUS line must contain exactly one known token"
                  :details {:text "notes\nPASS_STATUS: MAYBE"
                            :line "PASS_STATUS: MAYBE"
                            :value "MAYBE"}}
                 (get-in attempt [:judge-output :routing-result])))
          (is (zero? (count (get-in run [:step-runs "follow-up" :attempts]))))
          (is (= {:outcome :failed
                  :reason :malformed-pass-status
                  :step-id "review"
                  :attempt-id nil
                  :judge-output {:routing-result {:status :error
                                                  :reason :malformed-pass-status
                                                  :message "PASS_STATUS line must contain exactly one known token"
                                                  :details {:text "notes\nPASS_STATUS: MAYBE"
                                                            :line "PASS_STATUS: MAYBE"
                                                            :value "MAYBE"}}}}
                 (:terminal-outcome run))))))))

(deftest review-step-duplicate-pass-status-fails-before-follow-up-test
  (testing "duplicate PASS_STATUS lines fail terminally and do not execute follow-up"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])
          reply "notes\nPASS_STATUS: REVIEW_COMPLETE\nPASS_STATUS: REVIEW_COMPLETE"]
      (register-review-routing-ops! ctx)
      (create-review-run! ctx "run-review-duplicate")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text :text reply}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-review-duplicate")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-review-duplicate")
              attempt (get-in run [:step-runs "review" :attempts 0])]
          (is (= :failed (:status result)))
          (is (= :failed (:status run)))
          (is (= ["Review munera/open/189-deterministic-review-step-routing with task-implementation-review"]
                 (mapv :prompt @prompts*)))
          (is (= {:status :error
                  :reason :ambiguous-pass-status
                  :message "Multiple PASS_STATUS lines found"
                  :details {:text reply
                            :pass-status-lines ["PASS_STATUS: REVIEW_COMPLETE"
                                                "PASS_STATUS: REVIEW_COMPLETE"]}}
                 (get-in attempt [:judge-output :routing-result])))
          (is (zero? (count (get-in run [:step-runs "follow-up" :attempts]))))
          (is (= {:outcome :failed
                  :reason :ambiguous-pass-status
                  :step-id "review"
                  :attempt-id nil
                  :judge-output {:routing-result {:status :error
                                                  :reason :ambiguous-pass-status
                                                  :message "Multiple PASS_STATUS lines found"
                                                  :details {:text reply
                                                            :pass-status-lines ["PASS_STATUS: REVIEW_COMPLETE"
                                                                                "PASS_STATUS: REVIEW_COMPLETE"]}}}}
                 (:terminal-outcome run))))))))
