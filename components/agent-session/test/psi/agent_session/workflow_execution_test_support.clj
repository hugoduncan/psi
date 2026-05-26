(ns psi.agent-session.workflow-execution-test-support
  (:require
   [psi.test-support.workflow-test-fixtures :as workflow-fixtures]))

(def create-session-context workflow-fixtures/create-session-context)
(def multi-step-definition-with-meta workflow-fixtures/multi-step-definition-with-meta)

(def single-step-definition-with-meta
  {:definition-id "planner"
   :name "planner"
   :steps [{:name "step-1"
            :type :session
            :tools ["read"]
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]
   :workflow-file-meta {:system-prompt "You are a planner."
                        :tools ["read"]
                        :thinking-level :medium}})

(def workflow-selection-definition
  {:definition-id "planner-selection"
   :name "planner-selection"
   :steps [{:name "step-1"
            :type :session
            :prompt-component-selection {:components #{:skills}
                                         :tool-names ["read"]
                                         :skill-names ["testing-best-practices"]
                                         :extension-prompt-contributions []
                                         :agents-md? false}
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]}]
   :workflow-file-meta {:system-prompt "You are a planner."
                        :tools ["read" "bash"]
                        :skills ["testing-best-practices"]
                        :thinking-level :medium}})

(def judged-definition
  {:definition-id "plan-build-review-judged"
   :name "plan-build-review-judged"
   :steps [{:name "step-1-planner"
            :type :session
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}
                                    "original" {:from :workflow-input :path [:original]}}}]}
           {:name "step-2-builder"
            :type :session
            :contributions [{:type :template
                             :text "Execute: {{input}}\nOriginal: {{original}}"
                             :vars {"input" {:from {:step "step-1-planner" :yield :text}}
                                    "original" {:from :workflow-input :path [:original]}}}]}
           {:name "step-3-reviewer"
            :type :session
            :contributions [{:type :template
                             :text "Review: {{input}}\nOriginal: {{original}}"
                             :vars {"input" {:from {:step "step-2-builder" :output :final-llm-reply}}
                                    "original" {:from :workflow-input :path [:original]}}}]
            :judge {:type :llm
                    :contributions [{:type :template
                                     :text "APPROVED or REVISE?"
                                     :vars {}}]
                    :projection {:type :tail :turns 1}}
            :on {"APPROVED" {:goto :next}
                 "REVISE" {:goto "step-2-builder" :max-iterations 3}}}]})

(defn valid-child-session
  [child-session-id]
  {:session-id child-session-id
   :name child-session-id
   :messages []
   :message-history []
   :is-streaming false
   :tool-results []
   :skills []
   :thinking-level :off
   :cwd "/tmp"
   :worktree-path "/tmp"
   :context []
   :agent {:messages []}
   :statechart {:phase :idle}})
