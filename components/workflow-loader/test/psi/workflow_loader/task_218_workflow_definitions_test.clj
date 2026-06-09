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

(defn- munera-open-task-path-judge
  [step-name]
  {:type :invoke
   :operation "workflow/munera-open-task-path-routing"
   :args {:text {:from {:step step-name :output :final-llm-reply}}}})

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

(def ^:private supported-architecture-candidate-types
  #{:namespace :family :pair :community})

(defn- non-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn- architecture-targets-command
  [sh]
  (try
    (let [{:keys [exit out err]} (sh "bb" "gordian" "architecture-targets" "--edn")]
      (if (zero? exit)
        {:status :ok
         :out out}
        {:status :unavailable
         :reason (str "exit " exit)
         :err err}))
    (catch java.io.IOException e
      {:status :unavailable
       :reason (.getMessage e)})))

(defn- interpretable-candidate?
  [candidate]
  (let [candidate-type (:candidate/type candidate)
        candidate-id (:candidate/id candidate)
        members (:members candidate)]
    (and (contains? supported-architecture-candidate-types candidate-type)
         (vector? candidate-id)
         (= candidate-type (first candidate-id))
         (case candidate-type
           :namespace (and (= 2 (count candidate-id))
                           (non-blank-string? (second candidate-id)))
           :family (and (= 2 (count candidate-id))
                        (non-blank-string? (second candidate-id)))
           :pair (and (= 3 (count candidate-id))
                      (non-blank-string? (second candidate-id))
                      (non-blank-string? (nth candidate-id 2)))
           :community (and (= 2 (count candidate-id))
                           (integer? (second candidate-id))
                           (vector? members)
                           (seq members)
                           (every? non-blank-string? members))
           false))))

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
           malformed-stop (by-name "terminal-stop-malformed-task-path")
           clean-stop (by-name "terminal-stop-clean-baseline")
           coverage-stop (by-name "terminal-stop-coverage-disposition")
           diff-stop (by-name "terminal-stop-diff-gate")
           validation-stop (by-name "terminal-stop-validation-capture")
           proof-stop (by-name "terminal-stop-proof-sync")]
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
                 "validation-capture-disposition"
                 "review-implementation-correctness"
                 "review-implementation-tests"
                 "review-implementation-architecture"
                 "review-test-shape"
                 "review-task-docs"
                 "review-code-shape"
                 "proof-sync"
                 "proof-sync-disposition"
                 "proof-sync-fixed-point"
                 "final-summary"
                 "terminal-stop-malformed-task-path"
                 "terminal-stop-clean-baseline"
                 "terminal-stop-coverage-disposition"
                 "terminal-stop-diff-gate"
                 "terminal-stop-validation-capture"
                 "terminal-stop-proof-sync"]
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
           (is (.contains text "coverage-map.md"))
           (is (.contains text "initial `munera/open/NNN-slug/coverage-map.md` scaffold"))
           (is (.contains text "selected candidate score and confidence"))
           (is (.contains text "confidence is `:low`"))
           (is (.contains text "why the target remains actionable despite low confidence"))
           (is (.contains text "evidence that would falsify the target"))
           (is (.contains text "whether implementation scope should be narrowed"))
           (is (.contains text "validation-capture` records references to `after-diagnose.edn`"))
           (is (.contains text "final-summary` reads it as committed proof authority"))
           (is (.contains text "characterization-test"))
           (is (.contains text "--fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn"))
           (is (not (.contains text ":architecture-target-ranking")))
           (is (not (.contains text "--json")))
           (is (not (.contains text "JSON")))))
       (testing "extract-task-path is the target-present identity boundary"
         (is (= (munera-open-task-path-judge "extract-task-path") (:judge extract-step)))
         (is (= "review-task-design" (get-in extract-step [:on "DONE" :goto])))
         (is (= "terminal-stop-malformed-task-path" (get-in extract-step [:on "REPEAT" :goto])))
         (is (not= :llm (:type (:judge extract-step)))
             "valid path routing must be deterministic, not LLM-judged")
         (let [text (step-template-text extract-step)]
           (is (.contains text "exactly one line matching `munera_task_path: munera/open/NNN-slug`"))
           (is (.contains text "respond with ONLY that root-relative path"))
           (is (.contains text "do not guess"))
           (is (.contains text "Do not invent or read task-local artifacts"))))
       (testing "split terminal stops name explicit failed-gate sources"
         (is (.contains (step-template-text malformed-stop)
                        "Stop source: malformed/missing task path"))
         (is (.contains (step-template-text malformed-stop)
                        "Do not consume a validated task path"))
         (is (.contains (step-template-text malformed-stop)
                        "Do not require, invent, or read a task path"))
         (is (.contains (step-template-text clean-stop) "Stop source: clean-baseline"))
         (is (.contains (step-template-text coverage-stop) "Stop source: coverage-disposition"))
         (is (.contains (step-template-text diff-stop) "Stop source: diff-gate"))
         (is (.contains (step-template-text validation-stop) "Stop source: validation-capture"))
         (is (.contains (step-template-text proof-stop) "Stop source: proof-sync-fixed-point")))))))

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
           validation (by-name "validation-capture")
           validation-disposition (by-name "validation-capture-disposition")
           proof-sync (by-name "proof-sync")
           proof-disposition (by-name "proof-sync-disposition")
           proof-fixed-point (by-name "proof-sync-fixed-point")]
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
         (is (= "terminal-stop-clean-baseline" (get-in clean [:on "REPEAT" :goto])))
         (is (= "terminal-stop-coverage-disposition" (get-in disposition [:on "REPEAT" :goto])))
         (is (= "terminal-stop-diff-gate" (get-in diff [:on "REPEAT" :goto]))))
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
         (is (= "validation-capture-disposition" (get-in validation [:on "REPEAT" :goto])))
         (is (= (pass-status-judge "validation-capture") (:judge validation)))
         (is (= :invoke (:type validation-disposition)))
         (is (= "workflow/exact-marker-routing"
                (:operation validation-disposition)))
         (is (= {:text {:from {:step "validation-capture"
                               :output :final-llm-reply}}
                 :marker-label "VALIDATION_CAPTURE_ROUTE"
                 :allowed-routes ["IMPLEMENTATION_REPAIR" "TERMINAL_STOP"]}
                (:args validation-disposition)))
         (is (= {"IMPLEMENTATION_REPAIR" {:goto "implement-task"}
                 "TERMINAL_STOP" {:goto "terminal-stop-validation-capture"}}
                (:on validation-disposition))))

       (testing "proof-sync fixed-point gate follows code-shape before final summary"
         (is (= "final-summary" (get-in proof-sync [:on "DONE" :goto])))
         (is (= "proof-sync-disposition" (get-in proof-sync [:on "REPEAT" :goto])))
         (is (= (pass-status-judge "proof-sync") (:judge proof-sync)))
         (is (= :invoke (:type proof-disposition)))
         (is (= "workflow/exact-marker-routing" (:operation proof-disposition)))
         (is (= {:text {:from {:step "proof-sync"
                               :output :final-llm-reply}}
                 :marker-label "PROOF_SYNC_ROUTE"
                 :allowed-routes ["COVERAGE_REVIEW"
                                  "VALIDATION_RECAPTURE"
                                  "BOOKKEEPING_FIXED_POINT"]}
                (:args proof-disposition)))
         (is (= {"COVERAGE_REVIEW" {:goto "review-implementation-tests"}
                 "VALIDATION_RECAPTURE" {:goto "validation-capture"}
                 "BOOKKEEPING_FIXED_POINT" {:goto "proof-sync-fixed-point"}}
                (:on proof-disposition)))
         (is (= "final-summary" (get-in proof-fixed-point [:on "DONE" :goto])))
         (is (= "terminal-stop-proof-sync" (get-in proof-fixed-point [:on "REPEAT" :goto])))
         (let [text (step-template-text proof-sync)
               fixed-text (step-template-text proof-fixed-point)]
           (is (.contains text "committed task-local artifacts as proof authority"))
           (is (.contains text "coverage-map.md"))
           (is (.contains text "after-architecture-targets.edn"))
           (is (.contains text "PROOF_SYNC_ROUTE: COVERAGE_REVIEW"))
           (is (.contains text "PROOF_SYNC_ROUTE: VALIDATION_RECAPTURE"))
           (is (.contains text "PROOF_SYNC_ROUTE: BOOKKEEPING_FIXED_POINT"))
           (is (.contains text "must never route directly to final success"))
           (is (.contains fixed-text "read-only proof-sync fixed-point"))
           (is (.contains fixed-text "Do not mutate anything"))))
       (testing "validation prompt locks producer-before-review artifacts and failure routing"
         (let [text (step-template-text validation)]
           (is (.contains text "after-diagnose.edn"))
           (is (.contains text "after-architecture-targets.edn"))
           (is (.contains text "architecture-compare.edn"))
           (is (.contains text "architecture-gate.edn"))
           (is (.contains text "Immediately parse-check the written file as EDN"))
           (is (.contains text "Exit code alone is never proof"))
           (is (.contains text "Exit 0 with unreadable, truncated, empty, or non-EDN stdout"))
           (is (.contains text "readable EDN failure map"))
           (is (.contains text "VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR"))
           (is (.contains text "VALIDATION_CAPTURE_ROUTE: TERMINAL_STOP"))
           (is (.contains text "PASS_STATUS: ACTIONABLE_FEEDBACK"))
           (is (.contains text "deterministic `validation-capture-disposition` step"))
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

(deftest architecture-targets-command-unavailable-test
  ;; Tests absent bb/Gordian command handling without depending on the host
  ;; environment.
  (let [result (architecture-targets-command
                (fn [& _]
                  (throw (java.io.IOException. "bb missing"))))]
    (is (= :unavailable (:status result)))
    (is (= "bb missing" (:reason result)))))

(deftest architecture-targets-live-envelope-shape-test
  ;; Tests the live Gordian architecture-targets envelope shape when available,
  ;; without making bb/Gordian availability or repository-specific rankings a
  ;; test requirement.
  (let [{:keys [status out reason err]} (architecture-targets-command shell/sh)]
    (if (= :ok status)
      (let [payload (edn/read-string out)
            winner (:winner payload)]
        (is (map? payload))
        (is (vector? (:candidates payload)))
        (when (some? winner)
          (is (map? winner))
          (is (interpretable-candidate? winner)
              (str "winner must carry selector-interpretable candidate id/type shape, got "
                   (pr-str (select-keys winner [:candidate/id :candidate/type :members]))))))
      (do
        (println "Skipping live architecture-targets shape check; command unavailable or non-zero:"
                 (or err reason))
        (is true)))))
