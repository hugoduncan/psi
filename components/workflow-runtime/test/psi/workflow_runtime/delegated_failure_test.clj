(ns psi.workflow-runtime.delegated-failure-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-runtime.delegated-failure :as delegated-failure]
   [psi.workflow-runtime.statechart-runtime.delegate :as delegate]))

(defn- workflow-run
  [{:keys [step-order step-runs terminal-outcome current-step-id]}]
  {:effective-definition {:step-order step-order}
   :step-runs step-runs
   :terminal-outcome terminal-outcome
   :current-step-id current-step-id})

(deftest sanitize-component-test
  ;; The failure boundary removes unsafe detail before assembling public text.
  (testing "normalizes controls, whitespace, and sensitive lexical spans"
    (is (= "([STACKTRACE_REDACTED]), retry"
           (delegated-failure/sanitize-component
            "(at child.core/run(child.clj:42)),\nretry")))
    (is (= "[REDACTED], request rejected"
           (delegated-failure/sanitize-component
            "token=\"abc 123\", request rejected")))
    (is (= "Authorization: [REDACTED_TOKEN]."
           (delegated-failure/sanitize-component
            "Authorization: Bearer abcdefgh.")))
    (is (= "open [PATH_REDACTED]"
           (delegated-failure/sanitize-component "open C:\\private\\file.edn")))
    (is (= "open [PATH_REDACTED]"
           (delegated-failure/sanitize-component "open \\\\server\\share\\file.edn")))
    (is (= "open C:private\\file.edn"
           (delegated-failure/sanitize-component "open C:private\\file.edn")))
    (is (= "read [PATH_REDACTED]."
           (delegated-failure/sanitize-component "read config\\secret-store.edn.")))))

(deftest delegated-failure-selection-test
  ;; A terminal attempt, rather than historical map order, owns the diagnostic.
  (testing "selects an actionable terminal execution error"
    (let [run (workflow-run
               {:step-order ["build"]
                :current-step-id "build"
                :step-runs {"build" {:attempts [{:attempt-id "old"
                                                 :status :execution-failed
                                                 :execution-error {:reason :old-error
                                                                   :message "old failure"}}
                                                {:attempt-id "latest"
                                                 :status :execution-failed
                                                 :execution-error {:reason :tool-timeout
                                                                   :message "tool timed out"}}]}}
                :terminal-outcome {:step-id "build"}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'build': tool timed out"
              :delegate-failure {:source :execution-error
                                 :run-id "run-1"
                                 :target "child"
                                 :reason :tool-timeout
                                 :step-id "build"
                                 :attempt-id "latest"}}
             (delegated-failure/delegated-failure run "run-1" "child")))))

  (testing "falls through an ineligible execution message to terminal outcome"
    (let [run (workflow-run
               {:step-order ["loop"]
                :current-step-id "loop"
                :step-runs {"loop" {:attempts [{:attempt-id "attempt-4"
                                                :status :execution-failed
                                                :execution-error {:message "at child.core/run(child.clj:42)"}}]}}
                :terminal-outcome {:reason :iteration-limit-reached
                                   :step-id "loop"
                                   :iteration-count 4
                                   :max-iterations 4}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'loop': terminal outcome :iteration-limit-reached (iteration 4 of 4)"
              :delegate-failure {:source :terminal-outcome
                                 :run-id "run-2"
                                 :target "child"
                                 :reason :iteration-limit-reached
                                 :step-id "loop"
                                 :attempt-id "attempt-4"}}
             (delegated-failure/delegated-failure run "run-2" "child")))))

  (testing "retains selected location on fallback without unsafe cause data"
    (let [run (workflow-run
               {:step-order ["build"]
                :current-step-id "build"
                :step-runs {"build" {:attempts [{:attempt-id "attempt-2"
                                                 :status :execution-failed
                                                 :execution-error {:message "token=secret"}}]}}
                :terminal-outcome {}})]
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow failed"
              :delegate-failure {:source :fallback
                                 :run-id "run-3"
                                 :target "child"
                                 :step-id "build"
                                 :attempt-id "attempt-2"}}
             (delegated-failure/delegated-failure run "run-3" "child"))))))

(deftest delegate-boundary-failed-child-test
  ;; The runtime persists the lower-runtime envelope as the parent failure payload.
  (testing "failed child delegation returns the canonical envelope without details"
    (let [child (workflow-run
                 {:step-order ["build"]
                  :current-step-id "build"
                  :step-runs {"build" {:attempts [{:attempt-id "attempt-1"
                                                   :status :execution-failed
                                                   :execution-error {:reason :tool-timeout
                                                                     :message "tool timed out"}}]}}
                  :terminal-outcome {:step-id "build"}})
          result (#'delegate/delegate-run-runtime-result (assoc child :status :failed)
                                                         "child-run" "child" {})]
      (is (= :failure (:pending-kind result)))
      (is (= {:reason :delegated-workflow-failed
              :message "Delegated workflow 'child' failed at step 'build': tool timed out"
              :delegate-failure {:source :execution-error
                                 :run-id "child-run"
                                 :target "child"
                                 :reason :tool-timeout
                                 :step-id "build"
                                 :attempt-id "attempt-1"}}
             (:payload result))))))

(deftest nested-delegated-failure-test
  ;; Nested metadata is one-level, allowlisted identity only.
  (testing "copies valid immediate nested identity without recursive metadata"
    (let [nested {:reason :delegated-workflow-failed
                  :message "Delegated workflow 'grandchild' failed: tool timed out"
                  :delegate-failure {:source :execution-error
                                     :run-id "grandchild-run"
                                     :target "grandchild"
                                     :reason :tool-timeout
                                     :step-id "work"
                                     :nested-cause {:run-id "ignored"}}}
          run (workflow-run
               {:step-order ["delegate"]
                :current-step-id "delegate"
                :step-runs {"delegate" {:attempts [{:attempt-id "attempt-1"
                                                    :status :execution-failed
                                                    :execution-error nested}]}}
                :terminal-outcome {:step-id "delegate"}})
          result (delegated-failure/delegated-failure run "child-run" "child")]
      (is (= :delegated-workflow-failed (get-in result [:delegate-failure :reason])))
      (is (= {:run-id "grandchild-run"
              :target "grandchild"
              :reason :tool-timeout
              :step-id "work"}
             (get-in result [:delegate-failure :nested-cause])))
      (is (= "Delegated workflow 'child' failed at step 'delegate': Delegated workflow 'grandchild' failed: tool timed out"
             (:message result))))))
