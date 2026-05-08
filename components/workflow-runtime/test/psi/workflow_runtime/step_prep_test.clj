(ns psi.workflow-runtime.step-prep-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.step-prep :as workflow-step-prep]))

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
           (workflow-step-prep/materialize-step-inputs run "consume")))))

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
        messages (workflow-step-prep/materialize-step-session-conversation run "review")
        split (workflow-step-prep/split-step-session-conversation messages)]
    (is (= [{:role "user" :content "{:ticket 123}"}
            {:role "user" :content "plan text"}
            {:role "user" :content "Review plan text"}]
           messages))
    (is (= {:preloaded-messages [{:role "user" :content "{:ticket 123}"}
                                 {:role "user" :content "plan text"}]
            :prompt "Review plan text"}
           split))))
