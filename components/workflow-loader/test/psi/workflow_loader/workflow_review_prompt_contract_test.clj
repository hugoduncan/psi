(ns psi.workflow-loader.workflow-review-prompt-contract-test
  "Prompt-body contract tests for the merged design/plan review workflows.

   Locks the shared-session loader/reuser framing of the review prompts and the
   batch-follow-up evidence rules of the design/plan follow-up profiles. Split
   from workflow-definitions-test to keep each test file within the file-length
   gate; the shared loader seam (slurp-workflow-file) is single-sourced in
   psi.workflow-loader.workflow-test-support."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.workflow-test-support
    :refer [slurp-workflow-file]]))

(defn- terminal-review-pass-status-menu?
  [content]
  (boolean
   (re-find #"(?s)End your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: REVIEW_COMPLETE\s*\z"
            content)))

(deftest review-task-design-prompt-contract-test
  ;; Locks the merged design-review prompt contracts: the first turn loads the
  ;; shared context and later turns reuse it by default, with only targeted
  ;; re-reads. All review prompts must end with the same terminal PASS_STATUS menu.
  (let [architecture (slurp-workflow-file "review-task-design-architecture-review.md")
        ambiguity (slurp-workflow-file "review-task-design-ambiguity-review.md")
        inconsistency (slurp-workflow-file "review-task-design-inconsistency-review.md")]
    (testing "architecture prompt is the shared-session loader"
      (doseq [needle ["first turn of the shared `design-review` multi-prompt session"
                      "Read the task's design.md"
                      "AGENTS.md"
                      "ramora/META.md"
                      "doc/architecture.md"
                      "loads the task design and architecture context"]]
        (is (.contains architecture needle) needle))
      (is (.contains architecture "review-task-architecture")
          "architecture-review prompt loads the architecture review skill"))
    (testing "ambiguity prompt reuses prior shared-session context by default"
      (doseq [needle ["second turn of the shared `design-review` multi-prompt session"
                      "Use the already-loaded task design.md, architecture sources, and architecture-review reply"
                      "Perform only targeted re-reads when specific referenced material is missing from context, ambiguous, or plausibly stale"
                      "do not unconditionally re-read the whole task design and architecture source set"]]
        (is (.contains ambiguity needle) needle)))
    (testing "inconsistency prompt reuses both prior review replies by default"
      (doseq [needle ["third turn of the shared `design-review` multi-prompt session"
                      "Use the already-loaded task design.md, architecture sources, architecture-review reply, and ambiguity-review reply"
                      "Perform only targeted re-reads for specific missing or stale referenced material needed to decide an inconsistency"
                      "do not unconditionally re-read the whole task design and architecture source set"]]
        (is (.contains inconsistency needle) needle)))
    (testing "all design-review prompts end with the exact terminal PASS_STATUS menu"
      (doseq [[label content] {"architecture" architecture
                               "ambiguity" ambiguity
                               "inconsistency" inconsistency}]
        (is (terminal-review-pass-status-menu? content)
            (str label " prompt must end with the contiguous two-line PASS_STATUS menu"))))))

(deftest review-follow-up-design-prompt-contract-test
  ;; Locks AC3's batch-follow-up evidence rule for the merged design-review
  ;; workflow. These assertions intentionally name the git/task-file mechanics so
  ;; the prompt cannot regress to broad "new unchecked item" selection.
  (let [content (slurp-workflow-file "review-follow-up-design.md")]
    (testing "defines the preceding pass as the whole merged review batch"
      (doseq [needle ["immediately preceding whole `design-review` batch"
                      "architecture, ambiguity, and inconsistency review prompts run back-to-back"
                      "spanning all three review prompts in that immediately preceding batch"]]
        (is (.contains content needle) needle)))
    (testing "identifies the contiguous review-batch segment and parent baseline"
      (doseq [needle ["Identify the contiguous latest task-scoped review-batch segment"
                      "since the previous design-follow-up completion"
                      "Use the parent of the oldest commit in that segment as the batch baseline"
                      "git diff <baseline>..HEAD -- <task>/design-steps.md"]]
        (is (.contains content needle) needle)))
    (testing "limits candidates to current unchecked design-step checklist lines added by that diff"
      (doseq [needle ["candidate work set is exactly the checklist lines added by that diff"
                      "match unchecked design-step items"
                      "still exist unchecked in design-steps.md at follow-up start"]]
        (is (.contains content needle) needle)))
    (testing "excludes stale, pre-existing, checked, and non-design-step items"
      (doseq [needle ["Do not execute unchecked items that predate the preceding review pass"
                      "edited stale items whose addition cannot be attributed to the just-finished batch"
                      "checked items"
                      "items from steps.md"]]
        (is (.contains content needle) needle)))
    (testing "blocks rather than guessing when evidence is ambiguous or unattributable"
      (doseq [needle ["If the review-batch segment or baseline cannot be identified confidently"
                      "cannot be matched unambiguously to a current unchecked item"
                      "leave the item unchecked"
                      "record the blocking reason tersely in implementation.md rather than guessing"]]
        (is (.contains content needle) needle)))))

(deftest review-task-plan-prompt-contract-test
  ;; Locks the merged plan-review prompt contracts: the first turn loads the
  ;; shared plan context and the later turn reuses it by default, with only
  ;; targeted re-reads. Both review prompts must end with the same terminal
  ;; PASS_STATUS menu.
  (let [ambiguity (slurp-workflow-file "review-task-plan-ambiguity-review.md")
        inconsistency (slurp-workflow-file "review-task-plan-inconsistency-review.md")]
    (testing "ambiguity prompt is the shared-session loader"
      (doseq [needle ["first turn of the shared `plan-review` multi-prompt session"
                      "Read the task artifacts, especially plan.md, steps.md, and implementation.md"
                      "loads the task plan context"]]
        (is (.contains ambiguity needle) needle)))
    (testing "inconsistency prompt reuses prior shared-session context by default"
      (doseq [needle ["second turn of the shared `plan-review` multi-prompt session"
                      "Use the already-loaded task plan.md, steps.md, implementation.md, and ambiguity-review reply"
                      "Perform only targeted re-reads for specific missing or stale referenced material needed to decide an inconsistency"
                      "do not unconditionally re-read the whole task plan and referenced source set"]]
        (is (.contains inconsistency needle) needle)))
    (testing "all plan-review prompts end with the exact terminal PASS_STATUS menu"
      (doseq [[label content] {"ambiguity" ambiguity
                               "inconsistency" inconsistency}]
        (is (terminal-review-pass-status-menu? content)
            (str label " prompt must end with the contiguous two-line PASS_STATUS menu"))))))

(deftest review-follow-up-plan-prompt-contract-test
  ;; Locks the batch-follow-up evidence rule for the merged plan-review
  ;; workflow. These assertions name the git/task-file mechanics so the prompt
  ;; cannot regress to broad "new unchecked item" selection, and confirm it
  ;; stays a plan/steps profile (steps.md items, design.md read-only).
  (let [content (slurp-workflow-file "review-follow-up-plan.md")]
    (testing "defines the preceding pass as the whole merged review batch"
      (doseq [needle ["immediately preceding whole `plan-review` batch"
                      "ambiguity and inconsistency review prompts run back-to-back"
                      "spanning both review prompts in that immediately preceding batch"]]
        (is (.contains content needle) needle)))
    (testing "identifies the contiguous review-batch segment and parent baseline"
      (doseq [needle ["Identify the contiguous latest task-scoped review-batch segment"
                      "since the previous plan-follow-up completion"
                      "Use the parent of the oldest commit in that segment as the batch baseline"
                      "git diff <baseline>..HEAD -- <task>/steps.md"]]
        (is (.contains content needle) needle)))
    (testing "limits candidates to current unchecked step checklist lines added by that diff"
      (doseq [needle ["candidate work set is exactly the checklist lines added by that diff"
                      "match unchecked step items"
                      "still exist unchecked in steps.md at follow-up start"]]
        (is (.contains content needle) needle)))
    (testing "excludes stale, pre-existing, and checked items"
      (doseq [needle ["Do not execute unchecked items that predate the preceding review pass"
                      "edited stale items whose addition cannot be attributed to the just-finished batch"
                      "checked items"]]
        (is (.contains content needle) needle)))
    (testing "stays a plan/steps profile: steps.md items, design.md read-only, code/tests/docs writable"
      (is (.contains content "design.md as read-only context"))
      (is (.contains content "code, tests, and docs"))
      (is (not (.contains content "design-steps.md"))))
    (testing "blocks rather than guessing when evidence is ambiguous or unattributable"
      (doseq [needle ["If the review-batch segment or baseline cannot be identified confidently"
                      "cannot be matched unambiguously to a current unchecked item"
                      "leave the item unchecked"
                      "record the blocking reason tersely in implementation.md rather than guessing"]]
        (is (.contains content needle) needle)))))
