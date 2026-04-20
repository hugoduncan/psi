(ns psi.agent-session.workflow-runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(def registered-definition
  {:definition-id "plan-build-review"
   :name "Plan Build Review"
   :step-order ["plan" "build" "review"]
   :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed :validation-failed}}}
           "build" {:executor {:type :agent :profile "builder" :mode :async}
                    :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                    :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}
           "review" {:executor {:type :agent :profile "reviewer" :mode :sync}
                     :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                     :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}})

(def inline-definition
  {:name "Inline"
   :step-order ["only-step"]
   :steps {"only-step" {:executor {:type :agent :profile "builder" :mode :sync}
                         :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                         :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}})

(deftest register-definition-test
  (testing "register-definition stores validated definitions under canonical workflow root state"
    (let [[state definition-id stored]
          (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                registered-definition)]
      (is (= "plan-build-review" definition-id))
      (is (= stored (workflow-runtime/workflow-definition-in state definition-id)))
      (is (= [stored] (vals (get-in state [:workflows :definitions])))))))

(deftest create-run-from-registered-definition-test
  (testing "create-run captures immutable effective definition snapshot and initializes per-step runs"
    (let [[state1 definition-id _]
          (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                registered-definition)
          [state2 run-id run]
          (workflow-runtime/create-run state1 {:definition-id definition-id
                                               :run-id "run-1"
                                               :workflow-input {:task "ship it"}})]
      (is (= "run-1" run-id))
      (is (= :pending (:status run)))
      (is (= "plan" (:current-step-id run)))
      (is (= definition-id (:source-definition-id run)))
      (is (= registered-definition (:effective-definition run)))
      (is (= #{"plan" "build" "review"}
             (set (keys (:step-runs run)))))
      (is (= run (workflow-runtime/workflow-run-in state2 run-id)))
      (is (= [run-id] (get-in state2 [:workflows :run-order]))))))

(deftest create-run-from-inline-definition-test
  (testing "create-run accepts inline definitions and persists nil source-definition-id"
    (let [state {:workflows (workflow-model/initial-workflow-state)}
          [_ run-id run]
          (workflow-runtime/create-run state {:definition inline-definition
                                              :run-id "inline-run"
                                              :workflow-input {:task "inline"}})]
      (is (= "inline-run" run-id))
      (is (nil? (:source-definition-id run)))
      (is (= "only-step" (:current-step-id run)))
      (is (= "only-step" (-> run :effective-definition :step-order first))))))
