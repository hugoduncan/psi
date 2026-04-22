(ns psi.agent-session.workflow-execution-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.prompt-control]
   [psi.agent-session.workflow-attempts]
   [psi.agent-session.workflow-progression]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-request :as prompt-request]
   [psi.agent-session.session :as session-model]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def definition
  {:definition-id "plan-build"
   :name "Plan Build"
   :step-order ["step-1-planner" "step-2-builder"]
   :steps {"step-1-planner" {:executor {:type :agent :profile "planner" :mode :sync}
                             :prompt-template "$INPUT"
                             :input-bindings {:input {:source :workflow-input :path [:input]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}}
           "step-2-builder" {:executor {:type :agent :profile "builder" :mode :sync}
                             :prompt-template "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"
                             :input-bindings {:input {:source :step-output :path ["step-1-planner" :outputs :text]}
                                              :original {:source :workflow-input :path [:original]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}}}})

(def blocked-definition
  {:definition-id "blocked-review"
   :name "Blocked Review"
   :step-order ["step-1-review"]
   :steps {"step-1-review" {:executor {:type :agent :profile "reviewer" :mode :sync}
                            :prompt-template "$INPUT"
                            :input-bindings {:input {:source :workflow-input :path [:input]}}
                            :result-schema :any
                            :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}})

(def retry-definition
  {:definition-id "retry-build"
   :name "Retry Build"
   :step-order ["step-1-builder"]
   :steps {"step-1-builder" {:executor {:type :agent :profile "builder" :mode :sync}
                             :prompt-template "$INPUT"
                             :input-bindings {:input {:source :workflow-input :path [:input]}}
                             :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                             :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}}})

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

(deftest resolve-step-session-config-single-step-test
  (testing "single-step workflow pulls config from its own workflow-file-meta"
    (let [[ctx _] (create-session-context {:persist? false})
          single-step-with-model (assoc-in single-step-definition-with-meta [:workflow-file-meta :model] {:provider :anthropic :id "claude-test"})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-with-model)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-1"
                                                                   :workflow-input {:input "plan it"}})]
                       s)))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-1")
          config (workflow-execution/resolve-step-session-config ctx workflow-run "step-1")]
      (is (= "You are a planner." (:system-prompt config)))
      (is (= [{:name "read" :label "read" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}
              {:name "bash" :label "bash" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}]
             (:tool-defs config)))
      (is (= :medium (:thinking-level config)))
      (is (= [{:name "clojure-coding-standards"
               :description ""
               :file-path ""
               :base-dir ""
               :source :project
               :disable-model-invocation false}]
             (:skills config)))
      (is (= {:provider :anthropic :id "claude-test"} (:model config))))))

(deftest resolve-step-session-config-multi-step-test
  (testing "multi-step workflow composes referenced workflow system prompt with framing prompt"
    (let [[ctx _] (create-session-context {:persist? false})
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
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-2")

          ;; Step 1 references "planner" → should get planner meta + framing prompt
          config1 (workflow-execution/resolve-step-session-config ctx workflow-run "step-1-planner")
          ;; Step 2 references "builder" → should get builder meta + framing prompt
          config2 (workflow-execution/resolve-step-session-config ctx workflow-run "step-2-builder")]

      ;; Step 1: planner config + chain framing
      (is (= "You are a planner.\n\nCoordinate a plan-build cycle." (:system-prompt config1)))
      (is (= "You are a planner." (:base-system-prompt config1)))
      (is (= "Coordinate a plan-build cycle." (:framing-prompt config1)))
      (is (= [{:name "read" :label "read" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}
              {:name "bash" :label "bash" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}]
             (:tool-defs config1)))
      (is (= :medium (:thinking-level config1)))
      (is (= [{:name "clojure-coding-standards"
               :description ""
               :file-path ""
               :base-dir ""
               :source :project
               :disable-model-invocation false}]
             (:skills config1)))

      ;; Step 2: builder config + chain framing
      (is (= "You are a builder.\n\nCoordinate a plan-build cycle." (:system-prompt config2)))
      (is (= "You are a builder." (:base-system-prompt config2)))
      (is (= "Coordinate a plan-build cycle." (:framing-prompt config2)))
      (is (= [{:name "read" :label "read" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}
              {:name "bash" :label "bash" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}
              {:name "edit" :label "edit" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}
              {:name "write" :label "write" :description "" :parameters {:type "object" :properties {}} :lambda-description nil :source nil :ext-path nil :enabled? true}]
             (:tool-defs config2)))
      (is (= :off (:thinking-level config2)))
      (is (nil? (:skills config2))))))

(deftest resolve-step-session-config-multi-step-framing-fallback-test
  (testing "when referenced workflow has no system-prompt, framing-prompt from multi-step is still injected"
    (let [[ctx _] (create-session-context {:persist? false})
          ;; reviewer def with no system-prompt in meta
          reviewer-def {:definition-id "reviewer"
                        :name "reviewer"
                        :step-order ["step-1"]
                        :steps {"step-1" {:executor {:type :agent :profile "reviewer"}
                                          :prompt-template "$INPUT"
                                          :input-bindings {:input {:source :workflow-input :path [:input]}}
                                          :result-schema :any
                                          :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}
                        :workflow-file-meta {}}
          chain-def {:definition-id "review-chain"
                     :name "review-chain"
                     :step-order ["step-1-reviewer"]
                     :steps {"step-1-reviewer" {:executor {:type :agent :profile "reviewer"}
                                                :prompt-template "$INPUT"
                                                :input-bindings {:input {:source :workflow-input :path [:input]}}
                                                :result-schema :any
                                                :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}
                     :workflow-file-meta {:framing-prompt "Review carefully."}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state reviewer-def)
                           [s _ _] (workflow-runtime/register-definition s chain-def)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "review-chain"
                                                                   :run-id "run-3"
                                                                   :workflow-input {:input "review this"}})]
                       s)))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-3")
          config (workflow-execution/resolve-step-session-config ctx workflow-run "step-1-reviewer")]
      (is (nil? (:base-system-prompt config)))
      (is (= "Review carefully." (:framing-prompt config)))
      (is (= "Review carefully." (:system-prompt config))))))

(deftest materialize-step-inputs-and-prompt-test
  (let [[state1 _ _] (workflow-runtime/register-definition {:workflows {:definitions {} :runs {} :run-order []}} definition)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                               :run-id "run-1"
                                                               :workflow-input {:input "ship it" :original "build this feature"}})
        run0 (workflow-runtime/workflow-run-in state2 run-id)
        prompt0 (workflow-execution/step-prompt run0 "step-1-planner")
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-planner" :accepted-result]
                         {:outcome :ok :outputs {:text "plan text"}})
        run1 (workflow-runtime/workflow-run-in state3 run-id)
        prompt1 (workflow-execution/step-prompt run1 "step-2-builder")]
    (is (= {:input "ship it" :original "build this feature"} (:step-inputs prompt0)))
    (is (= "ship it" (:prompt prompt0)))
    (is (= {:input "plan text" :original "build this feature"} (:step-inputs prompt1)))
    (is (= "Execute this plan:\n\nplan text\n\nOriginal request: build this feature"
           (:prompt prompt1)))))

(deftest execute-current-step-test
  (testing "current step execution creates an attempt session, prompts it, and advances the workflow"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                                             :run-id "run-1"
                                                                             :workflow-input {:input "ship it"
                                                                                              :original "build this feature"}})]
                       state2)))
          prompted* (atom [])
          child-create-opts* (atom nil)]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (reset! child-create-opts* opts)
                      {:attempt {:attempt-id "a1" :status :pending :execution-session-id "child-1"}
                       :execution-session {:session-id "child-1"}})
                    psi.agent-session.prompt-control/prompt-in! (fn [_ctx child-session-id prompt]
                                                                  (swap! prompted* conj {:session-id child-session-id :prompt prompt})
                                                                  nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                                 {:content "planner output"})]
        (let [result (workflow-execution/execute-current-step! ctx session-id "run-1")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-1")]
          (is (= "step-1-planner" (:step-id result)))
          (is (= :running (:status run)))
          (is (= "step-2-builder" (:current-step-id run)))
          (is (= {:outcome :ok :outputs {:text "planner output"}}
                 (get-in run [:step-runs "step-1-planner" :accepted-result])))
          (is (= [{:session-id (:execution-session-id result)
                   :prompt "ship it"}]
                 @prompted*))
          ;; Single-step definition has no framing prompt, so system prompt is unchanged
          (is (nil? (:system-prompt @child-create-opts*))))))))

(deftest execute-run-test
  (testing "execute-run! drives a sequential workflow through all steps to completion"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                                             :run-id "run-1"
                                                                             :workflow-input {:input "ship it"
                                                                                              :original "build this feature"}})]
                       state2)))
          prompts*   (atom [])
          responses* (atom ["planner output" "builder output"])]
      (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx child-session-id prompt]
                                                                  (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                                                                  nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                                 (let [resp (first @responses*)]
                                                                                   (swap! responses* subvec 1)
                                                                                   {:content resp}))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-1")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-1")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= 2 (count (:steps-executed result))))
          (is (= :completed (:status run)))
          (is (= {:outcome :ok :outputs {:text "builder output"}}
                 (get-in run [:step-runs "step-2-builder" :accepted-result])))
          (is (= ["ship it"
                  "Execute this plan:\n\nplanner output\n\nOriginal request: build this feature"]
                 (mapv :prompt @prompts*))))))))

(deftest execute-current-step-multi-step-composes-child-system-prompt-test
  (testing "multi-step execution injects framing prompt and execution config into delegated child session context"
    (let [[ctx session-id] (create-session-context {:persist? false})
          planner-with-model (assoc-in single-step-definition-with-meta [:workflow-file-meta :model] {:provider :anthropic :id "claude-plan"})
          builder-with-model (assoc-in builder-definition-with-meta [:workflow-file-meta :model] {:provider :anthropic :id "claude-build"})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state planner-with-model)
                           [s _ _] (workflow-runtime/register-definition s builder-with-model)
                           [s _ _] (workflow-runtime/register-definition s multi-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-4"
                                                                   :workflow-input {:input "build it"
                                                                                    :original "build this"}})]
                       s)))
          child-create-opts* (atom [])]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (swap! child-create-opts* conj opts)
                      {:attempt {:attempt-id (str "a-" (count @child-create-opts*))
                                 :status :pending
                                 :execution-session-id (str "child-" (count @child-create-opts*))}
                       :execution-session {:session-id (str "child-" (count @child-create-opts*))}})
                    psi.agent-session.prompt-control/prompt-in! (fn [_ctx _child-session-id _prompt] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx child-session-id]
                                                                                 {:content (if (= child-session-id "child-1")
                                                                                             "planner output"
                                                                                             "builder output")})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-4")]
          (is (= :completed (:status result)))
          (is (= ["You are a planner.\n\nCoordinate a plan-build cycle."
                  "You are a builder.\n\nCoordinate a plan-build cycle."]
                 (mapv :system-prompt @child-create-opts*)))
          (is (= [[{:name "clojure-coding-standards"
                    :description ""
                    :file-path ""
                    :base-dir ""
                    :source :project
                    :disable-model-invocation false}]
                  nil]
                 (mapv :skills @child-create-opts*)))
          (is (= [{:provider :anthropic :id "claude-plan"}
                  {:provider :anthropic :id "claude-build"}]
                 (mapv :model @child-create-opts*))))))))

(deftest execute-current-step-resolves-workflow-skill-names-before-child-session-test
  (testing "delegated workflow skill names are resolved to canonical skill maps before child session creation"
    (let [[ctx session-id] (create-session-context {:persist? false})
          skill {:name "clojure-coding-standards"
                 :description "Clojure coding standards"
                 :file-path "/tmp/clojure-coding-standards/SKILL.md"
                 :base-dir "/tmp/clojure-coding-standards"
                 :source :project
                 :disable-model-invocation false}
          planner-with-skill (assoc single-step-definition-with-meta :workflow-file-meta
                                    {:system-prompt "You are a planner."
                                     :tools [{:name "read" :description "Read" :parameters {:type "object" :properties {}}}
                                             {:name "bash" :description "Bash" :parameters {:type "object" :properties {}}}]
                                     :skills ["clojure-coding-standards"]
                                     :thinking-level :medium})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state planner-with-skill)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-skill-1"
                                                                   :workflow-input {:input "plan it"}})
                           s (assoc-in s [:agent-session :sessions session-id :data :skills] [skill])
                           s (assoc-in s [:agent-session :sessions session-id :data :tool-defs]
                                       [{:name "read" :description "Read" :parameters {:type "object" :properties {}}}
                                        {:name "bash" :description "Bash" :parameters {:type "object" :properties {}}}])]
                       s)))
          child-create-opts* (atom nil)]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (reset! child-create-opts* opts)
                      {:attempt {:attempt-id "a1" :status :pending :execution-session-id "child-1"}
                       :execution-session {:session-id "child-1"}})
                    psi.agent-session.prompt-control/prompt-in! (fn [_ctx _child-session-id _prompt] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                                 {:content "planner output"})]
        (let [result (workflow-execution/execute-current-step! ctx session-id "run-skill-1")]
          (is (= "step-1" (:step-id result)))
          (is (= [skill] (:skills @child-create-opts*)))
          (is (true? (session-model/valid-skill? (first (:skills @child-create-opts*))))))))))

(deftest execute-current-step-resolves-workflow-tool-names-before-child-session-test
  (testing "delegated workflow tool names are resolved to canonical tool defs before child session creation"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool-defs [{:name "read" :description "Read" :parameters {:type "object" :properties {}}}
                     {:name "bash" :description "Bash" :parameters {:type "object" :properties {}}}]
          planner-with-tools (assoc single-step-definition-with-meta :workflow-file-meta
                                    {:system-prompt "You are a planner."
                                     :tools ["read" "bash"]
                                     :thinking-level :medium})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state planner-with-tools)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-tool-1"
                                                                   :workflow-input {:input "plan it"}})
                           s (assoc-in s [:agent-session :sessions session-id :data :tool-defs] tool-defs)]
                       s)))
          child-create-opts* (atom nil)]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (reset! child-create-opts* opts)
                      {:attempt {:attempt-id "a1"
                                 :status :pending
                                 :execution-session-id "child-1"}
                       :execution-session {:session-id "child-1"}})
                    psi.agent-session.prompt-control/prompt-in! (fn [_ctx _child-session-id _prompt] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                                 {:content "planner output"})]
        (let [result (workflow-execution/execute-current-step! ctx session-id "run-tool-1")]
          (is (= "step-1" (:step-id result)))
          (is (= [{:name "read" :description "Read" :parameters {:type "object" :properties {}}}
                  {:name "bash" :description "Bash" :parameters {:type "object" :properties {}}}]
                 (mapv #(select-keys % [:name :description :parameters])
                       (:tool-defs @child-create-opts*)))))))))

(deftest execute-current-step-preserves-parent-extension-prompt-contributions-test
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
                           s (assoc-in s [:agent-session :sessions session-id :data :prompt-contributions]
                                       [contribution])]
                       s)))]
      (with-redefs [psi.agent-session.prompt-control/prompt-in! (fn [_ctx _child-session-id _prompt] nil)
                    psi.agent-session.prompt-control/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                                 {:content "planner output"})]
        (let [result (workflow-execution/execute-current-step! ctx session-id "run-ext-1")
              child-id (:execution-session-id result)
              child-sd (ss/get-session-data-in ctx child-id)]
          (is (= "step-1" (:step-id result)))
          (is (= [contribution]
                 (mapv #(select-keys % [:id :ext-path :section :content :enabled :created-at :updated-at])
                       (:prompt-contributions child-sd))))
          (is (str/includes? (prompt-request/effective-system-prompt child-sd)
                             "command: /work-on")))))))

(deftest execute-run-blocked-test
  (testing "execute-run! stops and reports blocked runs"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state blocked-definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "blocked-review"
                                                                             :run-id "run-b1"
                                                                             :workflow-input {:input "need approval"}})]
                       state2)))]
      (with-redefs [psi.agent-session.workflow-execution/execute-current-step! (fn [_ctx _sid _run-id]
                                                                                 (swap! (:state* ctx)
                                                                                        update-in
                                                                                        [:workflows :runs "run-b1"]
                                                                                        #(-> %
                                                                                             (assoc :status :blocked
                                                                                                    :blocked {:question "approve?"})
                                                                                             (assoc-in [:step-runs "step-1-review" :attempts]
                                                                                                       [{:attempt-id "a1"
                                                                                                         :status :blocked
                                                                                                         :execution-session-id "child-1"
                                                                                                         :blocked {:question "approve?"}}])))
                                                                                 {:run-id "run-b1"
                                                                                  :step-id "step-1-review"
                                                                                  :attempt-id "a1"
                                                                                  :execution-session-id "child-1"
                                                                                  :status :blocked})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-b1")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-b1")]
          (is (= :blocked (:status result)))
          (is (false? (:terminal? result)))
          (is (true? (:blocked? result)))
          (is (= :blocked (:status run)))
          (is (= {:question "approve?"} (:blocked run))))))))

(deftest execute-run-retry-test
  (testing "execute-run! retries when execution failure leaves the run retryable"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state retry-definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "retry-build"
                                                                             :run-id "run-r1"
                                                                             :workflow-input {:input "retry me"}})]
                       (-> state2
                           (assoc-in [:workflows :runs "run-r1" :step-runs "step-1-builder" :attempts]
                                     [{:attempt-id "a1"
                                       :status :running
                                       :execution-session-id "child-1"}])))))
          calls* (atom 0)]
      (with-redefs [psi.agent-session.workflow-execution/execute-current-step!
                    (fn [_ctx _sid _run-id]
                      (let [n (swap! calls* inc)]
                        (if (= 1 n)
                          (do
                            (swap! (:state* ctx)
                                   psi.agent-session.workflow-progression/record-execution-failure
                                   "run-r1"
                                   "step-1-builder"
                                   {:message "transient provider error"})
                            {:run-id "run-r1"
                             :step-id "step-1-builder"
                             :attempt-id "a1"
                             :execution-session-id "child-1"
                             :status :running
                             :error "transient provider error"})
                          (do
                            (swap! (:state* ctx)
                                   update-in
                                   [:workflows :runs "run-r1" :step-runs "step-1-builder" :attempts]
                                   conj
                                   {:attempt-id "a2"
                                    :status :running
                                    :execution-session-id "child-2"})
                            (swap! (:state* ctx)
                                   psi.agent-session.workflow-progression/submit-result-envelope
                                   "run-r1"
                                   "step-1-builder"
                                   {:outcome :ok :outputs {:text "done"}})
                            {:run-id "run-r1"
                             :step-id "step-1-builder"
                             :attempt-id "a2"
                             :execution-session-id "child-2"
                             :status :completed}))))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-r1")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-r1")]
          (is (= 2 @calls*))
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= :completed (:status run)))
          (is (= {:outcome :ok :outputs {:text "done"}}
                 (get-in run [:step-runs "step-1-builder" :accepted-result]))))))))

(deftest resume-and-execute-run-test
  (testing "resume-and-execute-run! resumes blocked runs and continues with a fresh attempt"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state blocked-definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "blocked-review"
                                                                             :run-id "run-resume"
                                                                             :workflow-input {:input "need approval"}})]
                       (-> state2
                           (assoc-in [:workflows :runs "run-resume" :status] :blocked)
                           (assoc-in [:workflows :runs "run-resume" :blocked] {:question "approve?"})
                           (assoc-in [:workflows :runs "run-resume" :step-runs "step-1-review" :attempts]
                                     [{:attempt-id "a1"
                                       :status :blocked
                                       :execution-session-id "child-1"
                                       :blocked {:question "approve?"}}])))))
          calls* (atom 0)]
      (with-redefs [psi.agent-session.workflow-execution/execute-current-step!
                    (fn [_ctx _sid _run-id]
                      (swap! calls* inc)
                      (swap! (:state* ctx)
                             update-in
                             [:workflows :runs "run-resume" :step-runs "step-1-review" :attempts]
                             conj
                             {:attempt-id "a2"
                              :status :running
                              :execution-session-id "child-2"})
                      (swap! (:state* ctx)
                             psi.agent-session.workflow-progression/submit-result-envelope
                             "run-resume"
                             "step-1-review"
                             {:outcome :ok :outputs {:text "approved"}})
                      {:run-id "run-resume"
                       :step-id "step-1-review"
                       :attempt-id "a2"
                       :execution-session-id "child-2"
                       :status :completed})]
        (let [result (workflow-execution/resume-and-execute-run! ctx session-id "run-resume")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-resume")]
          (is (= 1 @calls*))
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= :completed (:status run)))
          (is (nil? (:blocked run))))))))
