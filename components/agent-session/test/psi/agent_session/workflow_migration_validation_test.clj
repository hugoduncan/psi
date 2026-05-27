(ns psi.agent-session.workflow-migration-validation-test
  "Validate the checked-in workflow corpus against the finalized file-kind
   split contract for `.psi/workflows/`."
  (:require
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
  (testing "required checked-in multi-step workflows are represented only by .edn files and compile successfully"
    (let [{:keys [parse-errors errors by-name files-by-name-and-kind]} (workflow-migration-view)
          known-workflow-names (set (keys files-by-name-and-kind))]
      (is (every? known-workflow-names required-multi-step-workflows)
          (str "Missing required multi-step workflows: "
               (pr-str (sort (remove known-workflow-names required-multi-step-workflows)))))
      (doseq [workflow-name required-multi-step-workflows]
        (let [definition (get by-name workflow-name)]
          (is (seq (get-in files-by-name-and-kind [workflow-name :edn]))
              (str workflow-name " should have a checked-in .edn workflow file"))
          (is (empty? (get-in files-by-name-and-kind [workflow-name :md]))
              (str workflow-name " should not have a sibling .md workflow file under the finalized split contract"))
          (is (not-any? #(= workflow-name (path->workflow-name (:source-path %))) parse-errors)
              (str workflow-name " should not have parse errors"))
          (is (contains? by-name workflow-name)
              (str workflow-name " should compile successfully"))
          (is (not-any? #(= workflow-name (:name %)) errors)
              (str workflow-name " should not have compile errors"))
          (is (seq (:steps definition))
              (str workflow-name " should compile to a non-empty steps vector")))))))

(deftest checked-in-workflow-corpus-has-no-mixed-kind-collisions-test
  (testing "the checked-in workflow corpus has no sibling mixed-kind name collisions"
    (let [{:keys [files-by-name-and-kind]} (workflow-migration-view)
          mixed-kind-names (->> files-by-name-and-kind
                                (keep (fn [[workflow-name kind->paths]]
                                        (when (> (count (keys kind->paths)) 1)
                                          workflow-name)))
                                sort
                                vec)]
      (is (empty? mixed-kind-names)
          (str "Checked-in workflow corpus still contains mixed-kind collisions: "
               (pr-str mixed-kind-names))))))

(deftest checked-in-workflow-corpus-required-sample-covers-no-other-collisions-test
  (testing "the required sample sets are disjoint and covered by the repository corpus"
    (let [{:keys [files-by-name-and-kind]} (workflow-migration-view)
          all-required (set/union required-single-step-workflows required-multi-step-workflows)]
      (is (empty? (set/intersection required-single-step-workflows required-multi-step-workflows))
          "Required single-step and multi-step workflow sample sets should be disjoint")
      (is (every? #(contains? files-by-name-and-kind %) all-required)
          (str "Required sample workflows missing from corpus: "
               (pr-str (sort (remove #(contains? files-by-name-and-kind %) all-required))))))))
