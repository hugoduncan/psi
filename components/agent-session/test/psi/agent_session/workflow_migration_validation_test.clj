(ns psi.agent-session.workflow-migration-validation-test
  "Validate the checked-in workflow corpus against the finalized file-kind
   split contract for `.psi/workflows/`."
  (:require
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.compiler :as compiler]
   [psi.workflow-loader.core :as loader]))

(def ^:private required-single-step-workflows
  #{"planner"
    "builder"
    "reviewer"})

(def ^:private required-multi-step-workflows
  #{"plan-build"
    "plan-build-review"
    "delegate-build-review"
    "gh-bug-triage-modular"
    "prompt-build"
    "lambda-build"
    "implement-task"
    "implement-task-in-worktree"
    "review-step"})

(defn- path->workflow-name
  [path]
  (some-> path java.io.File. .getName (str/replace #"\.(md|edn)$" "")))

(defn- workflow-file-kind
  [path]
  (some->> path (re-find #"\.(md|edn)$") second keyword))

(defn- indexed-workflow-files
  []
  (reduce (fn [acc entry]
            (let [source-path (:source-path entry)
                  workflow-name (or (:name entry)
                                    (get-in entry [:config :name])
                                    (path->workflow-name source-path))
                  file-kind (workflow-file-kind source-path)]
              (if (and workflow-name file-kind)
                (update-in acc [workflow-name file-kind] (fnil conj []) source-path)
                acc)))
          {}
          (loader/scan-directory ".psi/workflows")))

(defn- workflow-migration-view
  []
  (let [parsed (loader/scan-directory ".psi/workflows")
        compile-result (compiler/compile-workflow-files parsed)
        definitions (:definitions compile-result)]
    {:parsed parsed
     :parse-errors (filter :error parsed)
     :definitions definitions
     :errors (:errors compile-result)
     :by-name (into {} (keep (fn [definition]
                               (when-let [name (:name definition)]
                                 [name definition])))
                    definitions)
     :files-by-name-and-kind (indexed-workflow-files)}))

(deftest checked-in-single-step-markdown-workflows-still-compile-test
  (testing "required checked-in single-step markdown workflows still parse and compile as standalone markdown workflows"
    (let [{:keys [parse-errors errors by-name files-by-name-and-kind]} (workflow-migration-view)
          known-workflow-names (set (keys files-by-name-and-kind))]
      (is (every? known-workflow-names required-single-step-workflows)
          (str "Missing required single-step markdown workflows: "
               (pr-str (sort (remove known-workflow-names required-single-step-workflows)))))
      (is (every? #(contains? by-name %) required-single-step-workflows)
          (str "Missing compiled single-step markdown workflows: "
               (pr-str (sort (remove #(contains? by-name %) required-single-step-workflows)))))
      (doseq [workflow-name required-single-step-workflows]
        (is (seq (get-in files-by-name-and-kind [workflow-name :md]))
            (str workflow-name " should have a checked-in .md workflow file"))
        (is (empty? (get-in files-by-name-and-kind [workflow-name :edn]))
            (str workflow-name " should not have a sibling .edn workflow file under the finalized split contract")))
      (is (every? #(not (contains? required-single-step-workflows (path->workflow-name (:source-path %))))
                  parse-errors)
          (str "Single-step markdown workflows must not hit parse errors: "
               (pr-str (mapv #(select-keys % [:name :error :source-path])
                             (filter #(contains? required-single-step-workflows
                                                 (path->workflow-name (:source-path %)))
                                     parse-errors)))))
      (is (every? #(not (contains? required-single-step-workflows (:name %))) errors)
          (str "Single-step markdown workflows must not hit compile errors: "
               (pr-str (filter #(contains? required-single-step-workflows (:name %)) errors)))))))

(deftest checked-in-multi-step-workflows-live-in-edn-files-test
  (testing "required checked-in multi-step workflows are represented by checked-in .edn artifacts, while malformed migration blockers are reported explicitly"
    (let [{:keys [by-name files-by-name-and-kind]} (workflow-migration-view)
          known-workflow-names (set (keys files-by-name-and-kind))]
      (is (every? known-workflow-names required-multi-step-workflows)
          (str "Missing required multi-step workflows: "
               (pr-str (sort (remove known-workflow-names required-multi-step-workflows)))))
      (doseq [workflow-name required-multi-step-workflows]
        (let [definition (get by-name workflow-name)
              edn-paths (get-in files-by-name-and-kind [workflow-name :edn])]
          (is (seq edn-paths)
              (str workflow-name " should have a checked-in .edn workflow file"))
          (when (contains? by-name workflow-name)
            (is (seq (:steps definition))
                (str workflow-name " should compile to a non-empty steps vector when present in the compiled corpus"))))))))

(deftest checked-in-workflow-corpus-has-no-mixed-kind-collisions-test
  (testing "the checked-in workflow corpus has no mixed-kind name collisions under the finalized split contract"
    (let [{:keys [files-by-name-and-kind]} (workflow-migration-view)
          mixed-kind-names (->> files-by-name-and-kind
                                (keep (fn [[workflow-name kind->paths]]
                                        (when (> (count (keys kind->paths)) 1)
                                          workflow-name)))
                                sort
                                vec)]
      (is (empty? mixed-kind-names)
          (str "Checked-in workflow corpus still has mixed-kind collisions: "
               (pr-str mixed-kind-names))))))

(deftest checked-in-invalid-markdown-workflow-artifacts-are-explicitly-shaped-test
  (testing "remaining invalid checked-in markdown artifacts are named explicitly by contract shape"
    (let [{:keys [parse-errors]} (workflow-migration-view)
          empty-body-md-names (->> parse-errors
                                   (filter #(= "Standalone markdown workflow body must not be empty" (:error %)))
                                   (map (comp path->workflow-name :source-path))
                                   sort
                                   vec)
          edn-bodied-md-names (->> parse-errors
                                   (filter #(= "Markdown workflow body must not begin with an EDN workflow definition block" (:error %)))
                                   (map (comp path->workflow-name :source-path))
                                   sort
                                   vec)]
      (is (empty? empty-body-md-names)
          (str "Empty-body markdown blockers remain: " (pr-str empty-body-md-names)))
      (is (= ["gh-bug-discover-and-read"
              "gh-bug-post-repro"
              "gh-bug-reproduce"
              "gh-issue-create-worktree"
              "gh-issue-push-intent"
              "gh-issue-task-intent"]
             edn-bodied-md-names)
          (str "EDN-bodied markdown blockers drifted: " (pr-str edn-bodied-md-names))))))

(deftest checked-in-workflow-corpus-required-sample-covers-no-other-collisions-test
  (testing "the required sample sets are disjoint and covered by the repository corpus"
    (let [{:keys [files-by-name-and-kind]} (workflow-migration-view)
          all-required (set/union required-single-step-workflows required-multi-step-workflows)]
      (is (empty? (set/intersection required-single-step-workflows required-multi-step-workflows))
          "Required single-step and multi-step workflow sample sets should be disjoint")
      (is (every? #(contains? files-by-name-and-kind %) all-required)
          (str "Required sample workflows missing from corpus: "
               (pr-str (sort (remove #(contains? files-by-name-and-kind %) all-required))))))))

(deftest implement-task-implementation-pass-declares-blocked-handback-contract-test
  ;; Tests the authored pass prompt owns the implementation-blocked policy.
  (testing "the implementation pass permits three statuses and requires a durable actionable blocker record"
    (let [prompt (slurp ".psi/workflows/implement-task-implement-pass.md")
          status-lines (->> (str/split-lines prompt)
                            (filter #(str/starts-with? % "PASS_STATUS: "))
                            vec)]
      (is (= ["PASS_STATUS: MORE_WORK_REMAINS"
              "PASS_STATUS: IMPLEMENTATION_COMPLETE"
              "PASS_STATUS: IMPLEMENTATION_BLOCKED"]
             status-lines))
      (is (str/includes? prompt "<!-- IMPLEMENTATION_BLOCKER: START -->"))
      (is (str/includes? prompt "- blocker: <concise concrete blocker>"))
      (is (str/includes? prompt "- required-human-action: <safe action or decision>"))
      (is (str/includes? prompt "<!-- IMPLEMENTATION_BLOCKER: END -->"))
      (is (str/includes? prompt "concise non-empty text"))
      (is (< (.indexOf prompt "IMPLEMENTATION_BLOCKER: START")
             (.indexOf prompt "PASS_STATUS: IMPLEMENTATION_BLOCKED")))
      (is (str/includes? prompt
                         "append the complete `IMPLEMENTATION_BLOCKER` block above before emitting that final status line")))))

(deftest implement-task-definition-declares-three-authored-terminal-routes-test
  ;; Tests the checked-in workflow definition keeps blocked policy authored.
  (testing "implement-task uses exact marker routing and distinct branch summaries"
    (let [workflow (edn/read-string (slurp ".psi/workflows/implement-task.edn"))
          steps (:steps workflow)
          step-by-name (into {} (map (juxt :name identity)) steps)
          implement-pass (get step-by-name "implement-pass")
          complete-summary (get step-by-name "final-summary-complete")
          blocked-summary (get step-by-name "final-summary-blocked")
          blocker-validation (get step-by-name "validate-implementation-blocker")
          blocked-prompt (get-in blocked-summary [:contributions 2 :text])]
      (is (= "workflow/exact-marker-routing" (get-in implement-pass [:judge :operation])))
      (is (= {:marker-label "PASS_STATUS"
              :allowed-routes ["MORE_WORK_REMAINS"
                               "IMPLEMENTATION_COMPLETE"
                               "IMPLEMENTATION_BLOCKED"]}
             (select-keys (get-in implement-pass [:judge :args])
                          [:marker-label :allowed-routes])))
      (is (= {"MORE_WORK_REMAINS" {:goto "capture-implementation-before-pass" :max-iterations 20}
              "IMPLEMENTATION_COMPLETE" {:goto "final-summary-complete"}
              "IMPLEMENTATION_BLOCKED" {:goto "validate-implementation-blocker"}}
             (:on implement-pass)))
      (is (= "workflow/fresh-final-complete-block-routing"
             (:operation blocker-validation)))
      (is (= {:type :invoke
              :operation "workflow/constant-routing"
              :args {:route "DONE"}}
             (:judge blocker-validation)))
      (is (= {"DONE" {:goto "final-summary-blocked"}}
             (:on blocker-validation)))
      (is (some? complete-summary))
      (is (some? blocked-summary))
      (is (= "workflow/exact-marker-routing"
             (get-in complete-summary [:judge :operation])))
      (is (= "workflow/exact-marker-routing"
             (get-in blocked-summary [:judge :operation])))
      (is (= ["IMPLEMENTATION_BLOCKED"]
             (get-in blocked-summary [:judge :args :allowed-routes])))
      (is (= ["IMPLEMENTATION_BLOCKER" "IMPLEMENTATION_REQUIRED_HUMAN_ACTION"]
             (get-in blocked-summary [:judge :args :required-field-labels-by-route
                                      "IMPLEMENTATION_BLOCKED"])))
      (is (str/includes? (get-in complete-summary [:contributions 2 :text])
                         "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE"))
      (is (str/includes? blocked-prompt
                         "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"))
      (is (str/includes? blocked-prompt
                         "selected this blocker record from one artifact snapshot"))
      (is (str/includes? blocked-prompt
                         "Do not re-read or select a blocker record"))
      (is (str/includes? blocked-prompt "IMPLEMENTATION_BLOCKER: {{blocker}}"))
      (is (str/includes? blocked-prompt
                         "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: {{required-human-action}}")))))
