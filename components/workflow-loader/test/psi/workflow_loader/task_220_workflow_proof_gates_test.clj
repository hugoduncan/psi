(ns psi.workflow-loader.task-220-workflow-proof-gates-test
  "Content-lock tests for task 220 simplification workflow proof gates."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.workflow-test-support
    :refer [load-edn-only step-template-text]]))

(def ^:private workflows
  [{:filename "reduce-incidental-complexity.edn"
    :name "reduce-incidental-complexity"
    :validation-step "incidental-validation-capture"
    :review-reentry-step "review-task-implementation"
    :validation-recapture-step "incidental-validation-capture"
    :validation-artifacts ["after-local.json"
                           "incidental-burden-check.edn"
                           "incidental-gate.edn"]
    :proof-artifacts ["coverage-map.md"
                      "before-local.json"
                      "before-diagnose.edn"
                      "after-local.json"
                      "incidental-burden-check.edn"
                      "incidental-gate.edn"
                      "characterization-baseline.edn"]}
   {:filename "reduce-architectural-complexity.edn"
    :name "reduce-architectural-complexity"
    :validation-step "validation-capture"
    :review-reentry-step "review-implementation-tests"
    :validation-recapture-step "validation-capture"
    :validation-artifacts ["after-diagnose.edn"
                           "after-architecture-targets.edn"
                           "architecture-compare.edn"
                           "architecture-gate.edn"]
    :proof-artifacts ["coverage-map.md"
                      "characterization-baseline.edn"
                      "before-diagnose.edn"
                      "after-diagnose.edn"
                      "after-architecture-targets.edn"
                      "architecture-compare.edn"
                      "architecture-gate.edn"]}])

(defn- step-by-name
  [steps]
  (into {} (map (juxt :name identity) steps)))

(defn- workflow-steps
  [definitions workflow-name]
  (get-in definitions [workflow-name :steps]))

(defn- load-workflow
  [{:keys [filename name]} f]
  (load-edn-only
   filename
   (fn [{:keys [definitions errors]}]
     (is (empty? errors))
     (is (contains? definitions name))
     (f (workflow-steps definitions name)))))

(defn- input-from-extract?
  [step]
  (= {:from {:step "extract-task-path" :yield :text}}
     (get-in step [:prompt-string :fields :input])))

(defn- template-vars
  [step]
  (->> (:contributions step)
       (filter #(= :template (:type %)))
       (mapcat #(keys (:vars %)))
       set))

(defn- source-refs
  [step]
  (->> (:contributions step)
       (filter #(= :source (:type %)))
       (mapv :from)))

(defn- incoming-gotos
  [steps target]
  (->> steps
       (mapcat (fn [step]
                 (for [[route edge] (:on step)
                       :when (= target (:goto edge))]
                   [(:name step) route])))
       vec))

(defn- step-index
  [steps step-name]
  (first (keep-indexed (fn [idx step]
                         (when (= step-name (:name step))
                           idx))
                       steps)))

(deftest task-identity-boundaries-test
  ;; Tests task identity is deterministic and downstream steps consume the
  ;; extracted Munera path rather than the prose handoff.
  (doseq [workflow workflows]
    (load-workflow
     workflow
     (fn [steps]
       (let [by-name (step-by-name steps)
             select (by-name "select-and-create")
             extract (by-name "extract-task-path")]
         (testing (str (:name workflow) " selects then extracts task identity")
           (is (= (inc (step-index steps "select-and-create"))
                  (step-index steps "extract-task-path"))
               "extract-task-path is immediately after target-created selection")
           (is (= "extract-task-path" (get-in select [:on "DONE" :goto])))
           (is (= {:type :invoke
                   :operation "workflow/munera-open-task-path-routing"
                   :args {:text {:from {:step "extract-task-path"
                                        :output :final-llm-reply}}}}
                  (:judge extract)))
           (is (= "terminal-stop-malformed-task-path"
                  (get-in extract [:on "REPEAT" :goto]))))
         (testing (str (:name workflow) " downstream task inputs use extract-task-path")
           (doseq [step-name ["review-task-design" "create-task-plan" "review-task-plan"
                              "clean-baseline" "coverage-review" "coverage-disposition"
                              "coverage-fix" "diff-gate" "implement-task"]]
             (let [step (by-name step-name)]
               (is (some? step) step-name)
               (is (or (input-from-extract? step)
                       (= {:from {:step "extract-task-path" :yield :text}}
                          (get-in (first (filter #(= :template (:type %))
                                                 (:contributions step)))
                                  [:vars "input"])))
                   (str step-name " consumes extracted path"))
               (is (not= {:type :map
                          :fields {:input {:from {:step "select-and-create"
                                                  :yield :text}}}}
                         (:prompt-string step))
                   (str step-name " does not consume select handoff as input"))))))))))

(deftest disposition-routing-topology-test
  ;; Tests deterministic registered operations and route labels for proof-sync
  ;; and validation-capture disposition.
  (doseq [workflow workflows]
    (load-workflow
     workflow
     (fn [steps]
       (let [by-name (step-by-name steps)
             validation-step-name (:validation-step workflow)
             validation (by-name validation-step-name)
             validation-disposition (by-name "validation-capture-disposition")
             proof-sync (by-name "proof-sync")
             proof-disposition (by-name "proof-sync-disposition")
             proof-fixed-point (by-name "proof-sync-fixed-point")]
         (testing (str (:name workflow) " validation failures route via deterministic disposition")
           (is (= "validation-capture-disposition"
                  (get-in validation [:on "REPEAT" :goto])))
           (is (= "workflow/validation-capture-disposition-routing"
                  (:operation validation-disposition)))
           (is (= {:text {:from {:step validation-step-name
                                 :output :final-llm-reply}}}
                  (:args validation-disposition)))
           (is (= {"IMPLEMENTATION_REPAIR" {:goto "implement-task"}
                   "TERMINAL_STOP" {:goto "terminal-stop-validation-capture"}}
                  (:on validation-disposition)))
           (is (not= "terminal-stop-validation-capture"
                     (get-in validation [:on "REPEAT" :goto]))))
         (testing (str (:name workflow) " proof-sync fixed-point routes deterministically")
           (is (= "final-summary" (get-in proof-sync [:on "DONE" :goto])))
           (is (= "proof-sync-disposition"
                  (get-in proof-sync [:on "REPEAT" :goto])))
           (is (= "workflow/proof-sync-disposition-routing"
                  (:operation proof-disposition)))
           (is (= {:text {:from {:step "proof-sync"
                                 :output :final-llm-reply}}}
                  (:args proof-disposition)))
           (is (= {"COVERAGE_REVIEW" {:goto (:review-reentry-step workflow)}
                   "VALIDATION_RECAPTURE" {:goto (:validation-recapture-step workflow)}
                   "BOOKKEEPING_FIXED_POINT" {:goto "proof-sync-fixed-point"}}
                  (:on proof-disposition)))
           (is (= "final-summary" (get-in proof-fixed-point [:on "DONE" :goto])))
           (is (= "terminal-stop-proof-sync"
                  (get-in proof-fixed-point [:on "REPEAT" :goto])))
           (is (= [["proof-sync-fixed-point" "REPEAT"]]
                  (incoming-gotos steps "terminal-stop-proof-sync")))))))))

(deftest terminal-stop-source-context-test
  ;; Tests split terminal stops include explicit failed-gate source context and
  ;; malformed task-path stops do not consume a validated task path.
  (doseq [workflow workflows]
    (load-workflow
     workflow
     (fn [steps]
       (let [by-name (step-by-name steps)
             validation-step-name (:validation-step workflow)
             expected-sources {"terminal-stop-clean-baseline" "clean-baseline"
                               "terminal-stop-coverage-disposition" "coverage-disposition"
                               "terminal-stop-diff-gate" "diff-gate"
                               "terminal-stop-validation-capture" validation-step-name
                               "terminal-stop-proof-sync" "proof-sync-fixed-point"}
             malformed (by-name "terminal-stop-malformed-task-path")]
         (testing (str (:name workflow) " malformed task path summary has no task input")
           (is (= [{:step "select-and-create" :yield :text}
                   {:step "extract-task-path" :yield :text}]
                  (source-refs malformed)))
           (is (not (contains? (template-vars malformed) "input")))
           (let [text (step-template-text malformed)]
             (is (.contains text "Stop source: malformed/missing task path"))
             (is (.contains text "Do not consume a validated task path"))
             (is (.contains text "Do not require, invent, or read a task path"))
             (is (.contains text "Do not read task-local artifacts"))))
         (testing (str (:name workflow) " terminal stops source failed gates")
           (doseq [[terminal-step failed-step] expected-sources]
             (let [step (by-name terminal-step)
                   text (step-template-text step)]
               (is (some #(= {:step failed-step :yield :text} %)
                         (source-refs step))
                   (str terminal-step " sources " failed-step))
               (is (.contains text "Stop source:") terminal-step)
               (is (.contains text "committed task artifacts") terminal-step)
               (is (or (.contains text "artifact path")
                       (.contains text "artifact paths"))
                   (str terminal-step " names durable artifact path expectations")))))
         (testing (str (:name workflow) " proof terminal has both proof contexts")
           (let [proof-stop (by-name "terminal-stop-proof-sync")]
             (is (some #(= {:step "proof-sync" :yield :text} %)
                       (source-refs proof-stop)))
             (is (some #(= {:step "proof-sync-fixed-point" :yield :text} %)
                       (source-refs proof-stop)))
             (is (.contains (step-template-text proof-stop)
                            "committed proof-sync blocking note")))))))))

(deftest prompt-proof-artifact-content-lock-test
  ;; Tests prompts lock mandatory coverage maps, parse-checked Gordian artifacts,
  ;; selector uncertainty, and final-summary proof authority.
  (doseq [workflow workflows]
    (load-workflow
     workflow
     (fn [steps]
       (let [by-name (step-by-name steps)
             select-text (step-template-text (by-name "select-and-create"))
             validation-text (step-template-text (by-name (:validation-step workflow)))
             proof-text (step-template-text (by-name "proof-sync"))
             fixed-text (step-template-text (by-name "proof-sync-fixed-point"))
             final-summary (by-name "final-summary")
             final-text (step-template-text final-summary)]
         (testing (str (:name workflow) " select prompt creates mandatory coverage-map scaffold")
           (is (.contains select-text "coverage-map.md"))
           (is (.contains select-text "first writer"))
           (is (.contains select-text "initial `munera/open/NNN-slug/coverage-map.md` scaffold"))
           (is (.contains select-text "Pending or unknown coverage/test counts"))
           (is (.contains select-text "coverage-review` updates"))
           (is (.contains select-text "coverage-fix` updates"))
           (is (.contains select-text "diff-gate` records"))
           (is (.contains select-text "proof-sync` performs final"))
           (is (.contains select-text "final-summary` reads it as committed proof authority")))
         (testing (str (:name workflow) " validation prompt parse-checks proof artifacts")
           (doseq [artifact (:validation-artifacts workflow)]
             (is (.contains validation-text artifact) artifact))
           (is (or (.contains validation-text "Immediately parse-check")
                   (.contains validation-text "Parse-check the written EDN file")))
           (is (or (.contains validation-text "Exit 0 with unreadable")
                   (.contains validation-text "exit code alone")))
           (is (.contains validation-text "failure map"))
           (is (.contains validation-text "VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR"))
           (is (.contains validation-text "VALIDATION_CAPTURE_ROUTE: TERMINAL_STOP")))
         (testing (str (:name workflow) " proof prompts reread committed authority")
           (doseq [artifact (:proof-artifacts workflow)]
             (is (.contains proof-text artifact) artifact)
             (is (.contains fixed-text artifact) artifact))
           (is (.contains proof-text "committed task-local artifacts as proof authority"))
           (is (.contains proof-text "review child-session prose only as context"))
           (is (.contains proof-text "clean/no-op pass is the only direct route"))
           (is (.contains fixed-text "read-only proof-sync fixed-point"))
           (is (.contains fixed-text "Do not mutate anything")))
         (testing (str (:name workflow) " final summary reads committed proof artifacts")
           (doseq [artifact (:proof-artifacts workflow)]
             (is (.contains final-text artifact) artifact))
           (is (some #(= {:step "proof-sync" :yield :text} %)
                     (source-refs final-summary))
               "final-summary receives the clean proof-sync yield")
           (is (some #(= {:step "proof-sync-fixed-point" :yield :text} %)
                     (source-refs final-summary))
               "final-summary receives clean fixed-point verification yield")
           (is (.contains final-text "independently read committed task-local"))
           (is (.contains final-text "Do not claim proof coherence from workflow/review prose"))))))))

(deftest selector-uncertainty-content-lock-test
  ;; Tests generated task prompts record weak selector confidence instead of
  ;; silently trusting marginal targets.
  (load-workflow
   (second workflows)
   (fn [steps]
     (let [text (step-template-text ((step-by-name steps) "select-and-create"))]
       (testing "architecture design records low-confidence evidence and review questions"
         (is (.contains text "selected candidate score and confidence"))
         (is (.contains text "confidence is `:low`"))
         (is (.contains text "why the target remains actionable despite low confidence"))
         (is (.contains text "evidence that would falsify the target"))
         (is (.contains text "review questions"))
         (is (.contains text "whether implementation scope should be narrowed"))))))
  (load-workflow
   (first workflows)
   (fn [steps]
     (let [text (step-template-text ((step-by-name steps) "select-and-create"))]
       (testing "incidental design records guard evidence and marginal target concerns"
         (is (.contains text "top-5 guard evidence table"))
         (is (.contains text "rejected-essential"))
         (is (.contains text "If no higher candidate was rejected before the chosen target"))
         (is (.contains text "Mark an accepted target as marginal"))
         (is (.contains text "falsification evidence"))
         (is (.contains text "scope/coverage review questions")))))))
