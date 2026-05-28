(ns psi.workflow-loader.workflow-definitions-test
  "Loader/compiler tests for new and renamed workflow definitions.

   Each test loads a workflow through the full loader/compiler pipeline using
   temp-dir fixtures. Tests assert: no load errors, correct step count, correct
   step names, correct step types, and that :prompt-workflow references resolve.
   For judge steps: assert expected :on routing keys and :outputs presence."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.core :as loader]))

;;; ---------------------------------------------------------------------------
;;; Fixtures

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

(defn- load-edn-with-md-refs
  "Load an edn workflow and all its referenced .md files from the real .psi/workflows dir."
  [edn-filename md-filenames f]
  (let [files (into {edn-filename (slurp-workflow-file edn-filename)}
                    (map (fn [md] [md (slurp-workflow-file md)]) md-filenames))]
    (with-workflow-dir files f)))

;;; ---------------------------------------------------------------------------
;;; review-task-design

(deftest review-task-design-loads-test
  (testing "review-task-design loads without error"
    (load-edn-with-md-refs
     "review-task-design.edn"
     ["review-task-design-ambiguity-review.md"
      "review-task-design-ambiguity-follow-up.md"
      "review-task-design-inconsistency-review.md"
      "review-task-design-inconsistency-follow-up.md"
      "review-task-design-clarity-status.md"
      "review-task-design-final-summary.md"]
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (is (contains? definitions "review-task-design"))))))

(deftest review-task-design-step-count-test
  (testing "review-task-design has 6 steps"
    (load-edn-with-md-refs
     "review-task-design.edn"
     ["review-task-design-ambiguity-review.md"
      "review-task-design-ambiguity-follow-up.md"
      "review-task-design-inconsistency-review.md"
      "review-task-design-inconsistency-follow-up.md"
      "review-task-design-clarity-status.md"
      "review-task-design-final-summary.md"]
     (fn [{:keys [definitions]}]
       (is (= 6 (count (get-in definitions ["review-task-design" :steps]))))))))

(deftest review-task-design-step-names-and-types-test
  (testing "review-task-design has correct step names and types"
    (load-edn-with-md-refs
     "review-task-design.edn"
     ["review-task-design-ambiguity-review.md"
      "review-task-design-ambiguity-follow-up.md"
      "review-task-design-inconsistency-review.md"
      "review-task-design-inconsistency-follow-up.md"
      "review-task-design-clarity-status.md"
      "review-task-design-final-summary.md"]
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["review-task-design" :steps])]
         (is (= ["ambiguity-review"
                 "ambiguity-follow-up"
                 "inconsistency-review"
                 "inconsistency-follow-up"
                 "clarity-status"
                 "final-summary"]
                (mapv :name steps)))
         (is (= [:session :session :session :session :session :session]
                (mapv :type steps))))))))

(deftest review-task-design-prompt-workflow-resolves-test
  (testing "review-task-design prompt-workflow references resolve to contributions"
    (load-edn-with-md-refs
     "review-task-design.edn"
     ["review-task-design-ambiguity-review.md"
      "review-task-design-ambiguity-follow-up.md"
      "review-task-design-inconsistency-review.md"
      "review-task-design-inconsistency-follow-up.md"
      "review-task-design-clarity-status.md"
      "review-task-design-final-summary.md"]
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["review-task-design" :steps])]
         (doseq [step (take 5 steps)]
           (is (seq (:contributions step))
               (str "step " (:name step) " should have contributions from resolved .md")))
         (is (not (some #(contains? % :prompt-workflow) steps))
             "no step should retain :prompt-workflow after compilation"))))))

(deftest review-task-design-judge-routing-test
  (testing "review-task-design clarity-status judge has REPEAT/DONE routing"
    (load-edn-with-md-refs
     "review-task-design.edn"
     ["review-task-design-ambiguity-review.md"
      "review-task-design-ambiguity-follow-up.md"
      "review-task-design-inconsistency-review.md"
      "review-task-design-inconsistency-follow-up.md"
      "review-task-design-clarity-status.md"
      "review-task-design-final-summary.md"]
     (fn [{:keys [definitions]}]
       (let [clarity-step (->> (get-in definitions ["review-task-design" :steps])
                               (filter #(= "clarity-status" (:name %)))
                               first)]
         (is (= #{"REPEAT" "DONE"} (set (keys (:on clarity-step)))))
         (is (some? (:judge clarity-step))))))))

(deftest review-task-design-judge-outputs-test
  (testing "review-task-design clarity-status judge has :outputs key"
    (load-edn-with-md-refs
     "review-task-design.edn"
     ["review-task-design-ambiguity-review.md"
      "review-task-design-ambiguity-follow-up.md"
      "review-task-design-inconsistency-review.md"
      "review-task-design-inconsistency-follow-up.md"
      "review-task-design-clarity-status.md"
      "review-task-design-final-summary.md"]
     (fn [{:keys [definitions]}]
       (let [clarity-step (->> (get-in definitions ["review-task-design" :steps])
                               (filter #(= "clarity-status" (:name %)))
                               first)]
         (is (contains? (:judge clarity-step) :outputs))
         (is (= :psi.workflow/judge-routing-result
                (get-in clarity-step [:judge :outputs :schema-id]))))))))

;;; ---------------------------------------------------------------------------
;;; review-task-plan

(deftest review-task-plan-loads-test
  (testing "review-task-plan loads without error"
    (load-edn-with-md-refs
     "review-task-plan.edn"
     ["review-task-plan-ambiguity-review.md"
      "review-task-plan-ambiguity-follow-up.md"
      "review-task-plan-inconsistency-review.md"
      "review-task-plan-inconsistency-follow-up.md"
      "review-task-plan-clarity-status.md"]
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (is (contains? definitions "review-task-plan"))))))

(deftest review-task-plan-step-count-test
  (testing "review-task-plan has 6 steps"
    (load-edn-with-md-refs
     "review-task-plan.edn"
     ["review-task-plan-ambiguity-review.md"
      "review-task-plan-ambiguity-follow-up.md"
      "review-task-plan-inconsistency-review.md"
      "review-task-plan-inconsistency-follow-up.md"
      "review-task-plan-clarity-status.md"]
     (fn [{:keys [definitions]}]
       (is (= 6 (count (get-in definitions ["review-task-plan" :steps]))))))))

(deftest review-task-plan-step-names-and-types-test
  (testing "review-task-plan has correct step names and types"
    (load-edn-with-md-refs
     "review-task-plan.edn"
     ["review-task-plan-ambiguity-review.md"
      "review-task-plan-ambiguity-follow-up.md"
      "review-task-plan-inconsistency-review.md"
      "review-task-plan-inconsistency-follow-up.md"
      "review-task-plan-clarity-status.md"]
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["review-task-plan" :steps])]
         (is (= ["ambiguity-review"
                 "ambiguity-follow-up"
                 "inconsistency-review"
                 "inconsistency-follow-up"
                 "clarity-status"
                 "final-summary"]
                (mapv :name steps)))
         (is (= [:session :session :session :session :session :session]
                (mapv :type steps))))))))

(deftest review-task-plan-judge-routing-test
  (testing "review-task-plan clarity-status judge has REPEAT/DONE routing"
    (load-edn-with-md-refs
     "review-task-plan.edn"
     ["review-task-plan-ambiguity-review.md"
      "review-task-plan-ambiguity-follow-up.md"
      "review-task-plan-inconsistency-review.md"
      "review-task-plan-inconsistency-follow-up.md"
      "review-task-plan-clarity-status.md"]
     (fn [{:keys [definitions]}]
       (let [clarity-step (->> (get-in definitions ["review-task-plan" :steps])
                               (filter #(= "clarity-status" (:name %)))
                               first)]
         (is (= #{"REPEAT" "DONE"} (set (keys (:on clarity-step)))))
         (is (some? (:judge clarity-step))))))))

(deftest review-task-plan-judge-outputs-test
  (testing "review-task-plan clarity-status judge has :outputs key"
    (load-edn-with-md-refs
     "review-task-plan.edn"
     ["review-task-plan-ambiguity-review.md"
      "review-task-plan-ambiguity-follow-up.md"
      "review-task-plan-inconsistency-review.md"
      "review-task-plan-inconsistency-follow-up.md"
      "review-task-plan-clarity-status.md"]
     (fn [{:keys [definitions]}]
       (let [clarity-step (->> (get-in definitions ["review-task-plan" :steps])
                               (filter #(= "clarity-status" (:name %)))
                               first)]
         (is (contains? (:judge clarity-step) :outputs))
         (is (= :psi.workflow/judge-routing-result
                (get-in clarity-step [:judge :outputs :schema-id]))))))))

;;; ---------------------------------------------------------------------------
;;; review-task-implementation

(deftest review-task-implementation-loads-test
  (testing "review-task-implementation loads without error"
    (with-workflow-dir
      {"review-task-implementation.edn" (slurp-workflow-file "review-task-implementation.edn")}
      (fn [{:keys [definitions errors]}]
        (is (empty? errors))
        (is (contains? definitions "review-task-implementation"))))))

(deftest review-task-implementation-step-count-test
  (testing "review-task-implementation has 5 steps"
    (with-workflow-dir
      {"review-task-implementation.edn" (slurp-workflow-file "review-task-implementation.edn")}
      (fn [{:keys [definitions]}]
        (is (= 5 (count (get-in definitions ["review-task-implementation" :steps]))))))))

(deftest review-task-implementation-step-names-and-types-test
  (testing "review-task-implementation has correct step names and types"
    (with-workflow-dir
      {"review-task-implementation.edn" (slurp-workflow-file "review-task-implementation.edn")}
      (fn [{:keys [definitions]}]
        (let [steps (get-in definitions ["review-task-implementation" :steps])]
          (is (= ["review-task-implementation"
                  "review-task-tests"
                  "review-test-shape"
                  "review-task-docs"
                  "review-code-shape"]
                 (mapv :name steps)))
          (is (= [:delegate :delegate :delegate :delegate :delegate]
                 (mapv :type steps))))))))

;;; ---------------------------------------------------------------------------
;;; create-task-plan

(deftest create-task-plan-loads-test
  (testing "create-task-plan loads without error"
    (load-edn-with-md-refs
     "create-task-plan.edn"
     ["create-task-plan-create-plan.md"]
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (is (contains? definitions "create-task-plan"))))))

(deftest create-task-plan-step-count-test
  (testing "create-task-plan has 1 step"
    (load-edn-with-md-refs
     "create-task-plan.edn"
     ["create-task-plan-create-plan.md"]
     (fn [{:keys [definitions]}]
       (is (= 1 (count (get-in definitions ["create-task-plan" :steps]))))))))

(deftest create-task-plan-step-names-and-types-test
  (testing "create-task-plan has correct step name and type"
    (load-edn-with-md-refs
     "create-task-plan.edn"
     ["create-task-plan-create-plan.md"]
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["create-task-plan" :steps])]
         (is (= ["create-plan"] (mapv :name steps)))
         (is (= [:session] (mapv :type steps)))
         (is (seq (:contributions (first steps)))
             "create-plan step should have contributions from resolved .md"))))))
