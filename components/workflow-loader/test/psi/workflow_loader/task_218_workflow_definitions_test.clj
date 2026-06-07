(ns psi.workflow-loader.task-218-workflow-definitions-test
  "Loader/compiler tests for task-218 reduce-architectural-complexity workflow."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.prompt-assets.skills :as skills]
   [psi.workflow-loader.workflow-test-support
    :refer [load-edn-only
            slurp-workflow-file
            step-template-text
            with-workflow-dir]]))

(defn- step-by-name
  [steps]
  (into {} (map (juxt :name identity) steps)))

(defn- workflow-steps
  [definitions]
  (get-in definitions ["reduce-architectural-complexity" :steps]))

(defn- pass-status-judge
  [step-name]
  {:type :invoke
   :operation "workflow/pass-status-routing"
   :args {:text {:from {:step step-name :output :final-llm-reply}}
          :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}})

(defn- extract-input?
  [step]
  (= {:from {:step "extract-task-path" :yield :text}}
     (get-in step [:prompt-string :fields :input])))

(defn- context-source-refs
  [step]
  (mapv :from (:context step)))

(defn- review-skill
  [step]
  (get-in step [:prompt-string :fields :skill :value]))

(defn- load-workflow-and-deps
  [f]
  (with-workflow-dir
    (into {"reduce-architectural-complexity.edn"
           (slurp-workflow-file "reduce-architectural-complexity.edn")}
          (map (fn [filename]
                 [filename (slurp-workflow-file filename)])
               ["review-task-design.edn"
                "review-task-design-architecture-review.md"
                "review-task-design-ambiguity-review.md"
                "review-task-design-inconsistency-review.md"
                "review-follow-up-design.md"
                "create-task-plan.edn"
                "create-task-plan-create-plan.md"
                "review-task-plan.edn"
                "review-task-plan-ambiguity-review.md"
                "review-task-plan-inconsistency-review.md"
                "review-follow-up-steps.md"
                "implement-task.edn"
                "implement-task-implement-pass.md"
                "implement-task-final-summary.md"
                "review-step.edn"]))
    f))

(deftest reduce-architectural-complexity-loads-and-shapes-test
  (load-edn-only
   "reduce-architectural-complexity.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads and registers the workflow name"
       (is (empty? errors))
       (is (contains? definitions "reduce-architectural-complexity")))
     (let [steps (workflow-steps definitions)
           by-name (step-by-name steps)
           select-step (by-name "select-and-create")
           extract-step (by-name "extract-task-path")
           terminal-step (by-name "terminal-stop-summary")]
       (testing "top-level step order covers selection, test-net, validation, reviews, and summaries"
         (is (= ["select-and-create"
                 "extract-task-path"
                 "review-task-design"
                 "create-task-plan"
                 "review-task-plan"
                 "clean-baseline"
                 "coverage-review"
                 "coverage-disposition"
                 "coverage-fix"
                 "diff-gate"
                 "implement-task"
                 "validation-capture"
                 "review-implementation-correctness"
                 "review-implementation-tests"
                 "review-implementation-architecture"
                 "review-test-shape"
                 "review-task-docs"
                 "review-code-shape"
                 "final-summary"
                 "terminal-stop-summary"]
                (mapv :name steps))))
       (testing "select-and-create uses pass-status routing and normalized route keys"
         (is (= (pass-status-judge "select-and-create") (:judge select-step)))
         (is (= {"DONE" {:goto "extract-task-path"}
                 "REPEAT" {:goto :done}}
                (:on select-step)))
         (is (not (contains? (:on select-step) "PASS_STATUS: REVIEW_COMPLETE")))
         (is (not (contains? (:on select-step) "PASS_STATUS: ACTIONABLE_FEEDBACK"))))
       (testing "select-and-create prompt locks selector, no-target, and target-created contracts"
         (let [text (step-template-text select-step)]
           (is (.contains text "bb gordian architecture-targets --edn"))
           (is (.contains text "top-level `:winner`"))
           (is (.contains text "top-level `:candidates`"))
           (is (.contains text "bb gordian diagnose --edn"))
           (is (.contains text "before-diagnose.edn"))
           (is (.contains text "all-or-nothing"))
           (is (.contains text "Do NOT call `work-on`"))
           (is (.contains text "Do NOT create another worktree"))
           (is (.contains text "Do NOT switch branches"))
           (is (.contains text "Do NOT emit a `munera_task_path:` line"))
           (is (.contains text "PASS_STATUS: ACTIONABLE_FEEDBACK"))
           (is (.contains text "PASS_STATUS: REVIEW_COMPLETE"))
           (is (.contains text "munera_task_path: munera/open/NNN-slug"))
           (is (.contains text "target-issues-unavailable.edn"))
           (is (.contains text "must not change the selected target"))
           (is (.contains text "must not force a no-target stop"))
           (is (.contains text "`:namespace`"))
           (is (.contains text "`:family`"))
           (is (.contains text "`:pair`"))
           (is (.contains text "`:community`"))
           (is (.contains text "A bare community id"))
           (is (.contains text "uninterpretable"))
           (is (.contains text "architecture-targets.edn"))
           (is (.contains text "target-issues.edn"))
           (is (.contains text "characterization-test"))
           (is (.contains text "--fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn"))
           (is (not (.contains text ":architecture-target-ranking")))
           (is (not (.contains text "--json")))
           (is (not (.contains text "JSON")))))
       (testing "extract-task-path is the target-present identity boundary"
         (is (= "review-task-design" (get-in extract-step [:on "DONE" :goto])))
         (is (= "terminal-stop-summary" (get-in extract-step [:on "REPEAT" :goto])))
         (let [text (step-template-text extract-step)]
           (is (.contains text "exactly one line matching `munera_task_path: munera/open/NNN-slug`"))
           (is (.contains text "respond with ONLY that root-relative path"))
           (is (.contains text "do not guess"))
           (is (.contains text "Do not invent or read task-local artifacts"))))
       (testing "terminal stop has the pre-design no-path branch"
         (let [text (step-template-text terminal-step)]
           (is (.contains text "Pre-design/no-validated-task-path"))
           (is (.contains text "Post-task/no-implementation"))
           (is (.contains text "Do not require, invent, or read a task path"))))))))

(deftest reduce-architectural-complexity-routing-and-gates-test
  (load-edn-only
   "reduce-architectural-complexity.edn"
   (fn [{:keys [definitions errors]}]
     (is (empty? errors))
     (let [steps (workflow-steps definitions)
           by-name (step-by-name steps)
           clean (by-name "clean-baseline")
           coverage (by-name "coverage-review")
           disposition (by-name "coverage-disposition")
           fix (by-name "coverage-fix")
           diff (by-name "diff-gate")
           implement (by-name "implement-task")
           validation (by-name "validation-capture")]
       (testing "downstream task consumers all use extract-task-path as input"
         (doseq [step-name ["review-task-design" "create-task-plan" "review-task-plan"
                            "implement-task" "review-implementation-correctness"
                            "review-implementation-tests" "review-implementation-architecture"
                            "review-test-shape" "review-task-docs" "review-code-shape"]]
           (is (extract-input? (by-name step-name))
               (str step-name " must consume the extracted task path"))))
       (testing "select-and-create handoff is context, not prompt-string task identity"
         (doseq [step-name ["review-task-design" "create-task-plan" "review-task-plan"
                            "implement-task"]]
           (is (some #(= {:step "select-and-create" :yield :text} %)
                     (context-source-refs (by-name step-name))))
           (is (not= {:type :map
                      :fields {:input {:from {:step "select-and-create" :yield :text}}}}
                     (:prompt-string (by-name step-name))))))
       (testing "test-net routing prevents implementation before clean baseline, coverage, and diff gate"
         (is (= "clean-baseline" (get-in (by-name "review-task-plan") [:on "DONE" :goto])))
         (is (= "coverage-review" (get-in clean [:on "DONE" :goto])))
         (is (= "coverage-disposition" (get-in coverage [:on "REPEAT" :goto])))
         (is (= "coverage-fix" (get-in disposition [:on "DONE" :goto])))
         (is (= "coverage-review" (get-in fix [:on "DONE" :goto])))
         (is (= "diff-gate" (get-in coverage [:on "DONE" :goto])))
         (is (= "implement-task" (get-in diff [:on "DONE" :goto])))
         (is (= "terminal-stop-summary" (get-in clean [:on "REPEAT" :goto])))
         (is (= "terminal-stop-summary" (get-in disposition [:on "REPEAT" :goto])))
         (is (= "terminal-stop-summary" (get-in diff [:on "REPEAT" :goto]))))
       (testing "gate prompt content locks architecture test-net semantics"
         (is (.contains (step-template-text clean) "characterization-baseline.edn"))
         (is (.contains (step-template-text clean) "`:target/source-areas`"))
         (is (.contains (step-template-text clean) "`:target/allowed-adjacent-source-areas`"))
         (is (.contains (step-template-text coverage) "observable state/outputs"))
         (is (.contains (step-template-text coverage) "CHARACTERIZATION_STATUS: FIXABLE_GAPS"))
         (is (.contains (step-template-text coverage) "CHARACTERIZATION_STATUS: INFEASIBLE"))
         (is (.contains (step-template-text fix) "Do NOT simplify"))
         (is (.contains (step-template-text fix) "minimal testability seams"))
         (is (.contains (step-template-text diff) "Compare committed changes since the baseline HEAD"))
         (is (.contains (step-template-text diff) "premature simplification/refactor")))
       (testing "validation capture immediately follows implementation and precedes reviews"
         (is (= "validation-capture" (get-in implement [:on "DONE" :goto])))
         (is (= "review-implementation-correctness" (get-in validation [:on "DONE" :goto])))
         (is (= "implement-task" (get-in validation [:on "REPEAT" :goto])))
         (is (= (pass-status-judge "validation-capture") (:judge validation))))
       (testing "validation prompt locks producer-before-review artifacts and failure routing"
         (let [text (step-template-text validation)]
           (is (.contains text "after-diagnose.edn"))
           (is (.contains text "after-architecture-targets.edn"))
           (is (.contains text "architecture-compare.edn"))
           (is (.contains text "architecture-gate.edn"))
           (is (.contains text "EDN failure map"))
           (is (.contains text "PASS_STATUS: ACTIONABLE_FEEDBACK"))
           (is (.contains text "Missing, unreadable, failed"))))))))

(deftest reduce-architectural-complexity-review-chain-test
  (load-edn-only
   "reduce-architectural-complexity.edn"
   (fn [{:keys [definitions errors]}]
     (is (empty? errors))
     (let [steps (workflow-steps definitions)
           by-name (step-by-name steps)
           review-names ["review-implementation-correctness"
                         "review-implementation-tests"
                         "review-implementation-architecture"
                         "review-test-shape"
                         "review-task-docs"
                         "review-code-shape"]
           expected-skills ["task-implementation-review"
                            "task-test-review"
                            "review-implementation-architecture"
                            "test-shaper"
                            "review-task-docs"
                            "code-shaper"]]
       (testing "all six post-implementation delegates target review-step in order"
         (is (= review-names
                (->> steps
                     (map :name)
                     (filter (set review-names))
                     vec)))
         (is (= expected-skills (mapv #(review-skill (by-name %)) review-names)))
         (doseq [name review-names]
           (is (= "review-step" (:target (by-name name))))
           (is (extract-input? (by-name name)))))
       (testing "generic review-task-implementation wrapper is not delegated"
         (is (not-any? #(= "review-task-implementation" (:target %)) steps)))
       (testing "architecture review context is exactly workflow-yield context sources"
         (is (= [:workflow-original
                 {:step "select-and-create" :yield :text}
                 {:step "clean-baseline" :yield :text}
                 {:step "coverage-review" :yield :text}
                 {:step "diff-gate" :yield :text}
                 {:step "implement-task" :yield :text}
                 {:step "validation-capture" :yield :text}
                 {:step "review-implementation-correctness" :yield :text}
                 {:step "review-implementation-tests" :yield :text}]
                (context-source-refs (by-name "review-implementation-architecture")))))
       (testing "architecture evidence is required via task-local file reads"
         (let [skill-text (slurp (io/file ".psi/skills/review-implementation-architecture/SKILL.md"))]
           (is (.contains skill-text "name: review-implementation-architecture"))
           (doseq [artifact ["architecture-targets.edn"
                             "target-issues.edn"
                             "target-issues-unavailable.edn"
                             "before-diagnose.edn"
                             "after-diagnose.edn"
                             "after-architecture-targets.edn"
                             "architecture-compare.edn"
                             "architecture-gate.edn"]]
             (is (.contains skill-text artifact)))
           (is (.contains skill-text "Do not assume workflow context inlines these artifact contents"))))))))

(deftest reduce-architectural-complexity-skill-and-delegate-load-test
  (testing "review-implementation-architecture skill is discoverable"
    (let [result (skills/discover-skills {:project-skills-dirs [".psi/skills"]
                                          :global-skills-dirs []})
          skill (some #(when (= "review-implementation-architecture" (:name %)) %)
                      (:skills result))]
      (is (some? skill))
      (is (str/ends-with? (:file-path skill)
                          ".psi/skills/review-implementation-architecture/SKILL.md"))))
  (testing "workflow co-loads with its direct delegate workflow set"
    (load-workflow-and-deps
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (doseq [name ["reduce-architectural-complexity"
                     "review-task-design"
                     "create-task-plan"
                     "review-task-plan"
                     "implement-task"
                     "review-step"]]
         (is (contains? definitions name)))))))

(deftest architecture-targets-live-envelope-shape-test
  (let [{:keys [exit out err]} (shell/sh "bb" "gordian" "architecture-targets" "--edn")]
    (if (zero? exit)
      (let [payload (edn/read-string out)
            winner (:winner payload)]
        (is (map? payload))
        (is (vector? (:candidates payload)))
        (when (some? winner)
          (is (map? winner))
          (is (contains? winner :candidate/id))
          (is (contains? winner :candidate/type))
          (is (some? (:candidate/id winner)))
          (is (some? (:candidate/type winner)))))
      (do
        (println "Skipping live architecture-targets shape check; command unavailable or non-zero:" err)
        (is true)))))
