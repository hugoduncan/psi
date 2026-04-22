(ns psi.agent-session.workflow-session-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.context :as context]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as session-state]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(def definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :step-order ["plan" "build"]
   :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                   :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                   :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}}
           "build" {:executor {:type :agent :profile "builder" :mode :async}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}}})

(deftest workflow-root-state-is-present-in-context-test
  (testing "new contexts initialize canonical workflow root state"
    (let [ctx (context/create-context {:persist? false})]
      (try
        (is (= {:definitions {} :runs {} :run-order []}
               (session-state/get-state-value-in ctx (session-state/state-path :workflow-state))))
        (is (= [:workflows] (session/state-path :workflow-state)))
        (is (= [:workflows :definitions] (session/state-path :workflow-definitions)))
        (is (= [:workflows :runs] (session-state/state-path :workflow-runs)))
        (is (= [:workflows :run-order] (session-state/state-path :workflow-run-order)))
        (finally
          (context/shutdown-context! ctx))))))

(deftest workflow-run-stores-under-context-root-state-test
  (testing "workflow runtime stores definitions and runs under canonical context workflow state"
    (let [ctx (context/create-context {:persist? false})]
      (try
        (let [state0 @(:state* ctx)
              [state1 definition-id _] (workflow-runtime/register-definition state0 definition)
              [state2 run-id run]      (workflow-runtime/create-run state1 {:definition-id definition-id
                                                                            :run-id "run-1"
                                                                            :workflow-input {:task "ship it"}})]
          (reset! (:state* ctx) state2)
          (is (= definition (get-in @(:state* ctx) [:workflows :definitions definition-id])))
          (is (= run (get-in @(:state* ctx) [:workflows :runs run-id])))
          (is (workflow-model/valid-workflow-run? run)))
        (finally
          (context/shutdown-context! ctx))))))
