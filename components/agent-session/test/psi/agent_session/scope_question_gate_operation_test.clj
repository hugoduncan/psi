(ns psi.agent-session.scope-question-gate-operation-test
  "Invocation tests for the workflow/scope-question-gate-routing deterministic
   operation. Exercises the real handler through the registry boundary on both
   the direct-invoke key set (`:session-id`) and the production `:invoke`-step
   judge key set (`:parent-session-id`, no `:session-id`) — DI-3."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.deterministic-operation-registry.registry :as registry]
   [psi.deterministic-operation-runtime.core :as runtime]))

(def ^:private gate-operation-id "workflow/scope-question-gate-routing")

(def ^:private default-args
  {:artifact "design-steps.md"
   :marker "SCOPE_QUESTION:"
   :proceed-route "DONE"
   :open-route "SCOPE_QUESTION_OPEN"})

(defn- temp-dir!
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "psi-scope-gate-" (System/nanoTime)))]
    (.mkdirs dir)
    dir))

(defn- write-design-steps!
  [worktree task-dir content]
  (let [dir (io/file worktree task-dir)]
    (.mkdirs dir)
    (spit (io/file dir "design-steps.md") content)))

(defn- session-with-worktree!
  [worktree]
  (test-support/create-test-session
   {:persist? false
    :session-defaults {:worktree-path (str worktree)}}))

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

(defn- direct-invocation
  [ctx session-id task-path]
  {:ctx ctx
   :session-id session-id
   :args (assoc default-args :task-path task-path)})

(defn- judge-invocation
  "Mirror the workflow `:invoke`-step judge invocation key set: `:ctx` +
   `:parent-session-id`, and no `:session-id` (workflow_judge/execute-invoke-judge!)."
  [ctx parent-session-id task-path]
  {:ctx ctx
   :parent-session-id parent-session-id
   :args (assoc default-args :task-path task-path)})

(deftest gate-open-on-unchecked-scope-question-test
  ;; AC-1: an unchecked SCOPE_QUESTION halts (open route) and names the concern.
  (let [worktree (temp-dir!)
        [ctx sid] (session-with-worktree! worktree)]
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
  (let [worktree (temp-dir!)
        [ctx sid] (session-with-worktree! worktree)]
    (write-design-steps! worktree "munera/open/230-x"
                         "- [x] SCOPE_QUESTION: resolved here\n")
    (let [result (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))]
      (is (= "DONE" (:data result)) (pr-str result)))))

(deftest gate-proceeds-on-absent-artifact-test
  ;; AC-2: a task with no design-steps.md proceeds.
  (let [worktree (temp-dir!)
        [ctx sid] (session-with-worktree! worktree)]
    (.mkdirs (io/file worktree "munera/open/230-x"))
    (let [result (invoke-gate ctx (direct-invocation ctx sid "munera/open/230-x"))]
      (is (= "DONE" (:data result)) (pr-str result)))))

(deftest gate-resume-after-item-checked-test
  ;; AC-3: re-invoking after the human checks the item returns DONE.
  (let [worktree (temp-dir!)
        [ctx sid] (session-with-worktree! worktree)]
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
  (let [worktree (temp-dir!)
        [ctx sid] (session-with-worktree! worktree)]
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
  (let [worktree (temp-dir!)
        [ctx sid] (session-with-worktree! worktree)
        result (invoke-gate ctx {:ctx ctx
                                 :session-id sid
                                 :args (dissoc default-args :marker)})]
    (is (= :error (:status result)) (pr-str result))
    (is (= :invalid-scope-question-gate-args (:reason result)) (pr-str result))))

(deftest gate-judge-path-resolves-parent-session-test
  ;; DI-3 test/prod divergence guard: the production judge invocation supplies
  ;; :parent-session-id and NO :session-id. The gate must resolve the worktree
  ;; from :parent-session-id and still fire on an unchecked item.
  (let [worktree (temp-dir!)
        [ctx parent-sid] (session-with-worktree! worktree)]
    (write-design-steps! worktree "munera/open/230-x"
                         "- [ ] SCOPE_QUESTION: judge-path concern\n")
    (let [invocation (judge-invocation ctx parent-sid "munera/open/230-x")]
      (is (not (contains? invocation :session-id))
          "judge invocation carries no :session-id")
      (let [result (invoke-gate ctx invocation)]
        (is (= :ok (:status result)) (pr-str result))
        (is (= "SCOPE_QUESTION_OPEN" (:data result)) (pr-str result))
        (is (= ["judge-path concern"]
               (get-in result [:details :open-questions]))
            (pr-str result))))))
