(ns psi.agent-session.child-session-state-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.child-session-state :as child-session-state]
   [psi.prompt-assets.system-prompt :as system-prompt]
   [psi.session-state.init :as init]
   [psi.session-state.model :as session-model]
   [psi.session-state.state :as state]
   [psi.skill-registry.root-storage :as skill-storage]))

(def ^:private parent-tool-defs
  [{:name "read" :description "Read"}
   {:name "bash" :description "Bash"}
   {:name "psi-tool" :description "Psi tool"}])

(defn- parent-session-data
  ([]
   (parent-session-data {}))
  ([overrides]
   (merge (session-model/initial-session
           {:session-id "parent"
            :worktree-path "/tmp/ws"
            :prompt-mode :lambda
            :tool-ids ["read" "bash" "psi-tool"]
            :skill-ids ["skill-a"]
            :prompt-contribution-ids ["ext-a"]
            :cache-breakpoints #{:system :tools}
            :model {:provider "prov" :id "m"}
            :developer-prompt "parent-dev"
            :developer-prompt-source :memory
            :system-prompt-build-opts {:context-files [{:path "/AGENTS.md" :content "Context text"}]
                                       :tool-defs parent-tool-defs
                                       :selected-tools ["read" "bash" "psi-tool"]}
            :base-system-prompt "parent-base"
            :system-prompt "parent-system"})
          overrides)))

(defn- root-state-with-parent-skills
  "Build a root-state with parent session data, an agent-ctx holding tool-source,
   and registered skills."
  [parent-sd skills]
  (reduce (fn [root-state skill]
            (:root-state (skill-storage/register-skill-in-root-state root-state (:session-id parent-sd) skill)))
          {:agent-session {:sessions {(:session-id parent-sd)
                                      {:data parent-sd
                                       :agent-ctx {:data-atom (atom {:tools parent-tool-defs})}}}}
           :root-registries {:prompt-contributions {:entries-by-id {"ext-a" {:id "ext-a"
                                                                             :extension-id "/ext/a"
                                                                             :value {:id "ext-a"
                                                                                     :ext-path "/ext/a"
                                                                                     :section "Ext"
                                                                                     :content "A"
                                                                                     :enabled true}}}
                                                    :ids-by-extension {"/ext/a" #{"ext-a"}}}}}
          skills))

(deftest child-session-base-state-normalizes-and-inherits-test
  (let [parent-sd (parent-session-data)
        root-state (root-state-with-parent-skills
                    parent-sd
                    [{:name "skill-a" :description "A"
                      :file-path "/s/SKILL.md" :base-dir "/s"
                      :source :user :disable-model-invocation false}])
        child-sd (child-session-state/child-session-base-state
                  root-state
                  parent-sd
                  {:child-session-id "child-1"
                   :session-name "child"
                   :workflow-run-id "run-1"
                   :workflow-step-id "plan"
                   :workflow-attempt-id "attempt-1"
                   :workflow-owned? true
                   :response-mode :non-streaming
                   :logprobs true
                   :top-logprobs 7
                   :developer-prompt-source :fallback})]
    (testing "identity and workflow linkage are set"
      (is (= "child-1" (:session-id child-sd)))
      (is (= "parent" (:parent-session-id child-sd)))
      (is (= "child" (:session-name child-sd)))
      (is (= "run-1" (:workflow-run-id child-sd)))
      (is (= "plan" (:workflow-step-id child-sd)))
      (is (= "attempt-1" (:workflow-attempt-id child-sd)))
      (is (true? (:workflow-owned? child-sd)))
      (is (= :non-streaming (:response-mode child-sd)))
      (is (true? (:logprobs-enabled child-sd)))
      (is (= 7 (:top-logprobs child-sd))))

    (testing "fallback developer prompt source is normalized away"
      (is (= "parent-dev" (:developer-prompt child-sd)))
      (is (nil? (:developer-prompt-source child-sd))))

    (testing "lower-level state is inherited"
      (is (= #{:system :tools} (:cache-breakpoints child-sd)))
      (is (not (contains? child-sd :prompt-contributions))
          ":prompt-contributions no longer persisted in session state")
      (is (= {:provider "prov" :id "m"} (:model child-sd))))

    (testing ":tool-defs and :active-tools are not in session state"
      (is (not (contains? child-sd :tool-defs)))
      (is (not (contains? child-sd :active-tools))))))

(deftest child-session-base-state-applies-speed-effort-override-test
  (testing "task 207: explicit speed-mode/effort-override (from the inherited
            snapshot) win over the parent session; absence falls back to parent"
    (let [root-state {:agent-session {:sessions {"parent"
                                                 {:data (parent-session-data {:speed-mode :flex
                                                                              :effort-override :low})
                                                  :agent-ctx {:data-atom (atom {:tools parent-tool-defs})}}}}}
          parent-sd (parent-session-data {:speed-mode :flex :effort-override :low})]
      (testing "override supplied → override wins"
        (let [child-sd (child-session-state/child-session-base-state
                        root-state parent-sd
                        {:child-session-id "child-speed"
                         :speed-mode :fast
                         :effort-override :xhigh})]
          (is (= :fast (:speed-mode child-sd)))
          (is (= :xhigh (:effort-override child-sd)))))

      (testing "no override → falls back to the parent session's values"
        (let [child-sd (child-session-state/child-session-base-state
                        root-state parent-sd
                        {:child-session-id "child-speed-fallback"})]
          (is (= :flex (:speed-mode child-sd)))
          (is (= :low (:effort-override child-sd)))))

      (testing "neither override nor parent value → nil (initial-session default)"
        (let [bare-parent (parent-session-data)
              bare-root {:agent-session {:sessions {"parent"
                                                    {:data bare-parent
                                                     :agent-ctx {:data-atom (atom {:tools parent-tool-defs})}}}}}
              child-sd (child-session-state/child-session-base-state
                        bare-root bare-parent
                        {:child-session-id "child-speed-none"})]
          (is (nil? (:speed-mode child-sd)))
          (is (nil? (:effort-override child-sd))))))))

(deftest child-session-base-state-fallback-precedence-test
  (testing "explicit system prompt wins and becomes base prompt"
    (let [parent-sd (parent-session-data {:base-system-prompt "parent-base"
                                          :system-prompt "parent-system"})
          root-state {:agent-session {:sessions {(:session-id parent-sd)
                                                 {:data parent-sd
                                                  :agent-ctx {:data-atom (atom {:tools parent-tool-defs})}}}}}
          child-sd (child-session-state/child-session-base-state
                    root-state
                    parent-sd
                    {:child-session-id "child-2"
                     :system-prompt "explicit prompt"})]
      (is (= "explicit prompt" (:base-system-prompt child-sd)))
      (is (= "explicit prompt" (:system-prompt child-sd)))))

  (testing "parent base prompt is used when prompt rebuild returns nil"
    (with-redefs [system-prompt/build-system-prompt (constantly nil)]
      (let [parent-sd (parent-session-data {:base-system-prompt "parent-base"
                                            :system-prompt "parent-system"})
            root-state {:agent-session {:sessions {(:session-id parent-sd)
                                                   {:data parent-sd
                                                    :agent-ctx {:data-atom (atom {:tools parent-tool-defs})}}}}}
            child-sd (child-session-state/child-session-base-state
                      root-state
                      parent-sd
                      {:child-session-id "child-3"
                       :tool-ids []
                       :skills []})]
        (is (= "parent-base" (:base-system-prompt child-sd)))
        (is (= "parent-base" (:system-prompt child-sd)))))))

(deftest child-session-base-state-selection-filters-tools-and-skills-test
  (let [selection {:agents-md? false
                   :extension-prompt-contributions []
                   :tool-names ["read"]
                   :skill-names ["skill-a"]
                   :components #{:skills}}
        parent-sd (parent-session-data)
        root-state (root-state-with-parent-skills
                    parent-sd
                    [{:name "skill-a" :description "A"
                      :file-path "/s/SKILL.md" :base-dir "/s"
                      :source :user :disable-model-invocation false}])
        child-sd  (child-session-state/child-session-base-state
                   root-state
                   parent-sd
                   {:child-session-id "child-4"
                    :prompt-component-selection selection})]
    (testing "selection is normalized and stored"
      (is (= (assoc selection
                    :include-preamble? false
                    :include-context-files? false
                    :include-skills? true
                    :include-runtime-metadata? false)
             (:prompt-component-selection child-sd))))

    (testing "child tool-ids are filtered by selection"
      (is (= ["read"] (:tool-ids child-sd))))

    (testing "skills are filtered coherently"
      (is (= ["skill-a"] (:skill-ids child-sd)))
      (is (nil? (:skills child-sd))))))

(deftest child-session-base-state-selection-canonicalizes-selected-skills-test
  ;; Workflow/prompt-component skill selections are allowlists, not ordering directives.
  (let [selection {:agents-md? false
                   :extension-prompt-contributions []
                   :skill-names ["z-skill" "a-skill"]
                   :components #{:skills}}
        parent-sd (parent-session-data {:skill-ids ["z-skill" "a-skill" "m-skill"]})
        root-state (root-state-with-parent-skills
                    parent-sd
                    [{:name "z-skill" :description "Z"
                      :file-path "/z/SKILL.md" :base-dir "/z"
                      :source :user :disable-model-invocation false}
                     {:name "a-skill" :description "A"
                      :file-path "/a/SKILL.md" :base-dir "/a"
                      :source :user :disable-model-invocation false}
                     {:name "m-skill" :description "M"
                      :file-path "/m/SKILL.md" :base-dir "/m"
                      :source :user :disable-model-invocation false}])
        child-sd  (child-session-state/child-session-base-state
                   root-state
                   parent-sd
                   {:child-session-id "child-canonical-skills"
                    :prompt-component-selection selection})]
    (is (= ["a-skill" "z-skill"] (:skill-ids child-sd)))
    (is (< (.indexOf (:base-system-prompt child-sd) "a-skill")
           (.indexOf (:base-system-prompt child-sd) "z-skill")))))

(deftest child-session-base-state-temperature-test
  (let [parent-sd (parent-session-data)
        root-state {:agent-session {:sessions {(:session-id parent-sd)
                                               {:data parent-sd
                                                :agent-ctx {:data-atom (atom {:tools parent-tool-defs})}}}}}]
    (testing "non-nil temperature is stored in child session state"
      (let [child-sd (child-session-state/child-session-base-state
                      root-state parent-sd
                      {:child-session-id "child-temp-1"
                       :temperature 0.7})]
        (is (= 0.7 (:temperature child-sd)))))

    (testing "explicit 0.0 temperature is stored (falsy double must flow through)"
      (let [child-sd (child-session-state/child-session-base-state
                      root-state parent-sd
                      {:child-session-id "child-temp-2"
                       :temperature 0.0})]
        (is (= 0.0 (:temperature child-sd)))
        (is (contains? child-sd :temperature))))

    (testing "nil temperature is absent from child session state"
      (let [child-sd (child-session-state/child-session-base-state
                      root-state parent-sd
                      {:child-session-id "child-temp-3"
                       :temperature nil})]
        (is (not (contains? child-sd :temperature)))))

    (testing "absent temperature key leaves temperature absent from child session state"
      (let [child-sd (child-session-state/child-session-base-state
                      root-state parent-sd
                      {:child-session-id "child-temp-4"})]
        (is (not (contains? child-sd :temperature)))))))

(deftest initialize-child-session-state-initializes-persistence-slots-test
  (let [messages [{:role "user" :content [{:type :text :text "hello"}]}]
        parent-sd (parent-session-data)
        state0 {:agent-session {:sessions {(:session-id parent-sd)
                                           {:data parent-sd
                                            :agent-ctx {:data-atom (atom {:tools parent-tool-defs})}}}}}
        state1   (child-session-state/initialize-child-session-state
                  state0
                  parent-sd
                  {:child-session-id "child-5"
                   :session-name "child"
                   :preloaded-messages messages})]
    (testing "child session data is stored"
      (is (= "child-5" (get-in state1 (conj (state/session-data-path "child-5") :session-id)))))

    (testing "persistence slots are initialized to canonical child-session defaults"
      (is (= []
             (get-in state1 [:agent-session :sessions "child-5" :persistence :journal])))
      (is (= {:flushed? false :session-file nil}
             (get-in state1 [:agent-session :sessions "child-5" :persistence :flush-state]))))

    (testing "telemetry and turn slots are initialized"
      (is (= {:ctx nil}
             (get-in state1 [:agent-session :sessions "child-5" :turn])))
      (is (= init/initial-telemetry
             (get-in state1 [:agent-session :sessions "child-5" :telemetry]))))))

(deftest child-session-tool-ids-coherence-test
  ;; Verifies :tool-ids is correctly derived from parent tool-source
  (let [parent-sd  (parent-session-data)
        root-state (root-state-with-parent-skills
                    parent-sd
                    [{:name "skill-a" :description "A"
                      :file-path "/s/SKILL.md" :base-dir "/s"
                      :source :user :disable-model-invocation false}])]

    (testing "default inheritance: child tool-ids derived from parent tool-ids"
      (let [child-sd (child-session-state/child-session-base-state
                      root-state parent-sd
                      {:child-session-id "child-tool-ids-1"})]
        (is (= ["read" "bash" "psi-tool"] (:tool-ids child-sd)))))

    (testing "explicit tool-ids override: child tool-ids derived from override"
      (let [child-sd (child-session-state/child-session-base-state
                      root-state parent-sd
                      {:child-session-id "child-tool-ids-2"
                       :tool-ids ["bash" "read"]})]
        (is (= ["bash" "read"] (:tool-ids child-sd)))))

    (testing "prompt-component-selection filtering: child tool-ids matches filtered set"
      (let [selection {:agents-md? false
                       :extension-prompt-contributions []
                       :tool-names ["bash"]
                       :skill-names ["skill-a"]
                       :components #{:skills}}
            child-sd (child-session-state/child-session-base-state
                      root-state parent-sd
                      {:child-session-id "child-tool-ids-3"
                       :prompt-component-selection selection})]
        (is (= ["bash"] (:tool-ids child-sd)))))))
