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
            :on {"REPEAT" {:goto "review" :max-iterations 10}}}]})

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
                 (mapv :prompt @prompts*)))))))
  (testing "review can be entered ten total times before an eleventh entry fails the iteration guard"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-review-run! ctx "run-review-repeat-limit")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text
                                   :text (case prompt
                                           "Review munera/open/189-deterministic-review-step-routing with task-implementation-review"
                                           "PASS_STATUS: ACTIONABLE_FEEDBACK"

                                           "Execute follow-up for munera/open/189-deterministic-review-step-routing"
                                           "follow-up complete")}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-review-repeat-limit")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-review-repeat-limit")]
          (is (= :failed (:status result)))
          (is (= :failed (:status run)))
          (is (= 10 (count (get-in run [:step-runs "review" :attempts]))))
          (is (= 10 (count (get-in run [:step-runs "follow-up" :attempts]))))
          (is (= {:outcome :failed
                  :reason :iteration-exhausted
                  :step-id "follow-up"
                  :attempt-id nil
                  :judge-output {:routing-result {:status :ok
                                                  :data "REPEAT"
                                                  :summary "REPEAT"}}}
                 (:terminal-outcome run)))
          (is (= 20 (count @prompts*))))))))

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

(defn- conditional-review-phase-step
  [step-name next-step opts]
  {:name step-name
   :type :session
   :contributions [{:type :template :text step-name}]
   :judge {:type :invoke
           :operation "workflow/pass-status-routing"
           :args (conditional-review-pass-status-args step-name opts)}
   :on {"REPEAT" {:goto (str step-name "-follow-up")}
        "DONE" {:goto next-step}}})

(defn- conditional-review-follow-up-step
  [step-name next-step]
  {:name (str step-name "-follow-up")
   :type :session
   :contributions [{:type :template :text (str step-name "-follow-up")}]
   :judge {:type :invoke
           :operation "workflow/constant-routing"
           :args {:route "DONE"}}
   :on {"DONE" {:goto next-step}}})

(defn- conditional-review-clarity-status-step
  [phase-steps first-step max-iterations]
  (let [args (into {}
                   (map (fn [step-name]
                          [(keyword (str step-name "-text"))
                           {:from {:step step-name :output :final-llm-reply}}]))
                   phase-steps)]
    {:name "clarity-status"
     :type :invoke
     :operation "workflow/constant-routing"
     :args {:route "DONE"}
     :judge {:type :invoke
             :operation "workflow/pass-feedback-routing"
             :args args}
     :on {"REPEAT" {:goto first-step :max-iterations max-iterations}
          "DONE" {:goto "final-summary"}}}))

(defn- conditional-review-design-definition
  [definition-name]
  {:definition-id definition-name
   :name definition-name
   :steps [{:name "design-review"
            :type :session
            :prompts [{:name "architecture"
                       :contributions [{:type :template :text "architecture-review"}]}
                      {:name "ambiguity"
                       :contributions [{:type :template :text "ambiguity-review"}]}
                      {:name "inconsistency"
                       :contributions [{:type :template :text "inconsistency-review"}]}]
            :judge {:type :invoke
                    :operation "workflow/pass-feedback-routing"
                    :args {:architecture-text {:from {:step "design-review"
                                                      :prompt "architecture"
                                                      :output :final-llm-reply}}
                           :ambiguity-text {:from {:step "design-review"
                                                   :prompt "ambiguity"
                                                   :output :final-llm-reply}}
                           :inconsistency-text {:from {:step "design-review"
                                                       :prompt "inconsistency"
                                                       :output :final-llm-reply}}}}
            :on {"REPEAT" {:goto "design-follow-up"}
                 "DONE" {:goto "final-summary"}}}
           {:name "design-follow-up"
            :type :session
            :contributions [{:type :template :text "design-follow-up"}]
            :judge {:type :invoke
                    :operation "workflow/constant-routing"
                    :args {:route "DONE"}}
            :on {"DONE" {:goto "design-review" :max-iterations 6}}}
           {:name "final-summary"
            :type :session
            :contributions [{:type :template :text "final-summary"}]}]})

(defn- conditional-review-plan-definition
  [definition-name opts]
  (let [phase-steps ["ambiguity-review" "inconsistency-review"]]
    {:definition-id definition-name
     :name definition-name
     :steps (vec
             (concat
              (mapcat (fn [[step-name next-step]]
                        [(conditional-review-phase-step step-name next-step opts)
                         (conditional-review-follow-up-step step-name next-step)])
                      (map vector phase-steps (concat (rest phase-steps) ["clarity-status"])))
              [(conditional-review-clarity-status-step phase-steps "ambiguity-review" 5)
               {:name "final-summary"
                :type :session
                :contributions [{:type :template :text "final-summary"}]}]))}))

(defn- conditional-review-definition
  ([definition-name]
   (conditional-review-definition definition-name {}))
  ([definition-name opts]
   (if (= :design (:kind opts))
     (conditional-review-design-definition definition-name)
     (conditional-review-plan-definition definition-name opts))))

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

(defn- actor-turn-result
  [text]
  (let [message {:role "assistant"
                 :content [{:type :text :text text}]
                 :stop-reason :stop}]
    {:status :ok
     :assistant-message message
     :assistant-text text
     :execution-result {:execution-result/assistant-message message}}))

(defn- execute-conditional-review-proof!
  ([definition-name run-id replies]
   (execute-conditional-review-proof! definition-name run-id replies {}))
  ([definition-name run-id replies opts]
   (let [[ctx session-id] (support/create-session-context {:persist? false})
         prompts* (atom [])
         ctx (assoc ctx
                    :workflow-execute-actor-turn-fn
                    (fn [_ctx child-session-id prompt & _]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      (actor-turn-result
                       (if (fn? replies)
                         (replies prompt)
                         (get replies prompt prompt)))))]
     (register-review-routing-ops! ctx)
     (create-conditional-review-run! ctx definition-name run-id opts)
     (let [result (workflow-execution/execute-run! ctx session-id run-id)
           run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
       {:result result
        :run run
        :prompts (mapv :prompt @prompts*)}))))

(deftest conditional-review-invalid-implementation-status-fails-before-follow-up-test
  (testing "design/plan review routing rejects implementation-only PASS_STATUS tokens"
    (let [{:keys [result run prompts]} (execute-conditional-review-proof!
                                        "review-task-design-proof"
                                        "design-invalid-implementation-status"
                                        {"architecture-review" "PASS_STATUS: REVIEW_COMPLETE"
                                         "ambiguity-review" "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                                         "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"}
                                        {:kind :design})]
      (is (= :failed (:status result)))
      (is (= :failed (:status run)))
      (is (= ["architecture-review" "ambiguity-review" "inconsistency-review"] prompts))
      (is (zero? (count (get-in run [:step-runs "design-follow-up" :attempts]))))
      (is (= {:status :error
              :reason :invalid-pass-feedback
              :message "workflow/pass-feedback-routing replies are invalid"
              :details {:validation-failures
                        {:ambiguity-text
                         {:status :error
                          :reason :invalid-pass-status
                          :message "PASS_STATUS token is not valid for this workflow step"
                          :details {:text "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                                    :line "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                                    :value "IMPLEMENTATION_COMPLETE"
                                    :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}}}}
             (get-in run [:step-runs "design-review" :attempts 0 :judge-output :routing-result])))))
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

(deftest design-review-full-pass-routing-test
  ;; Tests design review runs a full architecture/ambiguity/inconsistency pass
  ;; before using pass-level feedback memory to restart or complete.
  (testing "clean design pass runs every phase once and reaches final summary"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-design-proof" "design-clean-pass"
                                    {"architecture-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"}
                                    {:kind :design})]
      (is (= :completed (:status result)))
      (is (= ["architecture-review" "ambiguity-review" "inconsistency-review" "final-summary"] prompts))))
  (testing "actionable architecture feedback still completes later phases before restarting"
    (let [review-counts* (atom {})
          {:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-design-proof" "design-architecture-restart"
                                    (fn [prompt]
                                      (case prompt
                                        "architecture-review"
                                        (if (= 1 (get (swap! review-counts* update prompt (fnil inc 0)) prompt))
                                          "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                          "PASS_STATUS: REVIEW_COMPLETE")
                                        "ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                        "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"
                                        prompt))
                                    {:kind :design})]
      (is (= :completed (:status result)))
      (is (= ["architecture-review" "ambiguity-review" "inconsistency-review"
              "design-follow-up"
              "architecture-review" "ambiguity-review" "inconsistency-review"
              "final-summary"]
             prompts))))
  (testing "actionable final-phase inconsistency feedback restarts instead of completing"
    (let [inconsistency-count* (atom 0)
          {:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-design-proof" "design-inconsistency-restart"
                                    (fn [prompt]
                                      (case prompt
                                        "architecture-review" "PASS_STATUS: REVIEW_COMPLETE"
                                        "ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                        "inconsistency-review"
                                        (if (= 1 (swap! inconsistency-count* inc))
                                          "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                          "PASS_STATUS: REVIEW_COMPLETE")
                                        prompt))
                                    {:kind :design})]
      (is (= :completed (:status result)))
      (is (= ["architecture-review" "ambiguity-review" "inconsistency-review"
              "design-follow-up"
              "architecture-review" "ambiguity-review" "inconsistency-review"
              "final-summary"]
             prompts)))))

(deftest plan-review-full-pass-routing-test
  ;; Tests plan review runs a full ambiguity/inconsistency pass before restart.
  (testing "clean plan pass runs every phase once and reaches final summary"
    (let [{:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-plan-proof" "plan-clean-pass"
                                    {"ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                     "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"})]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "inconsistency-review" "final-summary"] prompts))))
  (testing "actionable ambiguity feedback still completes inconsistency before restarting"
    (let [ambiguity-count* (atom 0)
          {:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-plan-proof" "plan-ambiguity-restart"
                                    (fn [prompt]
                                      (case prompt
                                        "ambiguity-review"
                                        (if (= 1 (swap! ambiguity-count* inc))
                                          "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                          "PASS_STATUS: REVIEW_COMPLETE")
                                        "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"
                                        prompt)))]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "ambiguity-review-follow-up"
              "inconsistency-review"
              "ambiguity-review" "inconsistency-review"
              "final-summary"]
             prompts))))
  (testing "actionable final-phase inconsistency feedback restarts instead of completing"
    (let [inconsistency-count* (atom 0)
          {:keys [result prompts]} (execute-conditional-review-proof!
                                    "review-task-plan-proof" "plan-inconsistency-restart"
                                    (fn [prompt]
                                      (case prompt
                                        "ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                        "inconsistency-review"
                                        (if (= 1 (swap! inconsistency-count* inc))
                                          "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                          "PASS_STATUS: REVIEW_COMPLETE")
                                        prompt)))]
      (is (= :completed (:status result)))
      (is (= ["ambiguity-review" "inconsistency-review"
              "inconsistency-review-follow-up"
              "ambiguity-review" "inconsistency-review"
              "final-summary"]
             prompts)))))

(deftest review-pass-loop-iteration-limit-failure-test
  ;; Tests final allowed pass feedback attempts another pass and fails through
  ;; the workflow iteration guard rather than silently completing.
  (testing "design pass 6 actionable feedback fails on attempted pass 7"
    (let [{:keys [result run prompts]} (execute-conditional-review-proof!
                                        "review-task-design-proof" "design-repeat-limit"
                                        {"architecture-review" "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                         "ambiguity-review" "PASS_STATUS: REVIEW_COMPLETE"
                                         "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"}
                                        {:kind :design})]
      (is (= :failed (:status result)))
      (is (= :failed (:status run)))
      (is (= 6 (count (get-in run [:step-runs "design-review" :attempts]))))
      (is (= 6 (count (get-in run [:step-runs "design-follow-up" :attempts]))))
      (is (= :iteration-exhausted (:reason (:terminal-outcome run))))
      (is (= "design-follow-up" (:step-id (:terminal-outcome run))))
      (is (= 24 (count prompts)))))
  (testing "plan pass 5 actionable feedback fails on attempted pass 6"
    (let [{:keys [result run prompts]} (execute-conditional-review-proof!
                                        "review-task-plan-proof" "plan-repeat-limit"
                                        {"ambiguity-review" "PASS_STATUS: ACTIONABLE_FEEDBACK"
                                         "inconsistency-review" "PASS_STATUS: REVIEW_COMPLETE"})]
      (is (= :failed (:status result)))
      (is (= :failed (:status run)))
      (is (= 5 (count (get-in run [:step-runs "ambiguity-review" :attempts]))))
      (is (= 5 (count (get-in run [:step-runs "ambiguity-review-follow-up" :attempts]))))
      (is (= 5 (count (get-in run [:step-runs "inconsistency-review" :attempts]))))
      (is (= :iteration-exhausted (:reason (:terminal-outcome run))))
      (is (= "clarity-status" (:step-id (:terminal-outcome run))))
      (is (= 15 (count prompts))))))
