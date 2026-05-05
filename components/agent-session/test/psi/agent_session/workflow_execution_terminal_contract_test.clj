(ns psi.agent-session.workflow-execution-terminal-contract-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-control]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(defn- valid-child-session
  [child-session-id]
  {:session-id child-session-id
   :name child-session-id
   :messages []
   :message-history []
   :is-streaming false
   :tool-results []
   :tool-defs []
   :skills []
   :thinking-level :off
   :cwd "/tmp"
   :worktree-path "/tmp"
   :context []
   :agent {:messages []}
   :statechart {:phase :idle}})

(deftest execute-run-delegate-terminal-contract-adds-structured-handoff-output-test
  (testing "delegate steps surface yielded text and structured handoff as distinct downstream contracts"
    (let [[ctx session-id] (create-session-context {:persist? false})
          callee-definition {:definition-id "bug-discover"
                             :terminal-contract {:handoff {:type :markdown-handoff-data}}
                             :steps [{:name "callee"
                                      :type :session
                                      :contributions [{:type :template
                                                       :text "Discover {{input}}"
                                                       :vars {"input" {:from :workflow-input}}}]}]}
          caller-definition {:definition-id "delegate-contract-caller"
                             :steps [{:name "discover"
                                      :type :delegate
                                      :target "bug-discover"
                                      :prompt-string "issue 42"
                                      :outputs {:handoff {:source :delegate/handoff}}
                                      :yields {:type :delegated}}
                                     {:name "report"
                                      :type :session
                                      :contributions [{:type :template
                                                       :text "Issue {{issue}} => {{text}}"
                                                       :vars {"issue" {:from {:step "discover" :output :handoff}
                                                                       :path [:issue_number]}
                                                              "text" {:from {:step "discover" :yield :text}}}}]}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state callee-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition caller-definition
                                                                   :run-id "run-delegate-contract"
                                                                   :workflow-input {}})]
                       s)))]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (valid-child-session sid)}))
                    psi.agent-session.prompt-control/prompt-execution-result-in!
                    (fn [_ctx child-session-id _prompt]
                      {:execution-result/assistant-message
                       {:content (case child-session-id
                                   "callee-child" "## Outcome\nDone\n\n## Handoff Data\n- issue_number: 42\n- issue_url: https://example.test/issues/42\n"
                                   "report-child" "Issue 42 => ## Outcome\nDone\n\n## Handoff Data\n- issue_number: 42\n- issue_url: https://example.test/issues/42\n"
                                   "unexpected")}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-delegate-contract")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-delegate-contract")]
          (is (= :completed (:status result)))
          (is (= {:issue_number "42"
                  :issue_url "https://example.test/issues/42"}
                 (get-in run [:step-runs "discover" :accepted-result :outputs :handoff])))
          (is (= "## Outcome\nDone\n\n## Handoff Data\n- issue_number: 42\n- issue_url: https://example.test/issues/42\n"
                 (get-in run [:step-runs "discover" :accepted-result :outputs :final-llm-reply])))
          (is (= "Issue 42 => ## Outcome\nDone\n\n## Handoff Data\n- issue_number: 42\n- issue_url: https://example.test/issues/42\n"
                 (get-in run [:step-runs "report" :accepted-result :outputs :final-llm-reply]))))))))
