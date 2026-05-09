(ns psi.workflow-runtime.step-prep-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.step-prep :as workflow-step-prep]
   [psi.workflow-runtime.step-prep-test-support :as support]
   [psi.workflow-registry.registry :as workflow-registry]))

(deftest resolve-step-session-config-single-step-test
  (testing "single-step workflow pulls config from its own workflow-file-meta"
    (let [[ctx _] (support/create-session-context {:persist? false})
          single-step-with-model (assoc-in support/single-step-definition-with-meta [:workflow-file-meta :model]
                                           {:provider :anthropic :id "claude-test"})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state single-step-with-model)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-1"
                                                                   :workflow-input {:input "plan it"}})]
                       s)))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-1")
          config (workflow-step-prep/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= "You are a planner." (:developer-prompt config)))
      (is (= :medium (:thinking-level config)))
      (is (= {:provider :anthropic :id "claude-test"} (:model config)))
      (is (= ["read" "bash"] (mapv :name (:tool-defs config))))
      (is (= ["clojure-coding-standards"] (mapv :name (:skills config)))))))

(deftest resolve-step-session-config-multi-step-test
  (testing "multi-step workflow composes referenced workflow prompt with framing prompt"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state support/single-step-definition-with-meta)
                           [s _ _] (workflow-registry/register-definition s support/builder-definition-with-meta)
                           [s _ _] (workflow-registry/register-definition s support/multi-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "plan-build"
                                                                   :run-id "run-2"
                                                                   :workflow-input {:input "build it"
                                                                                    :original "build this"}})]
                       s)))
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :model]
                   {:provider "openai" :id "gpt-test"})
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-2")
          planner-config (workflow-step-prep/resolve-step-session-config ctx nil workflow-run "step-1-planner")
          builder-config (workflow-step-prep/resolve-step-session-config ctx nil workflow-run "step-2-builder")]
      (is (= "Coordinate a plan-build cycle." (:developer-prompt planner-config)))
      (is (= "You are a builder.\n\nCoordinate a plan-build cycle." (:developer-prompt builder-config)))
      (is (= ["read" "bash"] (mapv :name (:tool-defs planner-config))))
      (is (= ["read" "bash" "edit" "write"] (mapv :name (:tool-defs builder-config))))
      (is (= {:provider "openai" :id "gpt-test"} (:model planner-config)))
      (is (= {:provider "openai" :id "gpt-test"} (:model builder-config))))))

(deftest resolve-step-session-config-step-overrides-test
  (testing "step overrides replace delegated defaults while system prompt still composes with framing prompt"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          override-definition {:definition-id "plan-build-overrides"
                               :name "plan-build-overrides"
                               :steps [{:name "step-1-planner"
                                        :type :session
                                        :contributions [{:type :template
                                                         :text "{{input}}"
                                                         :vars {"input" {:from :workflow-input :path [:input]}}}]}
                                       {:name "step-2-builder"
                                        :type :session
                                        :system-prompt "Focus only on correctness."
                                        :tools []
                                        :skills ["testing-best-practices"]
                                        :model "gpt-5"
                                        :thinking-level :high
                                        :contributions [{:type :template
                                                         :text "{{input}}"
                                                         :vars {"input" {:from {:step "step-1-planner" :output :final-llm-reply}}}}]}]
                               :workflow-file-meta {:framing-prompt "Coordinate a plan-build cycle."}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state support/single-step-definition-with-meta)
                           [s _ _] (workflow-registry/register-definition s support/builder-definition-with-meta)
                           [s _ _] (workflow-registry/register-definition s override-definition)
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
          builder-config (workflow-step-prep/resolve-step-session-config ctx nil workflow-run "step-2-builder")]
      (is (= "Focus only on correctness.\n\nCoordinate a plan-build cycle." (:developer-prompt builder-config)))
      (is (= [] (mapv :name (:tool-defs builder-config))))
      (is (= ["testing-best-practices"] (mapv :name (:skills builder-config))))
      (is (= "gpt-5" (:model builder-config)))
      (is (= :high (:thinking-level builder-config))))))

(deftest resolve-step-session-config-inherits-parent-prompt-mode-test
  (testing "workflow child sessions inherit parent prompt mode into step session config"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state support/single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-mode-1"
                                                                   :workflow-input {:input "plan it"}})]
                       (assoc-in s [:agent-session :sessions session-id :data :prompt-mode] :prose))))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-mode-1")
          config (workflow-step-prep/resolve-step-session-config ctx session-id workflow-run "step-1")]
      (is (= :prose (:prompt-mode config))))))
