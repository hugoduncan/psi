(ns psi.agent-session.workflow-execution-resume-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.session-persistence.core]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.statechart-runtime]))

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
  (testing "resume-and-execute-run! reports the resumed run state without interaction-heavy choreography"
    (let [[ctx session-id] (create-session-context {:persist? false})
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
          seen* (atom [])]
      (with-redefs [psi.workflow-runtime.statechart-runtime/create-workflow-context
                    (fn [_ctx parent-session-id run-id]
                      (swap! seen* conj [:create run-id parent-session-id])
                      {:wm :stub-wm})
                    psi.workflow-runtime.statechart-runtime/send-and-drain!
                    (fn [_wf-ctx _wm event _data]
                      (swap! seen* conj [:event event])
                      (swap! (:state* ctx)
                             (fn [state]
                               (-> state
                                   (assoc-in [:workflows :runs "run-resume" :status] :completed)
                                   (assoc-in [:workflows :runs "run-resume" :current-step-id] nil))))
                      :stubbed)]
        (let [result (workflow-execution/resume-and-execute-run! ctx session-id "run-resume")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (false? (:blocked? result)))
          (is (= [[:create "run-resume" session-id]
                  [:event :workflow/start]]
                 @seen*)))))))
