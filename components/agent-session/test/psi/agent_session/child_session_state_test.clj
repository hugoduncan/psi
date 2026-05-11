(ns psi.agent-session.child-session-state-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.child-session-state :as child-session-state]
   [psi.prompt-assets.system-prompt :as system-prompt]
   [psi.session-state.init :as init]
   [psi.session-state.model :as session-model]
   [psi.session-state.state :as state]))

(defn- parent-session-data
  ([]
   (parent-session-data {}))
  ([overrides]
   (merge (session-model/initial-session
           {:session-id "parent"
            :worktree-path "/tmp/ws"
            :prompt-mode :lambda
            :tool-defs [{:name "read" :description "Read"}
                        {:name "bash" :description "Bash"}
                        {:name "psi-tool" :description "Psi tool"}]
            :skills [{:name "skill-a" :description "A"
                      :file-path "/s/SKILL.md" :base-dir "/s"
                      :source :user :disable-model-invocation false}]
            :prompt-contributions [{:id "ext-a" :ext-path "/ext/a" :section "Ext" :content "A" :enabled true}]
            :cache-breakpoints #{:system :tools}
            :model {:provider "prov" :id "m"}
            :developer-prompt "parent-dev"
            :developer-prompt-source :memory
            :system-prompt-build-opts {:context-files [{:path "/AGENTS.md" :content "Context text"}]
                                       :selected-tools ["read" "bash" "psi-tool"]}
            :base-system-prompt "parent-base"
            :system-prompt "parent-system"})
          overrides)))

(deftest child-session-base-state-normalizes-and-inherits-test
  (let [child-sd (child-session-state/child-session-base-state
                  (parent-session-data)
                  {:child-session-id "child-1"
                   :session-name "child"
                   :workflow-run-id "run-1"
                   :workflow-step-id "plan"
                   :workflow-attempt-id "attempt-1"
                   :workflow-owned? true
                   :response-mode :non-streaming
                   :developer-prompt-source :fallback})]
    (testing "identity and workflow linkage are set"
      (is (= "child-1" (:session-id child-sd)))
      (is (= "parent" (:parent-session-id child-sd)))
      (is (= "child" (:session-name child-sd)))
      (is (= "run-1" (:workflow-run-id child-sd)))
      (is (= "plan" (:workflow-step-id child-sd)))
      (is (= "attempt-1" (:workflow-attempt-id child-sd)))
      (is (true? (:workflow-owned? child-sd)))
      (is (= :non-streaming (:response-mode child-sd))))

    (testing "fallback developer prompt source is normalized away"
      (is (= "parent-dev" (:developer-prompt child-sd)))
      (is (nil? (:developer-prompt-source child-sd))))

    (testing "lower-level state is inherited"
      (is (= #{:system :tools} (:cache-breakpoints child-sd)))
      (is (= [{:id "ext-a" :ext-path "/ext/a" :section "Ext" :content "A" :enabled true}]
             (:prompt-contributions child-sd)))
      (is (= {:provider "prov" :id "m"} (:model child-sd))))))

(deftest child-session-base-state-fallback-precedence-test
  (testing "explicit system prompt wins and becomes base prompt"
    (let [child-sd (child-session-state/child-session-base-state
                    (parent-session-data {:base-system-prompt "parent-base"
                                          :system-prompt "parent-system"})
                    {:child-session-id "child-2"
                     :system-prompt "explicit prompt"})]
      (is (= "explicit prompt" (:base-system-prompt child-sd)))
      (is (= "explicit prompt" (:system-prompt child-sd)))))

  (testing "parent base prompt is used when prompt rebuild returns nil"
    (with-redefs [system-prompt/build-system-prompt (constantly nil)]
      (let [child-sd (child-session-state/child-session-base-state
                      (parent-session-data {:base-system-prompt "parent-base"
                                            :system-prompt "parent-system"})
                      {:child-session-id "child-3"
                       :tool-defs []
                       :skills []})]
        (is (= "parent-base" (:base-system-prompt child-sd)))
        (is (= "parent-base" (:system-prompt child-sd)))))))

(deftest child-session-base-state-selection-filters-tools-and-skills-test
  (let [selection {:agents-md? false
                   :extension-prompt-contributions []
                   :tool-names ["read"]
                   :skill-names []
                   :components #{:skills}}
        child-sd  (child-session-state/child-session-base-state
                   (parent-session-data)
                   {:child-session-id "child-4"
                    :prompt-component-selection selection})]
    (testing "selection is normalized and stored"
      (is (= (assoc selection
                    :include-preamble? false
                    :include-context-files? false
                    :include-skills? true
                    :include-runtime-metadata? false)
             (:prompt-component-selection child-sd))))

    (testing "prompt-visible tools are filtered coherently"
      (is (= ["read"] (mapv :name (:tool-defs child-sd)))))

    (testing "skills are filtered coherently"
      (is (= [] (:skills child-sd))))))

(deftest initialize-child-session-state-initializes-persistence-slots-test
  (let [messages [{:role "user" :content [{:type :text :text "hello"}]}]
        state1   (child-session-state/initialize-child-session-state
                  {}
                  (parent-session-data)
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
