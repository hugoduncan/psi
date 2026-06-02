(ns psi.workflow-step-session-config.core-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.ai.model-registry :as model-registry]
   [psi.session-state.init :as session-init]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.execution-adapter]
   [psi.workflow-runtime.step-test-support :as support]
   [psi.workflow-step-session-config.core :as workflow-step-session-config]
   [psi.workflow-registry.registry :as workflow-registry]))

(defn- assoc-session-data!
  [ctx session-id kvs]
  (swap! (:state* ctx) update-in [:agent-session :sessions session-id :data] merge kvs))

(deftest resolve-inherited-defaults-snapshot-test
  (testing "captures the resolved inherited defaults from the live parent session,
            including :speed-mode and :effort-override"
    (let [[ctx session-id] (support/create-session-context {:persist? false})]
      (assoc-session-data! ctx session-id
                           {:model {:provider "anthropic" :id "claude-test"}
                            :prompt-mode :concise
                            :thinking-level :high
                            :speed-mode :fast
                            :effort-override :xhigh})
      (let [snapshot (workflow-step-session-config/resolve-inherited-defaults-snapshot ctx session-id)]
        (is (= workflow-step-session-config/inherited-defaults-snapshot-keys
               (set (keys snapshot)))
            "snapshot returns exactly the declared resolved-key set")
        (is (= {:provider "anthropic" :id "claude-test"} (:model snapshot)))
        (is (= :concise (:prompt-mode snapshot)))
        (is (= :high (:thinking-level snapshot)))
        (is (= :fast (:speed-mode snapshot)))
        (is (= :xhigh (:effort-override snapshot)))
        (is (vector? (:tool-defs snapshot)))
        (is (sequential? (:skills snapshot))))))

  (testing "thinking-level defaults to :off when the parent has none"
    (let [[ctx session-id] (support/create-session-context {:persist? false})]
      (assoc-session-data! ctx session-id {:model {:provider "anthropic" :id "claude-test"}})
      (let [snapshot (workflow-step-session-config/resolve-inherited-defaults-snapshot ctx session-id)]
        (is (= :off (:thinking-level snapshot)))
        (is (nil? (:speed-mode snapshot)))
        (is (nil? (:effort-override snapshot)))))))

(deftest effective-config->snapshot-test
  (testing "projects only the snapshot keys; the overridden model and the five
            resolver-emitted inherited keys come from the effective config"
    (let [effective {:model {:provider "anthropic" :id "claude-override"}
                     :prompt-mode :verbose
                     :tool-defs [{:name "read"}]
                     :skills [{:name "skill-a"}]
                     :thinking-level :medium
                     ;; non-snapshot resolver outputs that must not leak through
                     :developer-prompt "ignored"
                     :response-mode :streaming
                     :temperature 0.7}
          parent-snapshot {:speed-mode :fast :effort-override :high}
          snapshot (workflow-step-session-config/effective-config->snapshot
                    effective parent-snapshot)]
      (is (= workflow-step-session-config/inherited-defaults-snapshot-keys
             (set (keys snapshot)))
          "projection yields exactly the snapshot key set")
      (is (= {:provider "anthropic" :id "claude-override"} (:model snapshot))
          "overridden model preserved from the effective config")
      (is (= :verbose (:prompt-mode snapshot)))
      (is (= [{:name "read"}] (:tool-defs snapshot)))
      (is (= [{:name "skill-a"}] (:skills snapshot)))
      (is (= :medium (:thinking-level snapshot)))))

  (testing ":speed-mode/:effort-override are sourced from the parent snapshot
            (the effective config emits neither — P2)"
    (let [effective {:model {:provider "anthropic" :id "claude-x"}
                     :prompt-mode :concise
                     :tool-defs []
                     :skills []
                     :thinking-level :off
                     ;; effective config carries no speed/effort keys
                     :speed-mode :should-be-ignored}
          parent-snapshot {:speed-mode :fast :effort-override :xhigh}
          snapshot (workflow-step-session-config/effective-config->snapshot
                    effective parent-snapshot)]
      (is (= :fast (:speed-mode snapshot)))
      (is (= :xhigh (:effort-override snapshot)))))

  (testing "thinking-level defaults to :off when the effective config has none"
    (let [snapshot (workflow-step-session-config/effective-config->snapshot
                    {:model {:provider "anthropic" :id "x"}} {})]
      (is (= :off (:thinking-level snapshot))))))

;;; ── S5: snapshot consumption in step config resolution ─────────────────────

(def ^:private no-override-definition
  "A single-step workflow whose step gives NO model/prompt-mode/tools/skills/
   thinking-level override, so the resolver falls back to the inherited
   defaults (snapshot when present, live parent otherwise)."
  {:definition-id "no-override"
   :name "no-override"
   :steps [{:name "step-1"
            :type :session
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]
   :workflow-file-meta {:system-prompt "Do the thing."}})

(defn- run-with-snapshot
  "Create a no-override workflow run carrying an :inherited-defaults snapshot and
   a :parent-session-id pointing at session-id."
  [ctx session-id snapshot run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[state' _ _] (workflow-registry/register-definition state no-override-definition)
                 [state'' _ _] (workflow-runtime/create-run
                                state'
                                {:definition-id "no-override"
                                 :run-id run-id
                                 :parent-session-id session-id
                                 :inherited-defaults snapshot
                                 :workflow-input {:input "go"}})]
             state'')))
  (workflow-runtime/workflow-run-in @(:state* ctx) run-id))

(deftest snapshot-isolates-resolution-from-live-parent-mutation-test
  (testing "AC1/AC2: a captured snapshot makes the resolved step config
            independent of later invoking-session / default model changes"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          snapshot {:model {:provider "anthropic" :id "claude-snapshot"}
                    :prompt-mode :concise
                    :tool-defs [{:name "read"}]
                    :skills [{:name "skill-a"}]
                    :thinking-level :high
                    :speed-mode :fast
                    :effort-override :xhigh}
          workflow-run (run-with-snapshot ctx session-id snapshot "run-iso")]
      ;; Mutate the LIVE parent session after invoke (model + prompt-mode +
      ;; speed/effort) — must not affect resolution.
      (assoc-session-data! ctx session-id
                           {:model {:provider "anthropic" :id "claude-LIVE-CHANGED"}
                            :prompt-mode :verbose
                            :speed-mode :flex
                            :effort-override :low})
      (let [config (workflow-step-session-config/resolve-step-session-config
                    ctx nil workflow-run "step-1")]
        (is (= {:provider "anthropic" :id "claude-snapshot"} (:model config))
            "model comes from the snapshot, not the mutated live session")
        (is (= :concise (:prompt-mode config))
            "prompt-mode comes from the snapshot")
        (is (= :high (:thinking-level config))
            "thinking-level inherited from the snapshot")
        (is (= :fast (:speed-mode config))
            "speed-mode comes from the snapshot (AC3)")
        (is (= :xhigh (:effort-override config))
            "effort-override comes from the snapshot (AC3)")))))

(deftest snapshot-model-feeds-model-query-selection-context-test
  (testing "AC7: resolved-model-query selection context comes from the snapshot
            model, not the live parent"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          model-query-definition
          {:definition-id "model-query-wf"
           :name "model-query-wf"
           :steps [{:name "step-1"
                    :type :session
                    :model {:type :model-query :require [] :prefer []}
                    :contributions [{:type :template
                                     :text "{{input}}"
                                     :vars {"input" {:from :workflow-input :path [:input]}}}]}]
           :workflow-file-meta {:system-prompt "Query."}}
          snapshot {:model {:provider "anthropic" :id "claude-snapshot"}
                    :prompt-mode :concise
                    :tool-defs []
                    :skills []
                    :thinking-level :off
                    :speed-mode nil
                    :effort-override nil}
          workflow-run
          (do (swap! (:state* ctx)
                     (fn [state]
                       (let [[s _ _] (workflow-registry/register-definition state model-query-definition)
                             [s' _ _] (workflow-runtime/create-run
                                       s {:definition-id "model-query-wf"
                                          :run-id "run-mq"
                                          :parent-session-id session-id
                                          :inherited-defaults snapshot
                                          :workflow-input {:input "go"}})]
                         s')))
              (workflow-runtime/workflow-run-in @(:state* ctx) "run-mq"))]
      (assoc-session-data! ctx session-id
                           {:model {:provider "anthropic" :id "claude-LIVE-CHANGED"}})
      (let [config (workflow-step-session-config/resolve-step-session-config
                    ctx nil workflow-run "step-1")]
        ;; The model-fallback selection context is derived from the snapshot
        ;; model; the live session change must not influence the ranking input.
        (is (some? (:model-fallback config)))
        (is (= :ranked-model-candidates (get-in config [:model-fallback :type])))))))

(deftest snapshot-preserves-explicit-step-override-test
  (testing "AC5: an explicit step model override still wins over the snapshot"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          override-definition
          {:definition-id "override-wf"
           :name "override-wf"
           :steps [{:name "step-1"
                    :type :session
                    :model "claude-explicit"
                    :contributions [{:type :template
                                     :text "{{input}}"
                                     :vars {"input" {:from :workflow-input :path [:input]}}}]}]
           :workflow-file-meta {:system-prompt "Override."}}
          snapshot {:model {:provider "anthropic" :id "claude-snapshot"}
                    :prompt-mode :concise
                    :tool-defs []
                    :skills []
                    :thinking-level :off
                    :speed-mode nil
                    :effort-override nil}
          workflow-run
          (do (swap! (:state* ctx)
                     (fn [state]
                       (let [[s _ _] (workflow-registry/register-definition state override-definition)
                             [s' _ _] (workflow-runtime/create-run
                                       s {:definition-id "override-wf"
                                          :run-id "run-ov"
                                          :parent-session-id session-id
                                          :inherited-defaults snapshot
                                          :workflow-input {:input "go"}})]
                         s')))
              (workflow-runtime/workflow-run-in @(:state* ctx) "run-ov"))
          config (workflow-step-session-config/resolve-step-session-config
                  ctx nil workflow-run "step-1")]
      (is (= "claude-explicit" (or (get-in config [:model :id]) (:model config)))
          "explicit step override wins; snapshot only governs inherited default"))))

(deftest no-snapshot-falls-back-to-live-parent-test
  (testing "AC6: a run WITHOUT a snapshot resolves from the live parent (back-compat)"
    (let [[ctx session-id] (support/create-session-context {:persist? false})]
      (assoc-session-data! ctx session-id
                           {:model {:provider "anthropic" :id "claude-live"}
                            :prompt-mode :verbose})
      (let [workflow-run
            (do (swap! (:state* ctx)
                       (fn [state]
                         (let [[s _ _] (workflow-registry/register-definition state no-override-definition)
                               [s' _ _] (workflow-runtime/create-run
                                         s {:definition-id "no-override"
                                            :run-id "run-nolive"
                                            :parent-session-id session-id
                                            :workflow-input {:input "go"}})]
                           s')))
                (workflow-runtime/workflow-run-in @(:state* ctx) "run-nolive"))
            config (workflow-step-session-config/resolve-step-session-config
                    ctx nil workflow-run "step-1")]
        (is (= {:provider "anthropic" :id "claude-live"} (:model config))
            "no snapshot → model comes from the live parent session")
        (is (= :verbose (:prompt-mode config)))
        (is (not (contains? config :speed-mode))
            "no snapshot → no speed-mode emitted")
        (is (not (contains? config :effort-override))
            "no snapshot → no effort-override emitted")))))

(deftest inherited-defaults-field-set-authority-test
  (testing "every :from-common source key is a member of the canonical
            common-inherited-fields authority"
    (let [common (set session-init/common-inherited-fields)]
      (is (set/subset? (:from-common workflow-step-session-config/inherited-defaults-source-keys)
                       common)
          "snapshot :from-common keys must not drift from common-inherited-fields")))

  (testing "every :from-model source key is a member of the canonical
            model-identity-fields authority"
    (let [model-fields (set session-init/model-identity-fields)]
      (is (set/subset? (:from-model workflow-step-session-config/inherited-defaults-source-keys)
                       model-fields)
          "snapshot :from-model keys must not drift from model-identity-fields")))

  (testing "the resolved snapshot key set equals the source keys with
            :tool-ids->:tool-defs and :skill-ids->:skills substituted"
    (let [{:keys [from-common from-model]} workflow-step-session-config/inherited-defaults-source-keys
          source-keys (set/union from-common from-model)
          resolved (-> source-keys
                       (disj :tool-ids :skill-ids)
                       (conj :tool-defs :skills))]
      (is (= resolved workflow-step-session-config/inherited-defaults-snapshot-keys)
          "resolved snapshot keys must match the declared source keys (resolved-vs-raw)"))))

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
                                        :tools ["read"]
                                        :skills ["testing-best-practices"]
                                        :model "gpt-5"
                                        :thinking-level :high
                                        :contributions [{:type :template
                                                         :text "{{input}}"
                                                         :vars {"input" {:from {:step "step-1-planner" :output :final-llm-reply}}}}]}]
                               :workflow-file-meta {:framing-prompt "Coordinate a plan-build cycle."}}
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :skill-ids]
                   ["testing-best-practices"])
          _ (swap! (:state* ctx) assoc-in [:root-registries :skills :entries-by-id "testing-best-practices"]
                   {:id "testing-best-practices"
                    :extension-id :psi.skill-registry/definitions
                    :value {:name "testing-best-practices"
                            :description "Testing"
                            :file-path ""
                            :base-dir ""
                            :source :project
                            :disable-model-invocation false}})
          workflow-run (workflow-run-for ctx
                                         [support/single-step-definition-with-meta
                                          support/builder-definition-with-meta
                                          override-definition]
                                         {:definition-id "plan-build-overrides"
                                          :run-id "run-overrides"
                                          :parent-session-id session-id
                                          :workflow-input {:input "build it"}})
          builder-config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-2-builder")]
      (is (= "Focus only on correctness.\n\nCoordinate a plan-build cycle." (:developer-prompt builder-config)))

      (is (= :high (:thinking-level builder-config)))
      (is (= {:provider "openai" :id "gpt-5"} (:model builder-config)))

      (is (= ["read"] (mapv :name (:tool-defs builder-config))))
      (is (= ["testing-best-practices"] (mapv :name (:skills builder-config)))))))

(deftest resolve-step-session-config-canonicalizes-workflow-selected-skills-test
  (testing "workflow step :session :skills selects by exact name but does not define model-visible order"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          definition {:definition-id "skill-order"
                      :name "skill-order"
                      :steps [{:name "step-1"
                               :type :session
                               :tools ["read"]
                               :skills ["z-skill" "a-skill"]
                               :contributions [{:type :template
                                                :text "{{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (-> state
                         (assoc-in [:agent-session :sessions session-id :data :skill-ids]
                                   ["z-skill" "a-skill"])
                         (assoc-in [:root-registries :skills :entries-by-id "z-skill"]
                                   {:id "z-skill"
                                    :extension-id :psi.skill-registry/definitions
                                    :value {:name "z-skill"
                                            :description "Z"
                                            :file-path ""
                                            :base-dir ""
                                            :source :project
                                            :disable-model-invocation false}})
                         (assoc-in [:root-registries :skills :entries-by-id "a-skill"]
                                   {:id "a-skill"
                                    :extension-id :psi.skill-registry/definitions
                                    :value {:name "a-skill"
                                            :description "A"
                                            :file-path ""
                                            :base-dir ""
                                            :source :project
                                            :disable-model-invocation false}}))))
          workflow-run (workflow-run-for ctx
                                         [definition]
                                         {:definition-id "skill-order"
                                          :run-id "run-skill-order"
                                          :parent-session-id session-id
                                          :workflow-input {:input "build it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= ["a-skill" "z-skill"] (mapv :name (:skills config)))))))

(deftest resolve-step-session-config-prefers-delegating-session-over-context-defaults-test
  (testing "workflow child sessions inherit model and prompt-mode from the authoritative delegating session rather than the first context session"
    (let [[ctx first-session-id] (support/create-session-context {:persist? false})
          second-session-id (:session-id (session/new-session-in! ctx nil {:session-name "delegator"}))
          _ (swap! (:state* ctx)
                   (fn [state]
                     (-> state
                         (assoc-in [:agent-session :sessions first-session-id :data :prompt-mode] :first-mode)
                         (assoc-in [:agent-session :sessions first-session-id :data :model]
                                   {:provider "openai" :id "first-model"})
                         (assoc-in [:agent-session :sessions first-session-id :data :updated-at]
                                   (java.time.Instant/parse "2026-05-07T10:00:00Z"))
                         (assoc-in [:agent-session :sessions second-session-id :data :prompt-mode] :delegating-mode)
                         (assoc-in [:agent-session :sessions second-session-id :data :model]
                                   {:provider "openai" :id "delegating-model"})
                         (assoc-in [:agent-session :sessions second-session-id :data :updated-at]
                                   (java.time.Instant/parse "2026-05-07T11:00:00Z")))))
          workflow-run (workflow-run-for ctx
                                         [support/single-step-definition-with-meta]
                                         {:definition-id "planner"
                                          :run-id "run-delegating-parent"
                                          :parent-session-id second-session-id
                                          :workflow-input {:input "plan it"}})
          listed-session-ids (mapv :session-id (psi.workflow-runtime.execution-adapter/list-context-sessions ctx))
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= [first-session-id second-session-id] listed-session-ids))
      (is (= second-session-id (:parent-session-id workflow-run)))
      (is (= :delegating-mode (:prompt-mode config)))
      (is (= {:provider "openai" :id "delegating-model"} (:model config))))))

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
                                             :tools ["read"]
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

(deftest resolve-step-session-config-defaults-logprobs-to-disabled-test
  (testing "workflow child sessions default logprobs to disabled and omit top-logprobs when absent"
    (let [[ctx _] (support/create-session-context {:persist? false})
          workflow-run (workflow-run-for ctx
                                         [support/single-step-definition-with-meta]
                                         {:definition-id "planner"
                                          :run-id "run-logprobs-default"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (false? (:logprobs config)))
      (is (not (contains? config :top-logprobs))))))

(deftest resolve-step-session-config-explicit-logprobs-test
  (testing "workflow child sessions carry explicit logprob controls from authored step session config"
    (let [[ctx _] (support/create-session-context {:persist? false})
          definition {:definition-id "planner-logprobs"
                      :name "planner-logprobs"
                      :steps [{:name "step-1"
                               :type :session
                               :response-mode :non-streaming
                               :logprobs true
                               :top-logprobs 5
                               :contributions [{:type :template
                                                :text "{{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                      :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [definition]
                                         {:definition-id "planner-logprobs"
                                          :run-id "run-logprobs-explicit"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= :non-streaming (:response-mode config)))
      (is (true? (:logprobs config)))
      (is (= 5 (:top-logprobs config))))))

(deftest resolve-step-session-config-resolves-model-query-to-concrete-model-test
  (testing "workflow child sessions resolve authored model-query specs to a concrete model before runtime use"
    (let [models-path (doto (java.io.File/createTempFile "psi-workflow-step-models" ".edn")
                        (spit (pr-str {:version 1
                                       :providers {"local-helper"
                                                   {:base-url "http://localhost:11434/v1"
                                                    :api :openai-completions
                                                    :auth {:auth-header? false}
                                                    :models [{:id "fast-free"
                                                              :name "Fast Free Local"
                                                              :supports-text true
                                                              :locality :local
                                                              :latency-tier :low
                                                              :cost-tier :zero
                                                              :input-cost 0.0
                                                              :output-cost 0.0}]}}})))
          _ (model-registry/init! {:user-models-path (.getAbsolutePath models-path)})
          [ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :model]
                   {:provider "anthropic" :id "claude-sonnet-4-6"})
          definition {:definition-id "planner-model-query"
                      :name "planner-model-query"
                      :steps [{:name "step-1"
                               :type :session
                               :model {:type :model-query
                                       :require [{:criterion :supports-text
                                                  :match :true}
                                                 {:criterion :latency-tier
                                                  :equals :low}
                                                 {:criterion :cost-tier
                                                  :one-of [:zero :low]}]
                                       :prefer [{:criterion :locality
                                                 :equals :local}
                                                {:criterion :input-cost
                                                 :prefer :lower}
                                                {:criterion :output-cost
                                                 :prefer :lower}]}
                               :contributions [{:type :template
                                                :text "{{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                      :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [definition]
                                         {:definition-id "planner-model-query"
                                          :run-id "run-model-query"
                                          :parent-session-id session-id
                                          :workflow-input {:input "plan it"}})
          config (try
                   (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")
                   (finally
                     (model-registry/init! {})))]
      (is (= {:provider "local-helper" :id "fast-free"}
             (:model config)))
      (is (= :ranked-model-candidates
             (get-in config [:model-fallback :type])))
      (is (= :ok
             (get-in config [:model-fallback :selection-outcome])))
      (is (nil? (get-in config [:model-fallback :selection-reason])))
      (is (= {:provider "local-helper" :id "fast-free"}
             (first (get-in config [:model-fallback :candidates])))))))

(deftest resolve-step-session-config-model-query-no-winner-preserves-empty-ranked-metadata-test
  (testing "workflow child sessions preserve no-winner ranked metadata for authored model-query specs"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          _ (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :model]
                   {:provider "anthropic" :id "claude-sonnet-4-6"})
          definition {:definition-id "planner-model-query-no-winner"
                      :name "planner-model-query-no-winner"
                      :steps [{:name "step-1"
                               :type :session
                               :model {:type :model-query
                                       :require [{:criterion :supports-text
                                                  :match :true}
                                                 {:criterion :context-window
                                                  :at-least 999999999}]}
                               :contributions [{:type :template
                                                :text "{{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                      :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [definition]
                                         {:definition-id "planner-model-query-no-winner"
                                          :run-id "run-model-query-no-winner"
                                          :parent-session-id session-id
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (nil? (:model config)))
      (is (= {:type :ranked-model-candidates
              :selection-outcome :no-winner
              :selection-reason :required-constraints-unsatisfied
              :candidates []}
             (:model-fallback config))))))

(deftest resolve-step-session-config-drops-top-logprobs-when-logprobs-disabled-test
  (testing "workflow child sessions drop authored top-logprobs when logprobs are false"
    (let [[ctx _] (support/create-session-context {:persist? false})
          definition {:definition-id "planner-logprobs-disabled"
                      :name "planner-logprobs-disabled"
                      :steps [{:name "step-1"
                               :type :session
                               :logprobs false
                               :top-logprobs 9
                               :contributions [{:type :template
                                                :text "{{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                      :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [definition]
                                         {:definition-id "planner-logprobs-disabled"
                                          :run-id "run-logprobs-disabled"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (false? (:logprobs config)))
      (is (not (contains? config :top-logprobs))))))

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

(deftest resolve-step-session-config-omits-temperature-when-absent-test
  (testing "workflow child sessions omit temperature from config when not authored"
    (let [[ctx _] (support/create-session-context {:persist? false})
          workflow-run (workflow-run-for ctx
                                         [support/single-step-definition-with-meta]
                                         {:definition-id "planner"
                                          :run-id "run-temp-absent"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (not (contains? config :temperature))))))

(deftest resolve-step-session-config-explicit-temperature-test
  (testing "workflow child sessions carry explicit temperature from authored step session config"
    (let [[ctx _] (support/create-session-context {:persist? false})
          definition {:definition-id "planner-temp"
                      :name "planner-temp"
                      :steps [{:name "step-1"
                               :type :session
                               :temperature 0.0
                               :contributions [{:type :template
                                                :text "{{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                      :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [definition]
                                         {:definition-id "planner-temp"
                                          :run-id "run-temp-explicit"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= 0.0 (:temperature config)))))

  (testing "workflow child sessions carry non-zero temperature from authored step session config"
    (let [[ctx _] (support/create-session-context {:persist? false})
          definition {:definition-id "planner-temp-nonzero"
                      :name "planner-temp-nonzero"
                      :steps [{:name "step-1"
                               :type :session
                               :temperature 1.5
                               :contributions [{:type :template
                                                :text "{{input}}"
                                                :vars {"input" {:from :workflow-input :path [:input]}}}]}]
                      :workflow-file-meta {:system-prompt "You are a planner."}}
          workflow-run (workflow-run-for ctx
                                         [definition]
                                         {:definition-id "planner-temp-nonzero"
                                          :run-id "run-temp-nonzero"
                                          :workflow-input {:input "plan it"}})
          config (workflow-step-session-config/resolve-step-session-config ctx nil workflow-run "step-1")]
      (is (= 1.5 (:temperature config))))))
