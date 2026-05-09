(ns psi.workflow-runtime.step-prep-test-support
  (:require
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(defn create-session-context
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
