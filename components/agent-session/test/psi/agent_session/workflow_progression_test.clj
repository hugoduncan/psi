(ns psi.agent-session.workflow-progression-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-attempts :as workflow-attempts]
   [psi.agent-session.workflow-model :as workflow-model]
   [psi.agent-session.workflow-progression :as workflow-progression]
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
                    :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}})

(defn- base-state-with-run
  []
  (let [[state1 _ _] (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)}
                                                           definition)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build-review"
                                                               :run-id "run-1"
                                                               :workflow-input {:task "ship it"}})
        attempt           (workflow-attempts/new-attempt {:attempt-id "a1"
                                                          :status :pending
                                                          :execution-session-id "child-1"})
        state3            (update-in state2 [:workflows :runs run-id]
                                     #(workflow-attempts/append-attempt-to-run % "plan" attempt))]
    [state3 run-id]))

(deftest start-latest-attempt-test
  (testing "latest attempt can be marked running and run status enters running"
    (let [[state run-id] (base-state-with-run)
          state'         (workflow-progression/start-latest-attempt state run-id "plan")
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= :running (get-in run [:step-runs "plan" :attempts 0 :status]))))))

(deftest submit-ok-envelope-advances-to-next-step-test
  (testing "valid ok envelope succeeds step and advances workflow to next step"
    (let [[state run-id] (base-state-with-run)
          state'         (-> state
                             (workflow-progression/start-latest-attempt run-id "plan")
                             (workflow-progression/submit-result-envelope run-id "plan"
                                                                          {:outcome :ok
                                                                           :outputs {:plan "do it"}}))
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= "build" (:current-step-id run)))
      (is (= :succeeded (get-in run [:step-runs "plan" :attempts 0 :status])))
      (is (= {:outcome :ok :outputs {:plan "do it"}}
             (get-in run [:step-runs "plan" :accepted-result]))))))

(deftest submit-ok-envelope-completes-final-step-test
  (testing "valid ok envelope on final step completes workflow"
    (let [[state1 _ _] (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)} definition)
          [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build-review"
                                                                 :run-id "run-2"})
          state3            (assoc-in state2 [:workflows :runs run-id :current-step-id] "build")
          state4            (assoc-in state3 [:workflows :runs run-id :step-runs "build" :attempts]
                                      [(workflow-attempts/new-attempt {:attempt-id "b1"
                                                                       :status :pending
                                                                       :execution-session-id "child-2"})])
          state'            (-> state4
                                (workflow-progression/start-latest-attempt run-id "build")
                                (workflow-progression/submit-result-envelope run-id "build"
                                                                             {:outcome :ok
                                                                              :outputs {:review "approved"}}))
          run               (get-in state' [:workflows :runs run-id])]
      (is (= :completed (:status run)))
      (is (nil? (:current-step-id run)))
      (is (= :succeeded (get-in run [:step-runs "build" :attempts 0 :status]))))))

(deftest submit-blocked-envelope-blocks-run-test
  (testing "blocked envelope moves attempt and run into blocked state"
    (let [[state run-id] (base-state-with-run)
          state'         (-> state
                             (workflow-progression/start-latest-attempt run-id "plan")
                             (workflow-progression/submit-result-envelope run-id "plan"
                                                                          {:outcome :blocked
                                                                           :blocked {:question "need approval"}}))
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :blocked (:status run)))
      (is (= {:question "need approval"} (:blocked run)))
      (is (= :blocked (get-in run [:step-runs "plan" :attempts 0 :status]))))))

(deftest validation-failure-retries-when-available-test
  (testing "step-schema validation failure retries when retry policy allows it"
    (let [[state run-id] (base-state-with-run)
          state'         (-> state
                             (workflow-progression/start-latest-attempt run-id "plan")
                             (workflow-progression/submit-result-envelope run-id "plan"
                                                                          {:outcome :ok
                                                                           :outputs "wrong-shape"}))
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= :validation-failed (get-in run [:step-runs "plan" :attempts 0 :status])))))

  (testing "generic envelope validation failure also retries when retry policy allows it"
    (let [[state run-id] (base-state-with-run)
          state'         (-> state
                             (workflow-progression/start-latest-attempt run-id "plan")
                             (workflow-progression/submit-result-envelope run-id "plan"
                                                                          {:outputs {:plan "missing outcome"}}))
          run            (get-in state' [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (= :validation-failed (get-in run [:step-runs "plan" :attempts 0 :status]))))))

(deftest execution-failure-fails-when-retries-exhausted-test
  (testing "execution failure on a no-retry-remaining step fails the workflow"
    (let [[state1 _ _] (workflow-runtime/register-definition {:workflows (workflow-model/initial-workflow-state)} definition)
          [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build-review"
                                                                 :run-id "run-3"})
          state3            (assoc-in state2 [:workflows :runs run-id :current-step-id] "build")
          state4            (assoc-in state3 [:workflows :runs run-id :step-runs "build" :attempts]
                                      [(workflow-attempts/new-attempt {:attempt-id "b1"
                                                                       :status :running
                                                                       :execution-session-id "child-2"})])
          state'            (workflow-progression/record-execution-failure state4 run-id "build" {:message "provider error"})
          run               (get-in state' [:workflows :runs run-id])]
      (is (= :failed (:status run)))
      (is (= :execution-failed (get-in run [:step-runs "build" :attempts 0 :status]))))))

(deftest resume-blocked-run-test
  (testing "resuming a blocked run clears blocked payload and returns to running"
    (let [[state run-id] (base-state-with-run)
          blocked-state   (-> state
                              (workflow-progression/start-latest-attempt run-id "plan")
                              (workflow-progression/submit-result-envelope run-id "plan"
                                                                           {:outcome :blocked
                                                                            :blocked {:question "need approval"}}))
          resumed-state   (workflow-progression/resume-blocked-run blocked-state run-id)
          run             (get-in resumed-state [:workflows :runs run-id])]
      (is (= :running (:status run)))
      (is (nil? (:blocked run))))))
