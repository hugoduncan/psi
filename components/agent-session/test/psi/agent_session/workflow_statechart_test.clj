(ns psi.agent-session.workflow-statechart-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [psi.agent-session.workflow-statechart :as workflow-sc]))

(def sample-definition
  {:definition-id "plan-build-review"
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

(defn- run-chart-phase-after
  [events]
  (let [env        (simple/simple-env)
        session-id (java.util.UUID/randomUUID)]
    (simple/register! env :workflow-run workflow-sc/workflow-run-chart)
    (let [wm0 (sp/start! (::sc/processor env) env :workflow-run {::sc/session-id session-id})
          wmN (reduce (fn [wm event]
                        (sp/process-event! (::sc/processor env)
                                           env
                                           wm
                                           {:name event :data {}}))
                      wm0
                      events)]
      (first (::sc/configuration wmN)))))

(deftest workflow-definition-compilation-test
  (testing "canonical initial-step-id follows workflow definition order"
    (is (= "plan" (workflow-sc/initial-step-id sample-definition))))

  (testing "next-step-id follows workflow definition order"
    (is (= "build" (workflow-sc/next-step-id sample-definition "plan")))
    (is (= "review" (workflow-sc/next-step-id sample-definition "build")))
    (is (nil? (workflow-sc/next-step-id sample-definition "review")))))

(deftest workflow-run-statechart-test
  (testing "happy path phases"
    (is (= :pending (run-chart-phase-after [])))
    (is (= :running (run-chart-phase-after [:workflow/start])))
    (is (= :validating (run-chart-phase-after [:workflow/start :workflow/result-received])))
    (is (= :completed (run-chart-phase-after [:workflow/start :workflow/result-received :workflow/complete]))))

  (testing "retry and blocked paths return to legal phases"
    (is (= :running (run-chart-phase-after [:workflow/start :workflow/result-received :workflow/retry])))
    (is (= :blocked (run-chart-phase-after [:workflow/start :workflow/result-received :workflow/block])))
    (is (= :running (run-chart-phase-after [:workflow/start :workflow/result-received :workflow/block :workflow/resume]))))

  (testing "cancel and fail reach terminal phases"
    (is (= :cancelled (run-chart-phase-after [:workflow/cancel])))
    (is (= :failed (run-chart-phase-after [:workflow/start :workflow/fail])))))

(deftest workflow-run-event-surface-test
  (testing "supported run events are indexed and terminal statuses are explicit"
    (is (workflow-sc/supported-run-event? :workflow/start))
    (is (workflow-sc/supported-run-event? :workflow/complete))
    (is (not (workflow-sc/supported-run-event? :workflow/unknown)))
    (is (workflow-sc/terminal-run-status? :completed))
    (is (workflow-sc/terminal-run-status? :failed))
    (is (workflow-sc/terminal-run-status? :cancelled))
    (is (not (workflow-sc/terminal-run-status? :running)))))
