(ns psi.workflow-step-materialization.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-step-materialization.core :as workflow-step-materialization]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.step-test-support :as support]
   [psi.workflow-registry.registry :as workflow-registry]))

(deftest materialize-step-inputs-uses-canonical-output-and-yield-surfaces-test
  (let [definition {:steps [{:name "discover"
                             :type :invoke
                             :operation "github/search"
                             :args {}}
                            {:name "report"
                             :type :session
                             :contributions [{:type :template
                                              :text "report"
                                              :vars {}}]}
                            {:name "consume"
                             :type :session
                             :contributions [{:type :template
                                              :text "reply={{reply}} data={{data}} text={{text}}"
                                              :vars {"reply" {:from {:step "report" :output :final-llm-reply}}
                                                     "data" {:from {:step "discover" :yield :data}}
                                                     "text" {:from {:step "report" :yield :text}}}}]}]}
        [state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition definition
                                                        :run-id "run-canonical-surfaces"
                                                        :workflow-input {}})
        state3 (-> state2
                   (assoc-in [:workflows :runs run-id :step-runs "discover" :accepted-result]
                             {:outcome :ok
                              :outputs {:data {:issues [1 2]}
                                        :summary "found"
                                        :result :ignored-by-resolver}})
                   (assoc-in [:workflows :runs run-id :step-runs "report" :accepted-result]
                             {:outcome :ok
                              :outputs {:final-llm-reply "session text"}}))
        run (workflow-runtime/workflow-run-in state3 run-id)]
    (is (= {:reply "session text"
            :data {:issues [1 2]}
            :text "session text"}
           (workflow-step-materialization/materialize-step-inputs run "consume")))))

(deftest materialize-step-inputs-and-prompt-test
  (let [[state1 _ _] (workflow-registry/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                            support/multi-step-definition-with-meta)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                               :run-id "run-prompt"
                                                               :workflow-input {:input "ship it"
                                                                                :original "build this feature"}})
        run0 (workflow-runtime/workflow-run-in state2 run-id)
        prompt0 (workflow-step-materialization/step-prompt run0 "step-1-planner")
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-planner" :accepted-result]
                         {:outcome :ok :outputs {:final-llm-reply "plan text"}})
        run1 (workflow-runtime/workflow-run-in state3 run-id)
        prompt1 (workflow-step-materialization/step-prompt run1 "step-2-builder")]
    (is (= {:input "ship it" :original "build this feature"} (:step-inputs prompt0)))
    (is (= "ship it" (:prompt prompt0)))
    (is (= {:input "plan text" :original "build this feature"} (:step-inputs prompt1)))
    (is (= "Execute: plan text" (:prompt prompt1)))))

(deftest materialize-step-inputs-and-prompt-with-projections-test
  (let [definition {:definition-id "projection-proof"
                    :name "projection-proof"
                    :steps [{:name "step-1-discover"
                             :type :session
                             :contributions [{:type :template
                                              :text "{{input}}"
                                              :vars {"input" {:from :workflow-input :path [:ticket :body]}
                                                     "original" {:from :workflow-input :path [:original]}}}]}
                            {:name "step-2-request-more-info"
                             :type :session
                             :contributions [{:type :template
                                              :text "Need: {{input}} | Original: {{original}}"
                                              :vars {"input" {:from {:step "step-1-discover" :output :result}
                                                              :path [:diagnostics :summary]}
                                                     "original" {:from :workflow-input
                                                                 :path [:original :issue :title]}}}]}]
                    :workflow-file-meta {:framing-prompt "Projection proof."}}
        [state1 _ _] (workflow-registry/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                            definition)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "projection-proof"
                                                               :run-id "run-projection-proof"
                                                               :workflow-input {:ticket {:body "repro details"}
                                                                                :original {:issue {:title "Bug 123"}}}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-discover" :accepted-result]
                         {:outcome :ok
                          :outputs {:final-llm-reply "plan text"}
                          :diagnostics {:summary "need logs"}})
        run (workflow-runtime/workflow-run-in state3 run-id)
        prompt (workflow-step-materialization/step-prompt run "step-2-request-more-info")]
    (is (= {:input "need logs"
            :original "Bug 123"}
           (:step-inputs prompt)))
    (is (= "Need: need logs | Original: Bug 123"
           (:prompt prompt)))))

(deftest materialize-step-session-conversation-and-prompt-splitting-test
  (let [definition {:steps [{:name "plan"
                             :type :session
                             :contributions [{:type :template
                                              :text "Plan {{input}}"
                                              :vars {"input" {:from :workflow-input :path [:input]}}}]}
                            {:name "review"
                             :type :session
                             :contributions [{:type :source
                                              :from :workflow-original}
                                             {:type :source
                                              :from {:step "plan" :yield :text}}
                                             {:type :template
                                              :text "Review {{reply}}"
                                              :vars {"reply" {:from {:step "plan" :output :final-llm-reply}}}}]}]}
        [state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition definition
                                                        :run-id "run-session-conversation"
                                                        :workflow-input {:input "Ship it"
                                                                         :original {:ticket 123}}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "plan" :accepted-result]
                         {:outcome :ok
                          :outputs {:final-llm-reply "plan text"
                                    :text "plan text"}})
        run (workflow-runtime/workflow-run-in state3 run-id)
        messages (workflow-step-materialization/materialize-step-session-conversation run "review")
        split (workflow-step-materialization/split-step-session-conversation messages)]
    (is (= [{:role "user" :content "{:ticket 123}"}
            {:role "user" :content "plan text"}
            {:role "user" :content "Review plan text"}]
           messages))
    (is (= {:preloaded-messages [{:role "user" :content "{:ticket 123}"}
                                 {:role "user" :content "plan text"}]
            :prompt "Review plan text"}
           split))))

(deftest split-step-session-conversation-canonical-contributions-test
  (testing "canonical source and template contributions split into preload plus final prompt in author order"
    (let [definition {:definition-id "mixed-session-inputs"
                      :name "mixed-session-inputs"
                      :steps [{:name "plan"
                               :type :session
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}
                              {:name "review"
                               :type :session
                               :contributions [{:type :source
                                                :from :workflow-original}
                                               {:type :source
                                                :from {:step "plan" :output :final-llm-reply}}
                                               {:type :template
                                                :text "Review {{reply}}"
                                                :vars {"reply" {:from {:step "plan" :yield :text}}}}]}]}
          [state1 _ _] (workflow-registry/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                              definition)
          [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "mixed-session-inputs"
                                                                 :run-id "run-mixed-session-inputs"
                                                                 :workflow-input {:input "Ship it"
                                                                                  :original "Original request"}})
          state3 (assoc-in state2 [:workflows :runs run-id :step-runs "plan" :accepted-result]
                           {:outcome :ok
                            :outputs {:final-llm-reply "plan text"
                                      :text "plan text"}})
          workflow-run (workflow-runtime/workflow-run-in state3 run-id)
          conversation (workflow-step-materialization/materialize-step-session-conversation workflow-run "review")
          split (workflow-step-materialization/split-step-session-conversation conversation)]
      (is (= [{:role "user" :content "Original request"}
              {:role "user" :content "plan text"}
              {:role "user" :content "Review plan text"}]
             conversation))
      (is (= {:preloaded-messages [{:role "user" :content "Original request"}
                                   {:role "user" :content "plan text"}]
              :prompt "Review plan text"}
             split)))))

(deftest split-step-session-conversation-preloads-entire-conversation-when-final-message-is-not-user-text-test
  (testing "non-user final messages stay preloaded and produce an empty prompt"
    (let [messages [{:role "user" :content "Earlier context"}
                    {:role "assistant" :content "Assistant summary"}]]
      (is (= {:preloaded-messages messages
              :prompt ""}
             (workflow-step-materialization/split-step-session-conversation messages))))))

(deftest materialize-step-session-conversation-with-no-contributions-test
  (testing "steps without session contributions materialize to nil"
    (let [definition {:steps [{:name "empty-session"
                               :type :session
                               :session {:contributions []}}]}
          [state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                         {:definition definition
                                                          :run-id "run-empty-session"
                                                          :workflow-input {}})
          run (workflow-runtime/workflow-run-in state2 run-id)]
      (is (nil? (workflow-step-materialization/materialize-step-session-conversation
                 run
                 "empty-session"))))))

(deftest materialize-prompt-group-conversation-matches-single-prompt-materialization-test
  ;; Task 226 Slice 1 — the per-group materialization entry point. For the N=1
  ;; degenerate (one unnamed group carrying the whole step's contributions), the
  ;; per-group materialization reproduces the single-prompt step conversation.
  (let [contributions [{:type :source :from :workflow-original}
                       {:type :source :from {:step "plan" :output :final-llm-reply}}
                       {:type :template
                        :text "Review {{reply}}"
                        :vars {"reply" {:from {:step "plan" :output :final-llm-reply}}}}]
        definition {:steps [{:name "plan"
                             :type :session
                             :contributions [{:type :template
                                              :text "Plan {{input}}"
                                              :vars {"input" {:from :workflow-input :path [:input]}}}]}
                            {:name "review"
                             :type :session
                             :contributions contributions}]}
        [state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition definition
                                                        :run-id "run-prompt-group"
                                                        :workflow-input {:input "Ship it"
                                                                         :original {:ticket 123}}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "plan" :accepted-result]
                         {:outcome :ok
                          :outputs {:final-llm-reply "plan text"
                                    :text "plan text"}})
        run (workflow-runtime/workflow-run-in state3 run-id)
        step-conversation (workflow-step-materialization/materialize-step-session-conversation run "review")
        group-conversation (workflow-step-materialization/materialize-prompt-group-conversation
                            run {:contributions contributions})]
    (is (= [{:role "user" :content "{:ticket 123}"}
            {:role "user" :content "plan text"}
            {:role "user" :content "Review plan text"}]
           group-conversation))
    (is (= step-conversation group-conversation)
        "the N=1 unnamed group reproduces the single-prompt step conversation"))

  (testing "an empty-contributions prompt group materializes to nil"
    (let [definition {:steps [{:name "only" :type :session :contributions []}]}
          [state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                         {:definition definition
                                                          :run-id "run-empty-group"
                                                          :workflow-input {}})
          run (workflow-runtime/workflow-run-in state2 run-id)]
      (is (nil? (workflow-step-materialization/materialize-prompt-group-conversation
                 run {:contributions []}))))))
