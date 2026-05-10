(ns psi.agent-session.workflow-built-in-targeting-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow.bootstrap :as workflow-bootstrap]
   [psi.agent-session.workflow.core :as workflow]
   [psi.agent-session.workflow.runtime-state :as workflow-runtime-state]))

(defn- clean-workflow-runtime-state [f]
  (reset! workflow-runtime-state/state nil)
  (reset! workflow-runtime-state/inflight-runs {})
  (try
    (f)
    (finally
      (reset! workflow-runtime-state/state nil)
      (reset! workflow-runtime-state/inflight-runs {}))))

(use-fixtures :each clean-workflow-runtime-state)

(defn- create-two-session-context []
  (let [[ctx s0] (test-support/create-test-session {:persist? false
                                                    :cwd "/Users/duncan/projects/hugoduncan/psi/workflow-deterministic-steps"
                                                    :mutations mutations/all-mutations})
        s1       (session/new-session-in! ctx s0 {:session-name "one"})
        s2       (session/new-session-in! ctx (:session-id s1) {:session-name "two"})]
    [ctx (:session-id s1) (:session-id s2)]))

(defn- init-built-in-workflow! [ctx session-id]
  (workflow-bootstrap/init-built-in! ctx session-id)
  (swap! workflow/state assoc
         :loaded-definitions
         {"planner" {:definition-id "planner"
                     :summary "plan"
                     :step-order ["step-1"]
                     :steps {"step-1" {:label "plan" :tools ["read"]}}}}))

(defn- delegate-tool-for [ctx]
  (some #(when (= "delegate" (:name %)) %)
        (-> ctx :extension-registry :state deref :extensions (get workflow/built-in-workflow-path) :tools vals)))

(defn- stub-workflow-mutate [run-id]
  (fn [sym _params]
    (case sym
      psi.workflow/create-run {:psi.workflow/run-id run-id}
      psi.workflow/list-runs  {:psi.workflow/runs []}
      {})))

(deftest built-in-workflow-delegate-tool-targets-explicit-runtime-session-test
  (testing "built-in workflow delegate tool follows the executing runtime session rather than the originally bootstrapped session"
    (let [[ctx s1 s2] (create-two-session-context)
          created*    (atom [])]
      (init-built-in-workflow! ctx s1)
      (with-redefs [psi.agent-session.workflow.core/mutate! (stub-workflow-mutate "run-1")
                    psi.agent-session.workflow.core/execute-async!
                    (fn [run-id session-id workflow-name include?]
                      (swap! created* conj {:run-id run-id
                                            :session-id session-id
                                            :workflow-name workflow-name
                                            :include? include?})
                      run-id)]
        (let [delegate-tool (delegate-tool-for ctx)]
          (is (some? delegate-tool))
          ((:execute delegate-tool) {:workflow "planner" :prompt "hello"} {:session-id s2})
          (is (= [{:run-id "run-1"
                   :session-id s2
                   :workflow-name "planner"
                   :include? false}]
                 @created*)))))))

(deftest built-in-workflow-delegate-tool-follows-explicit-new-session-runtime-target-test
  (testing "built-in workflow delegate tool retargets when runtime execution supplies a newer session id after bootstrap"
    (let [[ctx sid1 sid2] (create-two-session-context)
          created*        (atom [])]
      (init-built-in-workflow! ctx sid1)
      (with-redefs [psi.agent-session.workflow.core/mutate! (stub-workflow-mutate "run-2")
                    psi.agent-session.workflow.core/execute-async!
                    (fn [run-id session-id workflow-name include?]
                      (swap! created* conj {:run-id run-id
                                            :session-id session-id
                                            :workflow-name workflow-name
                                            :include? include?})
                      run-id)]
        (let [delegate-tool (delegate-tool-for ctx)]
          (is (some? delegate-tool))
          ((:execute delegate-tool) {:workflow "planner" :prompt "hello"} {:session-id sid2})
          (is (= [{:run-id "run-2"
                   :session-id sid2
                   :workflow-name "planner"
                   :include? false}]
                 @created*)))))))
