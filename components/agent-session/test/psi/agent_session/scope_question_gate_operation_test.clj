(ns psi.agent-session.scope-question-gate-operation-test
  "Invocation tests for the workflow/scope-question-gate-routing deterministic
   operation. Exercises the real handler through the registry boundary on both
   the direct-invoke key set (`:session-id`) and the production `:invoke`-step
   judge key set (`:parent-session-id`, no `:session-id`) — DI-3."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.agent-session.workflow.artifact-routing :as artifact-routing]
   [psi.deterministic-operation-registry.registry :as registry]
   [psi.deterministic-operation-runtime.core :as runtime]))

;; Worktree/session/artifact ceremony is shared via test-support
;; (`with-temp-worktree-session`, `write-task-artifact!`), keeping this file
;; consistent with the task-artifact-content resolver suite.

(def ^:private gate-operation-id "workflow/scope-question-gate-routing")

(def ^:private default-args
  {:artifact "design-steps.md"
   :marker "SCOPE_QUESTION:"
   :proceed-route "DONE"
   :open-route "SCOPE_QUESTION_OPEN"})

(def ^:private blocker-routing-args
  {:artifact "implementation.md"
   :start-delimiter "<!-- IMPLEMENTATION_BLOCKER: START -->"
   :field-prefixes ["- blocker: " "- required-human-action: "]
   :end-delimiter "<!-- IMPLEMENTATION_BLOCKER: END -->"
   :valid-route "DONE"})

(def ^:private invalid-blocker-routing-overrides
  [{:task-path ""}
   {:task-path "   "}
   {:artifact ""}
   {:artifact "   "}
   {:start-delimiter ""}
   {:start-delimiter "   "}
   {:end-delimiter ""}
   {:end-delimiter "   "}
   {:field-prefixes []}
   {:field-prefixes ["- blocker: " "- blocker: "]}
   {:field-prefixes ["" "- required-human-action: "]}
   {:field-prefixes [" " "- required-human-action: "]}
   {:field-prefixes ["\t" "- required-human-action: "]}
   {:output-field-labels []}
   {:output-field-labels ["IMPLEMENTATION_BLOCKER" "IMPLEMENTATION_BLOCKER"]}
   {:output-field-labels ["" "IMPLEMENTATION_REQUIRED_HUMAN_ACTION"]}
   {:output-field-labels ["implementation_blocker"
                          "IMPLEMENTATION_REQUIRED_HUMAN_ACTION"]}
   {:valid-route ""}
   {:valid-route "IMPLEMENTATION BLOCKED"}])

(defn- write-task-artifact!
  [worktree task-dir artifact content]
  (test-support/write-task-artifact! worktree task-dir artifact content))

(defn- write-design-steps!
  "Write `content` as the task's design-steps.md (the artifact this gate reads)."
  [worktree task-dir content]
  (write-task-artifact! worktree task-dir "design-steps.md" content))

(defn- invoke-gate
  "Invoke the registered gate operation with a caller-supplied invocation map.
   The handler is registered under its production id so we exercise the real
   registry boundary."
  [ctx invocation]
  (let [reg (:deterministic-operation-registry ctx)]
    (registry/register-operation-in!
     reg {:id gate-operation-id
          :description "scope gate (test registration)"
          :handler artifact-routing/scope-question-gate-routing})
    (registry/invoke-operation-in reg gate-operation-id invocation
                                  runtime/invoke-operation)))

(defn- register-gate!
  "Register the real gate handler under its production id in the ctx registry."
  [ctx]
  (registry/register-operation-in!
   (:deterministic-operation-registry ctx)
   {:id gate-operation-id
    :description "scope gate (test registration)"
    :handler artifact-routing/scope-question-gate-routing}))

(defn- direct-invocation
  [ctx session-id task-path]
  {:ctx ctx
   :session-id session-id
   :args (assoc default-args :task-path task-path)})

(deftest final-complete-block-routing-reads-last-valid-block-test
  ;; Tests the production artifact-resolver seam rejects invalid blocker records.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx sid]
      (let [operation-id "workflow/final-complete-block-routing"]
        (registry/register-operation-in!
         (:deterministic-operation-registry ctx)
         {:id operation-id :handler artifact-routing/final-complete-block-routing})
        (write-task-artifact! worktree "munera/open/230-x" "implementation.md"
                              (str "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                                   "- blocker: stale\n"
                                   "- required-human-action: ignore\n"
                                   "<!-- IMPLEMENTATION_BLOCKER: END -->\n"
                                   "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                                   "- blocker: current decision\n"
                                   "- required-human-action: choose an API\n"
                                   "<!-- IMPLEMENTATION_BLOCKER: END -->\n"))
        (let [result (registry/invoke-operation-in
                      (:deterministic-operation-registry ctx) operation-id
                      {:ctx ctx :session-id sid
                       :args (assoc blocker-routing-args :task-path "munera/open/230-x")}
                      runtime/invoke-operation)]
          (is (= "DONE" (:data result)) (pr-str result))
          (is (= {"- blocker: " "current decision"
                  "- required-human-action: " "choose an API"}
                 (get-in result [:details :record])) (pr-str result)))
        (doseq [content ["<!-- IMPLEMENTATION_BLOCKER: START -->\n- blocker: missing action\n<!-- IMPLEMENTATION_BLOCKER: END -->"
                         "<!-- IMPLEMENTATION_BLOCKER: START -->\n- blocker:   \n- required-human-action: choose access\n<!-- IMPLEMENTATION_BLOCKER: END -->"
                         "<!-- IMPLEMENTATION_BLOCKER: START -->\n- blocker: missing access\n- required-human-action: \t \n<!-- IMPLEMENTATION_BLOCKER: END -->"]]
          (write-task-artifact! worktree "munera/open/230-x" "implementation.md" content)
          (is (= :missing-final-complete-block
                 (:reason (registry/invoke-operation-in
                           (:deterministic-operation-registry ctx) operation-id
                           {:ctx ctx :session-id sid
                            :args (assoc blocker-routing-args :task-path "munera/open/230-x")}
                           runtime/invoke-operation)))))))))

(deftest final-complete-block-routing-rejects-ambiguous-schema-test
  ;; The operation rejects schemas that cannot produce exact-marker-compatible fields.
  (let [base-args (assoc blocker-routing-args :task-path "munera/open/230-x")]
    (doseq [override invalid-blocker-routing-overrides]
      (let [result (#'artifact-routing/final-complete-block-routing-result
                    (merge base-args override) "")]
        (is (= :error (:status result)) (pr-str override result))
        (is (= :invalid-final-complete-block-routing-args (:reason result))
            (pr-str override result))))))

(deftest complete-block-routing-handlers-validate-before-artifact-read-test
  ;; Public handlers reject malformed schemas before crossing the resolver boundary.
  (let [reads (atom [])
        ctx {:workflow-task-artifact-content-read-fn
             (fn [& read-args]
               (swap! reads conj read-args)
               nil)}
        invocation {:ctx ctx :session-id "session-1"}]
    (testing "final block handler rejects every malformed base schema without reading"
      (doseq [override invalid-blocker-routing-overrides]
        (let [args (merge blocker-routing-args
                          {:task-path "munera/open/230-x"}
                          override)
              result (artifact-routing/final-complete-block-routing
                      (assoc invocation :args args))]
          (is (= :invalid-final-complete-block-routing-args (:reason result))
              (pr-str override result))
          (is (empty? @reads) (pr-str override @reads)))))
    (testing "fresh block handler rejects every malformed base schema without reading"
      (doseq [override invalid-blocker-routing-overrides]
        (let [args (merge blocker-routing-args
                          {:task-path "munera/open/230-x"
                           :before-content "prior implementation notes\n"}
                          override)
              result (artifact-routing/fresh-final-complete-block-routing
                      (assoc invocation :args args))]
          (is (= :invalid-final-complete-block-routing-args (:reason result))
              (pr-str override result))
          (is (empty? @reads) (pr-str override @reads)))))
    (testing "fresh block handler rejects malformed capture content without reading"
      (let [args (assoc blocker-routing-args
                        :task-path "munera/open/230-x"
                        :before-content nil)
            result (artifact-routing/fresh-final-complete-block-routing
                    (assoc invocation :args args))]
        (is (= :invalid-fresh-final-complete-block-routing-args (:reason result))
            (pr-str result))
        (is (empty? @reads) (pr-str @reads))))))

(deftest fresh-final-complete-block-routing-uses-one-artifact-revision-test
  ;; The fresh gate must derive validity and freshness from one resolved content.
  (let [before-content "prior implementation notes\n"
        current-content (str before-content
                             "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                             "- blocker: current decision\n"
                             "- required-human-action: choose an API\n"
                             "<!-- IMPLEMENTATION_BLOCKER: END -->\n")
        args (assoc blocker-routing-args
                    :task-path "munera/open/230-x"
                    :before-content before-content)
        result (#'artifact-routing/fresh-final-complete-block-routing-result
                args current-content)]
    (is (= :ok (:status result)) (pr-str result))
    (is (= "DONE" (:data result)) (pr-str result)))
  (testing "two newly appended complete blocks are rejected"
    (let [before-content "prior implementation notes\n"
          block (str "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                     "- blocker: current decision\n"
                     "- required-human-action: choose an API\n"
                     "<!-- IMPLEMENTATION_BLOCKER: END -->\n")
          args (assoc blocker-routing-args
                      :task-path "munera/open/230-x"
                      :before-content before-content)
          result (#'artifact-routing/fresh-final-complete-block-routing-result
                  args (str before-content block block))]
      (is (= :error (:status result)) (pr-str result))
      (is (= :missing-fresh-final-complete-block (:reason result))
          (pr-str result))))
  (testing "base schema validation failures are preserved"
    (let [args (assoc blocker-routing-args
                      :task-path " "
                      :before-content "prior implementation notes\n")
          result (#'artifact-routing/fresh-final-complete-block-routing-result args "")]
      (is (= :error (:status result)) (pr-str result))
      (is (= :invalid-final-complete-block-routing-args (:reason result))
          (pr-str result))))
  (testing "before-content must be a string"
    (doseq [before-content [nil 42]]
      (let [args (assoc blocker-routing-args
                        :task-path "munera/open/230-x"
                        :before-content before-content)
            result (#'artifact-routing/fresh-final-complete-block-routing-result args "")]
        (is (= :error (:status result)) (pr-str result))
        (is (= :invalid-fresh-final-complete-block-routing-args (:reason result))
            (pr-str result))))))

(deftest gate-open-on-unchecked-scope-question-test
  ;; AC-1: an unchecked SCOPE_QUESTION halts (open route) and names the concern.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx sid]
      (write-design-steps! worktree "munera/open/230-x"
                           "- [ ] SCOPE_QUESTION: bucket-size in reopen identity?\n")
      (let [result (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))]
        (is (= :ok (:status result)) (pr-str result))
        (is (= "SCOPE_QUESTION_OPEN" (:data result)) (pr-str result))
        (is (= ["bucket-size in reopen identity?"]
               (get-in result [:details :open-questions]))
            (pr-str result))))))

(deftest gate-proceeds-on-only-checked-items-test
  ;; AC-2: only-checked items route to proceed.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx sid]
      (write-design-steps! worktree "munera/open/230-x"
                           "- [x] SCOPE_QUESTION: resolved here\n")
      (let [result (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))]
        (is (= "DONE" (:data result)) (pr-str result))))))

(deftest gate-proceeds-on-absent-artifact-test
  ;; AC-2: a task with no design-steps.md proceeds.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx sid]
      (.mkdirs (io/file worktree "munera/open/230-x"))
      (let [result (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))]
        (is (= "DONE" (:data result)) (pr-str result))))))

(deftest gate-resume-after-item-checked-test
  ;; AC-3: re-invoking after the human checks the item returns DONE.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx sid]
      (write-design-steps! worktree "munera/open/230-x"
                           "- [ ] SCOPE_QUESTION: open one\n")
      (is (= "SCOPE_QUESTION_OPEN"
             (:data (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x")))))
      (write-design-steps! worktree "munera/open/230-x"
                           "- [x] SCOPE_QUESTION: open one\n")
      (is (= "DONE"
             (:data (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))))))))

(deftest gate-task-path-normalization-boundary-test
  ;; Representative integration cases: the operation uses the pure normalizer to
  ;; resolve workflow input before reading the task artifact. The full grammar is
  ;; locked by `normalize-open-task-path-test` in routing_test.clj; this test
  ;; keeps one positive and one disallowed-path boundary at the operation seam.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx sid]
      (write-design-steps! worktree "munera/open/230-x"
                           "- [ ] SCOPE_QUESTION: open one\n")
      (testing "bare anchored NNN-slug token reaches the normalized munera/open task"
        (is (= "SCOPE_QUESTION_OPEN"
               (:data (invoke-gate ctx (direct-invocation ctx sid "230-x"))))))
      (testing "closed task paths fail open instead of reading a disallowed artifact"
        (write-design-steps! worktree "munera/closed/230-x"
                             "- [ ] SCOPE_QUESTION: disallowed closed artifact\n")
        (is (= "DONE"
               (:data (invoke-gate ctx (direct-invocation ctx sid "munera/closed/230-x")))))))))

(deftest gate-malformed-args-error-test
  ;; Malformed args (missing/non-string) hard-fail with :status :error.
  (test-support/with-temp-worktree-session
    (fn [_worktree ctx sid]
      (let [result (invoke-gate ctx {:ctx ctx
                                     :session-id sid
                                     :args (dissoc default-args :marker)})]
        (is (= :error (:status result)) (pr-str result))
        (is (= :invalid-scope-question-gate-args (:reason result)) (pr-str result))))))

(deftest gate-judge-path-resolves-parent-session-test
  ;; DI-3 test/prod divergence guard. Rather than hand-rolling the judge
  ;; invocation key set (which could silently drift from production), drive the
  ;; gate through the REAL `:invoke`-step judge entry point
  ;; (workflow-judge/execute-judge! → execute-invoke-judge!). That production
  ;; code builds the invocation map inline with :parent-session-id and NO
  ;; :session-id; if that key set changes, this test moves with it. The gate
  ;; must resolve the worktree from :parent-session-id and still fire.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx parent-sid]
      (register-gate! ctx)
      (write-design-steps! worktree "munera/open/230-x"
                           "- [ ] SCOPE_QUESTION: judge-path concern\n")
      (let [judge-spec {:type :invoke
                        :operation gate-operation-id
                        :args (assoc default-args :task-path "munera/open/230-x")}
            routing-table {"DONE" {:goto "check-design-review-status"}
                           "SCOPE_QUESTION_OPEN" {:goto "final-summary-scope-question-open"}}
            routing-context {:current-step-id "check-scope-question-status"
                             :step-order ["check-scope-question-status"
                                          "final-summary-scope-question-open"]
                             :step-runs {}}
            result (workflow-judge/execute-judge!
                    ctx parent-sid nil judge-spec routing-table routing-context)
            op-result (get-in result [:judge-output :routing-result])]
      ;; The gate fired through the real judge path (worktree resolved from
      ;; :parent-session-id), naming the open question and routing to handback.
        (is (= "SCOPE_QUESTION_OPEN" (:judge-event result)) (pr-str result))
        (is (= :ok (:status op-result)) (pr-str result))
        (is (= ["judge-path concern"]
               (get-in op-result [:details :open-questions]))
            (pr-str result))
        (is (= {:action :goto :target "final-summary-scope-question-open"}
               (:routing-result result))
            (pr-str result))))))
