(ns psi.workflow-loader.task-209-workflow-definitions-test
  "Loader/compiler tests for the task-209 incidental-complexity workflows
   (reduce-incidental-complexity and related lifecycle wrappers).

   Split out of workflow-definitions-test (R6) to keep that shared ns under the
   800-line components/ length guard. These tests share a small set of loader
   fixtures (load-edn-only + step helpers) duplicated here; they assert no load
   errors, step counts/names/types, :vars wiring, :prompt-string/:context
   handoff plumbing, and the prompt-level behavioural contracts (NO_TARGET
   short-circuit, two-phase refactor gate, no-push/PR endpoint).

   The shared loader fixtures (load-edn-only + step helpers) live in
   psi.workflow-loader.workflow-test-support, single-sourced with
   workflow-definitions-test (CS3)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.workflow-test-support
    :refer [load-edn-only
            slurp-workflow-file
            step-has-input-var-wired?
            step-template-text
            with-workflow-dir]]))

;;; ---------------------------------------------------------------------------
;;; task-lifecycle-in-worktree (Slice 2 of task 209)

(deftest task-lifecycle-in-worktree-test
  (load-edn-only
   "task-lifecycle-in-worktree.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "task-lifecycle-in-worktree")))
     (let [steps (get-in definitions ["task-lifecycle-in-worktree" :steps])
           step-by-name (into {} (map (juxt :name identity) steps))
           resolve-step (get step-by-name "resolve-worktree")
           lifecycle-step (get step-by-name "lifecycle")
           summary-step (get step-by-name "summary")]
       (testing "is a three-step resolve-worktree -> lifecycle -> summary adapter"
         (is (= 3 (count steps)))
         (is (= ["resolve-worktree" "lifecycle" "summary"] (mapv :name steps)))
         (is (= [:session :delegate :session] (mapv :type steps))))
       (testing "resolve-worktree :session step includes the work-on tool and {{input}} wiring"
         (is (some #{"work-on"} (:tools resolve-step))
             "resolve-worktree tools include work-on")
         (is (step-has-input-var-wired? resolve-step)
             "resolve-worktree has {{input}} wired to :workflow-input"))
       (testing "lifecycle :delegate targets task-lifecycle with :input from resolve-worktree :yield :text"
         (is (= :delegate (:type lifecycle-step)))
         (is (= "task-lifecycle" (:target lifecycle-step)))
         (is (= {:type :map
                 :fields {:input {:from {:step "resolve-worktree" :yield :text}}}}
                (:prompt-string lifecycle-step))))
       ;; TR14 (pass 12, test-shaper): lock the wrapper lifecycle delegate's
       ;; :context — deliberately ONLY :workflow-original, NOT the resolve-worktree
       ;; yield (inner task-lifecycle reads the path via :prompt-string :input;
       ;; re-injecting the handoff would pollute the context). Mirrors
       ;; task-lifecycle-test + TR13. A regress adding {:step "resolve-worktree"
       ;; :yield :text} or dropping :workflow-original previously passed green.
       (testing "lifecycle :delegate :context is only :workflow-original (no prior-step yield) (TR14)"
         (is (= [{:type :source :from :workflow-original}]
                (:context lifecycle-step))))
       (testing "trailing summary :session step is present (terminal user-facing summary)"
         (is (some? summary-step))
         (is (= :session (:type summary-step)))
         (is (seq (:contributions summary-step))))
       ;; F1 (implementation review): early-stop is prompt-only and the grammar
       ;; has no conditional/skip step, so a no-target handoff must be detected
       ;; and short-circuited at the prompt level in both session steps.
       (testing "resolve-worktree prompt short-circuits a no-target handoff without calling work-on"
         (let [resolve-text (step-template-text resolve-step)]
           (is (.contains resolve-text "NO_TARGET")
               "resolve-worktree emits the NO_TARGET sentinel on a no-target handoff")
           (is (re-find #"(?i)do not call `?work-on" resolve-text)
               "resolve-worktree does not call work-on when no handoff fields are present")))
       (testing "summary prompt detects NO_TARGET and reports a clean nothing-to-do result"
         (let [summary-text (step-template-text summary-step)]
           (is (.contains summary-text "NO_TARGET")
               "summary detects the NO_TARGET sentinel from resolve-worktree")
           (is (some? (some #(= {:step "resolve-worktree" :yield :text}
                                (:from %))
                            (:contributions summary-step)))
               "summary sources the resolve-worktree :yield :text so it can detect NO_TARGET")))
       ;; TR19 (pass 15 — test-shaper): lock the summary's *substantive* NO_TARGET
       ;; contract (symmetric to TR10's positive-path lock). The .contains
       ;; "NO_TARGET" assertion above proves only that the sentinel is mentioned;
       ;; a regress that detects the sentinel but still inspects/invents task
       ;; artifacts (or reports lifecycle outcomes) on a no-target run passes
       ;; green. Lock the no-target behavioural contract substrings.
       (testing "summary prompt reports the substantive NO_TARGET contract (TR19)"
         (let [summary-text (step-template-text summary-step)]
           (is (.contains summary-text "ignore the `lifecycle` step output entirely")
               "summary ignores the lifecycle output entirely on a no-target run")
           (is (.contains summary-text
                          "no worktree was created, no task was created, and no lifecycle ran")
               "summary reports that no worktree/task/lifecycle occurred on a no-target run")
           (is (.contains summary-text "Do not inspect or invent task artifacts")
               "summary does not inspect or invent task artifacts on a no-target run")))
       ;; TR10 (pass 8): lock the summary's positive-path terminal contract
       ;; (symmetric to TR7). On a real munera/... path summary inspects the task
       ;; artifacts and reports the design → plan → implement → review run, the
       ;; artifacts updated, and the closed/open outcome, sourcing lifecycle yield.
       (testing "summary prompt reports the positive-path lifecycle terminal contract (TR10)"
         (let [summary-text (step-template-text summary-step)]
           (is (re-find #"(?i)independently inspect that specific task" summary-text)
               "summary independently inspects the resolved task's artifacts on a target-present run")
           (is (.contains summary-text "completed cleanly (design → plan → implement → review)")
               "summary reports whether the task-lifecycle run completed cleanly (design → plan → implement → review)")
           (is (re-find #"(?i)task artifact files updated" summary-text)
               "summary reports the task artifact files updated")
           (is (re-find #"(?i)closed \(moved to munera/closed/\) or remains open" summary-text)
               "summary reports whether the task was closed or remains open")))
       (testing "summary sources the lifecycle step :yield :text to report lifecycle outcomes (TR10)"
         (is (some? (some #(= {:step "lifecycle" :yield :text}
                              (:from %))
                          (:contributions summary-step)))
             "summary sources the lifecycle :yield :text so it can report the lifecycle run outcomes"))
       ;; TR7: lock the positive (target-present) branch — the cross-:delegate
       ;; worktree-continuity mechanism (Locked decision 11): the wrapper
       ;; re-calls work-on before sub-delegating. The NO_TARGET-only locks above
       ;; let a regress dropping this pass green.
       (testing "resolve-worktree prompt re-calls work-on and yields only the task path on a target-present handoff (TR7)"
         (let [resolve-text (step-template-text resolve-step)]
           (is (re-find #"(?i)call `?work-on`? with the extracted worktree path"
                        resolve-text)
               "resolve-worktree calls work-on with the extracted worktree path when both handoff fields are present")
           (is (re-find #"(?i)respond with ONLY the Munera task path" resolve-text)
               "resolve-worktree yields only the bare Munera task path on the positive path")
           (is (re-find #"(?i)on a single line" resolve-text)
               "resolve-worktree constrains the positive-path yield to a single line")))
       ;; TT-N (test review pass 31 — task-test-review): lock the wrapper's
       ;; CONSUMER side of the worktree handoff field-name contract. The
       ;; `reduce-incidental-complexity` step-1 EMITS `worktree_path:` +
       ;; `munera_task_path:` (locked in `reduce-incidental-complexity-test`) and
       ;; this `resolve-worktree` step EXTRACTS them (calls work-on from the
       ;; threaded `worktree_path:`, yields the `munera_task_path:` value). Only
       ;; the emit side was locked; F1/TR7 assert generic prose on `resolve-text`
       ;; but never the literal field tokens. A regress renaming the extracted
       ;; tokens (`worktree_path:` -> `worktree:`, `munera_task_path:` ->
       ;; `task_path:`) breaks the live cross-:delegate worktree handoff (Locked
       ;; decision 11) yet passes every existing wrapper test green. Symmetric to
       ;; the producer-side `select-text` lock in reduce-incidental-complexity-test.
       (testing "resolve-worktree prompt extracts the literal handoff field tokens (TT-N consumer side)"
         (let [resolve-text (step-template-text resolve-step)]
           (is (.contains resolve-text "worktree_path:")
               "resolve-worktree references the literal worktree_path: handoff field token")
           (is (.contains resolve-text "munera_task_path:")
               "resolve-worktree references the literal munera_task_path: handoff field token")))))))

;;; ---------------------------------------------------------------------------
;;; reduce-incidental-complexity (Slice 3 of task 209)

(deftest reduce-incidental-complexity-test
  (load-edn-only
   "reduce-incidental-complexity.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "reduce-incidental-complexity")))
     (let [steps (get-in definitions ["reduce-incidental-complexity" :steps])
           step-by-name (into {} (map (juxt :name identity) steps))
           select-step (get step-by-name "select-and-create")
           review-design-step (get step-by-name "review-task-design")
           create-plan-step (get step-by-name "create-task-plan")
           review-plan-step (get step-by-name "review-task-plan")
           clean-baseline-step (get step-by-name "clean-baseline")
           coverage-review-step (get step-by-name "coverage-review")
           coverage-disposition-step (get step-by-name "coverage-disposition")
           coverage-fix-step (get step-by-name "coverage-fix")
           diff-gate-step (get step-by-name "diff-gate")
           implement-step (get step-by-name "implement-task")
           implementation-review-step (get step-by-name "review-task-implementation")
           final-summary-step (get step-by-name "final-summary")
           terminal-stop-step (get step-by-name "terminal-stop-summary")
           select-text (step-template-text select-step)
           clean-text (step-template-text clean-baseline-step)
           coverage-review-text (step-template-text coverage-review-step)
           disposition-text (step-template-text coverage-disposition-step)
           coverage-fix-text (step-template-text coverage-fix-step)
           diff-gate-text (step-template-text diff-gate-step)
           final-summary-text (step-template-text final-summary-step)
           terminal-stop-text (step-template-text terminal-stop-step)]
       (testing "expands target-present execution into explicit phased topology"
         (is (= ["select-and-create"
                 "review-task-design"
                 "create-task-plan"
                 "review-task-plan"
                 "clean-baseline"
                 "coverage-review"
                 "coverage-disposition"
                 "coverage-fix"
                 "diff-gate"
                 "implement-task"
                 "review-task-implementation"
                 "final-summary"
                 "terminal-stop-summary"]
                (mapv :name steps)))
         (is (= [:session
                 :delegate
                 :delegate
                 :delegate
                 :session
                 :session
                 :session
                 :session
                 :session
                 :delegate
                 :delegate
                 :session
                 :session]
                (mapv :type steps))))
       (testing "select-and-create carries the current-worktree tools + design-named skills"
         (is (= ["read" "bash" "edit" "write"] (:tools select-step)))
         (is (not (some #{"work-on"} (:tools select-step))))
         (is (some #{"incidental-complexity-finder"} (:skills select-step)))
         (is (some #{"gordian"} (:skills select-step)))
         (is (some #{"code-shaper"} (:skills select-step))))
       (testing "select-and-create wires {{input}} to the bare :workflow-input"
         (let [tmpl (first (filter #(= :template (:type %))
                                   (:contributions select-step)))]
           (is (.contains (:text tmpl) "{{input}}"))
           (is (= {:from :workflow-input}
                  (get-in tmpl [:vars "input"])))))
       (testing "select-and-create routes no-target directly to done and target-present into design review"
         (is (= {:type :invoke
                 :operation "workflow/pass-status-routing"
                 :args {:text {:from {:step "select-and-create" :output :final-llm-reply}}
                        :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}
                (:judge select-step)))
         (is (= {"DONE" {:goto "review-task-design"}
                 "REPEAT" {:goto :done}}
                (:on select-step))
             "REVIEW_COMPLETE/DONE starts explicit lifecycle; ACTIONABLE_FEEDBACK/REPEAT stops no-target")
         (is (.contains select-text "PASS_STATUS: REVIEW_COMPLETE"))
         (is (.contains select-text "PASS_STATUS: ACTIONABLE_FEEDBACK"))
         (is (.contains select-text "skips every downstream design/plan/test-net/implementation step"))
         (is (not-any? #(= "task-lifecycle" (:target %)) steps)
             "target-present path no longer delegates to opaque task-lifecycle"))
       (testing "explicit lifecycle delegates target the reusable sub-workflows"
         (is (= "review-task-design" (:target review-design-step)))
         (is (= "create-task-plan" (:target create-plan-step)))
         (is (= "review-task-plan" (:target review-plan-step)))
         (is (= "implement-task" (:target implement-step)))
         (is (= "review-task-implementation" (:target implementation-review-step)))
         (doseq [step [review-design-step create-plan-step review-plan-step
                       implement-step implementation-review-step]]
           (is (= {:type :map
                   :fields {:input {:from {:step "select-and-create" :yield :text}}}}
                  (:prompt-string step)))))
       (testing "select-and-create prompt preserves task-209 selection and baseline contracts"
         (is (.contains select-text "munera_task_path:"))
         (is (.contains select-text "inherited session worktree"))
         (is (.contains select-text "worktree_path:` as informational context"))
         (is (re-find #"(?i)no unit qualif" select-text))
         (is (.contains select-text "Do NOT create or switch worktrees"))
         (is (.contains select-text "Do NOT create a task"))
         (is (.contains select-text "bb gordian local --json"))
         (is (.contains select-text "bb gordian diagnose --edn"))
         (is (.contains select-text "before-local.json"))
         (is (.contains select-text "before-diagnose.edn"))
         (is (.contains select-text "--baseline munera/open/NNN-slug/before-diagnose.edn"))
         (is (.contains select-text
                        "--fail-on new-cycles,new-high-findings --max-new-medium-findings 0"))
         (is (.contains select-text
                        "These tests must be GREEN against the unmodified code before any refactoring begins"))
         (is (.contains select-text "No refactor proceeds without a green net"))
         (is (.contains select-text
                        "behaviour is identical — meta/spec are unchanged; existing test expectations are not weakened"))
         (is (.contains select-text "keyed by `(ns, var, arity, line)`"))
         (is (.contains select-text "identified by `(ns, var, arity, line)`"))
         (is (.contains select-text
                        "Blast radius: the target unit PLUS the minimal surrounding helpers required to decomplect it; no unrelated cleanup"))
         (is (.contains select-text "decreased versus its `before-local.json` value"))
         (is (.contains select-text "after total is strictly less than the before total"))
         (is (.contains select-text
                        "the set is computed from the metric, not from the diff/touched files"))
         (is (.contains select-text "Commit the task creation on the current branch"))
         (is (.contains select-text "Do NOT push or open a PR")))
       (testing "clean-baseline step locks the clean-source precondition and baseline artifact contract"
         (is (= ["read" "bash" "edit" "write"] (:tools clean-baseline-step)))
         (is (= {"DONE" {:goto "coverage-review"}
                 "REPEAT" {:goto "terminal-stop-summary"}}
                (:on clean-baseline-step)))
         (is (.contains clean-text "characterization-baseline.edn"))
         (is (.contains clean-text "target/source paths are not already dirty"))
         (is (.contains clean-text "append a durable failure finding to task artifacts"))
         (is (.contains clean-text "pre-existing dirty target/source changes must be appended as a durable failure finding"))
         (is (.contains clean-text "missing-path or dirty-path failure finding has been recorded in task artifacts and committed"))
         (is (.contains clean-text "recorded git `HEAD`"))
         (is (.contains clean-text "git status --short"))
         (is (.contains clean-text "target/source paths identified by the task"))
         (is (.contains clean-text "explicitly classified pre-existing task-artifact-or-doc dirt"))
         (is (.contains clean-text "Do NOT call `work-on`")))
       (testing "coverage review is the pre-simplification characterization gate"
         (is (= ["read" "bash" "edit" "write"] (:tools coverage-review-step))
             "coverage-review can write required coverage/status records to task artifacts")
         (is (some #{"task-test-review"} (:skills coverage-review-step)))
         (is (some #{"testing-without-mocks"} (:skills coverage-review-step)))
         (is (= {"DONE" {:goto "diff-gate"}
                 "REPEAT" {:goto "coverage-disposition"}}
                (:on coverage-review-step)))
         (is (.contains coverage-review-text "pre-simplification characterization-test net"))
         (is (.contains coverage-review-text "Do NOT perform simplification or refactor work"))
         (is (.contains coverage-review-text "nominal, edge, and boundary"))
         (is (.contains coverage-review-text "externally observable state or outputs"))
         (is (.contains coverage-review-text
                        "avoid interaction assertions unless the interaction is itself the observable behavior"))
         (is (.contains coverage-review-text "green against the unmodified target behavior"))
         (is (.contains coverage-review-text "commit the task-artifact update"))
         (is (.contains coverage-review-text "new latest characterization-status note"))
         (is (.contains coverage-review-text "mention that marker and artifact path in the final response body"))
         (is (.contains coverage-review-text "CHARACTERIZATION_STATUS: FIXABLE_GAPS"))
         (is (.contains coverage-review-text "CHARACTERIZATION_STATUS: INFEASIBLE")))
       (testing "coverage disposition separates fixable gaps from infeasible characterization"
         (is (= ["read" "bash" "edit" "write"] (:tools coverage-disposition-step))
             "coverage-disposition can write durable stop findings for terminal failures")
         (is (= {"DONE" {:goto "coverage-fix"}
                 "REPEAT" {:goto "terminal-stop-summary"}}
                (:on coverage-disposition-step)))
         (is (.contains disposition-text "CHARACTERIZATION_STATUS: FIXABLE_GAPS"))
         (is (.contains disposition-text "PASS_STATUS: REVIEW_COMPLETE"))
         (is (.contains disposition-text "CHARACTERIZATION_STATUS: INFEASIBLE"))
         (is (.contains disposition-text "PASS_STATUS: ACTIONABLE_FEEDBACK"))
         (is (.contains disposition-text "Read the preceding coverage-review output first"))
         (is (.contains disposition-text "Task artifacts are append-only"))
         (is (.contains disposition-text "stale historical markers are non-authoritative"))
         (is (.contains disposition-text "immediately preceding coverage-review result"))
         (is (.contains disposition-text "use only the latest committed characterization-status note"))
         (is (.contains disposition-text "both markers appear in the immediately preceding output/latest note"))
         (is (.contains disposition-text "only historical markers are found"))
         (is (.contains disposition-text "durable coverage-disposition stop finding"))
         (is (.contains disposition-text "commit that task-artifact update"))
         (is (.contains disposition-text "stop reason must be recorded in committed task artifacts"))
         (is (.contains disposition-text "terminal-stop-summary` can explain the stop without relying on ephemeral coverage-disposition child-session output"))
         (is (.contains disposition-text "Do not scan all task artifacts for any marker as the routing source"))
         (is (.contains disposition-text "Do not let stale `FIXABLE_GAPS` or stale `INFEASIBLE` records override")))
       (testing "coverage-fix is constrained to characterization tests and minimal seams, then loops"
         (is (= ["read" "bash" "edit" "write"] (:tools coverage-fix-step)))
         (is (some #{"clojure-coding-standards"} (:skills coverage-fix-step)))
         (is (some #{"testing-without-mocks"} (:skills coverage-fix-step)))
         (is (= {"DONE" {:goto "coverage-review"}}
                (:on coverage-fix-step)))
         (is (.contains coverage-fix-text "characterization tests"))
         (is (.contains coverage-fix-text "explicitly justified minimal testability seams"))
         (is (.contains coverage-fix-text "Do NOT simplify, refactor, decomplect, rename, extract"))
         (is (.contains coverage-fix-text "Do NOT weaken existing expectations"))
         (is (.contains coverage-fix-text "Do NOT make broad production edits"))
         (is (.contains coverage-fix-text "Commit the characterization-fix changes"))
         (is (.contains coverage-fix-text "Do NOT call `work-on`")))
       (testing "diff gate blocks implementation unless coverage-phase diff is classified cleanly"
         (is (= ["read" "bash" "edit" "write"] (:tools diff-gate-step))
             "diff-gate can write required diff classification/stop findings to task artifacts")
         (is (= {"DONE" {:goto "implement-task"}
                 "REPEAT" {:goto "terminal-stop-summary"}}
                (:on diff-gate-step)))
         (is (.contains diff-gate-text "before `implement-task`"))
         (is (.contains diff-gate-text "characterization-baseline.edn"))
         (is (.contains diff-gate-text "dirty target/source paths at baseline time"))
         (is (.contains diff-gate-text "git diff <baseline-head>...HEAD"))
         (is (.contains diff-gate-text "current uncommitted worktree status/diff"))
         (is (.contains diff-gate-text
                        "Coverage-fix commits must not hide coverage-phase edits behind an empty uncommitted `git diff`"))
         (is (.contains diff-gate-text
                        "Allowed categories are only: characterization tests, task artifacts, docs, and explicitly justified minimal testability seams"))
         (is (.contains diff-gate-text "unclassified source/target change"))
         (is (.contains diff-gate-text "broad production edit"))
         (is (.contains diff-gate-text "premature simplification/refactor"))
         (is (.contains diff-gate-text "CHARACTERIZATION_STATUS: INFEASIBLE"))
         (is (.contains diff-gate-text "commit the task-artifact update"))
         (is (.contains diff-gate-text "PASS_STATUS: REVIEW_COMPLETE"))
         (is (.contains diff-gate-text "PASS_STATUS: ACTIONABLE_FEEDBACK")))
       (testing "implementation and summaries preserve target-present and no-target terminal contracts"
         (is (.contains final-summary-text
                        "design → plan → characterization-test-net gate → baseline/diff gate → simplification implementation → implementation review"))
         (is (= {"DONE" {:goto :done}}
                (:on final-summary-step))
             "successful target-present final summary stops before terminal-stop-summary")
         (is (.contains terminal-stop-text
                        "No-target runs route directly from `select-and-create` to workflow completion and must not run this step"))
         (is (.contains terminal-stop-text "dirty baseline"))
         (is (.contains terminal-stop-text "infeasible characterization"))
         (is (.contains terminal-stop-text "failed baseline/diff classification"))
         (is (.contains terminal-stop-text
                        "Do not claim `implement-task`, simplification, implementation review, push, or PR creation occurred")))))))

;;; ---------------------------------------------------------------------------
;;; Reference-chain resolution (TT-K)

;; TT-K/task-212: the isolated tests above assert only delegate :target *string
;; equality* while loading a single EDN — the loader does NOT validate delegate
;; targets at load time, so those asserts give no resolution guarantee. Co-load
;; the direct delegate set and assert each target is a registered workflow.
;; TT1 strengthens this from synthetic stubs to the real directly referenced
;; workflow EDNs plus their required prompt-workflow markdown files, so missing
;; or renamed real workflow/prompt assets fail here instead of being hidden by a
;; stub corpus.
(deftest task-209-workflow-set-loads-together-test
  (let [target-names ["review-task-design"
                      "create-task-plan"
                      "review-task-plan"
                      "implement-task"
                      "review-task-implementation"]
        workflow-filenames ["reduce-incidental-complexity.edn"
                            "review-task-design.edn"
                            "create-task-plan.edn"
                            "review-task-plan.edn"
                            "implement-task.edn"
                            "review-task-implementation.edn"]
        prompt-filenames ["review-task-design-architecture-review.md"
                          "review-task-design-ambiguity-review.md"
                          "review-task-design-inconsistency-review.md"
                          "review-follow-up-design.md"
                          "create-task-plan-create-plan.md"
                          "review-task-plan-ambiguity-review.md"
                          "review-task-plan-inconsistency-review.md"
                          "review-follow-up-steps.md"
                          "implement-task-implement-pass.md"]]
    (with-workflow-dir
      (into {}
            (map (fn [filename]
                   [filename (slurp-workflow-file filename)]))
            (concat workflow-filenames prompt-filenames))
      (fn [{:keys [definitions errors]}]
        (testing "the task-209/212 real delegate set loads together without compilation errors"
          (is (empty? errors))
          (is (contains? definitions "reduce-incidental-complexity"))
          (doseq [workflow target-names]
            (is (contains? definitions workflow))))
        (testing "reduce-incidental-complexity direct delegate targets resolve to registered real workflows"
          (let [outer-targets (->> (get-in definitions
                                           ["reduce-incidental-complexity" :steps])
                                   (keep #(when (= :delegate (:type %))
                                            (:target %)))
                                   set)]
            (is (= (set target-names) outer-targets))
            (doseq [target outer-targets]
              (is (contains? definitions target)
                  (str "delegate target resolves: " target)))))))))
