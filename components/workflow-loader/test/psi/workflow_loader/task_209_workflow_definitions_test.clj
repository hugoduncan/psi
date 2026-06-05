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
           delegate-step (get step-by-name "lifecycle")
           select-text (step-template-text select-step)]
       (testing "is a two-step select-and-create (:session) -> lifecycle (:delegate)"
         (is (= 2 (count steps)))
         (is (= ["select-and-create" "lifecycle"] (mapv :name steps)))
         (is (= [:session :delegate] (mapv :type steps))))
       (testing "select-and-create :session step carries the current-worktree tools + all three design-named skills"
         ;; The workflow now runs entirely in the invoking session's current
         ;; worktree. It must not expose `work-on` to the selector step; child
         ;; and delegated workflow sessions inherit the parent's worktree-path.
         (is (= ["read" "bash" "edit" "write"] (:tools select-step))
             "select-and-create tools are read/bash/edit/write only")
         (is (not (some #{"work-on"} (:tools select-step)))
             "select-and-create tools do not include work-on")
         ;; TT-A (test review pass 18, task-test-review): the design (Deliverable
         ;; 2, Step 1) names three step-1 skills — incidental-complexity-finder
         ;; (selection recipe), gordian (selection methodology), code-shaper
         ;; (refactor shaping). Lock all three; a regress dropping gordian or
         ;; code-shaper previously passed green.
         (is (some #{"incidental-complexity-finder"} (:skills select-step))
             "select-and-create skills include incidental-complexity-finder")
         (is (some #{"gordian"} (:skills select-step))
             "select-and-create skills include gordian")
         (is (some #{"code-shaper"} (:skills select-step))
             "select-and-create skills include code-shaper"))
       ;; TR15 (test review pass 13, test-shaper): lock the entry-point input
       ;; flow — step 1 wires "input" to the bare top-level :workflow-input (NO
       ;; :path, distinct from the wrapper steps' {:path [:input]} :map-field
       ;; selector). Previously uncovered: a regress dropping :vars/{{input}} or
       ;; mis-wiring passed green. (step-has-input-var-wired? can't be reused —
       ;; it requires the :path [:input] shape.)
       (testing "select-and-create wires {{input}} to the bare :workflow-input (TR15)"
         (let [tmpl (first (filter #(= :template (:type %))
                                   (:contributions select-step)))]
           (is (.contains (:text tmpl) "{{input}}")
               "select-and-create prompt references the {{input}} template var")
           (is (= {:from :workflow-input}
                  (get-in tmpl [:vars "input"]))
               "select-and-create wires input to the bare top-level :workflow-input (no :path)")))
       (testing "select-and-create uses deterministic PASS_STATUS routing to skip or run lifecycle"
         (is (= {:type :invoke
                 :operation "workflow/pass-status-routing"
                 :args {:text {:from {:step "select-and-create" :output :final-llm-reply}}
                        :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}
                (:judge select-step))
             "select-and-create judge routes from the actor final reply via deterministic PASS_STATUS parsing")
         (is (= {"DONE" {:goto "lifecycle"}
                 "REPEAT" {:goto :done}}
                (:on select-step))
             "REVIEW_COMPLETE/DONE runs lifecycle; ACTIONABLE_FEEDBACK/REPEAT skips lifecycle for no-target")
         (is (.contains select-text "PASS_STATUS: REVIEW_COMPLETE")
             "target-present select-and-create output is instructed to route to lifecycle")
         (is (.contains select-text "PASS_STATUS: ACTIONABLE_FEEDBACK")
             "no-target select-and-create output is instructed to terminate without lifecycle"))
       (testing "lifecycle :delegate targets task-lifecycle directly with :input from select-and-create :yield :text"
         (is (= :delegate (:type delegate-step)))
         (is (= "task-lifecycle" (:target delegate-step)))
         (is (= {:type :map
                 :fields {:input {:from {:step "select-and-create" :yield :text}}}}
                (:prompt-string delegate-step))))
       ;; TR13: lock the delegate's :context — the second source propagates the
       ;; step-1 handoff into the delegated task-lifecycle. The prior
       ;; :type/:target/:prompt-string-only locks let a regress dropping it pass
       ;; green.
       (testing "lifecycle :delegate :context propagates workflow-original + the select-and-create handoff yield (TR13)"
         (is (= [{:type :source :from :workflow-original}
                 {:type :source :from {:step "select-and-create" :yield :text}}]
                (:context delegate-step))))
       (testing "select-and-create prompt emits the munera_task_path handoff and treats worktree_path as informational only"
         (is (.contains select-text "munera_task_path:")
             "step-1 prompt emits munera_task_path: handoff field")
         (is (.contains select-text "inherited session worktree")
             "step-1 prompt states downstream execution relies on inherited session worktree")
         (is (.contains select-text "worktree_path:` as informational context")
             "step-1 prompt allows worktree_path only as informational context"))
       (testing "select-and-create prompt encodes the early-stop-on-no-target intent"
         (is (re-find #"(?i)no unit qualif" select-text)
             "step-1 prompt encodes early-stop when no unit qualifies")
         (is (.contains select-text "Do NOT create or switch worktrees")
             "step-1 prompt forbids creating or switching worktrees on early stop")
         (is (.contains select-text "Do NOT create a task")
             "step-1 prompt forbids creating a task on early stop"))
       (testing "select-and-create prompt embeds the enforcing gate flags + both baselines"
         (is (.contains select-text
                        "--fail-on new-cycles,new-high-findings --max-new-medium-findings 0")
             "step-1 prompt embeds the enforcing gate flags")
         (is (.contains select-text "before-local.json")
             "step-1 prompt names the before-local.json baseline (A5)")
         (is (.contains select-text "before-diagnose.edn")
             "step-1 prompt names the before-diagnose.edn baseline (A3)"))
       ;; TR11 (pass 9, test-shaper): A3 baseline paths must be WORKTREE-ROOT-
       ;; RELATIVE (`munera/open/NNN-slug/...`), NOT a bare filename (which does
       ;; not resolve from the worktree-root cwd where Phase 1 runs gate). The
       ;; bare-filename locks above don't anchor this, so the R3-warned regress
       ;; (`--baseline before-diagnose.edn`) would pass green.
       (testing "select-and-create prompt resolves A3/A5 baselines by worktree-relative path (TR11)"
         (is (.contains select-text
                        "--baseline munera/open/NNN-slug/before-diagnose.edn")
             "step-1 gate command embeds the worktree-root-relative A3 baseline path (not a bare filename)")
         (is (.contains select-text
                        "the stored `munera/open/NNN-slug/before-local.json`")
             "step-1 prompt names the worktree-relative A5 before-local.json comparison path (not a bare filename)"))
       ;; TR2: the step-7 two-phase behaviour-preserving contract is the design's
       ;; substantive acceptance, not just the gate flags/filenames locked above.
       ;; Lock its shape so a paraphrase/regress of the Phase-0 gate, the
       ;; behaviour-identical constraint, or the F3 A5/A2 key can't pass green.
       (testing "select-and-create prompt embeds the Phase-0 characterization-test gate"
         (is (.contains select-text
                        "These tests must be GREEN against the unmodified code before any refactoring begins")
             "step-1 prompt requires a green characterization net before refactor (Phase 0)")
         (is (re-find #"(?i)add characterization tests" select-text)
             "step-1 prompt instructs adding characterization tests when coverage is insufficient"))
       (testing "select-and-create prompt embeds the behaviour-identical constraint"
         (is (.contains select-text
                        "behaviour is identical — meta/spec are unchanged; existing test expectations are not weakened")
             "step-1 prompt states the behaviour-identical / meta-spec-unchanged constraint"))
       (testing "select-and-create prompt keys A5/A2 acceptance on (ns, var, arity, line) (F3 lock)"
         ;; F3 re-keyed A5/A2 onto the selector's unique (ns, var, arity, line)
         ;; join key; a regress back to (ns, var, arity) in the generated
         ;; contract must not pass green.
         (is (.contains select-text "keyed by `(ns, var, arity, line)`")
             "step-1 prompt keys the A5 burden-reduction acceptance on (ns, var, arity, line)")
         (is (.contains select-text "identified by `(ns, var, arity, line)`")
             "step-1 prompt keys the A2 touched-set identity on (ns, var, arity, line)"))
       ;; TT-I (test review pass 24, task-test-review): TR2 locked the Phase-0
       ;; gate + behaviour-identical constraint; two further clauses of the same
       ;; generated-design contract were unlocked — (1) the Blast-radius scope
       ;; fence and (2) the Phase-0 hard gate + untestable-tangle escape hatch.
       ;; A regress admitting unrelated cleanup, or letting a refactor proceed on
       ;; an uncharacterized unit without a green net, must not pass green.
       (testing "select-and-create prompt locks the Blast-radius scope fence (TT-I)"
         (is (.contains select-text
                        "Blast radius: the target unit PLUS the minimal surrounding helpers required to decomplect it; no unrelated cleanup")
             "step-1 prompt fences the refactor scope to the target unit + minimal helpers (no unrelated cleanup)"))
       (testing "select-and-create prompt locks the Phase-0 hard gate + untestable-tangle handling (TT-I)"
         (is (.contains select-text "cannot be characterized safely")
             "step-1 prompt handles the untestable-tangle case (cannot be characterized safely)")
         (is (.contains select-text "scope drift -> close per Munera")
             "step-1 prompt closes an uncharacterizable unit per Munera scope-drift")
         (is (.contains select-text "No refactor proceeds without a green net")
             "step-1 prompt hard-gates the refactor on a green characterization net"))
       ;; TT-M (test review pass 28, task-test-review): TT-I/TR2/TT-D locked
       ;; sibling clauses of the generated-design contract, but its FIRST stated
       ;; requirement (target unit + captured evidence) and the upstream step-1
       ;; evidence-capture instruction incl. the coverage hint were unlocked. A
       ;; regress emitting a refactor task with no evidence block, or dropping the
       ;; coverage hint (so the generated task can't see its test net), must fail.
       (testing "select-and-create prompt locks the generated-design evidence clause + capture instruction (TT-M)"
         (is (.contains select-text
                        "The target unit and the captured incidental-complexity evidence")
             "step-1 prompt's generated design.md contract opens with the target unit + captured evidence")
         (is (.contains select-text "Capture the chosen target's evidence")
             "step-1 prompt instructs capturing the chosen target's evidence")
         (is (.contains select-text "and the coverage hint")
             "step-1 evidence capture includes the coverage hint (the generated task's test-net signal)"))
       ;; TR8 (pass 6): the distinguishing endpoint — no push/PR (Locked
       ;; decisions 7 & 8: full lifecycle on a local worktree branch, not a
       ;; complexity-reduction-pr clone) — was unlocked. A regress adding a
       ;; push/PR step must not pass green.
       (testing "select-and-create prompt locks the no-push/PR endpoint constraint (TR8)"
         (is (.contains select-text "Do NOT push or open a PR")
             "step-1 prompt forbids pushing or opening a PR")
         (is (.contains select-text
                        "ends with a completed, reviewed task on the current local branch")
             "step-1 prompt states the current-local-branch endpoint (no push/PR)"))
       ;; TT-B replacement: lock the current-worktree context. The workflow no
       ;; longer creates or switches to an origin/master worktree; callers must
       ;; invoke it from the intended branch/worktree and child sessions inherit
       ;; that worktree-path.
       (testing "select-and-create prompt locks current-worktree execution (TT-B replacement)"
         (is (.contains select-text "git status --short --branch")
             "step-1 prompt checks the current branch/worktree context")
         (is (.contains select-text "treat the current branch/worktree as the intended refactor location")
             "step-1 prompt treats the current worktree as authoritative for the run")
         (is (.contains select-text "Do NOT call `work-on`")
             "step-1 prompt forbids calling work-on")
         (is (.contains select-text "Do NOT create or switch worktrees")
             "step-1 prompt forbids creating or switching worktrees"))
       ;; TT-C (test review pass 19, task-test-review): lock the baseline
       ;; *capture commands*, not just the output filenames — before-local.json
       ;; <- `bb gordian local --json` (bare) and before-diagnose.edn <-
       ;; `bb gordian diagnose --edn`. A regress to the selector's
       ;; `local --sort total --json` (forbidden as a baseline) or a wrong
       ;; diagnose flag previously passed green.
       (testing "select-and-create prompt locks the baseline capture commands (TT-C)"
         (is (.contains select-text "bb gordian local --json")
             "step-1 prompt captures before-local.json via bare bb gordian local --json")
         (is (.contains select-text "bb gordian diagnose --edn")
             "step-1 prompt captures before-diagnose.edn via bb gordian diagnose --edn"))
       ;; TT-D (test review pass 19, task-test-review): lock the A5/A2
       ;; direction-of-change. The F3 lock asserts only the join key, not the
       ;; directional acceptance; a paraphrase weakening "decreased" -> "changed"
       ;; or "strictly less" -> "not greater" previously passed green.
       (testing "select-and-create prompt locks the A5/A2 direction-of-change (TT-D)"
         (is (.contains select-text
                        "decreased versus its `before-local.json` value")
             "step-1 prompt states A5: target lcc-total decreased versus before-local.json")
         (is (.contains select-text
                        "after total is strictly less than the before total")
             "step-1 prompt states A2: after total strictly less than before total"))
       ;; TT-G (test review pass 22, task-test-review): lock the A2 "touched
       ;; units = metric-derived set" discriminator (Locked decision 4 / the
       ;; design's "Net burden (A2)" paragraph). F3 locks only the
       ;; (ns, var, arity, line) key and TT-D only the strictly-less direction;
       ;; neither anchors the metric-vs-file derivation. A paraphrase to "units
       ;; whose source/files changed" previously passed green while defeating
       ;; the global-recompute net check (a refactor could hide relocated
       ;; burden in an unedited caller).
       (testing "select-and-create prompt locks the metric-derived touched-set discriminator (TT-G)"
         (is (.contains select-text
                        "the set is computed from the metric, not from the diff/touched files")
             "step-1 prompt derives the A2 touched set from the metric, not the diff/touched files"))
       ;; TT-H replacement: lock current-worktree task creation. Task ids,
       ;; baselines, and task-creation commits are scoped to the invoking
       ;; session's current worktree/branch; no outer-vs-inner worktree handoff
       ;; remains.
       (testing "select-and-create prompt locks current-worktree task creation (TT-H replacement)"
         (is (.contains select-text "scanning the CURRENT WORKTREE's")
             "step-1 prompt allocates NNN by scanning the current worktree's task set")
         (is (.contains select-text "Commit the task creation on the current branch")
             "step-1 prompt commits task creation on the current branch")
         (is (.contains select-text "delegated lifecycle child sessions inherit the same worktree")
             "step-1 prompt relies on inherited worktree-path for lifecycle resolution"))))))

;;; ---------------------------------------------------------------------------
;;; Reference-chain resolution (TT-K)

;; TT-K: the isolated tests above assert only delegate :target *string equality*
;; while loading a single EDN — the loader does NOT validate delegate targets at
;; load time, so those asserts give no resolution guarantee. Co-load the direct
;; delegate set (reduce-incidental-complexity -> task-lifecycle) and assert the
;; target is a key in the combined definitions.
(deftest task-209-workflow-set-loads-together-test
  (with-workflow-dir
    {"reduce-incidental-complexity.edn"
     (slurp-workflow-file "reduce-incidental-complexity.edn")
     "task-lifecycle.edn"
     (slurp-workflow-file "task-lifecycle.edn")}
    (fn [{:keys [definitions errors]}]
      (testing "the task-209 delegate set loads together without compilation errors"
        (is (empty? errors))
        (is (contains? definitions "reduce-incidental-complexity"))
        (is (contains? definitions "task-lifecycle")))
      (testing "the reduce-incidental-complexity delegate :target resolves to a registered workflow"
        (let [outer-target (->> (get-in definitions
                                        ["reduce-incidental-complexity" :steps])
                                (some #(when (= "lifecycle" (:name %))
                                         (:target %))))]
          (is (= "task-lifecycle" outer-target)
              "reduce-incidental-complexity delegates directly to task-lifecycle")
          (is (contains? definitions outer-target)
              "the outer delegate :target resolves to a registered workflow"))))))
