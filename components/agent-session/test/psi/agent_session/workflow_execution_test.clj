(ns psi.agent-session.workflow-execution-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.deterministic-operation-registry.registry]
   [psi.session-persistence.core]
   [psi.agent-session.turn]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.agent-session.test-support :as core-support]
   [psi.shared-config.project :as project-prefs]
   [psi.shared-config.user :as user-config]
   [psi.skill-registry.root-storage :as skill-storage]
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
                 (get-in run [:step-runs "report-call" :accepted-result :diagnostics :delegate])))
          ;; The nested run records the delegating run as its run-level parent
          ;; (`:delegating-run-id`) so workflow-run retention can exclude it from
          ;; the originating session's per-session budget — without this tag a
          ;; single multi-step delegation would evict its own sub-run/sessions.
          (is (= "run-delegate" (:delegating-run-id delegated-run))
              "nested delegate-step run is tagged with the delegating run's id")
          ;; The nested run shares the originating execution session as its
          ;; `:parent-session-id` (sessionless runs pass it straight through),
          ;; which is exactly why retention must key off `:delegating-run-id`
          ;; rather than the shared session id.
          (is (= session-id (:parent-session-id delegated-run))
              "nested delegate-step run shares the originating session id"))))))

(defn- create-dynamic-delegate-run!
  [{:keys [run-id workflow-input chooser-result register-builder? remove-builder-before-delegate?]}]
  (let [[ctx session-id] (support/create-session-context {:persist? false})
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
        builder-definition {:definition-id "builder"
                            :steps [{:name "callee"
                                     :type :session
                                     :contributions [{:type :source
                                                      :from :workflow-original}
                                                     {:type :template
                                                      :text "Do {{input}}"
                                                      :vars {"input" {:from :workflow-input}}}]}]}
        _ (swap! (:state* ctx)
                 (fn [state]
                   (let [state (if register-builder?
                                 (first (workflow-registry/register-definition state builder-definition))
                                 state)
                         [s _ _] (workflow-runtime/create-run state {:definition caller-definition
                                                                     :run-id run-id
                                                                     :workflow-input (or workflow-input {})})]
                     s)))
        prompts* (atom [])]
    {:ctx ctx
     :session-id session-id
     :prompts* prompts*
     :run-id run-id
     :execute! (fn []
                 (with-redefs [psi.deterministic-operation-registry.registry/invoke-operation-in
                               (fn [_registry operation-id _invocation _invoke-operation]
                                 (if (= operation-id "demo/select-workflow")
                                   (do
                                     (when remove-builder-before-delegate?
                                       (swap! (:state* ctx)
                                              (fn [state]
                                                (first (workflow-registry/remove-definition state "builder")))))
                                     {:status :ok
                                      :data chooser-result
                                      :summary "selected workflow"})
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
                   (workflow-execution/execute-run! ctx session-id run-id)))}))

(deftest execute-run-dynamic-delegate-step-success-test
  (let [{:keys [ctx prompts* execute! run-id]}
        (create-dynamic-delegate-run!
         {:run-id "run-dynamic-delegate"
          :workflow-input {:original {:ticket 123 :request "Please triage"}}
          :register-builder? true
          :chooser-result {:selected-workflow {:type :workflow-ref
                                               :name "builder"}}})
        result (execute!)
        run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
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
           @prompts*))))

(deftest execute-run-dynamic-delegate-step-wrong-type-failure-test
  (let [{:keys [ctx execute! run-id]}
        (create-dynamic-delegate-run!
         {:run-id "run-dynamic-delegate-invalid"
          :chooser-result {:selected-workflow "builder"}})
        result (execute!)
        run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
    (is (= :failed (:status result)))
    (is (= "Dynamic delegate target must resolve to a workflow reference"
           (get-in run [:step-runs "run-selected-workflow" :attempts 0 :execution-error :message])))))

(deftest execute-run-dynamic-delegate-step-unknown-target-lookup-failure-test
  (let [{:keys [ctx execute! run-id]}
        (create-dynamic-delegate-run!
         {:run-id "run-dynamic-delegate-unknown"
          :chooser-result {:selected-workflow {:type :workflow-ref
                                               :name "missing-workflow"}}})
        result (execute!)
        run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
    (is (= :failed (:status result)))
    (is (= "Delegated workflow definition not found: missing-workflow"
           (get-in run [:step-runs "run-selected-workflow" :attempts 0 :execution-error :message])))
    (is (= :workflow/execution-failure-recorded
           (get-in run [:history (dec (count (:history run))) :event])))))

(deftest execute-run-dynamic-delegate-step-removed-target-lookup-failure-test
  (let [{:keys [ctx execute! run-id]}
        (create-dynamic-delegate-run!
         {:run-id "run-dynamic-delegate-removed"
          :register-builder? true
          :remove-builder-before-delegate? true
          :chooser-result {:selected-workflow {:type :workflow-ref
                                               :name "builder"}}})
        result (execute!)
        run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
    (is (= :failed (:status result)))
    (is (= "Delegated workflow definition not found: builder"
           (get-in run [:step-runs "run-selected-workflow" :attempts 0 :execution-error :message])))
    (is (= :workflow/execution-failure-recorded
           (get-in run [:history (dec (count (:history run))) :event])))))

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
                           s (assoc-in s [:agent-session :sessions session-id :data :tool-ids]
                                       ["read"])
                           ;; Set tool-source in agent data-atom for resolve-tool-defs
                           _ (swap! (get-in s [:agent-session :sessions session-id :agent-ctx :data-atom])
                                    assoc :tools [{:name "read" :description "Read" :parameters {:type "object" :properties {}}}])
                           s (assoc-in s [:agent-session :sessions session-id :data :system-prompt-build-opts]
                                       {:selected-tools ["read" "psi-tool"]})
                           s (assoc-in s [:agent-session :sessions session-id :data :prompt-contribution-ids]
                                       [(:id contribution)])
                           s (assoc-in s [:root-registries :prompt-contributions :entries-by-id (:id contribution)]
                                       {:id (:id contribution)
                                        :extension-id (:ext-path contribution)
                                        :value contribution})]
                       s)))]
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in! (fn [_ctx _child-session-id _prompt]
                                                                         {:execution-result/assistant-message
                                                                          {:content "planner output"}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-ext-1")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-ext-1")
              child-id (get-in run [:step-runs "step-1" :attempts 0 :execution-session-id])
              child-sd (get-in @(:state* ctx) [:agent-session :sessions child-id :data])
              _ (core-support/seed-augmentation-record! ctx child-id "wf-child-proof")
              prepared (prompt-request/build-prepared-request
                        ctx child-id
                        {:turn-id "wf-child-proof"
                         :user-message {:role "user"
                                        :content [{:type :text :text "plan it"}]}})]
          (is (= :completed (:status result)))
          (is (not (contains? child-sd :prompt-contributions))
              ":prompt-contributions no longer persisted in session state")
          (is (= [(:id contribution)] (:prompt-contribution-ids child-sd)))
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
                                                                   :workflow-input {:input "plan it"}})
                           skill {:name "testing-best-practices"
                                  :description "Testing guidance"
                                  :file-path "/tmp/SKILL.md"
                                  :base-dir "/tmp"
                                  :source :project
                                  :disable-model-invocation false}
                           s (:root-state (skill-storage/set-skills-in-root-state s session-id [skill]))]
                       (-> s
                           (assoc-in [:agent-session :sessions session-id :data :tool-ids]
                                     ["read" "bash"])
                           ;; Set tool-source in agent data-atom for resolve-tool-defs
                           (update-in [:agent-session :sessions session-id :agent-ctx :data-atom]
                                      (fn [a] (swap! a assoc :tools [{:name "read" :description "Read" :parameters {:type "object" :properties {}}}
                                                                     {:name "bash" :description "Bash" :parameters {:type "object" :properties {}}}]) a))
                           (assoc-in [:agent-session :sessions session-id :data :prompt-contribution-ids]
                                     ["a"])
                           (assoc-in [:root-registries :prompt-contributions :entries-by-id "a"]
                                     {:id "a"
                                      :extension-id "/ext/a"
                                      :value {:id "a"
                                              :ext-path "/ext/a"
                                              :content "A"
                                              :enabled true
                                              :created-at (java.time.Instant/parse "2026-04-22T12:00:00Z")
                                              :updated-at (java.time.Instant/parse "2026-04-22T12:00:00Z")}})))))]
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _child-session-id _prompt]
                      {:execution-result/assistant-message
                       {:content "planner output"}})]
        (let [result   (workflow-execution/execute-run! ctx session-id "run-selection-1")
              run      (workflow-runtime/workflow-run-in @(:state* ctx) "run-selection-1")
              child-id (get-in run [:step-runs "step-1" :attempts 0 :execution-session-id])
              child-sd (get-in @(:state* ctx) [:agent-session :sessions child-id :data])
              _ (core-support/seed-augmentation-record! ctx child-id "wf-selection-proof")
              prepared (prompt-request/build-prepared-request
                        ctx child-id
                        {:turn-id "wf-selection-proof"
                         :user-message {:role "user"
                                        :content [{:type :text :text "plan it"}]}})]
          (is (= :completed (:status result)))
          (is (= ["read"] (:tool-ids child-sd)))
          (is (= ["testing-best-practices"] (:skill-ids child-sd)))
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

(deftest execute-run-initial-workflow-child-session-model-setup-does-not-persist-config-test
  (testing "initial workflow child-session model setup leaves project prefs and user config untouched"
    (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-workflow-session-scope-" (java.util.UUID/randomUUID))
          _ (.mkdirs (java.io.File. cwd))
          shared-f (project-prefs/project-preferences-file cwd)
          local-f (project-prefs/project-local-preferences-file cwd)
          user-f (java.io.File. (str cwd "/user-home/.psi/agent/config.edn"))
          _ (.mkdirs (.getParentFile shared-f))
          _ (.mkdirs (.getParentFile user-f))
          _ (spit shared-f (pr-str {:version 1
                                    :agent-session {:prompt-mode :prose}}))
          [ctx session-id] (support/create-session-context {:cwd cwd})
          definition {:definition-id "workflow-initial-model-no-persist"
                      :steps [{:name "plan"
                               :type :session
                               :model {:provider "openai" :id "gpt-5"}
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/create-run state {:definition definition
                                                                       :run-id "run-initial-model-no-persist"
                                                                       :parent-session-id session-id
                                                                       :workflow-input {:input "ship it"}})]
                       s)))
          user-calls* (atom [])]
      (with-redefs [user-config/user-config-file (fn [] user-f)
                    user-config/update-agent-session! (fn [prefs]
                                                        (swap! user-calls* conj prefs)
                                                        {:version 1 :agent-session prefs})
                    psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _child-session-id _prompt]
                      {:execution-result/assistant-message
                       {:content "planner output"}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-initial-model-no-persist")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-initial-model-no-persist")
              child-id (get-in run [:step-runs "plan" :attempts 0 :execution-session-id])
              child-sd (get-in @(:state* ctx) [:agent-session :sessions child-id :data])]
          (is (= :completed (:status result)))
          (is (= {:provider "openai" :id "gpt-5"} (:model child-sd)))
          (is (= {:version 1
                  :agent-session {:prompt-mode :prose}}
                 (edn/read-string (slurp shared-f))))
          (is (false? (.exists local-f)))
          (is (false? (.exists user-f)))
          (is (= [] @user-calls*)))))))


