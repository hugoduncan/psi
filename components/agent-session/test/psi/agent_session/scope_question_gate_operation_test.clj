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
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.deterministic-operation-registry.registry :as registry]
   [psi.deterministic-operation-runtime.core :as runtime]))

;; Worktree/session/artifact ceremony is shared via test-support
;; (`temp-worktree-dir!`, `session-with-worktree!`, `write-task-artifact!`),
;; keeping this file consistent with the task-artifact-content resolver suite.

(def ^:private gate-operation-id "workflow/scope-question-gate-routing")

(def ^:private default-args
  {:artifact "design-steps.md"
   :marker "SCOPE_QUESTION:"
   :proceed-route "DONE"
   :open-route "SCOPE_QUESTION_OPEN"})

(defn- write-design-steps!
  "Write `content` as the task's design-steps.md (the artifact this gate reads)."
  [worktree task-dir content]
  (test-support/write-task-artifact! worktree task-dir "design-steps.md" content))

(defn- invoke-gate
  "Invoke the registered gate operation with a caller-supplied invocation map.
   The handler is registered under its production id so we exercise the real
   registry boundary."
  [ctx invocation]
  (let [reg (:deterministic-operation-registry ctx)]
    (registry/register-operation-in!
     reg {:id gate-operation-id
          :description "scope gate (test registration)"
          :handler workflow-core/scope-question-gate-routing})
    (registry/invoke-operation-in reg gate-operation-id invocation
                                  runtime/invoke-operation)))

(defn- register-gate!
  "Register the real gate handler under its production id in the ctx registry."
  [ctx]
  (registry/register-operation-in!
   (:deterministic-operation-registry ctx)
   {:id gate-operation-id
    :description "scope gate (test registration)"
    :handler workflow-core/scope-question-gate-routing}))

(defn- direct-invocation
  [ctx session-id task-path]
  {:ctx ctx
   :session-id session-id
   :args (assoc default-args :task-path task-path)})

(deftest gate-open-on-unchecked-scope-question-test
  ;; AC-1: an unchecked SCOPE_QUESTION halts (open route) and names the concern.
  (let [worktree (test-support/temp-worktree-dir!)
        [ctx sid] (test-support/session-with-worktree! worktree)]
    (write-design-steps! worktree "munera/open/230-x"
                         "- [ ] SCOPE_QUESTION: bucket-size in reopen identity?\n")
    (let [result (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))]
      (is (= :ok (:status result)) (pr-str result))
      (is (= "SCOPE_QUESTION_OPEN" (:data result)) (pr-str result))
      (is (= ["bucket-size in reopen identity?"]
             (get-in result [:details :open-questions]))
          (pr-str result)))))

(deftest gate-proceeds-on-only-checked-items-test
  ;; AC-2: only-checked items route to proceed.
  (let [worktree (test-support/temp-worktree-dir!)
        [ctx sid] (test-support/session-with-worktree! worktree)]
    (write-design-steps! worktree "munera/open/230-x"
                         "- [x] SCOPE_QUESTION: resolved here\n")
    (let [result (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))]
      (is (= "DONE" (:data result)) (pr-str result)))))

(deftest gate-proceeds-on-absent-artifact-test
  ;; AC-2: a task with no design-steps.md proceeds.
  (let [worktree (test-support/temp-worktree-dir!)
        [ctx sid] (test-support/session-with-worktree! worktree)]
    (.mkdirs (io/file worktree "munera/open/230-x"))
    (let [result (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))]
      (is (= "DONE" (:data result)) (pr-str result)))))

(deftest gate-resume-after-item-checked-test
  ;; AC-3: re-invoking after the human checks the item returns DONE.
  (let [worktree (test-support/temp-worktree-dir!)
        [ctx sid] (test-support/session-with-worktree! worktree)]
    (write-design-steps! worktree "munera/open/230-x"
                         "- [ ] SCOPE_QUESTION: open one\n")
    (is (= "SCOPE_QUESTION_OPEN"
           (:data (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x")))))
    (write-design-steps! worktree "munera/open/230-x"
                         "- [x] SCOPE_QUESTION: open one\n")
    (is (= "DONE"
           (:data (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x")))))))

(deftest gate-task-path-normalization-test
  ;; DI-4: open-only, anchored full-match normalization.
  (let [worktree (test-support/temp-worktree-dir!)
        [ctx sid] (test-support/session-with-worktree! worktree)]
    (write-design-steps! worktree "munera/open/230-x"
                         "- [ ] SCOPE_QUESTION: open one\n")
    (testing "full munera/open/NNN-slug path is used verbatim"
      (is (= "SCOPE_QUESTION_OPEN"
             (:data (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))))))
    (testing "bare anchored NNN-slug token becomes munera/open/<token>"
      (is (= "SCOPE_QUESTION_OPEN"
             (:data (invoke-gate ctx (direct-invocation ctx sid "230-x"))))))
    (testing "a munera/closed/... path fails open (no usable path → proceed)"
      (is (= "DONE"
             (:data (invoke-gate ctx (direct-invocation ctx sid "munera/closed/230-x"))))))
    (testing "free text fails open to proceed"
      (is (= "DONE"
             (:data (invoke-gate ctx (direct-invocation ctx sid "please decide scope"))))))))

(deftest gate-malformed-args-error-test
  ;; Malformed args (missing/non-string) hard-fail with :status :error.
  (let [worktree (test-support/temp-worktree-dir!)
        [ctx sid] (test-support/session-with-worktree! worktree)
        result (invoke-gate ctx {:ctx ctx
                                 :session-id sid
                                 :args (dissoc default-args :marker)})]
    (is (= :error (:status result)) (pr-str result))
    (is (= :invalid-scope-question-gate-args (:reason result)) (pr-str result))))

(deftest gate-judge-path-resolves-parent-session-test
  ;; DI-3 test/prod divergence guard. Rather than hand-rolling the judge
  ;; invocation key set (which could silently drift from production), drive the
  ;; gate through the REAL `:invoke`-step judge entry point
  ;; (workflow-judge/execute-judge! → execute-invoke-judge!). That production
  ;; code builds the invocation map inline with :parent-session-id and NO
  ;; :session-id; if that key set changes, this test moves with it. The gate
  ;; must resolve the worktree from :parent-session-id and still fire.
  (let [worktree (test-support/temp-worktree-dir!)
        [ctx parent-sid] (test-support/session-with-worktree! worktree)]
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
          (pr-str result)))))
