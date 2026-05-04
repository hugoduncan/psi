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
        delegate-step (nth (get-in run [:effective-definition :canonical-ir :steps]) 2)]
    (is (= "Ship [\"i-1\" \"i-2\"]"
           (workflow-source-resolution/render-delegate-prompt-string run (get-in delegate-step [:delegate :prompt-string]))))
    (is (= [{:ticket 123 :request "Please triage"}
            ["i-1" "i-2"]]
           (workflow-source-resolution/resolve-delegate-context run (get-in delegate-step [:delegate :context]))))))

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

(deftest apply-source-spec-projects-transcript-surfaces-test
  (let [[state2 run-id _] (workflow-runtime/create-run {:workflows {:definitions {} :runs {} :run-order []}}
                                                       {:definition {:steps [{:name "report"
                                                                              :type :session
                                                                              :contributions [{:type :template
                                                                                               :text "x"
                                                                                               :vars {}}]}]}
                                                        :run-id "run-transcript"
                                                        :workflow-input {}})
        transcript [{:role "user" :content "Request"}
                    {:role "assistant" :content [{:type :text :text "Thinking"}
                                                 {:type :tool_use :id "t1" :name "read" :input {:path "x"}}]}
                    {:role "tool" :content [{:type :tool_result :tool-use-id "t1" :content "ok"}]}
                    {:role "assistant" :content [{:type :text :text "Done"}]}]
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "report" :accepted-result]
                         {:outcome :ok
                          :outputs {:transcript transcript
                                    :final-llm-reply "Done"}})
        run (workflow-runtime/workflow-run-in state3 run-id)]
    (is (= [{:role "user" :content "Request"}
            {:role "assistant" :content [{:type :text :text "Thinking"}]}
            {:role "assistant" :content [{:type :text :text "Done"}]}]
           (workflow-source-resolution/apply-source-spec
            run
            {:from {:step "report" :output :transcript}
             :projection {:type :tail :turns 1 :tool-output false}})))))
