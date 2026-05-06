(ns psi.agent-session.workflow-execution-resume-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.persistence]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-statechart-runtime]))

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
                     (let [[s _ _] (workflow-runtime/register-definition state single-step-definition-with-meta)
                           [s _ _] (workflow-runtime/create-run s {:definition-id "planner"
                                                                   :run-id "run-resume"
                                                                   :workflow-input {:input "plan it"}})]
                       (-> s
                           (assoc-in [:workflows :runs "run-resume" :status] :completed)
                           (assoc-in [:workflows :runs "run-resume" :current-step-id] nil)
                           (assoc-in [:workflows :runs "run-resume" :step-runs "step-1" :attempts]
                                     [{:attempt-id "a1"
                                       :status :succeeded
                                       :execution-session-id "child-1"}])))))
          seen* (atom [])]
      (with-redefs [psi.agent-session.workflow-statechart-runtime/create-workflow-context
                    (fn [_ctx _parent-session-id run-id]
                      (swap! seen* conj [:create run-id])
                      {:wm :stub-wm})
                    psi.agent-session.workflow-statechart-runtime/send-and-drain!
                    (fn [_wf-ctx _wm event _data]
                      (swap! seen* conj [:event event])
                      :stubbed)]
        (let [result (workflow-execution/resume-and-execute-run! ctx session-id "run-resume")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (false? (:blocked? result)))
          (is (= [[:create "run-resume"]
                  [:event :workflow/resume]]
                 @seen*)))))))
