(ns psi.agent-session.workflow.routing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow.routing :as routing]))

(def ^:private example-marker-label "QUALITY_GATE")
(def ^:private example-routes ["APPROVE" "REPAIR" "ESCALATE_NOW"])

(defn- exact-marker-args
  ([text]
   (exact-marker-args text example-routes))
  ([text allowed-routes]
   {:text text
    :marker-label example-marker-label
    :allowed-routes allowed-routes}))

(defn- parse-exact-marker
  [text]
  (routing/parse-exact-marker-routing (exact-marker-args text)))

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

(deftest exact-marker-routing-valid-and-missing-test
  ;; Tests generic exact-marker routing accepts arbitrary workflow-owned marker
  ;; labels/routes and ignores surrounding non-candidate lines.
  (testing "routes exact supported markers with surrounding prose and PASS_STATUS"
    (assert-route "APPROVE"
                  (parse-exact-marker
                   "Intro prose\nPASS_STATUS: ACTIONABLE_FEEDBACK\nQUALITY_GATE: APPROVE\nDone"))
    (assert-route "ESCALATE_NOW"
                  (parse-exact-marker "QUALITY_GATE: ESCALATE_NOW")))
  (testing "reports missing marker for empty text and prose-only marker mentions"
    (assert-error :missing-route-marker (parse-exact-marker ""))
    (assert-error :missing-route-marker
                  (parse-exact-marker
                   "The QUALITY_GATE should probably approve, but no marker colon exists.")))
  (testing "column-0 marker-prefix prose without a marker colon is ordinary prose"
    ;; Guards the marker-prefix? ∧ ¬marker-attempt? → :ordinary branch: a line
    ;; starting at column 0 with the marker label but no marker colon must be
    ;; missing-route-marker, never malformed-route-marker.
    (assert-error :missing-route-marker
                  (parse-exact-marker "QUALITY_GATE recommends APPROVE"))))

(deftest exact-marker-routing-duplicate-and-unsupported-test
  ;; Tests duplicate candidates always produce ambiguity diagnostics, while one
  ;; unsupported exact-shape route reports the unsupported token.
  (testing "duplicate valid markers include complete candidate diagnostics"
    (let [result (assert-error :ambiguous-route-marker
                               (parse-exact-marker
                                "QUALITY_GATE: APPROVE\nQUALITY_GATE: REPAIR"))]
      (is (= example-marker-label (get-in result [:details :marker-label]))
          (pr-str result))
      (is (= ["QUALITY_GATE: APPROVE" "QUALITY_GATE: REPAIR"]
             (get-in result [:details :route-marker-lines]))
          (pr-str result))
      (is (= [{:kind :exact :line "QUALITY_GATE: APPROVE" :route "APPROVE"}
              {:kind :exact :line "QUALITY_GATE: REPAIR" :route "REPAIR"}]
             (get-in result [:details :route-marker-candidates]))
          (pr-str result))))
  (testing "one unsupported marker reports value and allowed routes"
    (let [result (assert-error :unsupported-route-marker
                               (parse-exact-marker "QUALITY_GATE: DENY"))]
      (is (= {:text "QUALITY_GATE: DENY"
              :marker-label example-marker-label
              :line "QUALITY_GATE: DENY"
              :value "DENY"
              :allowed-routes example-routes}
             (:details result))
          (pr-str result)))))

(deftest exact-marker-routing-malformed-test
  ;; Tests malformed marker attempts are candidate errors, not ordinary prose.
  (testing "single malformed candidates identify the malformed line shape"
    (doseq [[text reason value]
            [[" QUALITY_GATE: APPROVE" :leading-whitespace nil]
             ["QUALITY_GATE : APPROVE" :whitespace-before-colon nil]
             ["QUALITY_GATE:APPROVE" :missing-space-after-colon nil]
             ["QUALITY_GATE: APPROVE " :malformed-route-token "APPROVE "]
             ["QUALITY_GATE: APPROVE because done" :malformed-route-token "APPROVE because done"]
             ["QUALITY_GATE: approve" :malformed-route-token "approve"]]]
      (let [result (assert-error :malformed-route-marker
                                 (parse-exact-marker text))]
        (is (= reason (get-in result [:details :reason])) (pr-str result))
        (is (= text (get-in result [:details :line])) (pr-str result))
        (when value
          (is (= value (get-in result [:details :value])) (pr-str result)))))))

(deftest exact-marker-routing-mixed-candidate-precedence-test
  ;; Tests all multi-candidate replies return ambiguity before malformed or
  ;; unsupported single-candidate handling, with every candidate classified.
  (testing "mixed candidates all return ambiguous-route-marker"
    (doseq [[text expected-candidates]
            [["QUALITY_GATE: APPROVE\n QUALITY_GATE: REPAIR"
              [{:kind :exact :line "QUALITY_GATE: APPROVE" :route "APPROVE"}
               {:kind :malformed :line " QUALITY_GATE: REPAIR" :reason :leading-whitespace}]]
             ["QUALITY_GATE: APPROVE\nQUALITY_GATE: DENY"
              [{:kind :exact :line "QUALITY_GATE: APPROVE" :route "APPROVE"}
               {:kind :unsupported :line "QUALITY_GATE: DENY" :value "DENY"}]]
             ["QUALITY_GATE: nope\nQUALITY_GATE: DENY"
              [{:kind :malformed
                :line "QUALITY_GATE: nope"
                :reason :malformed-route-token
                :value "nope"}
               {:kind :unsupported :line "QUALITY_GATE: DENY" :value "DENY"}]]
             [" QUALITY_GATE: APPROVE\nQUALITY_GATE: nope\nQUALITY_GATE: DENY"
              [{:kind :malformed :line " QUALITY_GATE: APPROVE" :reason :leading-whitespace}
               {:kind :malformed
                :line "QUALITY_GATE: nope"
                :reason :malformed-route-token
                :value "nope"}
               {:kind :unsupported :line "QUALITY_GATE: DENY" :value "DENY"}]]]]
      (let [result (assert-error :ambiguous-route-marker (parse-exact-marker text))]
        (is (= (mapv :line expected-candidates)
               (get-in result [:details :route-marker-lines]))
            (pr-str result))
        (is (= expected-candidates
               (get-in result [:details :route-marker-candidates]))
            (pr-str result))))))

(deftest exact-marker-routing-invalid-args-test
  ;; Tests exact-marker operation arguments are validated before marker parsing
  ;; and return accumulated diagnostics without throwing.
  (testing "invalid args report the tagged invalid-arg result"
    (let [result (routing/parse-exact-marker-routing {})]
      (is (= :error (:status result)) (pr-str result))
      (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
      (is (= "workflow/exact-marker-routing args are invalid" (:message result))
          (pr-str result))))
  (testing "required invalid arg cases"
    (doseq [[args expected-errors]
            [[{} [{:field :text :reason :missing-text}
                  {:field :marker-label :reason :missing-marker-label}
                  {:field :allowed-routes :reason :missing-allowed-routes}]]
             [{:text 1 :marker-label "QUALITY_GATE" :allowed-routes ["APPROVE"]}
              [{:field :text :reason :non-string-text :value 1}]]
             [{:text "" :marker-label 1 :allowed-routes ["APPROVE"]}
              [{:field :marker-label :reason :non-string-marker-label :value 1}]]
             [{:text "" :marker-label "QUALITY-GATE" :allowed-routes ["APPROVE"]}
              [{:field :marker-label :reason :invalid-marker-label :value "QUALITY-GATE"}]]
             [{:text "" :marker-label "quality_gate" :allowed-routes ["APPROVE"]}
              [{:field :marker-label :reason :invalid-marker-label :value "quality_gate"}]]
             [{:text "" :marker-label "QUALITY1" :allowed-routes ["APPROVE"]}
              [{:field :marker-label :reason :invalid-marker-label :value "QUALITY1"}]]
             [{:text "" :marker-label "QUALITY GATE" :allowed-routes ["APPROVE"]}
              [{:field :marker-label :reason :invalid-marker-label :value "QUALITY GATE"}]]
             [{:text "" :marker-label "" :allowed-routes ["APPROVE"]}
              [{:field :marker-label :reason :invalid-marker-label :value ""}]]
             [{:text "" :marker-label "QUALITY_GATE" :allowed-routes #{"APPROVE"}}
              [{:field :allowed-routes
                :reason :non-vector-allowed-routes
                :value #{"APPROVE"}}]]
             [{:text "" :marker-label "QUALITY_GATE" :allowed-routes nil}
              [{:field :allowed-routes
                :reason :non-vector-allowed-routes
                :value nil}]]
             [{:text "" :marker-label "QUALITY_GATE" :allowed-routes '("APPROVE")}
              [{:field :allowed-routes
                :reason :non-vector-allowed-routes
                :value '("APPROVE")}]]
             [{:text "" :marker-label "QUALITY_GATE" :allowed-routes []}
              [{:field :allowed-routes :reason :empty-allowed-routes}]]
             [{:text "" :marker-label "QUALITY_GATE" :allowed-routes ["APPROVE" "approve" 1 "APPROVE"]}
              [{:field :allowed-routes
                :reason :invalid-allowed-route
                :index 1
                :value "approve"}
               {:field :allowed-routes
                :reason :invalid-allowed-route
                :index 2
                :value 1}
               {:field :allowed-routes
                :reason :duplicate-allowed-route
                :value "APPROVE"
                :indices [0 3]}]]]]
      (let [result (assert-error :invalid-route-marker-args
                                 (routing/parse-exact-marker-routing args))]
        (is (= expected-errors (get-in result [:details :errors]))
            (pr-str result))))))
