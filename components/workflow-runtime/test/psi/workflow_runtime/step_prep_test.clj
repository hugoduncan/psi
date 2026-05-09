(ns psi.workflow-runtime.step-prep-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.step-prep :as workflow-step-prep]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def single-step-definition-with-meta
  {:definition-id "planner"
   :name "planner"
   :steps [{:name "step-1"
            :type :session
            :tools ["read" "bash"]
            :skills ["clojure-coding-standards"]
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}
                                    "original" {:from :workflow-input :path [:original]}}}]}]
   :workflow-file-meta {:system-prompt "You are a planner."
                        :tools ["read" "bash"]
                        :skills ["clojure-coding-standards"]
                        :thinking-level :medium}})

(def builder-definition-with-meta
  {:definition-id "builder"
   :name "builder"
   :steps [{:name "step-1"
            :type :session
            :tools ["read" "bash" "edit" "write"]
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]
   :workflow-file-meta {:system-prompt "You are a builder."
                        :tools ["read" "bash" "edit" "write"]
                        :skills ["clojure-coding-standards"]
                        :thinking-level :off}})

(def multi-step-definition-with-meta
  {:definition-id "plan-build"
   :name "plan-build"
   :steps [{:name "step-1-planner"
            :type :session
            :tools ["read" "bash"]
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}
                                    "original" {:from :workflow-input :path [:original]}}}]}
           {:name "step-2-builder"
            :type :session
            :system-prompt "You are a builder."
            :tools ["read" "bash" "edit" "write"]
            :contributions [{:type :template
                             :text "Execute: {{input}}"
                             :vars {"input" {:from {:step "step-1-planner" :output :final-llm-reply}}
                                    "original" {:from :workflow-input :path [:original]}}}]}]
   :workflow-file-meta {:framing-prompt "Coordinate a plan-build cycle."}})

(deftest resolve-step-session-config-single-step-test
  (testing "single-step workflow pulls config from its own workflow-file-meta"
    (let [[ctx _] (create-session-context {:persist? false})
          single-step-with-model (assoc-in single-step-definition-with-meta [:workflow-file-meta :model]
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
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-registry/register-definition s builder-definition-with-meta)
                           [s _ _] (workflow-registry/register-definition s multi-step-definition-with-meta)
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
    (let [[ctx session-id] (create-session-context {:persist? false})
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
                     (let [[s _ _] (workflow-registry/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-registry/register-definition s builder-definition-with-meta)
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
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-mode-1"
                                                                   :workflow-input {:input "plan it"}})]
                       (assoc-in s [:agent-session :sessions session-id :data :prompt-mode] :prose))))
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-mode-1")
          config (workflow-step-prep/resolve-step-session-config ctx session-id workflow-run "step-1")]
      (is (= :prose (:prompt-mode config))))))

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

(deftest materialize-step-inputs-and-prompt-test
  (let [[state1 _ _] (workflow-registry/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                            multi-step-definition-with-meta)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                               :run-id "run-prompt"
                                                               :workflow-input {:input "ship it"
                                                                                :original "build this feature"}})
        run0 (workflow-runtime/workflow-run-in state2 run-id)
        prompt0 (workflow-step-prep/step-prompt run0 "step-1-planner")
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-planner" :accepted-result]
                         {:outcome :ok :outputs {:final-llm-reply "plan text"}})
        run1 (workflow-runtime/workflow-run-in state3 run-id)
        prompt1 (workflow-step-prep/step-prompt run1 "step-2-builder")]
    (is (= {:input "ship it" :original "build this feature"} (:step-inputs prompt0)))
    (is (= "ship it" (:prompt prompt0)))
    (is (= {:input "plan text" :original "build this feature"} (:step-inputs prompt1)))
    (is (= "Execute: plan text" (:prompt prompt1)))))

(deftest materialize-step-inputs-and-prompt-with-projections-test
  (let [definition {:definition-id "projection-proof"
                    :name "projection-proof"
                    :steps [{:name "step-1-discover"
                             :type :session
                             :contributions [{:type :template
                                              :text "{{input}}"
                                              :vars {"input" {:from :workflow-input :path [:ticket :body]}
                                                     "original" {:from :workflow-input :path [:original]}}}]}
                            {:name "step-2-request-more-info"
                             :type :session
                             :contributions [{:type :template
                                              :text "Need: {{input}} | Original: {{original}}"
                                              :vars {"input" {:from {:step "step-1-discover" :output :result}
                                                              :path [:diagnostics :summary]}
                                                     "original" {:from :workflow-input
                                                                 :path [:original :issue :title]}}}]}]
                    :workflow-file-meta {:framing-prompt "Projection proof."}}
        [state1 _ _] (workflow-registry/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                            definition)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "projection-proof"
                                                               :run-id "run-projection-proof"
                                                               :workflow-input {:ticket {:body "repro details"}
                                                                                :original {:issue {:title "Bug 123"}}}})
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-discover" :accepted-result]
                         {:outcome :ok
                          :outputs {:final-llm-reply "plan text"}
                          :diagnostics {:summary "need logs"}})
        run (workflow-runtime/workflow-run-in state3 run-id)
        prompt (workflow-step-prep/step-prompt run "step-2-request-more-info")]
    (is (= {:input "need logs"
            :original "Bug 123"}
           (:step-inputs prompt)))
    (is (= "Need: need logs | Original: Bug 123"
           (:prompt prompt)))))

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

(deftest split-step-session-conversation-canonical-contributions-test
  (testing "canonical source and template contributions split into preload plus final prompt in author order"
    (let [definition {:definition-id "mixed-session-inputs"
                      :name "mixed-session-inputs"
                      :steps [{:name "plan"
                               :type :session
                               :contributions [{:type :template
                                                :text "Plan {{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}
                              {:name "review"
                               :type :session
                               :contributions [{:type :source
                                                :from :workflow-original}
                                               {:type :source
                                                :from {:step "plan" :output :final-llm-reply}}
                                               {:type :template
                                                :text "Review {{reply}}"
                                                :vars {"reply" {:from {:step "plan" :yield :text}}}}]}]}
          [state1 _ _] (workflow-registry/register-definition {:workflows {:definitions {} :runs {} :run-order []}}
                                                              definition)
          [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "mixed-session-inputs"
                                                                 :run-id "run-mixed-session-inputs"
                                                                 :workflow-input {:input "Ship it"
                                                                                  :original "Original request"}})
          state3 (assoc-in state2 [:workflows :runs run-id :step-runs "plan" :accepted-result]
                           {:outcome :ok
                            :outputs {:final-llm-reply "plan text"
                                      :text "plan text"}})
          workflow-run (workflow-runtime/workflow-run-in state3 run-id)
          conversation (workflow-step-prep/materialize-step-session-conversation workflow-run "review")
          split (workflow-step-prep/split-step-session-conversation conversation)]
      (is (= [{:role "user" :content "Original request"}
              {:role "user" :content "plan text"}
              {:role "user" :content "Review plan text"}]
             conversation))
      (is (= {:preloaded-messages [{:role "user" :content "Original request"}
                                   {:role "user" :content "plan text"}]
              :prompt "Review plan text"}
             split)))))
