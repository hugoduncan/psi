(ns psi.workflow-runtime.step-test-support
  (:require
   [psi.test-support.workflow-test-fixtures :as workflow-fixtures]))

(def create-session-context workflow-fixtures/create-session-context)
(def multi-step-definition-with-meta workflow-fixtures/multi-step-definition-with-meta)

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
