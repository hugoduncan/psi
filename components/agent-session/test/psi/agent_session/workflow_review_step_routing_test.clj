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
                    :args {:text {:from {:step "review" :output :final-llm-reply}}
                           :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}
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

(def implement-task-definition
  {:definition-id "implement-task-proof"
   :name "implement-task-proof"
   :steps [{:name "implement-pass"
            :type :session
            :contributions [{:type :template
                             :text "Implement {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]
            :judge {:type :invoke
                    :operation "workflow/pass-status-routing"
                    :args {:text {:from {:step "implement-pass" :output :final-llm-reply}}}}
            :on {"REPEAT" {:goto "implement-pass" :max-iterations 8}
                 "DONE" {:goto "final-summary"}}}
           {:name "final-summary"
            :type :session
            :contributions [{:type :template :text "Final summary"}]}]})

(defn- create-implement-task-run!
  [ctx run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[s _ _] (workflow-runtime/create-run state {:definition implement-task-definition
                                                             :run-id run-id
                                                             :workflow-input {:input "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"}})]
             s))))

(deftest review-step-definition-now-validates-with-same-step-invoke-judge-output-ref-test
  (testing "the authored deterministic review-step shape now compiles"
    (let [[ctx _session-id] (support/create-session-context {:persist? false})]
      (register-review-routing-ops! ctx)
      (is (some? (create-review-run! ctx "run-review-complete"))))))

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

                                           "Final summary"
                                           "final summary")}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-implement-complete")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-implement-complete")]
          (is (= :completed (:status result)))
          (is (= :completed (:status run)))
          (is (= 1 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (= 1 (count (get-in run [:step-runs "final-summary" :attempts]))))
          (is (= {:status :ok :data "DONE" :summary "DONE"}
                 (get-in run [:step-runs "implement-pass" :attempts 0 :judge-output :routing-result])))
          (is (= ["Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                  "Final summary"]
                 (mapv :prompt @prompts*))))))))

(deftest review-step-invalid-implementation-status-fails-before-follow-up-test
  (testing "implementation-only PASS_STATUS tokens are invalid for generic review-step routing"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-review-run! ctx "run-review-invalid-implementation-status")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text :text "PASS_STATUS: IMPLEMENTATION_COMPLETE"}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-review-invalid-implementation-status")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-review-invalid-implementation-status")]
          (is (= :failed (:status result)))
          (is (= :failed (:status run)))
          (is (= ["Review munera/open/189-deterministic-review-step-routing with task-implementation-review"]
                 (mapv :prompt @prompts*)))
          (is (zero? (count (get-in run [:step-runs "follow-up" :attempts]))))
          (is (= {:status :error
                  :reason :invalid-pass-status
                  :message "PASS_STATUS token is not valid for this workflow step"
                  :details {:text "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                            :line "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                            :value "IMPLEMENTATION_COMPLETE"
                            :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}
                 (get-in run [:step-runs "review" :attempts 0 :judge-output :routing-result]))))))))

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

(defn- conditional-review-pass-status-args
  [step-name opts]
  (cond-> {:text {:from {:step step-name :output :final-llm-reply}}}
    (:allowed-statuses opts) (assoc :allowed-statuses (:allowed-statuses opts))))

(defn- conditional-review-definition
  ([definition-name]
   (conditional-review-definition definition-name {}))
  ([definition-name opts]
   {:definition-id definition-name
    :name definition-name
    :steps [{:name "ambiguity-review"
             :type :session
             :contributions [{:type :template :text "ambiguity-review"}]
             :judge {:type :invoke
                     :operation "workflow/pass-status-routing"
                     :args (conditional-review-pass-status-args "ambiguity-review" opts)}
             :on {"REPEAT" {:goto "ambiguity-follow-up"}
                  "DONE" {:goto "inconsistency-review"}}}
            {:name "ambiguity-follow-up"
             :type :session
             :contributions [{:type :template :text "ambiguity-follow-up"}]
             :judge {:type :invoke
                     :operation "workflow/constant-routing"
                     :args {:route "DONE"}}
             :on {"DONE" {:goto "inconsistency-review"}}}
            {:name "inconsistency-review"
             :type :session
             :contributions [{:type :template :text "inconsistency-review"}]
             :judge {:type :invoke
                     :operation "workflow/pass-status-routing"
                     :args (conditional-review-pass-status-args "inconsistency-review" opts)}
             :on {"REPEAT" {:goto "inconsistency-follow-up"}
                  "DONE" {:goto "clarity-status"}}}
            {:name "inconsistency-follow-up"
             :type :session
             :contributions [{:type :template :text "inconsistency-follow-up"}]
             :judge {:type :invoke
                     :operation "workflow/constant-routing"
                     :args {:route "DONE"}}
             :on {"DONE" {:goto "clarity-status"}}}
            {:name "clarity-status"
             :type :session
             :contributions [{:type :template :text "clarity-status"}]
             :judge {:type :invoke
                     :operation "workflow/constant-routing"
                     :args {:route "DONE"}}
             :on {"REPEAT" {:goto "ambiguity-review" :max-iterations 6}
                  "DONE" {:goto "final-summary"}}}
            {:name "final-summary"
             :type :session
             :contributions [{:type :template :text "final-summary"}]}]}))

(defn- create-conditional-review-run!
  ([ctx definition-name run-id]
   (create-conditional-review-run! ctx definition-name run-id {}))
  ([ctx definition-name run-id opts]
   (swap! (:state* ctx)
          (fn [state]
            (let [[s _ _] (workflow-runtime/create-run state {:definition (conditional-review-definition definition-name opts)
                                                              :run-id run-id
                                                              :workflow-input {:input "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"}})]
              s)))))

(defn- execute-conditional-review-proof!
  ([definition-name run-id replies]
   (execute-conditional-review-proof! definition-name run-id replies {}))
  ([definition-name run-id replies opts]
   (let [[ctx session-id] (support/create-session-context {:persist? false})
         prompts* (atom [])]
     (register-review-routing-ops! ctx)
     (create-conditional-review-run! ctx definition-name run-id opts)
     (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                   (fn [_ctx child-session-id prompt]
                     (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                     {:execution-result/assistant-message
                      {:role "assistant"
                       :content [{:type :text :text (get replies prompt prompt)}]
                       :stop-reason :stop}})]
       (let [result (workflow-execution/execute-run! ctx session-id run-id)
             run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
         {:result result
          :run run
          :prompts (mapv :prompt @prompts*)})))))

(deftest conditional-review-invalid-implementation-status-fails-before-follow-up-test
  (testing "design/plan review routing rejects implementation-only PASS_STATUS tokens"
    (let [{:keys [result run prompts]} (execute-conditional-review-proof!
                                        "review-task-design-proof"
                                        "design-invalid-implementation-status"
                                        {"ambiguity-review" "PASS_STATUS: IMPLEMENTATION_COMPLETE"}
                                        {:allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]})]
      (is (= :failed (:status result)))
      (is (= :failed (:status run)))
      (is (= ["ambiguity-review"] prompts))
      (is (zero? (count (get-in run [:step-runs "ambiguity-follow-up" :attempts]))))
      (is (= {:status :error
              :reason :invalid-pass-status
              :message "PASS_STATUS token is not valid for this workflow step"
              :details {:text "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                        :line "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                        :value "IMPLEMENTATION_COMPLETE"
                        :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}
             (get-in run [:step-runs "ambiguity-review" :attempts 0 :judge-output :routing-result])))))
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
                                           "Final summary")}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-implement-more-work")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-implement-more-work")]
          (is (= :completed (:status result)))
          (is (= :completed (:status run)))
          (is (= 2 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (= {:status :ok :data "REPEAT" :summary "REPEAT"}
                 (get-in run [:step-runs "implement-pass" :attempts 0 :judge-output :routing-result])))
          (is (= {:status :ok :data "DONE" :summary "DONE"}
                 (get-in run [:step-runs "implement-pass" :attempts 1 :judge-output :routing-result]))))))))

(deftest design-review-conditional-follow-up-routing-test
  ;; Tests design review per-reviewer PASS_STATUS routing while preserving the
  ;; all-reviewers-before-cycle ordering.
  (testing "design ambiguity REVIEW_COMPLETE skips ambiguity follow-up and still runs inconsistency review"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-design-proof" "design-skip-ambiguity"
                                    {"ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "inconsistency-review" "clarity-status" "final-summary"] prompts))))
  (testing "design inconsistency REVIEW_COMPLETE skips inconsistency follow-up and still runs clarity-status"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-design-proof" "design-skip-inconsistency"
                                    {"ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "inconsistency-review" "clarity-status" "final-summary"] prompts))))
  (testing "design ambiguity ACTIONABLE_FEEDBACK runs only ambiguity follow-up before inconsistency review"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-design-proof" "design-run-ambiguity"
                                    {"ambiguity-review" "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                     "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "ambiguity-follow-up" "inconsistency-review" "clarity-status" "final-summary"] prompts))))
  (testing "design inconsistency ACTIONABLE_FEEDBACK runs inconsistency follow-up before clarity-status"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-design-proof" "design-run-inconsistency"
                                    {"ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "inconsistency-review" "PASS_STATUS: ACTIONABLE_FEEDBACK"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "inconsistency-review" "inconsistency-follow-up" "clarity-status" "final-summary"] prompts)))))

(deftest plan-review-conditional-follow-up-routing-test
  ;; Tests plan review per-reviewer PASS_STATUS routing mirrors design review
  ;; while preserving all-reviewers-before-cycle ordering.
  (testing "plan ambiguity REVIEW_COMPLETE skips ambiguity follow-up and still runs inconsistency review"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-plan-proof" "plan-skip-ambiguity"
                                    {"ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "inconsistency-review" "clarity-status" "final-summary"] prompts))))
  (testing "plan inconsistency REVIEW_COMPLETE skips inconsistency follow-up and still runs clarity-status"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-plan-proof" "plan-skip-inconsistency"
                                    {"ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "inconsistency-review" "clarity-status" "final-summary"] prompts))))
  (testing "plan ambiguity ACTIONABLE_FEEDBACK runs only ambiguity follow-up before inconsistency review"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-plan-proof" "plan-run-ambiguity"
                                    {"ambiguity-review" "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                     "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "ambiguity-follow-up" "inconsistency-review" "clarity-status" "final-summary"] prompts))))
  (testing "plan inconsistency ACTIONABLE_FEEDBACK runs inconsistency follow-up before clarity-status"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-plan-proof" "plan-run-inconsistency"
                                    {"ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "inconsistency-review" "PASS_STATUS: ACTIONABLE_FEEDBACK"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "inconsistency-review" "inconsistency-follow-up" "clarity-status" "final-summary"] prompts)))))
