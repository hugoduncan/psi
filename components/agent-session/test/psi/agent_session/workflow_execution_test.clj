(ns psi.agent-session.workflow-execution-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence]
   [psi.agent-session.prompt-control]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-judge]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-statechart-runtime :as workflow-statechart-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def single-step-definition-with-meta
  {:definition-id "planner"
   :name "planner"
   :step-order ["step-1"]
   :steps {"step-1" {:executor {:type :agent :profile "planner"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}
                                      :original {:source :workflow-input :path [:original]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                     :capability-policy {:tools #{"read" "bash"}}}}
   :workflow-file-meta {:system-prompt "You are a planner."
                        :tools ["read" "bash"]
                        :skills ["clojure-coding-standards"]
                        :thinking-level :medium}})

(def builder-definition-with-meta
  {:definition-id "builder"
   :name "builder"
   :step-order ["step-1"]
   :steps {"step-1" {:executor {:type :agent :profile "builder"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                     :capability-policy {:tools #{"read" "bash" "edit" "write"}}}}
   :workflow-file-meta {:system-prompt "You are a builder."
                        :tools ["read" "bash" "edit" "write"]
                        :thinking-level :off}})

(def multi-step-definition-with-meta
  {:definition-id "plan-build"
   :name "plan-build"
   :step-order ["step-1-planner" "step-2-builder"]
   :steps {"step-1-planner" {:executor {:type :agent :profile "planner"}
                             :prompt-template "$INPUT"
                             :input-bindings {:input {:source :workflow-input :path [:input]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "step-2-builder" {:executor {:type :agent :profile "builder"}
                             :prompt-template "Execute: $INPUT"
                             :input-bindings {:input {:source :step-output :path ["step-1-planner" :outputs :text]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}
   :workflow-file-meta {:framing-prompt "Coordinate a plan-build cycle."}})

(def workflow-selection-definition
  {:definition-id "planner-selection"
   :name "planner-selection"
   :step-order ["step-1"]
   :steps {"step-1" {:executor {:type :agent :profile "planner"}
                     :prompt-template "$INPUT"
                     :input-bindings {:input {:source :workflow-input :path [:input]}}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                     :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                     :session-overrides {:prompt-component-selection {:components #{:skills}
                                                                      :tool-names ["read"]
                                                                      :skill-names ["testing-best-practices"]
                                                                      :extension-prompt-contributions []
                                                                      :agents-md? false}}}}
   :workflow-file-meta {:system-prompt "You are a planner."
                        :tools ["read" "bash"]
                        :skills ["testing-best-practices"]
                        :thinking-level :medium}})

(def judged-definition
  {:definition-id "plan-build-review-judged"
   :name "plan-build-review-judged"
   :step-order ["step-1-planner" "step-2-builder" "step-3-reviewer"]
   :steps {"step-1-planner" {:executor {:type :agent :profile "planner"}
                             :prompt-template "$INPUT"
                             :input-bindings {:input {:source :workflow-input :path [:input]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "step-2-builder" {:executor {:type :agent :profile "builder"}
                             :prompt-template "Execute: $INPUT\nOriginal: $ORIGINAL"
                             :input-bindings {:input {:source :step-output :path ["step-1-planner" :outputs :text]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
           "step-3-reviewer" {:executor {:type :agent :profile "reviewer"}
                              :prompt-template "Review: $INPUT\nOriginal: $ORIGINAL"
                              :input-bindings {:input {:source :step-output :path ["step-2-builder" :outputs :text]}
                                               :original {:source :workflow-input :path [:original]}}
                              :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                              :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                              :judge {:prompt "APPROVED or REVISE?"
                                      :system-prompt "You are a routing judge."
                                      :projection {:type :tail :turns 1}}
                              :on {"APPROVED" {:goto :next}
                                   "REVISE" {:goto "step-2-builder" :max-iterations 3}}}}})

(deftest resolve-step-session-config-single-step-test
  (testing "single-step workflow pulls config from its own workflow-file-meta"
    (let [[ctx _] (create-session-context {:persist? false})
          single-step-with-model (assoc-in single-step-definition-with-meta [:workflow-file-meta :model]
                                           {:provider :anthropic :id "claude-test"})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-with-model)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-1"
                                                                   :workflow-input {:input "plan it"}})]
                       s)))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-1")
          config (workflow-execution/resolve-step-session-config ctx workflow-run "step-1")]
      (is (= "You are a planner." (:developer-prompt config)))
      (is (= :medium (:thinking-level config)))
      (is (= {:provider :anthropic :id "claude-test"} (:model config)))
      (is (= ["read" "bash"] (mapv :name (:tool-defs config))))
      (is (= ["clojure-coding-standards"] (mapv :name (:skills config)))))))

(deftest resolve-step-session-config-multi-step-test
  (testing "multi-step workflow composes referenced workflow prompt with framing prompt"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/register-definition s builder-definition-with-meta)
                           [s _ _] (workflow-runtime/register-definition s multi-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-2"
                                                                   :workflow-input {:input "build it"
                                                                                    :original "build this"}})]
                       s)))
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :model]
                   {:provider "openai" :id "gpt-test"})
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-2")
          planner-config (workflow-execution/resolve-step-session-config ctx workflow-run "step-1-planner")
          builder-config (workflow-execution/resolve-step-session-config ctx workflow-run "step-2-builder")]
      (is (= "You are a planner.\n\nCoordinate a plan-build cycle." (:developer-prompt planner-config)))
      (is (= "You are a builder.\n\nCoordinate a plan-build cycle." (:developer-prompt builder-config)))
      (is (= ["read" "bash"] (mapv :name (:tool-defs planner-config))))
      (is (= ["read" "bash" "edit" "write"] (mapv :name (:tool-defs builder-config))))
      (is (= {:provider "openai" :id "gpt-test"} (:model planner-config)))
      (is (= {:provider "openai" :id "gpt-test"} (:model builder-config))))))

(deftest resolve-step-session-config-step-overrides-test
  (testing "step overrides replace delegated defaults while system prompt still composes with framing prompt"
    (let [[ctx session-id] (create-session-context {:persist? false})
          override-definition {:definition-id "plan-build-overrides"
                               :name "plan-build-overrides"
                               :step-order ["step-1-planner" "step-2-builder"]
                               :steps {"step-1-planner" {:executor {:type :agent :profile "planner"}
                                                         :prompt-template "$INPUT"
                                                         :input-bindings {:input {:source :workflow-input :path [:input]}}
                                                         :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                                         :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
                                       "step-2-builder" {:executor {:type :agent :profile "builder"}
                                                         :prompt-template "$INPUT"
                                                         :input-bindings {:input {:source :step-output :path ["step-1-planner" :outputs :text]}}
                                                         :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                                         :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}
                                                         :session-overrides {:system-prompt "Focus only on correctness."
                                                                             :tools []
                                                                             :skills ["testing-best-practices"]
                                                                             :model "gpt-5"
                                                                             :thinking-level :high}}}
                               :workflow-file-meta {:framing-prompt "Coordinate a plan-build cycle."}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/register-definition s builder-definition-with-meta)
                           [s _ _] (workflow-runtime/register-definition s override-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build-overrides"
                                                                   :run-id "run-overrides"
                                                                   :workflow-input {:input "build it"}})]
                       s)))
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :skills]
                   [{:name "testing-best-practices"
                     :description "Testing"
                     :file-path ""
                     :base-dir ""
                     :source :project
                     :disable-model-invocation false}])
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-overrides")
          builder-config (workflow-execution/resolve-step-session-config ctx workflow-run "step-2-builder")]
      (is (= "Focus only on correctness.\n\nCoordinate a plan-build cycle." (:developer-prompt builder-config)))
      (is (= [] (mapv :name (:tool-defs builder-config))))
      (is (= ["testing-best-practices"] (mapv :name (:skills builder-config))))
      (is (= "gpt-5" (:model builder-config)))
      (is (= :high (:thinking-level builder-config))))))

(deftest materialize-step-inputs-and-prompt-test
  (let [[state1 _ _] (workflow-runtime/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                           multi-step-definition-with-meta)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                               :run-id "run-prompt"
                                                               :workflow-input {:input "ship it"
                                                                                :original "build this feature"}})
        run0 (workflow-runtime/workflow-run-in state2 run-id)
        prompt0 (workflow-execution/step-prompt run0 "step-1-planner")
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-planner" :accepted-result]
                         {:outcome :ok :outputs {:text "plan text"}})
        run1 (workflow-runtime/workflow-run-in state3 run-id)
        prompt1 (workflow-execution/step-prompt run1 "step-2-builder")]
    (is (= {:input "ship it" :original "build this feature"} (:step-inputs prompt0)))
    (is (= "ship it" (:prompt prompt0)))
    (is (= {:input "plan text" :original "build this feature"} (:step-inputs prompt1)))
    (is (= "Execute: plan text" (:prompt prompt1)))))

(deftest materialize-step-inputs-and-prompt-with-projections-test
  (let [definition {:definition-id "projection-proof"
                    :name "projection-proof"
                    :step-order ["step-1-discover" "step-2-request-more-info"]
                    :steps {"step-1-discover" {:executor {:type :agent :profile "planner"}
                                               :prompt-template "$INPUT"
                                               :input-bindings {:input {:source :workflow-input :path [:ticket :body]}
                                                                :original {:source :workflow-input :path [:original]}}
                                               :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                               :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}
                            "step-2-request-more-info" {:executor {:type :agent :profile "reviewer"}
                                                        :prompt-template "Need: $INPUT | Original: $ORIGINAL"
                                                        :input-bindings {:input {:source :step-output
                                                                                 :path ["step-1-discover" :diagnostics :summary]}
                                                                         :original {:source :workflow-input
                                                                                    :path [:original :issue :title]}}
                                                        :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                                                        :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}
                    :workflow-file-meta {:framing-prompt "Projection proof."}}
        [state1 _ _] (workflow-runtime/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                           definition)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "projection-proof"
                                                               :run-id "run-projection-proof"
                                                               :workflow-input {:ticket {:body "repro details"}
                                                                                :original {:issue {:title "Bug 123"}}}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-discover" :accepted-result]
                         {:outcome :ok
                          :outputs {:text "plan text"}
                          :diagnostics {:summary "need logs"}})
        run (workflow-runtime/workflow-run-in state3 run-id)
        prompt (workflow-execution/step-prompt run "step-2-request-more-info")]
    (is (= {:input "need logs"
            :original "Bug 123"}
           (:step-inputs prompt)))
    (is (= "Need: need logs | Original: Bug 123"
           (:prompt prompt)))))

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

(deftest execute-run-linear-test
  (testing "execute-run! drives a linear workflow to completion through the statechart runtime"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state multi-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-linear"
                                                                   :workflow-input {:input "ship it"
                                                                                    :original "build this feature"}})]
                       s)))
          prompts* (atom [])
          responses* (atom ["planner output" "builder output"])]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (valid-child-session sid)}))
                    psi.agent-session.prompt-control/prompt-execution-result-in! (fn [_ctx child-session-id prompt]
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
          (is (= {:outcome :ok :outputs {:text "builder output"}}
                 (get-in run [:step-runs "step-2-builder" :accepted-result])))
          (is (= ["ship it"
                  "Execute: planner output"]
                 (mapv :prompt @prompts*))))))))

(deftest resolve-step-session-config-inherits-parent-prompt-mode-test
  (testing "workflow child sessions inherit parent prompt mode into step session config"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-mode-1"
                                                                   :workflow-input {:input "plan it"}})]
                       (assoc-in s [:agent-session :sessions session-id :data :prompt-mode] :prose))))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-mode-1")
          config (workflow-execution/resolve-step-session-config ctx session-id workflow-run "step-1")]
      (is (= :prose (:prompt-mode config))))))

(deftest execute-run-preserves-parent-extension-prompt-contributions-test
  (testing "workflow child sessions inherit parent extension prompt contributions by default"
    (let [[ctx session-id] (create-session-context {:persist? false})
          planner-def (assoc single-step-definition-with-meta :workflow-file-meta
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
                     (let [[s _ _] (workflow-runtime/register-definition state planner-def)
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
      (with-redefs [psi.agent-session.prompt-control/prompt-execution-result-in! (fn [_ctx _child-session-id _prompt]
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
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state workflow-selection-definition)
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
      (with-redefs [psi.agent-session.prompt-control/prompt-execution-result-in!
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
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state judged-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build-review-judged"
                                                                   :run-id "run-loop"
                                                                   :workflow-input {:input "ship it"
                                                                                    :original "build feature"}})]
                       s)))
          step-executions* (atom [])
          judge-call-count* (atom 0)]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        (swap! step-executions* conj (:workflow-step-id opts))
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (valid-child-session sid)}))
                    psi.agent-session.prompt-control/prompt-execution-result-in!
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

(deftest resume-and-execute-run-test
  (testing "resume-and-execute-run! reports the resumed run state without interaction-heavy choreography"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-resume"
                                                                   :workflow-input {:input "plan it"}})]
                       (-> s
                           (assoc-in [:workflows :runs "run-resume" :status] :completed)
                           (assoc-in [:workflows :runs "run-resume" :current-step-id] nil)
                           (assoc-in [:workflows :runs "run-resume" :step-runs "step-1" :attempts]
                                     [{:attempt-id "a1"
                                       :status :succeeded
                                       :execution-session-id "child-1"}])))))
          seen* (atom [])]
      (with-redefs [psi.agent-session.workflow-statechart-runtime/create-workflow-context
                    (fn [_ctx _parent-session-id run-id]
                      (swap! seen* conj [:create run-id])
                      {:wm :stub-wm})
                    psi.agent-session.workflow-statechart-runtime/send-and-drain!
                    (fn [_wf-ctx _wm event _data]
                      (swap! seen* conj [:event event])
                      :stubbed)]
        (let [result (workflow-execution/resume-and-execute-run! ctx session-id "run-resume")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (false? (:blocked? result)))
          (is (= [[:create "run-resume"]
                  [:event :workflow/resume]]
                 @seen*)))))))
