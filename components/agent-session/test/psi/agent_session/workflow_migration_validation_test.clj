(ns psi.agent-session.workflow-migration-validation-test
  "Validate the checked-in workflow corpus against the finalized file-kind
   split contract for `.psi/workflows/`."
  (:require
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

(defn- parse-workflow-name
  [entry]
  (or (:name entry)
      (get-in entry [:config :name])
      (some-> entry :source-path java.io.File. .getName (str/replace #"\.(md|edn)$" ""))))

(defn- file-kind-for
  [parsed-name->entry workflow-name]
  (->> (get parsed-name->entry workflow-name)
       :source-path
       (re-find #"\.(md|edn)$")
       second
       keyword))

(defn- workflow-migration-view
  []
  (let [parsed (loader/scan-directory ".psi/workflows")
        compile-result (compiler/compile-workflow-files parsed)
        definitions (:definitions compile-result)]
    {:parsed parsed
     :parse-errors (filter :error parsed)
     :definitions definitions
     :errors (:errors compile-result)
     :by-name (into {} (map (juxt :name identity)) definitions)}))

(defn- parsed-by-name
  [parsed]
  (reduce (fn [acc entry]
            (if-let [workflow-name (parse-workflow-name entry)]
              (assoc acc workflow-name entry)
              acc))
          {}
          parsed))

(deftest checked-in-single-step-markdown-workflows-still-compile-test
  (testing "required checked-in single-step markdown workflows still parse and compile as standalone markdown workflows"
    (let [{:keys [parsed parse-errors errors by-name]} (workflow-migration-view)]
      (is (every? #(contains? (set (map parse-workflow-name parsed)) %) required-single-step-workflows)
          (str "Missing required single-step markdown workflows: "
               (pr-str (sort (remove #(contains? (set (map parse-workflow-name parsed)) %) required-single-step-workflows)))))
      (is (every? #(contains? by-name %) required-single-step-workflows)
          (str "Missing compiled single-step markdown workflows: "
               (pr-str (sort (remove #(contains? by-name %) required-single-step-workflows)))))
      (is (every? #(not (contains? required-single-step-workflows (parse-workflow-name %)))
                  parse-errors)
          (str "Single-step markdown workflows must not hit parse errors: "
               (pr-str (mapv #(select-keys % [:name :error :source-path])
                             (filter #(contains? required-single-step-workflows
                                                 (parse-workflow-name %))
                                     parse-errors)))))
      (is (every? #(not (contains? required-single-step-workflows (:name %))) errors)
          (str "Single-step markdown workflows must not hit compile errors: "
               (pr-str (filter #(contains? required-single-step-workflows (:name %)) errors)))))))

(deftest checked-in-multi-step-workflows-live-in-edn-files-test
  (testing "required checked-in multi-step workflows are discovered from .edn files and compile successfully"
    (let [{:keys [parsed parse-errors errors by-name]} (workflow-migration-view)
          parsed-name->entry (parsed-by-name parsed)]
      (is (every? #(contains? parsed-name->entry %) required-multi-step-workflows)
          (str "Missing required multi-step workflows: "
               (pr-str (sort (remove #(contains? parsed-name->entry %) required-multi-step-workflows)))))
      (doseq [workflow-name required-multi-step-workflows]
        (let [definition (get by-name workflow-name)]
          (is (= :edn (file-kind-for parsed-name->entry workflow-name))
              (str workflow-name " should be discovered from a checked-in .edn file"))
          (is (not (contains? (set (map parse-workflow-name parse-errors)) workflow-name))
              (str workflow-name " should not have parse errors"))
          (is (contains? by-name workflow-name)
              (str workflow-name " should compile successfully"))
          (is (not-any? #(= workflow-name (:name %)) errors)
              (str workflow-name " should not have compile errors"))
          (is (seq (:steps definition))
              (str workflow-name " should compile to a non-empty steps vector")))))))
