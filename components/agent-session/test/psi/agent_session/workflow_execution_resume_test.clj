(ns psi.agent-session.workflow-execution-resume-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.session-persistence.core]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.workflow-runtime.core :as workflow-runtime]
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
            :contributions [{:type :template
                             :text "{{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}
                                    "original" {:from :workflow-input :path [:original]}}}]}]
   :workflow-file-meta {:system-prompt "You are a planner."
                        :tools ["read" "bash"]
                        :skills ["clojure-coding-standards"]
                        :thinking-level :medium}})

(deftest resume-and-execute-run-test
  ;; A real fresh statechart must observe the canonical resume update before it
  ;; starts, then execute a new attempt rather than reusing blocked attempt state.
  (testing "resume-and-execute-run! executes the resumed run through the real statechart"
    (let [[base-ctx session-id] (create-session-context {:persist? false})
          ctx (assoc base-ctx
                     :workflow-execute-actor-turn-fn
                     (fn [_ctx child-session-id _prompt & _]
                       {:status :ok
                        :session-id child-session-id
                        :assistant-message {:role "assistant"
                                            :content [{:type :text :text "completed after resume"}]}
                        :assistant-text "completed after resume"
                        :execution-result {}}))
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[s _ _] (workflow-registry/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-resume"
                                                                   :workflow-input {:input "plan it"}})]
                       (-> s
                           (assoc-in [:workflows :runs "run-resume" :status] :blocked)
                           (assoc-in [:workflows :runs "run-resume" :blocked]
                                     {:step-id "step-1"})
                           (assoc-in [:workflows :runs "run-resume" :step-runs "step-1" :attempts]
                                     [{:attempt-id "a1"
                                       :status :blocked
                                       :execution-session-id "child-1"}])))))
          result (workflow-execution/resume-and-execute-run! ctx session-id "run-resume")
          attempts (get-in @(:state* ctx)
                           [:workflows :runs "run-resume" :step-runs "step-1" :attempts])]
      (is (= :completed (:status result)))
      (is (true? (:terminal? result)))
      (is (false? (:blocked? result)))
      (is (= [:blocked :succeeded] (mapv :status attempts)))
      (is (not= (:attempt-id (first attempts))
                (:attempt-id (second attempts)))))))
