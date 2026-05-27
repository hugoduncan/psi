(ns psi.agent-session.workflow-migration-validation-test
  "Validate the deferred-migration compatibility contract for checked-in
   `.psi/workflows/*.md` artifacts during the file-kind split task."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.compiler :as compiler]
   [psi.workflow-loader.core :as loader]))

(def ^:private required-single-step-workflows
  #{"planner"
    "builder"
    "reviewer"})

(def ^:private required-transitional-multi-step-workflows
  #{"plan-build"
    "plan-build-review"
    "delegate-build-review"
    "gh-bug-triage-modular"
    "prompt-build"
    "lambda-build"})

(defn- parse-workflow-name
  [entry]
  (or (:name entry)
      (get-in entry [:config :name])
      (some-> entry :source-path java.io.File. .getName (str/replace #"\.(md|edn)$" ""))))

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
  (into {}
        (keep (fn [entry]
                (when-let [workflow-name (parse-workflow-name entry)]
                  [workflow-name entry])))
        parsed))

(defn- body-has-leading-edn-map-error?
  [entry]
  (= "Markdown workflow body must not begin with an EDN workflow definition block"
     (:error entry)))

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
          (str "Single-step markdown workflows must not hit transitional parse errors: "
               (pr-str (mapv #(select-keys % [:name :error :source-path])
                             (filter #(contains? required-single-step-workflows
                                                 (parse-workflow-name %))
                                     parse-errors)))))
      (is (every? #(not (contains? required-single-step-workflows (:name %))) errors)
          (str "Single-step markdown workflows must not hit compile errors: "
               (pr-str (filter #(contains? required-single-step-workflows (:name %)) errors)))))))

(deftest checked-in-transitional-multi-step-markdown-workflows-are-explicitly-covered-test
  (testing "required checked-in multi-step markdown workflows are explicitly covered by the deferred-migration compatibility contract"
    (let [{:keys [parsed by-name]} (workflow-migration-view)
          parsed-name->entry (parsed-by-name parsed)]
      (is (every? #(contains? parsed-name->entry %) required-transitional-multi-step-workflows)
          (str "Missing required transitional multi-step markdown artifacts: "
               (pr-str (sort (remove #(contains? parsed-name->entry %) required-transitional-multi-step-workflows)))))
      (doseq [workflow-name required-transitional-multi-step-workflows]
        (let [entry (get parsed-name->entry workflow-name)]
          (is (= :md (:file-kind entry))
              (str workflow-name " should still be a checked-in transitional markdown artifact during this task"))
          (is (body-has-leading-edn-map-error? entry)
              (str workflow-name " should be surfaced by the markdown-single-step parser as a transitional multi-step markdown artifact"))
          (is (not (contains? by-name workflow-name))
              (str workflow-name " should not compile through the new standalone markdown path until a later migration moves it to .edn")))))))
