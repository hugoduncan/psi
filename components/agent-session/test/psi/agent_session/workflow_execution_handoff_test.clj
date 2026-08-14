(ns psi.agent-session.workflow-execution-handoff-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-execution :as workflow-execution]))

(deftest execution-result-hands-off-selected-terminal-execution-error-test
  ;; The facade selects the final attempt from canonical ordered state, while
  ;; leaving the public attempt projection a string-only diagnostic surface.
  (testing "terminal execution errors retain the exact persisted envelope privately"
    (let [envelope {:reason :delegated-workflow-failed
                    :message "Delegated workflow 'child' failed at step 'build': tool timed out"
                    :delegate-failure {:source :execution-error
                                       :run-id "child-run"
                                       :target "child"
                                       :step-id "build"
                                       :attempt-id "build-2"}}
          workflow-run {:status :failed
                        :current-step-id "build"
                        :terminal-outcome {:step-id "build"}
                        :effective-definition {:step-order ["plan" "build"]}
                        ;; Deliberately scramble map insertion order: selection
                        ;; follows declared step order and ordered attempts.
                        :step-runs {"build" {:attempts [{:attempt-id "build-1"
                                                         :status :execution-failed
                                                         :execution-error {:message "superseded failure"}}
                                                        {:attempt-id "build-2"
                                                         :status :execution-failed
                                                         :execution-error envelope}]}
                                    "plan" {:attempts [{:attempt-id "plan-1"
                                                        :status :execution-failed
                                                        :execution-error {:message "earlier step failure"}}]}}}
          result (#'workflow-execution/execution-result "parent-run" workflow-run)]
      (is (= envelope (:terminal-execution-error result)))
      (is (= [{:step-id "plan"
               :attempt-id "plan-1"
               :execution-session-id nil
               :status :execution-failed
               :error "earlier step failure"}
              {:step-id "build"
               :attempt-id "build-1"
               :execution-session-id nil
               :status :execution-failed
               :error "superseded failure"}
              {:step-id "build"
               :attempt-id "build-2"
               :execution-session-id nil
               :status :execution-failed
               :error "Delegated workflow 'child' failed at step 'build': tool timed out"}]
             (:steps-executed result)))
      (is (every? #(= #{:step-id :attempt-id :execution-session-id :status :error}
                      (set (keys %)))
                  (:steps-executed result))))))

(deftest execution-result-handoff-is-nil-without-terminal-attempt-error-test
  ;; Non-failure execution surfaces carry the private key without inventing an
  ;; error envelope from unrelated state.
  (testing "missing selected attempt errors remain nil"
    (let [workflow-run {:status :completed
                        :effective-definition {:step-order ["plan"]}
                        :step-runs {"plan" {:attempts [{:attempt-id "plan-1"
                                                        :status :succeeded}]}}}
          result (#'workflow-execution/execution-result "completed-run" workflow-run)]
      (is (contains? result :terminal-execution-error))
      (is (nil? (:terminal-execution-error result))))))
