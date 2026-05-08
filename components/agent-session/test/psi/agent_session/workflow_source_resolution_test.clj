(ns psi.agent-session.workflow-source-resolution-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-source-resolution :as workflow-source-resolution]))

(def mixed-form-definition
  {:steps [{:name "discover"
            :type :invoke
            :operation "github/search"
            :args {:repo {:from :workflow-input :path [:repo]}
                   :labels {:from :workflow-input :path [:labels]}
                   :state "open"}}
           {:name "report"
            :type :session
            :contributions [{:type :source
                             :from :workflow-original}
                            {:type :template
                             :text "Review {{issues}} / {{summary}}"
                             :vars {"issues" {:from {:step "discover" :output :data}
                                              :path [:issues]}
                                    "summary" {:from {:step "discover" :yield :data}
                                               :path [:summary]}}}]}
           {:name "report-call"
            :type :delegate
            :target "builder"
            :prompt-string {:type :template
                            :text "Ship {{issues}}"
                            :vars {"issues" {:from {:step "discover" :output :data}
                                             :path [:issues]}}}
            :context [{:type :source
                       :from :workflow-original}
                      {:type :source
                       :from {:step "discover" :output :data}
                       :path [:issues]}]}]})

(defn- workflow-run-with-results
  []
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition mixed-form-definition
                                                        :run-id "run-mixed"
                                                        :workflow-input {:repo "org/repo"
                                                                         :labels ["bug"]
                                                                         :original {:ticket 123 :request "Please triage"}}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "discover" :accepted-result]
                         {:outcome :ok
                          :outputs {:data {:issues ["i-1" "i-2"]
                                           :summary "2 issues found"}
                                    :summary "2 issues found"}})]
    (workflow-runtime/workflow-run-in state3 run-id)))

(deftest resolve-invoke-args-shares-canonical-source-spec-semantics-test
  (let [run (workflow-run-with-results)
        invoke-step (first (get-in run [:effective-definition :canonical-ir :steps]))]
    (is (= {:repo "org/repo"
            :labels ["bug"]
            :state "open"}
           (workflow-source-resolution/resolve-invoke-args run (get-in invoke-step [:invoke :args]))))))

(deftest render-template-contribution-shares-canonical-source-spec-semantics-test
  (let [run (workflow-run-with-results)
        contribution (-> run :effective-definition :canonical-ir :steps second :session :contributions second)]
    (is (= "Review [\"i-1\" \"i-2\"] / 2 issues found"
           (workflow-source-resolution/render-template-contribution run contribution)))))

(deftest resolve-delegate-prompt-and-context-share-canonical-source-spec-semantics-test
  (let [run (workflow-run-with-results)
        delegate-step (nth (get-in run [:effective-definition :canonical-ir :steps]) 2)
        rendered-prompt (workflow-source-resolution/render-delegate-prompt-string run (get-in delegate-step [:delegate :prompt-string]))
        resolved-context (workflow-source-resolution/resolve-delegate-context run (get-in delegate-step [:delegate :context]))]
    (is (= "Ship [\"i-1\" \"i-2\"]"
           rendered-prompt))
    (is (= [{:ticket 123 :request "Please triage"}
            ["i-1" "i-2"]]
           resolved-context))
    (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                         {:definition {:steps [{:name "callee"
                                                                                :type :session
                                                                                :contributions [{:type :source
                                                                                                 :from :workflow-input}
                                                                                                {:type :source
                                                                                                 :from :workflow-original}]}]}
                                                          :run-id "run-delegate-callee"
                                                          :workflow-input {:original resolved-context
                                                                           :prompt-string rendered-prompt}})
          callee-run (-> state2
                         (assoc-in [:workflows :runs run-id :workflow-input] rendered-prompt)
                         (assoc-in [:workflows :runs run-id :workflow-original] resolved-context)
                         (workflow-runtime/workflow-run-in run-id))]
      (is (= rendered-prompt
             (workflow-source-resolution/resolve-source-ref callee-run :workflow-input)))
      (is (= resolved-context
             (or (:workflow-original callee-run)
                 (workflow-source-resolution/resolve-source-ref callee-run :workflow-original)))))))

(deftest resolve-delegate-yielded-text-from-canonical-terminal-envelope-test
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition {:steps [{:name "delegate-step"
                                                                              :type :delegate
                                                                              :target "builder"
                                                                              :prompt-string "Do it"
                                                                              :outputs {:handoff {:source :delegate/handoff}}
                                                                              :yields {:type :delegated}}
                                                                             {:name "report"
                                                                              :type :session
                                                                              :contributions [{:type :template
                                                                                               :text "Report {{delegated}} / {{issue}}"
                                                                                               :vars {"delegated" {:from {:step "delegate-step" :yield :text}}
                                                                                                      "issue" {:from {:step "delegate-step" :output :handoff}
                                                                                                               :path [:issue_number]}}}]}]}
                                                        :run-id "run-delegate-yield"
                                                        :workflow-input {}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "delegate-step" :accepted-result]
                         {:outcome :ok
                          :outputs {:final-llm-reply "delegated terminal text"
                                    :handoff {:issue_number "42"}
                                    :result {:outcome :ok}}
                          :diagnostics {:delegate {:target "builder"}}})
        run (workflow-runtime/workflow-run-in state3 run-id)]
    (is (= "delegated terminal text"
           (workflow-source-resolution/resolve-source-ref run {:step "delegate-step" :yield :text})))
    (is (= {:issue_number "42"}
           (workflow-source-resolution/resolve-source-ref run {:step "delegate-step" :output :handoff})))
    (is (= "Report delegated terminal text / 42"
           (workflow-source-resolution/render-template-contribution
            run
            (-> run :effective-definition :canonical-ir :steps second :session :contributions first))))))

(deftest apply-source-spec-rejects-both-path-and-projection-test
  (let [run (workflow-run-with-results)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cannot contain both `:path` and `:projection`"
         (workflow-source-resolution/apply-source-spec
          run
          {:from {:step "discover" :output :data}
           :path [:issues]
           :projection :full})))))

(defn- run-with-transcript
  [run-id transcript]
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition {:steps [{:name "report"
                                                                              :type :session
                                                                              :contributions [{:type :template
                                                                                               :text "x"
                                                                                               :vars {}}]}]}
                                                        :run-id run-id
                                                        :workflow-input {}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "report" :accepted-result]
                         {:outcome :ok
                          :outputs {:transcript transcript
                                    :final-llm-reply "Done"}})]
    (workflow-runtime/workflow-run-in state3 run-id)))

(deftest apply-source-spec-projects-transcript-surfaces-test
  (let [transcript [{:role "user" :content "Request"}
                    {:role "assistant" :content [{:type :text :text "Thinking"}
                                                 {:type :tool_use :id "t1" :name "read" :input {:path "x"}}]}
                    {:role "tool" :content [{:type :tool_result :tool-use-id "t1" :content "ok"}]}
                    {:role "assistant" :content [{:type :text :text "Done"}]}]
        run (run-with-transcript "run-transcript" transcript)]
    (is (= [{:role "user" :content "Request"}
            {:role "assistant" :content [{:type :text :text "Thinking"}]}
            {:role "assistant" :content [{:type :text :text "Done"}]}]
           (workflow-source-resolution/apply-source-spec
            run
            {:from {:step "report" :output :transcript}
             :projection {:type :tail :turns 1 :tool-output false}})))
    (is (= transcript
           (workflow-source-resolution/apply-source-spec
            run
            {:from {:step "report" :output :transcript}
             :projection :full})))))

(deftest apply-source-spec-projects-through-lower-owner-dropping-emptied-messages-test
  (let [transcript [{:role "user" :content "Request"}
                    {:role "assistant" :content [{:type :tool_use :id "t1" :name "read" :input {:path "x"}}]}
                    {:role "tool" :content [{:type :tool_result :tool-use-id "t1" :content "ok"}]}
                    {:role "assistant" :content [{:type :text :text "Done"}]}]
        run (run-with-transcript "run-transcript-tool-only" transcript)]
    (is (= [{:role "user" :content "Request"}
            {:role "assistant" :content [{:type :text :text "Done"}]}]
           (workflow-source-resolution/apply-source-spec
            run
            {:from {:step "report" :output :transcript}
             :projection {:type :tail :turns 1 :tool-output false}})))))

(deftest resolve-binding-ref-normalizes-session-output-paths-through-canonical-ir-test
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition {:steps [{:name "report"
                                                                              :type :session
                                                                              :session {:contributions [{:type :template
                                                                                                         :text "x"
                                                                                                         :vars {}}]}
                                                                              :outputs {:final-llm-reply {:source :session/final-llm-reply}}
                                                                              :yields {:type :text :text :final-llm-reply}}]}
                                                        :run-id "run-binding-ref"
                                                        :workflow-input {}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "report" :accepted-result]
                         {:outcome :ok
                          :outputs {:final-llm-reply "Done"
                                    :text "legacy text"}})
        run (workflow-runtime/workflow-run-in state3 run-id)]
    (is (= "Done"
           (workflow-source-resolution/resolve-binding-ref
            run
            {:source :step-output
             :path ["report" :outputs :final-llm-reply]})))
    (is (= "Done"
           (workflow-source-resolution/resolve-binding-ref
            run
            {:source :step-output
             :path ["report" :outputs :text]})))))
