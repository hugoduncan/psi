(ns psi.workflow-step-session-config.inheritance-snapshot-test
  "Task 207 — workflow inherited-defaults snapshot tests.

   Covers snapshot capture from the live parent, effective-config projection,
   snapshot consumption during step config resolution, nested/delegated
   propagation of overridden defaults, and the inherited-defaults field-set
   authority. Split out of core-test to keep each test file under the
   commit-check file-length limit."
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [psi.session-state.init :as session-init]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.execution-adapter]
   [psi.workflow-runtime.statechart-runtime.delegate :as delegate]
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

(def ^:private tools-skills-definition
  "A single-step workflow whose step references one tool and one skill BY NAME,
   so the resolver resolves each name against the inherited resolution pool
   (the snapshot's :tool-defs / :skills on the snapshot path)."
  {:definition-id "tools-skills"
   :name "tools-skills"
   :steps [{:name "step-1"
            :type :session
            :tools ["read" "shared-tool"]
            :skills ["shared-skill"]
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]
   :workflow-file-meta {:system-prompt "Tools and skills."}})

(defn- run-tools-skills-with-snapshot
  [ctx session-id snapshot run-id]
  (swap! (:state* ctx)
         (fn [state]
           (let [[state' _ _] (workflow-registry/register-definition state tools-skills-definition)
                 [state'' _ _] (workflow-runtime/create-run
                                state'
                                {:definition-id "tools-skills"
                                 :run-id run-id
                                 :parent-session-id session-id
                                 :inherited-defaults snapshot
                                 :workflow-input {:input "go"}})]
             state'')))
  (workflow-runtime/workflow-run-in @(:state* ctx) run-id))

(deftest snapshot-isolates-tools-skills-from-live-parent-mutation-test
  (testing "AC3: tools/skills resolve from the captured snapshot pool, not the
            live parent. The step references `shared-tool`/`shared-skill` by
            name; the snapshot and the (post-invoke) live parent each hold a
            distinct def for those SAME names, so the resolved def's
            distinguishing field proves which pool fed name resolution. A
            regression re-reading the live tools/skills on the snapshot path
            would flip these assertions."
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          snapshot {:model {:provider "anthropic" :id "claude-snapshot"}
                    :prompt-mode :concise
                    :tool-defs [{:name "shared-tool" :description "from-snapshot"}]
                    :skills [{:name "shared-skill"
                              :description "from-snapshot"
                              :file-path "" :base-dir "" :source :project
                              :disable-model-invocation false}]
                    :thinking-level :off
                    :speed-mode nil
                    :effort-override nil}
          workflow-run (run-tools-skills-with-snapshot ctx session-id snapshot "run-ts")]
      ;; Mutate the LIVE parent session's tools/skills after invoke: a DIFFERENT
      ;; def for the same names. The snapshot path must not read these.
      (assoc-session-data! ctx session-id
                           {:tool-source {"shared-tool" {:name "shared-tool"
                                                         :description "from-live"}}
                            :tool-ids ["shared-tool"]
                            :skills [{:name "shared-skill"
                                      :description "from-live"
                                      :file-path "" :base-dir "" :source :project
                                      :disable-model-invocation false}]})
      (let [config (workflow-step-session-config/resolve-step-session-config
                    ctx nil workflow-run "step-1")
            resolved-tool (some #(when (= "shared-tool" (:name %)) %) (:tool-defs config))
            resolved-skill (some #(when (= "shared-skill" (:name %)) %) (:skills config))]
        (is (some? resolved-tool) "the named tool resolved from the inherited pool")
        (is (= "from-snapshot" (:description resolved-tool))
            "resolved tool def comes from the snapshot pool, not the mutated live parent")
        (is (some? resolved-skill) "the named skill resolved from the inherited pool")
        (is (= "from-snapshot" (:description resolved-skill))
            "resolved skill comes from the snapshot pool, not the mutated live parent")))))

(deftest snapshot-model-feeds-model-query-selection-context-test
  (testing "AC7: resolved-model-query selection context comes from the snapshot
            model, not the live parent. Proven via a `:same-model-as-session`
            preference: the snapshot model and the (post-invoke) live model are
            two DISTINCT real registered models, so the ranking winner — which
            the criterion pulls toward whatever `:session-model` fed the
            selection request — distinguishes snapshot from live. If the
            resolver leaked the live model into the selection context the winner
            would flip; the assertion below would fail."
    (let [;; Two distinct real registered anthropic models. Same provider so a
          ;; provider-level match cannot disambiguate — only the exact-model
          ;; context decides the winner.
          snapshot-model {:provider "anthropic" :id "claude-opus-4-5"}
          live-model     {:provider "anthropic" :id "claude-haiku-4-5"}
          [ctx session-id] (support/create-session-context {:persist? false})
          model-query-definition
          {:definition-id "model-query-wf"
           :name "model-query-wf"
           :steps [{:name "step-1"
                    :type :session
                    :model {:type :model-query
                            :require []
                            :prefer [{:criterion :same-model-as-session
                                      :prefer :context-match}]}
                    :contributions [{:type :template
                                     :text "{{input}}"
                                     :vars {"input" {:from :workflow-input :path [:input]}}}]}]
           :workflow-file-meta {:system-prompt "Query."}}
          snapshot {:model snapshot-model
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
      ;; Mutate the live parent AFTER invoke to a different real model.
      (assoc-session-data! ctx session-id {:model live-model})
      (let [config (workflow-step-session-config/resolve-step-session-config
                    ctx nil workflow-run "step-1")
            ranked (get-in config [:model-fallback :candidates])]
        (is (= :ranked-model-candidates (get-in config [:model-fallback :type])))
        ;; The `:same-model-as-session` preference ranks the candidate matching
        ;; the selection context's session-model first. The winner reflects the
        ;; SNAPSHOT model, proving the live mutation did not feed the context.
        (is (= snapshot-model (:model config))
            "top-ranked model reflects the snapshot session-model, not the live parent")
        (is (= snapshot-model (first ranked))
            "ranked candidates are ordered by the snapshot session-model context")
        (is (not= live-model (:model config))
            "the post-invoke live model must not win the selection")))))

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

(deftest nested-delegation-effective-snapshot-propagates-overridden-model-test
  (testing "AC4: a step overriding the model, whose effective config feeds
            effective-config->snapshot, yields a nested snapshot carrying the
            OVERRIDDEN model (captured at sub-delegation creation), with
            speed/effort threaded from the parent run snapshot (P2)"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          override-definition
          {:definition-id "delegating-wf"
           :name "delegating-wf"
           :steps [{:name "step-1"
                    :type :session
                    :model "claude-override-model"
                    :contributions [{:type :template
                                     :text "{{input}}"
                                     :vars {"input" {:from :workflow-input :path [:input]}}}]}]
           :workflow-file-meta {:system-prompt "Delegate."}}
          ;; parent run snapshot: model A, speed/effort set on the parent run.
          parent-run-snapshot {:model {:provider "anthropic" :id "claude-PARENT"}
                               :prompt-mode :concise
                               :tool-defs []
                               :skills []
                               :thinking-level :off
                               :speed-mode :fast
                               :effort-override :xhigh}
          workflow-run
          (do (swap! (:state* ctx)
                     (fn [state]
                       (let [[s _ _] (workflow-registry/register-definition state override-definition)
                             [s' _ _] (workflow-runtime/create-run
                                       s {:definition-id "delegating-wf"
                                          :run-id "run-delegating"
                                          :parent-session-id session-id
                                          :inherited-defaults parent-run-snapshot
                                          :workflow-input {:input "go"}})]
                         s')))
              (workflow-runtime/workflow-run-in @(:state* ctx) "run-delegating"))
          ;; This mirrors the injected resolve-inherited-defaults-fn closure
          ;; bound in context.clj for the nested/delegated path.
          effective-config (workflow-step-session-config/resolve-step-session-config
                            ctx nil workflow-run "step-1")
          nested-snapshot (workflow-step-session-config/effective-config->snapshot
                           effective-config (:inherited-defaults workflow-run))]
      (is (= "claude-override-model"
             (or (get-in nested-snapshot [:model :id]) (:model nested-snapshot)))
          "nested snapshot carries the delegating step's overridden model")
      (is (= :fast (:speed-mode nested-snapshot))
          "speed-mode threaded from the parent run snapshot (P2)")
      (is (= :xhigh (:effort-override nested-snapshot))
          "effort-override threaded from the parent run snapshot (P2)")
      (is (= workflow-step-session-config/inherited-defaults-snapshot-keys
             (set (keys nested-snapshot)))
          "nested snapshot has exactly the snapshot key set"))))

(def ^:private delegate-child-definition
  "Target definition for the delegated child run."
  {:definition-id "child-wf"
   :name "child-wf"
   :steps [{:name "child-step"
            :type :session
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]
   :workflow-file-meta {:system-prompt "Child."}})

(def ^:private delegating-definition
  "Delegating run definition whose step delegates to child-wf. A delegate
   step's compiled effective definition carries no per-step model override, so
   its effective config inherits the parent run's snapshot model — exactly the
   nested-inheritance behaviour the child run must capture."
  {:definition-id "delegating-e2e"
   :name "delegating-e2e"
   :steps [{:name "delegate-step"
            :type :delegate
            :target "child-wf"
            :prompt-string "go"
            :delegate {:target "child-wf"}}]
   :workflow-file-meta {:system-prompt "Delegate."}})

(deftest delegate-step-runtime-result-persists-child-inherited-defaults-test
  (testing "AC4 end-to-end: delegate-step-runtime-result, given the real
            injected resolve-inherited-defaults-fn closure, persists the
            delegating step's effective snapshot (inherited parent-snapshot
            model + parent-snapshot speed/effort) as the CHILD run's
            :inherited-defaults via the delegate.clj wiring"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          parent-run-snapshot {:model {:provider "anthropic" :id "claude-PARENT"}
                               :prompt-mode :concise
                               :tool-defs []
                               :skills []
                               :thinking-level :off
                               :speed-mode :fast
                               :effort-override :xhigh}
          workflow-run
          (do (swap! (:state* ctx)
                     (fn [state]
                       (let [[s _ _] (workflow-registry/register-definition state delegate-child-definition)
                             [s _ _] (workflow-registry/register-definition s delegating-definition)
                             [s' _ _] (workflow-runtime/create-run
                                       s {:definition-id "delegating-e2e"
                                          :run-id "run-delegating-e2e"
                                          :parent-session-id session-id
                                          :inherited-defaults parent-run-snapshot
                                          :workflow-input {:input "go"}})]
                         s')))
              (workflow-runtime/workflow-run-in @(:state* ctx) "run-delegating-e2e"))
          ;; The real injected closure bound in context.clj for the nested path.
          resolve-inherited-defaults-fn
          (fn [ctx* parent-session-id* workflow-run* step-id*]
            (workflow-step-session-config/effective-config->snapshot
             (workflow-step-session-config/resolve-step-session-config
              ctx* parent-session-id* workflow-run* step-id*)
             (:inherited-defaults workflow-run*)))
          ;; Stub the two other injected fns: a no-op send-and-drain leaves the
          ;; child run at its created status; create-workflow-context returns a
          ;; minimal ctx sharing the same state atom.
          create-workflow-context-fn (fn [ctx* _psid _run-id] (assoc ctx* :wm nil))
          send-and-drain-fn (fn [_wf-ctx _wm _event _payload] nil)
          step-def (get-in workflow-run [:effective-definition :steps "delegate-step"])
          result (delegate/delegate-step-runtime-result
                  create-workflow-context-fn
                  send-and-drain-fn
                  resolve-inherited-defaults-fn
                  ctx session-id "delegate-step" step-def workflow-run)
          child-run-id (get-in result [:payload :delegate-run-id])
          child-run (workflow-runtime/workflow-run-in @(:state* ctx) child-run-id)
          child-snapshot (:inherited-defaults child-run)]
      (is (some? child-run-id) "delegate created a child run")
      (is (some? child-snapshot)
          "the child run persists an :inherited-defaults snapshot")
      (is (= {:provider "anthropic" :id "claude-PARENT"} (:model child-snapshot))
          "child snapshot carries the delegating step's effective model
           (inherited from the parent run snapshot)")
      (is (= :fast (:speed-mode child-snapshot))
          "speed-mode threaded from the parent run snapshot (P2)")
      (is (= :xhigh (:effort-override child-snapshot))
          "effort-override threaded from the parent run snapshot (P2)")
      (is (= workflow-step-session-config/inherited-defaults-snapshot-keys
             (set (keys child-snapshot)))
          "child snapshot has exactly the snapshot key set"))))

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
