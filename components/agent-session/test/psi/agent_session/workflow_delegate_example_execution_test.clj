(ns psi.agent-session.workflow-delegate-example-execution-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.prompt-control]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-attempts]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

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

(def delegate-build-review-definition
  {:definition-id "delegate-build-review"
   :steps [{:name "plan"
            :type :delegate
            :target "planner"
            :prompt-string {:type :template
                            :text "{{input}}"
                            :vars {"input" {:from :workflow-input
                                            :path [:input]}}}
            :context [{:type :source
                       :from :workflow-original}]}
           {:name "build"
            :type :delegate
            :target "builder"
            :prompt-string {:type :template
                            :text "Execute this plan:\n\n{{plan}}\n\nOriginal request: {{original}}"
                            :vars {"plan" {:from {:step "plan" :yield :text}}
                                   "original" {:from :workflow-original
                                               :path [:original]}}}
            :context [{:type :source
                       :from :workflow-original}
                      {:type :source
                       :from {:step "plan" :yield :text}}]}
           {:name "review"
            :type :session
            :tools ["read" "bash"]
            :contributions [{:type :source
                             :from :workflow-original}
                            {:type :template
                             :text "Review the following delegated implementation:\n\n{{implementation}}\n\nOriginal request: {{original}}"
                             :vars {"implementation" {:from {:step "build" :yield :text}}
                                    "original" {:from :workflow-original
                                                :path [:original]}}}]}]})

(def planner-definition
  {:definition-id "planner"
   :steps [{:name "step-1"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{input}}"
                             :vars {"input" {:from :workflow-input}}}]}]})

(def builder-definition
  {:definition-id "builder"
   :steps [{:name "step-1"
            :type :session
            :contributions [{:type :template
                             :text "Build {{input}}"
                             :vars {"input" {:from :workflow-input}}}]}]})

(deftest delegate-build-review-example-executes-through-canonical-runtime-test
  (testing "checked-in delegate-heavy example runs through the canonical execution path and downstream steps consume delegated yielded text"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-runtime/register-definition state planner-definition)
                           [s _ _] (workflow-runtime/register-definition s builder-definition)
                           [s _ _] (workflow-runtime/create-run s {:definition delegate-build-review-definition
                                                                   :run-id "run-delegate-build-review"
                                                                   :workflow-input {:input "add workflow docs"
                                                                                    :original {:original "add workflow docs"
                                                                                               :ticket 42}}})]
                       s)))
          prompts* (atom [])]
      (with-redefs [psi.agent-session.workflow-attempts/create-step-attempt-session!
                    (fn [_ctx _parent-session-id opts]
                      (let [sid (str (:workflow-step-id opts) "-child")]
                        {:attempt {:attempt-id (str sid "-attempt")
                                   :status :pending
                                   :execution-session-id sid}
                         :execution-session (valid-child-session sid)}))
                    psi.agent-session.prompt-control/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:content (cond
                                   (= prompt "Plan add workflow docs")
                                   "plan from planner"

                                   (= prompt "Build Execute this plan:\n\nplan from planner\n\nOriginal request: add workflow docs")
                                   "implementation from builder"

                                   (= prompt "Review the following delegated implementation:\n\nimplementation from builder\n\nOriginal request: add workflow docs")
                                   "review summary"

                                   :else
                                   "unexpected")}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-delegate-build-review")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-delegate-build-review")]
          (is (= :completed (:status result)))
          (is (= "plan from planner"
                 (get-in run [:step-runs "plan" :accepted-result :outputs :final-llm-reply])))
          (is (= "implementation from builder"
                 (get-in run [:step-runs "build" :accepted-result :outputs :final-llm-reply])))
          (is (= "review summary"
                 (get-in run [:step-runs "review" :accepted-result :outputs :final-llm-reply])))
          (is (= [{:session-id "step-1-child"
                   :prompt "Plan add workflow docs"}
                  {:session-id "step-1-child"
                   :prompt "Build Execute this plan:\n\nplan from planner\n\nOriginal request: add workflow docs"}
                  {:session-id "review-child"
                   :prompt "Review the following delegated implementation:\n\nimplementation from builder\n\nOriginal request: add workflow docs"}]
                 @prompts*)))))))
