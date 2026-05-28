(ns psi.workflow-loader.workflow-definitions-test
  "Loader/compiler tests for new and renamed workflow definitions.

   Each test loads a workflow through the full loader/compiler pipeline using
   temp-dir fixtures. Tests assert: no load errors, correct step count, correct
   step names, correct step types, and that :prompt-workflow references resolve.
   For judge steps: assert expected :on routing keys and :outputs presence.
   For {{input}}-bearing steps: assert :vars wired to :workflow-input."
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

;;; ---------------------------------------------------------------------------
;;; review-task-design

(deftest review-task-design-loads-test
  (testing "review-task-design loads without error"
    (load-edn-only
     "review-task-design.edn"
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (is (contains? definitions "review-task-design"))))))

(deftest review-task-design-step-count-test
  (testing "review-task-design has 6 steps"
    (load-edn-only
     "review-task-design.edn"
     (fn [{:keys [definitions]}]
       (is (= 6 (count (get-in definitions ["review-task-design" :steps]))))))))

(deftest review-task-design-step-names-and-types-test
  (testing "review-task-design has correct step names and types"
    (load-edn-only
     "review-task-design.edn"
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

(deftest review-task-design-input-vars-wired-test
  (testing "review-task-design actor steps have {{input}} wired to :workflow-input"
    (load-edn-only
     "review-task-design.edn"
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["review-task-design" :steps])]
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))))))

(deftest review-task-design-judge-routing-test
  (testing "review-task-design clarity-status judge has REPEAT/DONE routing"
    (load-edn-only
     "review-task-design.edn"
     (fn [{:keys [definitions]}]
       (let [clarity-step (->> (get-in definitions ["review-task-design" :steps])
                               (filter #(= "clarity-status" (:name %)))
                               first)]
         (is (= #{"REPEAT" "DONE"} (set (keys (:on clarity-step)))))
         (is (some? (:judge clarity-step))))))))

(deftest review-task-design-judge-outputs-test
  (testing "review-task-design clarity-status judge has :outputs key with routing-result entry"
    (load-edn-only
     "review-task-design.edn"
     (fn [{:keys [definitions]}]
       (let [clarity-step (->> (get-in definitions ["review-task-design" :steps])
                               (filter #(= "clarity-status" (:name %)))
                               first)]
         (is (contains? (:judge clarity-step) :outputs))
         (is (= :psi.workflow/judge-routing-result
                (get-in clarity-step [:judge :outputs :routing-result :schema-id]))))))))

;;; ---------------------------------------------------------------------------
;;; review-task-plan

(deftest review-task-plan-loads-test
  (testing "review-task-plan loads without error"
    (load-edn-only
     "review-task-plan.edn"
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (is (contains? definitions "review-task-plan"))))))

(deftest review-task-plan-step-count-test
  (testing "review-task-plan has 6 steps"
    (load-edn-only
     "review-task-plan.edn"
     (fn [{:keys [definitions]}]
       (is (= 6 (count (get-in definitions ["review-task-plan" :steps]))))))))

(deftest review-task-plan-step-names-and-types-test
  (testing "review-task-plan has correct step names and types"
    (load-edn-only
     "review-task-plan.edn"
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

(deftest review-task-plan-input-vars-wired-test
  (testing "review-task-plan actor steps have {{input}} wired to :workflow-input"
    (load-edn-only
     "review-task-plan.edn"
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["review-task-plan" :steps])]
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))))))

(deftest review-task-plan-judge-routing-test
  (testing "review-task-plan clarity-status judge has REPEAT/DONE routing"
    (load-edn-only
     "review-task-plan.edn"
     (fn [{:keys [definitions]}]
       (let [clarity-step (->> (get-in definitions ["review-task-plan" :steps])
                               (filter #(= "clarity-status" (:name %)))
                               first)]
         (is (= #{"REPEAT" "DONE"} (set (keys (:on clarity-step)))))
         (is (some? (:judge clarity-step))))))))

(deftest review-task-plan-judge-outputs-test
  (testing "review-task-plan clarity-status judge has :outputs key with routing-result entry"
    (load-edn-only
     "review-task-plan.edn"
     (fn [{:keys [definitions]}]
       (let [clarity-step (->> (get-in definitions ["review-task-plan" :steps])
                               (filter #(= "clarity-status" (:name %)))
                               first)]
         (is (contains? (:judge clarity-step) :outputs))
         (is (= :psi.workflow/judge-routing-result
                (get-in clarity-step [:judge :outputs :routing-result :schema-id]))))))))

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
    (load-edn-only
     "create-task-plan.edn"
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (is (contains? definitions "create-task-plan"))))))

(deftest create-task-plan-step-count-test
  (testing "create-task-plan has 1 step"
    (load-edn-only
     "create-task-plan.edn"
     (fn [{:keys [definitions]}]
       (is (= 1 (count (get-in definitions ["create-task-plan" :steps]))))))))

(deftest create-task-plan-step-names-and-types-test
  (testing "create-task-plan has correct step name and type"
    (load-edn-only
     "create-task-plan.edn"
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["create-task-plan" :steps])]
         (is (= ["create-plan"] (mapv :name steps)))
         (is (= [:session] (mapv :type steps)))
         (is (seq (:contributions (first steps)))
             "create-plan step should have contributions"))))))

(deftest create-task-plan-input-vars-wired-test
  (testing "create-task-plan create-plan step has {{input}} wired to :workflow-input"
    (load-edn-only
     "create-task-plan.edn"
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["create-task-plan" :steps])]
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))))))

;;; ---------------------------------------------------------------------------
;;; review-step

(deftest review-step-loads-test
  (testing "review-step loads without error"
    (load-edn-only
     "review-step.edn"
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (is (contains? definitions "review-step"))))))

(deftest review-step-step-count-test
  (testing "review-step has 2 steps"
    (load-edn-only
     "review-step.edn"
     (fn [{:keys [definitions]}]
       (is (= 2 (count (get-in definitions ["review-step" :steps]))))))))

(deftest review-step-step-names-and-types-test
  (testing "review-step has correct step names and types"
    (load-edn-only
     "review-step.edn"
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["review-step" :steps])]
         (is (= ["review" "follow-up"] (mapv :name steps)))
         (is (= [:session :session] (mapv :type steps))))))))

(deftest review-step-input-vars-wired-test
  (testing "review-step steps have {{input}} wired to :workflow-input"
    (load-edn-only
     "review-step.edn"
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["review-step" :steps])]
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))))))

(deftest review-step-judge-routing-test
  (testing "review-step follow-up judge has REPEAT/DONE routing"
    (load-edn-only
     "review-step.edn"
     (fn [{:keys [definitions]}]
       (let [follow-up-step (->> (get-in definitions ["review-step" :steps])
                                 (filter #(= "follow-up" (:name %)))
                                 first)]
         (is (= #{"REPEAT" "DONE"} (set (keys (:on follow-up-step)))))
         (is (some? (:judge follow-up-step))))))))

(deftest review-step-judge-outputs-test
  (testing "review-step follow-up judge has :outputs key with judge-routing-result schema-id"
    (load-edn-only
     "review-step.edn"
     (fn [{:keys [definitions]}]
       (let [follow-up-step (->> (get-in definitions ["review-step" :steps])
                                 (filter #(= "follow-up" (:name %)))
                                 first)]
         (is (contains? (:judge follow-up-step) :outputs))
         (is (= :psi.workflow/judge-routing-result
                (get-in follow-up-step [:judge :outputs :routing-result :schema-id]))))))))

;;; ---------------------------------------------------------------------------
;;; implement-task

(deftest implement-task-loads-test
  (testing "implement-task loads without error"
    (load-edn-only
     "implement-task.edn"
     (fn [{:keys [definitions errors]}]
       (is (empty? errors))
       (is (contains? definitions "implement-task"))))))

(deftest implement-task-step-count-test
  (testing "implement-task has 2 steps"
    (load-edn-only
     "implement-task.edn"
     (fn [{:keys [definitions]}]
       (is (= 2 (count (get-in definitions ["implement-task" :steps]))))))))

(deftest implement-task-step-names-and-types-test
  (testing "implement-task has correct step names and types"
    (load-edn-only
     "implement-task.edn"
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["implement-task" :steps])]
         (is (= ["implement-pass" "final-summary"] (mapv :name steps)))
         (is (= [:session :session] (mapv :type steps))))))))

(deftest implement-task-input-vars-wired-test
  (testing "implement-task actor steps have {{input}} wired to :workflow-input"
    (load-edn-only
     "implement-task.edn"
     (fn [{:keys [definitions]}]
       (let [steps (get-in definitions ["implement-task" :steps])]
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))))))

(deftest implement-task-judge-routing-test
  (testing "implement-task implement-pass judge has REPEAT/DONE routing"
    (load-edn-only
     "implement-task.edn"
     (fn [{:keys [definitions]}]
       (let [pass-step (->> (get-in definitions ["implement-task" :steps])
                            (filter #(= "implement-pass" (:name %)))
                            first)]
         (is (= #{"REPEAT" "DONE"} (set (keys (:on pass-step)))))
         (is (some? (:judge pass-step))))))))

(deftest implement-task-judge-outputs-test
  (testing "implement-task implement-pass judge has :outputs key with routing-result entry"
    (load-edn-only
     "implement-task.edn"
     (fn [{:keys [definitions]}]
       (let [pass-step (->> (get-in definitions ["implement-task" :steps])
                            (filter #(= "implement-pass" (:name %)))
                            first)]
         (is (contains? (:judge pass-step) :outputs))
         (is (= :psi.workflow/judge-routing-result
                (get-in pass-step [:judge :outputs :routing-result :schema-id]))))))))
