(ns psi.agent-session.workflow.routing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow.routing :as routing]))

(defn- assert-route
  [expected-route result]
  (is (= :ok (:status result)) (pr-str result))
  (is (= expected-route (:data result)) (pr-str result))
  (is (= expected-route (:summary result)) (pr-str result))
  result)

(defn- assert-error
  [expected-reason result]
  (is (= :error (:status result)) (pr-str result))
  (is (= expected-reason (:reason result)) (pr-str result))
  result)

(deftest pass-status-routing-parser-test
  ;; Tests pure PASS_STATUS final-reply routing grammar without workflow runtime
  ;; or delegate harness setup.
  (testing "routes exact supported PASS_STATUS lines"
    (is (= {:status :ok :data "DONE" :summary "DONE"}
           (routing/parse-pass-status-routing "No new feedback.\nPASS_STATUS: REVIEW_COMPLETE" nil)))
    (is (= {:status :ok :data "REPEAT" :summary "REPEAT"}
           (routing/parse-pass-status-routing "PASS_STATUS: ACTIONABLE_FEEDBACK" ["ACTIONABLE_FEEDBACK"])))
    (is (= {:status :ok :data "DONE" :summary "DONE"}
           (routing/parse-pass-status-routing "PASS_STATUS: IMPLEMENTATION_COMPLETE" ["IMPLEMENTATION_COMPLETE"])))
    (is (= {:status :ok :data "REPEAT" :summary "REPEAT"}
           (routing/parse-pass-status-routing "PASS_STATUS: MORE_WORK_REMAINS" ["MORE_WORK_REMAINS"]))))
  (testing "rejects missing, duplicate, disallowed, and malformed PASS_STATUS lines"
    (assert-error :missing-pass-status
                  (routing/parse-pass-status-routing "No marker here" nil))
    (let [result (assert-error :ambiguous-pass-status
                               (routing/parse-pass-status-routing
                                "PASS_STATUS: REVIEW_COMPLETE\nPASS_STATUS: ACTIONABLE_FEEDBACK"
                                nil))]
      (is (= ["PASS_STATUS: REVIEW_COMPLETE" "PASS_STATUS: ACTIONABLE_FEEDBACK"]
             (get-in result [:details :pass-status-lines]))
          (pr-str result)))
    (assert-error :invalid-pass-status
                  (routing/parse-pass-status-routing
                   "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                   ["REVIEW_COMPLETE" "ACTIONABLE_FEEDBACK"]))
    (assert-error :missing-pass-status
                  (routing/parse-pass-status-routing " PASS_STATUS: REVIEW_COMPLETE" nil))
    (doseq [text ["PASS_STATUS:REVIEW_COMPLETE"
                  "PASS_STATUS: REVIEW_COMPLETE "
                  "PASS_STATUS: MAYBE"
                  "PASS_STATUS: REVIEW_COMPLETE because done"]]
      (assert-error :malformed-pass-status
                    (routing/parse-pass-status-routing text nil)))))

(deftest munera-open-task-path-routing-parser-test
  ;; Tests pure Munera open task path routing accepts only a single normalized
  ;; root-relative open-task path as the extracted task identity.
  (testing "routes one valid munera/open path to DONE"
    (assert-route "DONE"
                  (routing/parse-munera-open-task-path-routing
                   "munera/open/220-harden-simplification-workflow-proof-gates")))
  (testing "routes prose, malformed, non-open, absolute, and raw handoff outputs to REPEAT"
    (doseq [invalid ["Here is the generated task.\nmunera/open/220-harden-simplification-workflow-proof-gates"
                     "munera/open/220-harden-simplification-workflow-proof-gates\nPASS_STATUS: REVIEW_COMPLETE"
                     "munera/open/220-harden-simplification-workflow-proof-gates\nmunera/open/221-other-task"
                     "munera/closed/220-harden-simplification-workflow-proof-gates"
                     "/Users/duncan/projects/hugoduncan/psi/reduce-architectural-complexity/munera/open/220-harden-simplification-workflow-proof-gates"
                     "munera_task_path: munera/open/220-harden-simplification-workflow-proof-gates"
                     "## Munera Task\n\nmunera_task_path: munera/open/220-harden-simplification-workflow-proof-gates\nPASS_STATUS: REVIEW_COMPLETE"
                     "munera/open/not-a-number-task"
                     "munera/open/220-Harden-Simplification-Workflow-Proof-Gates"
                     "munera/open/220_harden_simplification_workflow_proof_gates"
                     "munera/open/220-harden-simplification-workflow-proof-gates/"]]
      (let [result (assert-route "REPEAT"
                                 (routing/parse-munera-open-task-path-routing invalid))]
        (is (= {:reason :invalid-munera-open-task-path
                :text invalid}
               (:details result))
            (pr-str result))))))

(defn- parse-marker-routing
  [marker-label text]
  (case marker-label
    "PROOF_SYNC_ROUTE"
    (routing/parse-proof-sync-disposition-routing text)

    "VALIDATION_CAPTURE_ROUTE"
    (routing/parse-validation-capture-disposition-routing text)))

(defn- assert-marker-route
  [marker-label route]
  (assert-route route
                (parse-marker-routing
                 marker-label
                 (str "Before prose\nPASS_STATUS: ACTIONABLE_FEEDBACK\n"
                      marker-label ": " route
                      "\nAfter prose"))))

(defn- assert-marker-error
  [marker-label expected-reason text]
  (assert-error expected-reason (parse-marker-routing marker-label text)))

(defn- assert-marker-routing-contract
  [{:keys [marker-label valid-routes unsupported-route]}]
  (doseq [route valid-routes]
    (assert-marker-route marker-label route))
  (assert-marker-error marker-label
                       :missing-route-marker
                       (str "Mentioned " marker-label " in prose without emitting a route.\nPASS_STATUS: ACTIONABLE_FEEDBACK"))
  (let [duplicate-text (str marker-label ": " (first valid-routes)
                            "\n"
                            marker-label ": " (second valid-routes))
        result (assert-marker-error marker-label :ambiguous-route-marker duplicate-text)]
    (is (= [(str marker-label ": " (first valid-routes))
            (str marker-label ": " (second valid-routes))]
           (get-in result [:details :route-marker-lines]))
        (pr-str result)))
  (assert-marker-error marker-label
                       :unsupported-route-marker
                       (str marker-label ": " unsupported-route))
  (doseq [text [(str " " marker-label ": " (first valid-routes))
                (str marker-label ":" (first valid-routes))
                (str marker-label " : " (first valid-routes))
                (str marker-label ": " (first valid-routes) " ")
                (str marker-label ": " (first valid-routes) " because tests changed")]]
    (assert-marker-error marker-label :malformed-route-marker text)))

(deftest proof-sync-disposition-routing-parser-test
  ;; Tests pure proof-sync route marker parsing and classifier edge cases.
  (testing "proof-sync route marker grammar"
    (assert-marker-routing-contract
     {:marker-label "PROOF_SYNC_ROUTE"
      :valid-routes ["COVERAGE_REVIEW" "VALIDATION_RECAPTURE" "BOOKKEEPING_FIXED_POINT"]
      :unsupported-route "TERMINAL_STOP"})))

(deftest validation-capture-disposition-routing-parser-test
  ;; Tests pure validation-capture route marker parsing and classifier edge cases.
  (testing "validation-capture route marker grammar"
    (assert-marker-routing-contract
     {:marker-label "VALIDATION_CAPTURE_ROUTE"
      :valid-routes ["IMPLEMENTATION_REPAIR" "TERMINAL_STOP"]
      :unsupported-route "COVERAGE_REVIEW"})))
