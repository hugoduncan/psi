(ns psi.agent-session.workflow-execution-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.deterministic-operation-registry.registry]
   [psi.session-persistence.core]
   [psi.agent-session.turn]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.workflow-runtime.attempts]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-judge]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.statechart-runtime]
   [psi.workflow-registry.registry :as workflow-registry]))

(deftest execute-run-linear-test
  (testing "execute-run! drives a linear workflow to completion through the statechart runtime"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state support/multi-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-linear"
                                                                   :workflow-input {:input "ship it"
                                                                                    :original "build this feature"}})]
                       s)))
          prompts* (atom [])
          created* (atom [])
          responses* (atom ["planner output" "builder output"])]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        (swap! created* conj {:step-id (:workflow-step-id opts)
                                              :preloaded-messages (:preloaded-messages opts)})
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (support/valid-child-session sid)}))
                    psi.agent-session.turn/prompt-execution-result-in! (fn [_ctx child-session-id prompt]
                                                                         (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                                                                         {:execution-result/assistant-message
                                                                          {:content (let [resp (first @responses*)]
                                                                                      (swap! responses* subvec 1)
                                                                                      resp)}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-linear")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-linear")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (false? (:blocked? result)))
          (is (= 2 (count (:steps-executed result))))
          (is (= "builder output"
                 (get-in run [:step-runs "step-2-builder" :accepted-result :outputs :final-llm-reply])))
          (is (= ["ship it"
                  "Execute: planner output"]
                 (mapv :prompt @prompts*)))
          (is (= [{:step-id "step-1-planner"
                   :preloaded-messages nil}
                  {:step-id "step-2-builder"
                   :preloaded-messages nil}]
                 @created*)))))))

(deftest execute-run-materializes-session-contributions-into-child-session-conversation-test
  (testing "IR session contributions become canonical child-session preload plus final prompt submission"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          definition {:steps [{:name "discover"
                               :type :invoke
                               :operation "demo/discover"
                               :args {}}
                              {:name "report"
                               :type :session
                               :tools ["read"]
                               :contributions [{:type :source
                                                :from :workflow-original}
                                               {:type :template
                                                :text "Review {{issues}} / {{summary}}"
                                                :vars {"issues" {:from {:step "discover" :output :data}
                                                                 :path [:issues]}
                                                       "summary" {:from {:step "discover" :yield :data}
                                                                  :path [:summary]}}}]}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/create-run state {:definition definition
                                                                       :run-id "run-session-contrib"
                                                                       :workflow-input {:original {:ticket 123
                                                                                                   :request "Please triage"}}})]
                       s)))
          created* (atom [])
          prompts* (atom [])]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        (swap! created* conj {:step-id (:workflow-step-id opts)
                                              :preloaded-messages (:preloaded-messages opts)})
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (support/valid-child-session sid)}))
                    psi.deterministic-operation-registry.registry/invoke-operation-in
                    (fn [_registry operation-id invocation _invoke-operation]
                      (if (= operation-id "demo/discover")
                        {:status :ok
                         :data {:issues ["i-1" "i-2"]
                                :summary "2 issues found"}
                         :summary "2 issues found"}
                        (throw (ex-info "unexpected operation" {:operation-id operation-id
                                                                :invocation invocation}))))
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:content "triage output"}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-session-contrib")
              report-created (last @created*)]
          (is (= :completed (:status result)))
          (is (= {:step-id "report"
                  :preloaded-messages [{:role "user"
                                        :content "{:ticket 123, :request \"Please triage\"}"}]}
                 report-created))
          (is (= [{:session-id "report-child"
                   :prompt "Review [\"i-1\" \"i-2\"] / 2 issues found"}]
                 @prompts*)))))))

(deftest execute-run-delegate-step-invokes-callee-workflow-with-explicit-boundary-test
  (testing "IR delegate steps invoke the target workflow with rendered workflow-input and ordered workflow-original context"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          callee-definition {:steps [{:name "callee"
                                      :type :session
                                      :contributions [{:type :source
                                                       :from :workflow-original}
                                                      {:type :template
                                                       :text "Do {{input}}"
                                                       :vars {"input" {:from :workflow-input}}}]}]}
          caller-definition {:definition-id "delegate-caller"
                             :steps [{:name "discover"
                                      :type :invoke
                                      :operation "demo/discover"
                                      :args {}}
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
                                                 :path [:issues]}]}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state (assoc callee-definition :definition-id "builder"))
                           [s _ _] (workflow-runtime/create-run s {:definition caller-definition
                                                                   :run-id "run-delegate"
                                                                   :workflow-input {:original {:ticket 123
                                                                                               :request "Please triage"}}})]
                       s)))
          created* (atom [])
          prompts* (atom [])]
      (with-redefs [psi.deterministic-operation-registry.registry/invoke-operation-in
                    (fn [_registry operation-id invocation _invoke-operation]
                      (if (= operation-id "demo/discover")
                        {:status :ok
                         :data {:issues ["i-1" "i-2"]
                                :summary "2 issues found"}
                         :summary "2 issues found"}
                        (throw (ex-info "unexpected operation" {:operation-id operation-id
                                                                :invocation invocation}))))
                    psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        (swap! created* conj {:step-id (:workflow-step-id opts)
                                              :workflow-run-id (:workflow-run-id opts)
                                              :preloaded-messages (:preloaded-messages opts)})
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (support/valid-child-session sid)}))
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:content "delegated output"}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-delegate")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-delegate")
              delegate-created (last @created*)
              delegated-run-id (:workflow-run-id delegate-created)
              delegated-run (workflow-runtime/workflow-run-in @(:state* ctx) delegated-run-id)]
          (is (= :completed (:status result)))
          (is (= "delegated output"
                 (get-in run [:step-runs "report-call" :accepted-result :outputs :final-llm-reply])))
          (is (= "Ship [\"i-1\" \"i-2\"]"
                 (:workflow-input delegated-run)))
          (is (= [{:ticket 123 :request "Please triage"}
                  ["i-1" "i-2"]]
                 (:workflow-original delegated-run)))
          (is (= [{:role "user"
                   :content "[{:ticket 123, :request \"Please triage\"} [\"i-1\" \"i-2\"]]"}]
                 (:preloaded-messages delegate-created)))
          (is (= [{:session-id "callee-child"
                   :prompt "Do Ship [\"i-1\" \"i-2\"]"}]
                 @prompts*))
          (is (= {:target "builder"
                  :resolved-target "builder"
                  :run-id delegated-run-id
                  :step-id "report-call"
                  :prompt-string "Ship [\"i-1\" \"i-2\"]"
                  :context [{:ticket 123 :request "Please triage"}
                            ["i-1" "i-2"]]}
                 (get-in run [:step-runs "report-call" :accepted-result :diagnostics :delegate]))))))))

(deftest execute-run-dynamic-delegate-step-invokes-selected-workflow-reference-test
  (testing "dynamic delegate steps resolve explicit workflow references and invoke the selected canonical workflow"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          callee-definition {:steps [{:name "callee"
                                      :type :session
                                      :contributions [{:type :source
                                                       :from :workflow-original}
                                                      {:type :template
                                                       :text "Do {{input}}"
                                                       :vars {"input" {:from :workflow-input}}}]}]}
          caller-definition {:definition-id "dynamic-delegate-caller"
                             :steps [{:name "choose-workflow"
                                      :type :invoke
                                      :operation "demo/select-workflow"
                                      :args {}}
                                     {:name "run-selected-workflow"
                                      :type :delegate
                                      :target {:from {:step "choose-workflow" :output :data}
                                               :path [:selected-workflow]}
                                      :prompt-string "Handle the issue using the selected workflow."
                                      :context [{:type :source
                                                 :from :workflow-original}]}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state (assoc callee-definition :definition-id "builder"))
                           [s _ _] (workflow-runtime/create-run s {:definition caller-definition
                                                                   :run-id "run-dynamic-delegate"
                                                                   :workflow-input {:original {:ticket 123
                                                                                               :request "Please triage"}}})]
                       s)))
          prompts* (atom [])]
      (with-redefs [psi.deterministic-operation-registry.registry/invoke-operation-in
                    (fn [_registry operation-id _invocation _invoke-operation]
                      (if (= operation-id "demo/select-workflow")
                        {:status :ok
                         :data {:selected-workflow {:type :workflow-ref
                                                    :name "builder"}}
                         :summary "selected builder"}
                        (throw (ex-info "unexpected operation" {:operation-id operation-id}))))
                    psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (support/valid-child-session sid)}))
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:content "delegated output"}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-dynamic-delegate")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-dynamic-delegate")]
          (is (= :completed (:status result)))
          (is (= "delegated output"
                 (get-in run [:step-runs "run-selected-workflow" :accepted-result :outputs :final-llm-reply])))
          (is (= {:target "builder"
                  :resolved-target "builder"
                  :run-id (get-in run [:step-runs "run-selected-workflow" :accepted-result :diagnostics :delegate :run-id])
                  :step-id "run-selected-workflow"
                  :prompt-string "Handle the issue using the selected workflow."
                  :context [{:ticket 123 :request "Please triage"}]}
                 (get-in run [:step-runs "run-selected-workflow" :accepted-result :diagnostics :delegate])))
          (is (= [{:session-id "callee-child"
                   :prompt "Do Handle the issue using the selected workflow."}]
                 @prompts*))))))

  (testing "dynamic delegate steps fail explicitly when the resolved target value is not a workflow reference"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          caller-definition {:definition-id "dynamic-delegate-caller-invalid"
                             :steps [{:name "choose-workflow"
                                      :type :invoke
                                      :operation "demo/select-workflow"
                                      :args {}}
                                     {:name "run-selected-workflow"
                                      :type :delegate
                                      :target {:from {:step "choose-workflow" :output :data}
                                               :path [:selected-workflow]}
                                      :prompt-string "Handle the issue using the selected workflow."}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/create-run state {:definition caller-definition
                                                                       :run-id "run-dynamic-delegate-invalid"
                                                                       :workflow-input {}})]
                       s)))]
      (with-redefs [psi.deterministic-operation-registry.registry/invoke-operation-in
                    (fn [_registry operation-id _invocation _invoke-operation]
                      (if (= operation-id "demo/select-workflow")
                        {:status :ok
                         :data {:selected-workflow "builder"}
                         :summary "selected builder"}
                        (throw (ex-info "unexpected operation" {:operation-id operation-id}))))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-dynamic-delegate-invalid")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-dynamic-delegate-invalid")]
          (is (= :failed (:status result)))
          (is (= "Dynamic delegate target must resolve to a workflow reference"
                 (get-in run [:step-runs "run-selected-workflow" :attempts 0 :execution-error :message])))))))

  (testing "dynamic delegate steps fail explicitly when the resolved workflow reference names an unknown workflow"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          caller-definition {:definition-id "dynamic-delegate-caller-unknown"
                             :steps [{:name "choose-workflow"
                                      :type :invoke
                                      :operation "demo/select-workflow"
                                      :args {}}
                                     {:name "run-selected-workflow"
                                      :type :delegate
                                      :target {:from {:step "choose-workflow" :output :data}
                                               :path [:selected-workflow]}
                                      :prompt-string "Handle the issue using the selected workflow."}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/create-run state {:definition caller-definition
                                                                       :run-id "run-dynamic-delegate-unknown"
                                                                       :workflow-input {}})]
                       s)))]
      (with-redefs [psi.deterministic-operation-registry.registry/invoke-operation-in
                    (fn [_registry operation-id _invocation _invoke-operation]
                      (if (= operation-id "demo/select-workflow")
                        {:status :ok
                         :data {:selected-workflow {:type :workflow-ref
                                                    :name "missing-workflow"}}
                         :summary "selected missing workflow"}
                        (throw (ex-info "unexpected operation" {:operation-id operation-id}))))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-dynamic-delegate-unknown")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-dynamic-delegate-unknown")]
          (is (= :failed (:status result)))
          (is (= "Delegated workflow definition not found"
                 (get-in run [:step-runs "run-selected-workflow" :attempts 0 :execution-error :message])))
          (is (= :workflow/execution-failure-recorded
                 (get-in run [:history (dec (count (:history run))) :event])))))))

  (testing "dynamic delegate steps fail through the same lookup path when a previously selected workflow is removed before delegation"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          callee-definition {:definition-id "builder"
                             :steps [{:name "callee"
                                      :type :session
                                      :contributions [{:type :template
                                                       :text "Do {{input}}"
                                                       :vars {"input" {:from :workflow-input}}}]}]}
          caller-definition {:definition-id "dynamic-delegate-caller-removed"
                             :steps [{:name "choose-workflow"
                                      :type :invoke
                                      :operation "demo/select-workflow"
                                      :args {}}
                                     {:name "run-selected-workflow"
                                      :type :delegate
                                      :target {:from {:step "choose-workflow" :output :data}
                                               :path [:selected-workflow]}
                                      :prompt-string "Handle the issue using the selected workflow."}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state callee-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition caller-definition
                                                                   :run-id "run-dynamic-delegate-removed"
                                                                   :workflow-input {}})]
                       s)))]
      (with-redefs [psi.deterministic-operation-registry.registry/invoke-operation-in
                    (fn [_registry operation-id _invocation _invoke-operation]
                      (if (= operation-id "demo/select-workflow")
                        (do
                          (swap! (:state* ctx)
                                 (fn [state]
                                   (first (workflow-registry/remove-definition state "builder"))))
                          {:status :ok
                           :data {:selected-workflow {:type :workflow-ref
                                                      :name "builder"}}
                           :summary "selected builder"})
                        (throw (ex-info "unexpected operation" {:operation-id operation-id}))))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-dynamic-delegate-removed")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-dynamic-delegate-removed")]
          (is (= :failed (:status result)))
          (is (= "Delegated workflow definition not found"
                 (get-in run [:step-runs "run-selected-workflow" :attempts 0 :execution-error :message])))
          (is (= :workflow/execution-failure-recorded
                 (get-in run [:history (dec (count (:history run))) :event]))))))))

(deftest execute-run-mixed-session-then-delegate-propagates-callee-yield-test
  (testing "mixed session and delegate workflows propagate the callee yielded value back through the delegating step"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          callee-definition {:steps [{:name "callee"
                                      :type :session
                                      :contributions [{:type :template
                                                       :text "Build {{input}}"
                                                       :vars {"input" {:from :workflow-input}}}]}]}
          caller-definition {:definition-id "mixed-delegate-caller"
                             :steps [{:name "plan"
                                      :type :session
                                      :contributions [{:type :template
                                                       :text "Plan {{input}}"
                                                       :vars {"input" {:from :workflow-input
                                                                       :path [:task]}}}]}
                                     {:name "build"
                                      :type :delegate
                                      :target "builder"
                                      :prompt-string {:type :template
                                                      :text "{{plan}}"
                                                      :vars {"plan" {:from {:step "plan" :yield :text}}}}
                                      :context [{:type :source
                                                 :from :workflow-original}
                                                {:type :source
                                                 :from {:step "plan" :yield :text}}]}
                                     {:name "report"
                                      :type :session
                                      :contributions [{:type :template
                                                       :text "Summarize {{build-result}}"
                                                       :vars {"build-result" {:from {:step "build" :yield :text}}}}]}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state (assoc callee-definition :definition-id "builder"))
                           [s _ _] (workflow-runtime/create-run s {:definition caller-definition
                                                                   :run-id "run-mixed-delegate"
                                                                   :workflow-input {:task "ship it"
                                                                                    :original {:request-id 7}}})]
                       s)))
          prompts* (atom [])]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (support/valid-child-session sid)}))
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:content (case child-session-id
                                   "plan-child" "plan output"
                                   "report-child" "final summary"
                                   "delegated build output")}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-mixed-delegate")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-mixed-delegate")]
          (is (= :completed (:status result)))
          (is (= "delegated build output"
                 (get-in run [:step-runs "build" :accepted-result :outputs :final-llm-reply])))
          (is (= "final summary"
                 (get-in run [:step-runs "report" :accepted-result :outputs :final-llm-reply])))
          (is (= [{:session-id "plan-child"
                   :prompt "Plan ship it"}
                  {:session-id "callee-child"
                   :prompt "Build plan output"}
                  {:session-id "report-child"
                   :prompt "Summarize delegated build output"}]
                 @prompts*)))))))

(deftest execute-run-preserves-parent-extension-prompt-contributions-test
  (testing "workflow child sessions inherit parent extension prompt contributions by default"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          planner-def (assoc support/single-step-definition-with-meta :workflow-file-meta
                             {:system-prompt "You are a planner."
                              :tools ["read"]
                              :thinking-level :medium})
          contribution {:id "work-on"
                        :ext-path "/extensions/work-on"
                        :section "Extension Capabilities"
                        :content "command: /work-on"
                        :enabled true
                        :created-at (java.time.Instant/parse "2026-04-22T12:00:00Z")
                        :updated-at (java.time.Instant/parse "2026-04-22T12:00:00Z")}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state planner-def)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-ext-1"
                                                                   :workflow-input {:input "plan it"}})
                           s (assoc-in s [:agent-session :sessions session-id :data :tool-defs]
                                       [{:name "read" :description "Read" :parameters {:type "object" :properties {}}}])
                           s (assoc-in s [:agent-session :sessions session-id :data :system-prompt-build-opts]
                                       {:selected-tools ["read" "psi-tool"]})
                           s (assoc-in s [:agent-session :sessions session-id :data :prompt-contributions]
                                       [contribution])]
                       s)))]
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in! (fn [_ctx _child-session-id _prompt]
                                                                         {:execution-result/assistant-message
                                                                          {:content "planner output"}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-ext-1")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-ext-1")
              child-id (get-in run [:step-runs "step-1" :attempts 0 :execution-session-id])
              child-sd (get-in @(:state* ctx) [:agent-session :sessions child-id :data])
              prepared (prompt-request/build-prepared-request
                        ctx child-id
                        {:turn-id "wf-child-proof"
                         :user-message {:role "user"
                                        :content [{:type :text :text "plan it"}]}})]
          (is (= :completed (:status result)))
          (is (= [contribution]
                 (mapv #(select-keys % [:id :ext-path :section :content :enabled :created-at :updated-at])
                       (:prompt-contributions child-sd))))
          (is (str/includes? (:base-system-prompt child-sd) "λ engage(nucleus)."))
          (is (= "You are a planner." (:developer-prompt child-sd)))
          (is (str/includes? (:prepared-request/system-prompt prepared) "You are a planner."))
          (is (str/includes? (:prepared-request/system-prompt prepared) "command: /work-on"))
          (is (= (:prepared-request/system-prompt prepared)
                 (get-in prepared [:prepared-request/provider-conversation :system-prompt]))))))))

(deftest execute-run-selection-filters-rendered-prompt-and-tools-test
  (testing "workflow child explicit prompt-component-selection filters rendered prompt content and provider tools"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state support/workflow-selection-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner-selection"
                                                                   :run-id "run-selection-1"
                                                                   :workflow-input {:input "plan it"}})]
                       (-> s
                           (assoc-in [:agent-session :sessions session-id :data :tool-defs]
                                     [{:name "read" :description "Read"}
                                      {:name "bash" :description "Bash"}])
                           (assoc-in [:agent-session :sessions session-id :data :skills]
                                     [{:name "testing-best-practices" :description "Testing"
                                       :file-path "/s/SKILL.md"
                                       :base-dir "/s"
                                       :source :project
                                       :disable-model-invocation false}])
                           (assoc-in [:agent-session :sessions session-id :data :prompt-contributions]
                                     [{:id "a"
                                       :ext-path "/ext/a"
                                       :content "A"
                                       :enabled true
                                       :created-at (java.time.Instant/parse "2026-04-22T12:00:00Z")
                                       :updated-at (java.time.Instant/parse "2026-04-22T12:00:00Z")}])))))]
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _child-session-id _prompt]
                      {:execution-result/assistant-message
                       {:content "planner output"}})]
        (let [result   (workflow-execution/execute-run! ctx session-id "run-selection-1")
              run      (workflow-runtime/workflow-run-in @(:state* ctx) "run-selection-1")
              child-id (get-in run [:step-runs "step-1" :attempts 0 :execution-session-id])
              child-sd (get-in @(:state* ctx) [:agent-session :sessions child-id :data])
              prepared (prompt-request/build-prepared-request
                        ctx child-id
                        {:turn-id "wf-selection-proof"
                         :user-message {:role "user"
                                        :content [{:type :text :text "plan it"}]}})]
          (is (= :completed (:status result)))
          (is (= ["read"] (mapv :name (:tool-defs child-sd))))
          (is (= ["testing-best-practices"] (mapv :name (:skills child-sd))))
          (is (= {:agents-md? false
                  :extension-prompt-contributions []
                  :tool-names ["read"]
                  :skill-names ["testing-best-practices"]
                  :components #{:skills}
                  :include-preamble? false
                  :include-context-files? false
                  :include-skills? true
                  :include-runtime-metadata? false}
                 (:prompt-component-selection child-sd)))
          (is (not (str/includes? (:base-system-prompt child-sd) "λ engage(nucleus).")))
          (is (str/includes? (:base-system-prompt child-sd) "testing-best-practices"))
          (is (not (str/includes? (:prepared-request/system-prompt prepared) "A")))
          (is (= ["read"] (mapv :name (:prepared-request/tools prepared)))))))))

(deftest execute-run-with-judge-loop-test
  (testing "execute-run! handles a judge loop via the statechart runtime"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state support/judged-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build-review-judged"
                                                                   :run-id "run-loop"
                                                                   :workflow-input {:input "ship it"
                                                                                    :original "build feature"}})]
                       s)))
          step-executions* (atom [])
          judge-call-count* (atom 0)]
      (with-redefs [psi.workflow-runtime.attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        (swap! step-executions* conj (:workflow-step-id opts))
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (support/valid-child-session sid)}))
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx sid _text]
                      {:execution-result/assistant-message
                       (cond
                         (str/includes? sid "step-1-planner") {:content "plan output"}
                         (str/includes? sid "step-2-builder") {:content "build output"}
                         (str/includes? sid "step-3-reviewer") {:content "review output"}
                         :else {:content "unknown"})})
                    psi.agent-session.workflow-judge/execute-judge!
                    (fn [& _args]
                      (let [n (swap! judge-call-count* inc)]
                        {:judge-session-id (str "judge-" n)
                         :judge-output (if (= 1 n) "REVISE" "APPROVED")
                         :judge-event (if (= 1 n) "REVISE" "APPROVED")
                         :routing-result (if (= 1 n)
                                           {:action :goto :target "step-2-builder"}
                                           {:action :complete})}))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-loop")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-loop")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= 5 (count (:steps-executed result))))
          (is (= ["step-1-planner" "step-2-builder" "step-3-reviewer" "step-2-builder" "step-3-reviewer"]
                 @step-executions*))
          (is (= 2 (get-in run [:step-runs "step-2-builder" :iteration-count])))
          (is (= 2 (get-in run [:step-runs "step-3-reviewer" :iteration-count]))))))))


