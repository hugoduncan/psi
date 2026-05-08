(ns psi.agent-session.workflow-session-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.context :as context]
   [psi.session-state.state :as session-state]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]))

(def definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :steps [{:name "plan"
            :type :session
            :contributions [{:type :template
                             :text "Plan {{task}}"
                             :vars {"task" {:from :workflow-input :path [:task]}}}]}
           {:name "build"
            :type :session
            :contributions [{:type :template
                             :text "Build {{plan}}"
                             :vars {"plan" {:from {:step "plan" :yield :text}}}}]}]})

(deftest workflow-root-state-is-present-in-context-test
  (testing "new contexts initialize canonical workflow root state"
    (let [ctx (context/create-context {:persist? false})]
      (try
        (is (= {:definitions {} :runs {} :run-order []}
               (session-state/get-state-value-in ctx (session-state/state-path :workflow-state))))
        (is (= [:workflows] (session-state/state-path :workflow-state)))
        (is (= [:workflows :definitions] (session-state/state-path :workflow-definitions)))
        (is (= [:workflows :runs] (session-state/state-path :workflow-runs)))
        (is (= [:workflows :run-order] (session-state/state-path :workflow-run-order)))
        (finally
          (context/shutdown-context! ctx))))))

(deftest workflow-run-stores-under-context-root-state-test
  (testing "workflow runtime stores definitions and runs under canonical context workflow state"
    (let [ctx (context/create-context {:persist? false})]
      (try
        (let [state0 @(:state* ctx)
              [state1 definition-id _] (workflow-registry/register-definition state0 definition)
              [state2 run-id run]      (workflow-runtime/create-run state1 {:definition-id definition-id
                                                                            :run-id "run-1"
                                                                            :workflow-input {:task "ship it"}})]
          (reset! (:state* ctx) state2)
          (is (= "plan-build-review" definition-id))
          (is (= definition (workflow-registry/workflow-definition @(:state* ctx) definition-id)))
          (is (= run (get-in @(:state* ctx) [:workflows :runs run-id])))
          (is (workflow-model/valid-workflow-run? run))
          (is (= ["plan" "build"] (get-in run [:effective-definition :step-order])))
          (is (= :workflow-ir/v1 (get-in run [:effective-definition :canonical-ir :version]))))
        (finally
          (context/shutdown-context! ctx))))))
