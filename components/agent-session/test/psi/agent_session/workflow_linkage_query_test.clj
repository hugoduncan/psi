(ns psi.agent-session.workflow-linkage-query-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  []
  (let [ctx (session/create-context (test-support/safe-context-opts {}))
        sd  (session/new-session-in! ctx nil {})]
    [ctx (:session-id sd)]))

(def ^:private workflow-linkage-query
  [:psi.agent-session/parent-session-id
   :psi.agent-session/workflow-run-id
   :psi.agent-session/workflow-step-id
   :psi.agent-session/workflow-attempt-id
   :psi.agent-session/workflow-owned?])

(deftest agent-session-workflow-linkage-query-test
  (testing "ordinary root sessions expose explicit non-workflow linkage values"
    (let [[ctx session-id] (create-session-context)
          result (session/query-in ctx session-id workflow-linkage-query)]
      (is (= {:psi.agent-session/parent-session-id nil
              :psi.agent-session/workflow-run-id nil
              :psi.agent-session/workflow-step-id nil
              :psi.agent-session/workflow-attempt-id nil
              :psi.agent-session/workflow-owned? false}
             result))))

  (testing "child workflow-owned sessions expose parent and workflow linkage values"
    (let [[ctx parent-id] (create-session-context)
          child-id "workflow-child-session"]
      (swap! (:state* ctx) assoc-in [:agent-session :sessions child-id :data]
             {:session-id child-id
              :parent-session-id parent-id
              :workflow-run-id "run-1"
              :workflow-step-id "step-1"
              :workflow-attempt-id "attempt-1"
              :workflow-owned? true})
      (let [result (session/query-in ctx child-id workflow-linkage-query)]
        (is (= {:psi.agent-session/parent-session-id parent-id
                :psi.agent-session/workflow-run-id "run-1"
                :psi.agent-session/workflow-step-id "step-1"
                :psi.agent-session/workflow-attempt-id "attempt-1"
                :psi.agent-session/workflow-owned? true}
               result))))))
