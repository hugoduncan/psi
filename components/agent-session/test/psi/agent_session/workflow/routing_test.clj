(ns psi.agent-session.workflow.routing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow.authored-token :as authored-token]
   [psi.agent-session.workflow.exact-marker-routing :as exact-marker-routing]
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
  (exact-marker-routing/parse-exact-marker-routing (exact-marker-args text)))

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

(deftest final-complete-block-parser-test
  ;; Tests the final syntactically complete block wins over stale or malformed attempts.
  (testing "last complete block is authoritative"
    (is (= {"- blocker: " "current access decision"
            "- required-human-action: " "grant repository access"}
           (routing/parse-final-complete-block
            (str "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                 "- blocker: stale choice\n"
                 "- required-human-action: decide stale choice\n"
                 "<!-- IMPLEMENTATION_BLOCKER: END -->\n"
                 "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                 "- blocker: incomplete\n"
                 "<!-- IMPLEMENTATION_BLOCKER: END -->\n"
                 "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                 "- blocker: current access decision\n"
                 "- required-human-action: grant repository access\n"
                 "<!-- IMPLEMENTATION_BLOCKER: END -->\n")
            "<!-- IMPLEMENTATION_BLOCKER: START -->"
            ["- blocker: " "- required-human-action: "]
            "<!-- IMPLEMENTATION_BLOCKER: END -->"))))
  (testing "absent, malformed, or whitespace-only records are rejected"
    (doseq [content ["<!-- IMPLEMENTATION_BLOCKER: START -->\n- blocker: \n<!-- IMPLEMENTATION_BLOCKER: END -->"
                     "<!-- IMPLEMENTATION_BLOCKER: START -->\n- blocker:   \t\n- required-human-action: choose access\n<!-- IMPLEMENTATION_BLOCKER: END -->"
                     "<!-- IMPLEMENTATION_BLOCKER: START -->\n- blocker: missing access\n- required-human-action:  \t \n<!-- IMPLEMENTATION_BLOCKER: END -->"]]
      (is (nil? (routing/parse-final-complete-block
                 content
                 "<!-- IMPLEMENTATION_BLOCKER: START -->"
                 ["- blocker: " "- required-human-action: "]
                 "<!-- IMPLEMENTATION_BLOCKER: END -->")))))
  (testing "empty, blank, and duplicate field-prefix schemas are rejected"
    (doseq [field-prefixes [[] [" "] ["- blocker: " "- blocker: "]]]
      (is (nil? (routing/parse-final-complete-block
                 (str "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                      "- blocker: first\n"
                      "- blocker: second\n"
                      "<!-- IMPLEMENTATION_BLOCKER: END -->\n")
                 "<!-- IMPLEMENTATION_BLOCKER: START -->"
                 field-prefixes
                 "<!-- IMPLEMENTATION_BLOCKER: END -->"))))))

(deftest final-complete-block-appended-test
  ;; A blocked pass must append exactly one complete record after its capture.
  (let [start "<!-- IMPLEMENTATION_BLOCKER: START -->"
        fields ["- blocker: " "- required-human-action: "]
        end "<!-- IMPLEMENTATION_BLOCKER: END -->"
        before "prior implementation notes\n"
        block (str start "\n"
                   "- blocker: awaiting a decision\n"
                   "- required-human-action: choose the policy\n"
                   end "\n")]
    (testing "one newly appended complete block is accepted"
      (is (routing/final-complete-block-appended?
           before (str before block) start fields end)))
    (testing "two newly appended complete blocks are rejected"
      (is (not (routing/final-complete-block-appended?
                before (str before block block) start fields end))))))

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

(deftest pass-feedback-routing-parser-test
  ;; Tests pass-level review feedback routing validates every prompt/phase reply
  ;; before computing the aggregate review-pass route.
  (testing "rejects empty feedback inputs with deterministic diagnostics"
    (let [result (assert-error :invalid-pass-feedback
                               (routing/parse-pass-feedback-routing {}))]
      (is (= :empty-pass-feedback
             (get-in result [:details :validation-failures :feedback-inputs :reason]))
          (pr-str result))))
  (testing "routes DONE only when every supplied reply is REVIEW_COMPLETE"
    (assert-route "DONE"
                  (routing/parse-pass-feedback-routing
                   {:architecture-text "Architecture clear.\nPASS_STATUS: REVIEW_COMPLETE"
                    :ambiguity-text "No ambiguity.\nPASS_STATUS: REVIEW_COMPLETE"
                    :inconsistency-text "No inconsistency.\nPASS_STATUS: REVIEW_COMPLETE"})))
  (testing "routes REPEAT when any supplied reply is ACTIONABLE_FEEDBACK"
    (let [result (assert-route "REPEAT"
                               (routing/parse-pass-feedback-routing
                                {:architecture-text "Architecture clear.\nPASS_STATUS: REVIEW_COMPLETE"
                                 :ambiguity-text "Ambiguity found.\nPASS_STATUS: ACTIONABLE_FEEDBACK"
                                 :inconsistency-text "No inconsistency.\nPASS_STATUS: REVIEW_COMPLETE"}))]
      (is (= [:ambiguity-text]
             (get-in result [:details :actionable-keys]))
          (pr-str result))))
  (testing "rejects missing, duplicate, malformed, and disallowed statuses per key"
    (let [result (assert-error
                  :invalid-pass-feedback
                  (routing/parse-pass-feedback-routing
                   {:missing-text "No marker here"
                    :duplicate-text "PASS_STATUS: REVIEW_COMPLETE\nPASS_STATUS: REVIEW_COMPLETE"
                    :malformed-text "PASS_STATUS: REVIEW_COMPLETE because done"
                    :disallowed-text "PASS_STATUS: IMPLEMENTATION_COMPLETE"}))
          failures (get-in result [:details :validation-failures])]
      (is (= #{:missing-text :duplicate-text :malformed-text :disallowed-text}
             (set (keys failures)))
          (pr-str result))
      (is (= :missing-pass-status (get-in failures [:missing-text :reason]))
          (pr-str result))
      (is (= :ambiguous-pass-status (get-in failures [:duplicate-text :reason]))
          (pr-str result))
      (is (= :malformed-pass-status (get-in failures [:malformed-text :reason]))
          (pr-str result))
      (is (= :invalid-pass-status (get-in failures [:disallowed-text :reason]))
          (pr-str result))
      (is (= ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]
             (get-in failures [:disallowed-text :details :allowed-statuses]))
          (pr-str result))))
  (testing "rejects non-string feedback values with deterministic diagnostics"
    (let [result (assert-error
                  :invalid-pass-feedback
                  (routing/parse-pass-feedback-routing
                   {:numeric-text 42
                    :vector-text ["PASS_STATUS: REVIEW_COMPLETE"]
                    :valid-text "PASS_STATUS: REVIEW_COMPLETE"}))
          failures (get-in result [:details :validation-failures])]
      (is (= #{:numeric-text :vector-text}
             (set (keys failures)))
          (pr-str result))
      (is (= :non-string-pass-feedback (get-in failures [:numeric-text :reason]))
          (pr-str result))
      (is (= 42 (get-in failures [:numeric-text :details :text]))
          (pr-str result))
      (is (= :non-string-pass-feedback (get-in failures [:vector-text :reason]))
          (pr-str result))
      (is (= ["PASS_STATUS: REVIEW_COMPLETE"]
             (get-in failures [:vector-text :details :text]))
          (pr-str result)))))

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

(deftest normalize-open-task-path-test
  ;; Tests the pure workflow-input normalizer directly. Operation-level tests keep
  ;; only a representative boundary case; this pure unit owns the grammar.
  (testing "full munera/open paths are returned after trimming surrounding whitespace"
    (is (= "munera/open/230-scope-question-lifecycle-gate"
           (routing/normalize-open-task-path
            "munera/open/230-scope-question-lifecycle-gate")))
    (is (= "munera/open/230-scope-question-lifecycle-gate"
           (routing/normalize-open-task-path
            "  munera/open/230-scope-question-lifecycle-gate\n"))))
  (testing "bare task tokens become munera/open paths after trimming whitespace"
    (is (= "munera/open/230-scope-question-lifecycle-gate"
           (routing/normalize-open-task-path
            "230-scope-question-lifecycle-gate")))
    (is (= "munera/open/230-scope-question-lifecycle-gate"
           (routing/normalize-open-task-path
            "\t230-scope-question-lifecycle-gate "))))
  (testing "non-open, free-text, partial, malformed, and nil inputs yield nil"
    (doseq [invalid ["munera/closed/230-scope-question-lifecycle-gate"
                     "please run 230-scope-question-lifecycle-gate"
                     "prefix munera/open/230-scope-question-lifecycle-gate"
                     "munera/open/230-scope-question-lifecycle-gate/extra"
                     "munera/open/not-a-number"
                     "230-Scope-Question-Lifecycle-Gate"
                     nil]]
      (is (nil? (routing/normalize-open-task-path invalid))
          (pr-str invalid)))))

(deftest authored-route-token-validation-parity-test
  ;; Producer route tokens and exact-marker route/field labels share one grammar.
  (doseq [token ["QUALITY_GATE" "APPROVE" "ESCALATE_NOW"]]
    (testing (str "accepts " (pr-str token) " at every authored-token boundary")
      (is (true? (authored-token/valid-route-token? token)))
      (is (= :ok
             (:status
              (exact-marker-routing/parse-exact-marker-routing
               {:text (str token ": " token "\nFIELD_LABEL: value")
                :marker-label token
                :allowed-routes [token]
                :required-fields-by-route {token {"FIELD_LABEL" "value"}}}))))))
  (doseq [token ["lowercase" "HAS-DASH" "HAS SPACE" "TOKEN1" "" nil]]
    (testing (str "rejects " (pr-str token) " at every authored-token boundary")
      (is (false? (authored-token/valid-route-token? token)))
      (is (= :invalid-route-marker-args
             (:reason
              (exact-marker-routing/parse-exact-marker-routing
               {:text "QUALITY_GATE: APPROVE"
                :marker-label "QUALITY_GATE"
                :allowed-routes ["APPROVE"]
                :required-fields-by-route {"APPROVE" {token "value"}}})))))))

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
                  (parse-exact-marker "QUALITY_GATE recommends APPROVE")))
  (testing "column-0 line whose leading token is a superstring of the marker label is ordinary prose"
    ;; Guards the marker-attempt? prefix boundary: the char immediately after
    ;; the marker label must be a colon (or whitespace-before-colon). A line
    ;; whose leading token merely *starts with* the label and has a later colon
    ;; (e.g. "QUALITY_GATED:" / "QUALITY_GATE_NOW:") is ordinary prose, never a
    ;; candidate, so it routes to missing-route-marker. Distinct from the
    ;; label + space + no colon case above.
    (assert-error :missing-route-marker
                  (parse-exact-marker "QUALITY_GATED: APPROVE"))
    (assert-error :missing-route-marker
                  (parse-exact-marker "QUALITY_GATE_NOW: APPROVE"))))

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

(deftest exact-marker-routing-required-fields-test
  ;; Tests terminal handback fields are exact, unique, branch-specific, and may
  ;; be derived from a previously validated source handback.
  (let [base {:marker-label "IMPLEMENTATION_STATUS"
              :allowed-routes ["IMPLEMENTATION_COMPLETE" "IMPLEMENTATION_BLOCKED"]
              :required-fields-by-route
              {"IMPLEMENTATION_BLOCKED"
               {"IMPLEMENTATION_BLOCKER" "validated blocker"
                "IMPLEMENTATION_REQUIRED_HUMAN_ACTION" "validated action"}}}
        valid (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                   "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                   "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")]
    (is (= "IMPLEMENTATION_BLOCKED"
           (:data (exact-marker-routing/parse-exact-marker-routing (assoc base :text valid)))))
    (let [complete-base {:marker-label "IMPLEMENTATION_STATUS"
                         :allowed-routes ["IMPLEMENTATION_COMPLETE"]
                         :forbidden-field-labels-by-route
                         {"IMPLEMENTATION_COMPLETE"
                          ["IMPLEMENTATION_BLOCKER"
                           "IMPLEMENTATION_REQUIRED_HUMAN_ACTION"]}}
          complete "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE"]
      (is (= "IMPLEMENTATION_COMPLETE"
             (:data (exact-marker-routing/parse-exact-marker-routing
                     (assoc complete-base :text complete)))))
      (doseq [field ["IMPLEMENTATION_BLOCKER"
                     "IMPLEMENTATION_REQUIRED_HUMAN_ACTION"]]
        (is (= :unexpected-route-field
               (:reason (exact-marker-routing/parse-exact-marker-routing
                         (assoc complete-base :text (str field ": stale\n" complete)))))
            field)))
    (doseq [[label text reason]
            [["missing"
              "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\nIMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"
              :missing-route-field]
             ["malformed"
              "IMPLEMENTATION_BLOCKER:validated blocker\nIMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\nIMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"
              :mismatched-route-field]
             ["duplicate"
              (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                   "IMPLEMENTATION_BLOCKER: validated blocker\n"
                   "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                   "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
              :ambiguous-route-field]
             ["branch mismatch"
              (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                   "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                   "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
              :unexpected-route-field]
             ["snapshot mismatch"
              (str "IMPLEMENTATION_BLOCKER: changed blocker\n"
                   "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                   "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
              :mismatched-route-field]]]
      (is (= reason
             (:reason (exact-marker-routing/parse-exact-marker-routing (assoc base :text text))))
          label))
    (let [source (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                      "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                      "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
          args {:text valid
                :marker-label "IMPLEMENTATION_STATUS"
                :allowed-routes ["IMPLEMENTATION_COMPLETE" "IMPLEMENTATION_BLOCKED"]
                :required-fields-source-text source
                :required-field-labels-by-route
                {"IMPLEMENTATION_BLOCKED"
                 ["IMPLEMENTATION_BLOCKER" "IMPLEMENTATION_REQUIRED_HUMAN_ACTION"]}}]
      (is (= "IMPLEMENTATION_BLOCKED"
             (:data (exact-marker-routing/parse-exact-marker-routing args))))
      (testing "source-derived fields belong only to their route"
        (doseq [field ["IMPLEMENTATION_BLOCKER"
                       "IMPLEMENTATION_REQUIRED_HUMAN_ACTION"]]
          (let [result (exact-marker-routing/parse-exact-marker-routing
                        (assoc args :text
                               (str field ": stale\n"
                                    "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")))]
            (is (= :unexpected-route-field (:reason result)) (pr-str result))
            (is (= field (get-in result [:details :field-label]))
                (pr-str result)))))
      (testing "matching direct and source-derived fields agree"
        (is (= "IMPLEMENTATION_BLOCKED"
               (:data (exact-marker-routing/parse-exact-marker-routing
                       (assoc args :required-fields-by-route
                              {"IMPLEMENTATION_BLOCKED"
                               {"IMPLEMENTATION_BLOCKER" "validated blocker"}}))))))
      (testing "conflicting direct and source-derived fields are invalid"
        (let [result (exact-marker-routing/parse-exact-marker-routing
                      (assoc args :required-fields-by-route
                             {"IMPLEMENTATION_BLOCKED"
                              {"IMPLEMENTATION_BLOCKER" "different blocker"}}))]
          (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
          (is (= [{:field :required-fields-by-route
                   :reason :conflicting-required-field-sources
                   :route "IMPLEMENTATION_BLOCKED"
                   :label "IMPLEMENTATION_BLOCKER"
                   :direct-value "different blocker"
                   :source-value "validated blocker"}]
                 (get-in result [:details :errors]))
              (pr-str result))))
      (doseq [blank-value ["" "   "]]
        (let [blank-source (str "IMPLEMENTATION_BLOCKER: " blank-value "\n"
                                "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action")]
          (is (= :invalid-required-fields-source
                 (:reason (exact-marker-routing/parse-exact-marker-routing
                           (assoc args :required-fields-source-text blank-source))))
              (pr-str blank-value)))))))

(deftest exact-marker-routing-invalid-args-test
  ;; Tests exact-marker operation arguments are validated before marker parsing
  ;; and return accumulated diagnostics without throwing.
  (testing "invalid args report the tagged invalid-arg result"
    (let [result (exact-marker-routing/parse-exact-marker-routing {})]
      (is (= :error (:status result)) (pr-str result))
      (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
      (is (= "workflow/exact-marker-routing args are invalid" (:message result))
          (pr-str result))))
  (testing "empty source-derived required-field schemas compose as identity"
    (doseq [required-field-labels-by-route
            [{} {"APPROVE" []} {"APPROVE" [] "DENY" []}]]
      (let [result (exact-marker-routing/parse-exact-marker-routing
                    {:text "QUALITY_GATE: APPROVE"
                     :marker-label "QUALITY_GATE"
                     :allowed-routes ["APPROVE" "DENY"]
                     :required-field-labels-by-route required-field-labels-by-route})]
        (is (= :ok (:status result)) (pr-str required-field-labels-by-route result))
        (is (= "APPROVE" (:data result)) (pr-str required-field-labels-by-route result)))))
  (testing "direct and source-derived required fields are both validated"
    (let [result (exact-marker-routing/parse-exact-marker-routing
                  {:text "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"
                   :marker-label "IMPLEMENTATION_STATUS"
                   :allowed-routes ["IMPLEMENTATION_BLOCKED"]
                   :required-fields-by-route []
                   :required-fields-source-text "IMPLEMENTATION_BLOCKER: blocker"
                   :required-field-labels-by-route
                   {"IMPLEMENTATION_BLOCKED" ["IMPLEMENTATION_BLOCKER"]}})]
      (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
      (is (= [{:field :required-fields-by-route
               :reason :non-map-required-fields-by-route
               :value []}]
             (get-in result [:details :errors]))
          (pr-str result))))
  (testing "invalid allowed-routes remains structured with optional route fields"
    (doseq [optional-args
            [{:required-fields-by-route {"APPROVE" {"FIELD" "value"}}}
             {:required-fields-source-text "FIELD: value"
              :required-field-labels-by-route {"APPROVE" ["FIELD"]}}
             {:forbidden-field-labels-by-route {"APPROVE" ["FIELD"]}}]]
      (let [result (exact-marker-routing/parse-exact-marker-routing
                    (merge {:text "QUALITY_GATE: APPROVE"
                            :marker-label "QUALITY_GATE"
                            :allowed-routes 1}
                           optional-args))]
        (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
        (is (= [{:field :allowed-routes
                 :reason :non-vector-allowed-routes
                 :value 1}]
               (get-in result [:details :errors]))
            (pr-str result)))))
  (testing "duplicate source-derived required labels are invalid arguments"
    (let [result (exact-marker-routing/parse-exact-marker-routing
                  {:text "QUALITY_GATE: APPROVE"
                   :marker-label "QUALITY_GATE"
                   :allowed-routes ["APPROVE"]
                   :required-fields-source-text "FIELD: value"
                   :required-field-labels-by-route {"APPROVE" ["FIELD" "FIELD"]}})]
      (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
      (is (= [{:field :required-field-labels-by-route
               :reason :duplicate-required-field-label
               :route "APPROVE"
               :value "FIELD"
               :indices [0 1]}]
             (get-in result [:details :errors]))
          (pr-str result))))
  (testing "duplicate forbidden field labels are invalid arguments"
    (let [result (exact-marker-routing/parse-exact-marker-routing
                  {:text "QUALITY_GATE: APPROVE"
                   :marker-label "QUALITY_GATE"
                   :allowed-routes ["APPROVE"]
                   :forbidden-field-labels-by-route {"APPROVE" ["FIELD" "FIELD"]}})]
      (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
      (is (= [{:field :forbidden-field-labels-by-route
               :reason :duplicate-field-label
               :route "APPROVE"
               :value "FIELD"
               :indices [0 1]}]
             (get-in result [:details :errors]))
          (pr-str result))))
  (testing "malformed overlapping field labels remain structured invalid arguments"
    (doseq [label [nil 1]]
      (let [result (exact-marker-routing/parse-exact-marker-routing
                    {:text "QUALITY_GATE: APPROVE"
                     :marker-label "QUALITY_GATE"
                     :allowed-routes ["APPROVE"]
                     :required-fields-by-route {"APPROVE" {label "direct"}}
                     :required-fields-source-text "FIELD: source"
                     :required-field-labels-by-route {"APPROVE" [label]}})]
        (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
        (is (some #(= :invalid-required-field-label (:reason %))
                  (get-in result [:details :errors]))
            (pr-str result))
        (is (some #(= :invalid-required-field-labels (:reason %))
                  (get-in result [:details :errors]))
            (pr-str result)))))
  (testing "a route field cannot be both required and forbidden"
    (doseq [required-args
            [{:required-fields-by-route {"APPROVE" {"FIELD" "value"}}}
             {:required-fields-source-text "FIELD: value"
              :required-field-labels-by-route {"APPROVE" ["FIELD"]}}]]
      (let [result (exact-marker-routing/parse-exact-marker-routing
                    (merge {:text "FIELD: value\nQUALITY_GATE: APPROVE"
                            :marker-label "QUALITY_GATE"
                            :allowed-routes ["APPROVE"]
                            :forbidden-field-labels-by-route {"APPROVE" ["FIELD"]}}
                           required-args))]
        (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
        (is (= [{:field :forbidden-field-labels-by-route
                 :reason :required-and-forbidden-route-field
                 :route "APPROVE"
                 :label "FIELD"}]
               (get-in result [:details :errors]))
            (pr-str result)))))
  (testing "the marker label cannot also be a route field label"
    (doseq [[field optional-args]
            [[:required-fields-by-route
              {:required-fields-by-route
               {"APPROVE" {"QUALITY_GATE" "APPROVE"}}}]
             [:required-field-labels-by-route
              {:required-fields-source-text "QUALITY_GATE: APPROVE"
               :required-field-labels-by-route
               {"APPROVE" ["QUALITY_GATE"]}}]
             [:forbidden-field-labels-by-route
              {:forbidden-field-labels-by-route
               {"APPROVE" ["QUALITY_GATE"]}}]]]
      (let [result (exact-marker-routing/parse-exact-marker-routing
                    (merge {:text "QUALITY_GATE: APPROVE"
                            :marker-label "QUALITY_GATE"
                            :allowed-routes ["APPROVE"]}
                           optional-args))]
        (is (= :invalid-route-marker-args (:reason result)) (pr-str result))
        (is (= [{:field field
                 :reason :marker-label-route-field
                 :route "APPROVE"
                 :label "QUALITY_GATE"}]
               (get-in result [:details :errors]))
            (pr-str result)))))
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
                                 (exact-marker-routing/parse-exact-marker-routing args))]
        (is (= expected-errors (get-in result [:details :errors]))
            (pr-str result))))))

(def ^:private scope-marker "SCOPE_QUESTION:")
(def ^:private scope-proceed "DONE")
(def ^:private scope-open "SCOPE_QUESTION_OPEN")

(defn- parse-scope-gate
  [content]
  (routing/parse-scope-question-gate content scope-marker scope-proceed scope-open))

(deftest scope-question-gate-parser-test
  ;; Tests the pure content scanner that gates the task lifecycle on unchecked
  ;; SCOPE_QUESTION items in design-steps.md content. No IO; route labels and the
  ;; marker are supplied as args (not hardcoded).
  (testing "single unchecked SCOPE_QUESTION item routes to the open route with named concern"
    (let [result (parse-scope-gate "- [ ] SCOPE_QUESTION: bucket-size in reopen identity?")]
      (assert-route scope-open result)
      (is (= ["bucket-size in reopen identity?"]
             (get-in result [:details :open-questions]))
          (pr-str result))))

  (testing "only-checked SCOPE_QUESTION items route to proceed (AC-2)"
    (assert-route scope-proceed
                  (parse-scope-gate "- [x] SCOPE_QUESTION: resolved one\n- [X] SCOPE_QUESTION: resolved two")))

  (testing "nil and empty content route to proceed (AC-2 absent file)"
    (assert-route scope-proceed (parse-scope-gate nil))
    (assert-route scope-proceed (parse-scope-gate "")))

  (testing "mixed checked and unchecked items route to open, naming only open concerns"
    (let [result (parse-scope-gate
                  (str "- [x] SCOPE_QUESTION: resolved\n"
                       "- [ ] SCOPE_QUESTION: still open"))]
      (assert-route scope-open result)
      (is (= ["still open"] (get-in result [:details :open-questions]))
          (pr-str result))))

  (testing "indented unchecked item routes to open (leading whitespace tolerated)"
    (let [result (parse-scope-gate "    - [ ] SCOPE_QUESTION: indented concern")]
      (assert-route scope-open result)
      (is (= ["indented concern"] (get-in result [:details :open-questions]))
          (pr-str result))))

  (testing "unchecked non-marker checklist item is ignored (proceed)"
    (assert-route scope-proceed
                  (parse-scope-gate "- [ ] ordinary follow-up item")))

  (testing "marker present but not as the item prefix routes to proceed (no false halt)"
    ;; The marker must be the item prose prefix (immediately after the
    ;; checkbox). A line that merely *mentions* SCOPE_QUESTION: later in the
    ;; prose is not an open item — wrongly halting on it would block the
    ;; lifecycle (the inverse failure mode to a missed halt).
    (assert-route scope-proceed
                  (parse-scope-gate "- [ ] note: SCOPE_QUESTION: is discussed elsewhere"))
    (assert-route scope-proceed
                  (parse-scope-gate "- [ ] resolved the SCOPE_QUESTION: about bucket-size")))

  (testing "route labels are honoured from args, not hardcoded"
    (let [result (routing/parse-scope-question-gate
                  "- [ ] SCOPE_QUESTION: pick" scope-marker "GO" "STOP")]
      (assert-route "STOP" result))
    (assert-route "GO"
                  (routing/parse-scope-question-gate
                   "- [x] SCOPE_QUESTION: done" scope-marker "GO" "STOP")))

  (testing "multiple open items are all named in order"
    (let [result (parse-scope-gate
                  (str "- [ ] SCOPE_QUESTION: first\n"
                       "- [ ] SCOPE_QUESTION: second"))]
      (assert-route scope-open result)
      (is (= ["first" "second"] (get-in result [:details :open-questions]))
          (pr-str result)))))
