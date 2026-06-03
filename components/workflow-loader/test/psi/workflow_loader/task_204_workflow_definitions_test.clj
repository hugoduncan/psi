(ns psi.workflow-loader.task-204-workflow-definitions-test
  "Loader/compiler tests for the task-204 incidental-complexity workflows
   (task-lifecycle-in-worktree and reduce-incidental-complexity).

   Split out of workflow-definitions-test (R6) to keep that shared ns under the
   800-line components/ length guard. These tests share a small set of loader
   fixtures (load-edn-only + step helpers) duplicated here; they assert no load
   errors, step counts/names/types, :vars wiring, :prompt-string/:context
   handoff plumbing, and the prompt-level behavioural contracts (NO_TARGET
   short-circuit, two-phase refactor gate, no-push/PR endpoint)."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.core :as loader]))

;;; ---------------------------------------------------------------------------
;;; Fixtures (mirror workflow-definitions-test; the subset these tests use)

(defn- slurp-workflow-file
  [filename]
  (slurp (io/file (System/getProperty "user.dir")
                  ".psi/workflows"
                  filename)))

(defn- with-workflow-dir
  "Write files to a temp dir and call f with the loader result.
   files is a map of filename -> content string."
  [files f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "wf-def-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (try
      (doseq [[filename content] files]
        (spit (io/file dir filename) content))
      (with-redefs [loader/global-workflow-dirs (constantly [])
                    loader/project-workflow-dir (constantly (.getAbsolutePath dir))]
        (f (loader/load-workflow-definitions (.getAbsolutePath dir))))
      (finally
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(defn- load-edn-only
  "Load a single edn workflow (no .md refs) from the real .psi/workflows dir."
  [edn-filename f]
  (with-workflow-dir
    {edn-filename (slurp-workflow-file edn-filename)}
    f))

(defn- input-var-wired?
  "True if the contribution has :vars with 'input' wired to :workflow-input."
  [contribution]
  (= {:from :workflow-input :path [:input]}
     (get-in contribution [:vars "input"])))

(defn- step-has-input-var-wired?
  "True if any template contribution in step has 'input' wired to :workflow-input."
  [step]
  (some (fn [c]
          (and (= :template (:type c))
               (input-var-wired? c)))
        (:contributions step)))

(defn- step-template-text
  "Concatenated text of all template contributions in step."
  [step]
  (->> (:contributions step)
       (filter #(= :template (:type %)))
       (map :text)
       (apply str)))

;;; ---------------------------------------------------------------------------
;;; task-lifecycle-in-worktree (Slice 2 of task 204)

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
               "resolve-worktree constrains the positive-path yield to a single line")))))))

;;; ---------------------------------------------------------------------------
;;; reduce-incidental-complexity (Slice 3 of task 204)

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
           delegate-step (get step-by-name "lifecycle-in-worktree")
           select-text (step-template-text select-step)]
       (testing "is a two-step select-and-create (:session) -> lifecycle-in-worktree (:delegate)"
         (is (= 2 (count steps)))
         (is (= ["select-and-create" "lifecycle-in-worktree"] (mapv :name steps)))
         (is (= [:session :delegate] (mapv :type steps))))
       (testing "select-and-create :session step carries work-on tool + all three design-named skills"
         (is (some #{"work-on"} (:tools select-step))
             "select-and-create tools include work-on")
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
       (testing "lifecycle-in-worktree :delegate targets the wrapper with :input from select-and-create :yield :text"
         (is (= :delegate (:type delegate-step)))
         (is (= "task-lifecycle-in-worktree" (:target delegate-step)))
         (is (= {:type :map
                 :fields {:input {:from {:step "select-and-create" :yield :text}}}}
                (:prompt-string delegate-step))))
       ;; TR13 (pass 11, test-shaper): lock the delegate's :context — the second
       ;; source propagates the step-1 handoff into the delegated wrapper
       ;; (companion to :input, Locked decision 11). The prior :type/:target/
       ;; :prompt-string-only locks let a regress dropping it pass green.
       (testing "lifecycle-in-worktree :delegate :context propagates workflow-original + the select-and-create handoff yield (TR13)"
         (is (= [{:type :source :from :workflow-original}
                 {:type :source :from {:step "select-and-create" :yield :text}}]
                (:context delegate-step))))
       (testing "select-and-create prompt emits the worktree_path:/munera_task_path: handoff fields"
         (is (.contains select-text "worktree_path:")
             "step-1 prompt emits worktree_path: handoff field")
         (is (.contains select-text "munera_task_path:")
             "step-1 prompt emits munera_task_path: handoff field"))
       (testing "select-and-create prompt encodes the early-stop-on-no-target intent"
         (is (re-find #"(?i)no unit qualif" select-text)
             "step-1 prompt encodes early-stop when no unit qualifies")
         (is (.contains select-text "Do NOT create a worktree")
             "step-1 prompt forbids creating a worktree on early stop")
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
       ;; TR8 (pass 6): the distinguishing endpoint — no push/PR (Locked
       ;; decisions 7 & 8: full lifecycle on a local worktree branch, not a
       ;; complexity-reduction-pr clone) — was unlocked. A regress adding a
       ;; push/PR step must not pass green.
       (testing "select-and-create prompt locks the no-push/PR endpoint constraint (TR8)"
         (is (.contains select-text "Do NOT push or open a PR")
             "step-1 prompt forbids pushing or opening a PR")
         (is (.contains select-text
                        "ends with a completed, reviewed task on the local worktree branch")
             "step-1 prompt states the local-worktree-branch endpoint (no push/PR)"))
       ;; TT-B (test review pass 19, task-test-review): lock the step-1
       ;; base-refresh behaviour (design Deliverable 2, Step 1 first bullet) —
       ;; `git fetch origin master` + treat origin/master as the authoritative
       ;; base + base the worktree on origin/master. Previously unlocked: a
       ;; regress dropping the fetch or rebasing off stale local master passed
       ;; green.
       (testing "select-and-create prompt locks the origin/master base-refresh (TT-B)"
         (is (.contains select-text "git fetch origin master")
             "step-1 prompt refreshes the base with git fetch origin master")
         (is (.contains select-text
                        "Treat `origin/master` as the authoritative base")
             "step-1 prompt treats origin/master as the authoritative base")
         (is (.contains select-text "Base the worktree on `origin/master`")
             "step-1 prompt bases the worktree on origin/master"))
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
       ;; TT-H (test review pass 23, task-test-review): lock the step-1
       ;; worktree-scoped task creation behaviour (the P3 resolution). Design
       ;; Deliverable 2 Step 1 + resolved P3: NNN is allocated by scanning the
       ;; WORKTREE's open/ ∪ closed/ (not the outer checkout's, so the id does
       ;; not collide), and the create + commit happen on the worktree branch so
       ;; the emitted munera_task_path: resolves for the delegated lifecycle.
       ;; No existing test anchors it: a regress to outer-checkout-scoped id
       ;; allocation (reintroducing P3) or committing on the wrong branch (so
       ;; munera_task_path: does not resolve under the delegated work-on) passed
       ;; green. Same TT-class symmetry gap as TT-B/TT-G.
       (testing "select-and-create prompt locks the worktree-scoped task creation (TT-H)"
         (is (.contains select-text "scanning the WORKTREE's")
             "step-1 prompt allocates NNN by scanning the worktree's task set")
         (is (.contains select-text
                        "so the id does not collide with the outer checkout's open tasks")
             "step-1 prompt states worktree-scoped allocation avoids outer-checkout collision (P3)")
         (is (.contains select-text "Commit the task creation ON THE WORKTREE BRANCH")
             "step-1 prompt commits the task creation on the worktree branch so munera_task_path: resolves"))))))
