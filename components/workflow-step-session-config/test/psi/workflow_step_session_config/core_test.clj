(ns psi.workflow-step-session-config.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.execution-adapter]
   [psi.workflow-runtime.step-test-support :as support]
   [psi.workflow-step-session-config.core :as workflow-step-session-config]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn- workflow-run-for
  [ctx definitions run-opts]
  (swap! (:state* ctx)
         (fn [state]
           (let [state (reduce (fn [s definition]
                                 (let [[s' _ _] (workflow-registry/register-definition s definition)]
                                   s'))
                               state
                               definitions)
                 [state' _ _] (workflow-runtime/create-run state run-opts)]
             state')))
  (workflow-runtime/workflow-run-in @(:state* ctx) (:run-id run-opts)))

(deftest resolve-step-session-config-single-step-test
  (testing "single-step workflow pulls config from its own workflow-file-meta"
    (let [[ctx _] (support/create-session-context {:persist? false})
          single-step-with-model (assoc-in support/single-step-definition-with-meta [:workflow-file-meta :model]
                                           {:provider :anthropic :id "claude-test"})
          workflow-run (workflow-run-for ctx
                                         [single-step-with-model]
                                         {:definition-id "planner"
                                          :run-id "run-1"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= "You are a planner." (:developer-prompt config)))

      (is (= :medium (:thinking-level config)))
      (is (= {:provider :anthropic :id "claude-test"} (:model config)))

      (is (= ["read" "bash"] (mapv :name (:tool-defs config))))
      (is (= ["clojure-coding-standards"] (mapv :name (:skills config)))))))

(deftest resolve-step-session-config-multi-step-test
  (testing "multi-step workflow composes referenced workflow prompt with framing prompt"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :model]
                   {:provider "openai" :id "gpt-test"})
          workflow-run (workflow-run-for ctx
                                         [support/single-step-definition-with-meta
                                          support/builder-definition-with-meta
                                          support/multi-step-definition-with-meta]
                                         {:definition-id "plan-build"
                                          :run-id "run-2"
                                          :workflow-input {:input "build it"
                                                           :original "build this"}})
          planner-config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1-planner")
          builder-config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-2-builder")]
      (is (= "Coordinate a plan-build cycle." (:developer-prompt planner-config)))
      (is (= "You are a builder.\n\nCoordinate a plan-build cycle." (:developer-prompt builder-config)))

      (is (= {:provider "openai" :id "gpt-test"} (:model planner-config)))
      (is (= {:provider "openai" :id "gpt-test"} (:model builder-config)))

      (is (= ["read" "bash"] (mapv :name (:tool-defs planner-config))))
      (is (= ["read" "bash" "edit" "write"] (mapv :name (:tool-defs builder-config)))))))

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
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :skills]
                   [{:name "testing-best-practices"
                     :description "Testing"
                     :file-path ""
                     :base-dir ""
                     :source :project
                     :disable-model-invocation false}])
          workflow-run (workflow-run-for ctx
                                         [support/single-step-definition-with-meta
                                          support/builder-definition-with-meta
                                          override-definition]
                                         {:definition-id "plan-build-overrides"
                                          :run-id "run-overrides"
                                          :workflow-input {:input "build it"}})
          builder-config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-2-builder")]
      (is (= "Focus only on correctness.\n\nCoordinate a plan-build cycle." (:developer-prompt builder-config)))

      (is (= :high (:thinking-level builder-config)))
      (is (= "gpt-5" (:model builder-config)))

      (is (= [] (mapv :name (:tool-defs builder-config))))
      (is (= ["testing-best-practices"] (mapv :name (:skills builder-config)))))))

(deftest resolve-step-session-config-defaults-response-mode-to-streaming-test
  (testing "workflow child sessions resolve explicit default :streaming response mode when absent"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          workflow-run (do
                         (swap! (:state* ctx)
                                (fn [state]
                                  (let [state (reduce (fn [s definition]
                                                        (let [[s' _ _] (workflow-registry/register-definition s definition)]
                                                          s'))
                                                      state
                                                      [support/single-step-definition-with-meta])
                                        [state' _ _] (workflow-runtime/create-run state
                                                                                  {:definition-id "planner"
                                                                                   :run-id "run-response-mode-1"
                                                                                   :workflow-input {:input "plan it"}})]
                                    (assoc-in state' [:agent-session :sessions session-id :data :prompt-mode] :prose))))
                         (workflow-runtime/workflow-run-in @(:state* ctx) "run-response-mode-1"))
          config (workflow-step-session-config/resolve-step-session-config ctx session-id workflow-run "step-1")]
      (is (= :streaming (:response-mode config))))))

(deftest resolve-step-session-config-inherits-parent-prompt-mode-test
  (testing "workflow child sessions inherit parent prompt mode into step session config"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          workflow-run (do
                         (swap! (:state* ctx)
                                (fn [state]
                                  (let [state (reduce (fn [s definition]
                                                        (let [[s' _ _] (workflow-registry/register-definition s definition)]
                                                          s'))
                                                      state
                                                      [support/single-step-definition-with-meta])
                                        [state' _ _] (workflow-runtime/create-run state
                                                                                  {:definition-id "planner"
                                                                                   :run-id "run-mode-1"
                                                                                   :workflow-input {:input "plan it"}})]
                                    (assoc-in state' [:agent-session :sessions session-id :data :prompt-mode] :prose))))
                         (workflow-runtime/workflow-run-in @(:state* ctx) "run-mode-1"))
          config (workflow-step-session-config/resolve-step-session-config ctx session-id workflow-run "step-1")]
      (is (= :prose (:prompt-mode config))))))

(deftest resolve-step-session-config-falls-back-to-first-context-session-when-parent-session-id-is-nil-test
  (testing "nil parent-session-id falls back to the first listed context session"
    (let [[ctx first-session-id] (support/create-session-context {:persist? false})
          second-session-id (:session-id (session/new-session-in! ctx nil {:session-name "fallback-second"}))
          _ (swap! (:state* ctx)
                   (fn [state]
                     (-> state
                         (assoc-in [:agent-session :sessions first-session-id :data :prompt-mode] :first-mode)
                         (assoc-in [:agent-session :sessions first-session-id :data :model]
                                   {:provider "openai" :id "first-model"})
                         (assoc-in [:agent-session :sessions first-session-id :data :updated-at]
                                   (java.time.Instant/parse "2026-05-07T10:00:00Z"))
                         (assoc-in [:agent-session :sessions second-session-id :data :prompt-mode] :second-mode)
                         (assoc-in [:agent-session :sessions second-session-id :data :model]
                                   {:provider "openai" :id "second-model"})
                         (assoc-in [:agent-session :sessions second-session-id :data :updated-at]
                                   (java.time.Instant/parse "2026-05-07T11:00:00Z")))))
          workflow-run (workflow-run-for ctx
                                         [support/single-step-definition-with-meta]
                                         {:definition-id "planner"
                                          :run-id "run-fallback-1"
                                          :workflow-input {:input "plan it"}})
          listed-session-ids (mapv :session-id (psi.workflow-runtime.execution-adapter/list-context-sessions ctx))
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= [first-session-id second-session-id] listed-session-ids))

      (is (= :first-mode (:prompt-mode config)))
      (is (= {:provider "openai" :id "first-model"} (:model config))))))

(deftest resolve-step-session-config-missing-skill-falls-back-to-placeholder-shape-test
  (testing "missing skill references fall back to the placeholder skill map shape at the public boundary"
    (let [[ctx _] (support/create-session-context {:persist? false})
          missing-skill-definition {:definition-id "planner-missing-skill"
                                    :name "planner-missing-skill"
                                    :steps [{:name "step-1"
                                             :type :session
                                             :skills ["missing-skill"]
                                             :contributions [{:type :template
                                                              :text "{{input}}"
                                                              :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                                    :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [missing-skill-definition]
                                         {:definition-id "planner-missing-skill"
                                          :run-id "run-missing-skill"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= [{:name "missing-skill"
               :description ""
               :file-path ""
               :base-dir ""
               :source :project
               :disable-model-invocation false}]
             (:skills config))))))

(deftest resolve-step-session-config-explicit-response-mode-test
  (testing "workflow child sessions carry explicit :response-mode from authored step session config"
    (let [[ctx _] (support/create-session-context {:persist? false})
          response-mode-definition {:definition-id "planner-non-streaming"
                                    :name "planner-non-streaming"
                                    :steps [{:name "step-1"
                                             :type :session
                                             :response-mode :non-streaming
                                             :contributions [{:type :template
                                                              :text "{{input}}"
                                                              :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                                    :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [response-mode-definition]
                                         {:definition-id "planner-non-streaming"
                                          :run-id "run-non-streaming"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= :non-streaming (:response-mode config))))))

(deftest resolve-step-session-config-missing-tool-falls-back-to-normalized-tool-shape-test
  (testing "missing tool references fall back to normalized tool definition shape at the public boundary"
    (let [[ctx _] (support/create-session-context {:persist? false})
          missing-tool-definition {:definition-id "planner-missing-tool"
                                   :name "planner-missing-tool"
                                   :steps [{:name "step-1"
                                            :type :session
                                            :tools ["missing-tool"]
                                            :contributions [{:type :template
                                                             :text "{{input}}"
                                                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                                   :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [missing-tool-definition]
                                         {:definition-id "planner-missing-tool"
                                          :run-id "run-missing-tool"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= ["missing-tool"] (mapv :name (:tool-defs config)))))))
