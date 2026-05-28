(ns psi.workflow-loader.workflow-definitions-test
  "Loader/compiler tests for new and renamed workflow definitions.

   Each workflow gets one deftest with testing blocks, one load-edn-only call.
   Tests assert: no load errors, correct step count, correct step names and
   types, :vars wired to :workflow-input for {{input}}-bearing steps, and for
   judge steps: expected :on routing keys and :outputs presence."
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

(deftest review-task-design-test
  (load-edn-only
   "review-task-design.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "review-task-design")))
     (let [steps (get-in definitions ["review-task-design" :steps])]
       (testing "has 6 steps with correct names and types"
         (is (= 6 (count steps)))
         (is (= ["ambiguity-review"
                 "ambiguity-follow-up"
                 "inconsistency-review"
                 "inconsistency-follow-up"
                 "clarity-status"
                 "final-summary"]
                (mapv :name steps)))
         (is (= [:session :session :session :session :session :session]
                (mapv :type steps))))
       (testing "actor steps have {{input}} wired to :workflow-input"
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))
       (let [clarity-step (first (filter #(= "clarity-status" (:name %)) steps))]
         (testing "clarity-status judge has REPEAT/DONE routing"
           (is (= #{"REPEAT" "DONE"} (set (keys (:on clarity-step)))))
           (is (some? (:judge clarity-step))))
         (testing "clarity-status judge has :outputs with judge-routing-result schema-id"
           (is (contains? (:judge clarity-step) :outputs))
           (is (= :psi.workflow/judge-routing-result
                  (get-in clarity-step [:judge :outputs :routing-result :schema-id])))))))))

;;; ---------------------------------------------------------------------------
;;; review-task-plan

(deftest review-task-plan-test
  (load-edn-only
   "review-task-plan.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "review-task-plan")))
     (let [steps (get-in definitions ["review-task-plan" :steps])]
       (testing "has 6 steps with correct names and types"
         (is (= 6 (count steps)))
         (is (= ["ambiguity-review"
                 "ambiguity-follow-up"
                 "inconsistency-review"
                 "inconsistency-follow-up"
                 "clarity-status"
                 "final-summary"]
                (mapv :name steps)))
         (is (= [:session :session :session :session :session :session]
                (mapv :type steps))))
       (testing "actor steps have {{input}} wired to :workflow-input"
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))
       (let [clarity-step (first (filter #(= "clarity-status" (:name %)) steps))]
         (testing "clarity-status judge has REPEAT/DONE routing"
           (is (= #{"REPEAT" "DONE"} (set (keys (:on clarity-step)))))
           (is (some? (:judge clarity-step))))
         (testing "clarity-status judge has :outputs with judge-routing-result schema-id"
           (is (contains? (:judge clarity-step) :outputs))
           (is (= :psi.workflow/judge-routing-result
                  (get-in clarity-step [:judge :outputs :routing-result :schema-id])))))))))

;;; ---------------------------------------------------------------------------
;;; review-task-implementation

(deftest review-task-implementation-test
  (load-edn-only
   "review-task-implementation.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "review-task-implementation")))
     (let [steps (get-in definitions ["review-task-implementation" :steps])]
       (testing "has 5 steps with correct names and types"
         (is (= 5 (count steps)))
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

(deftest create-task-plan-test
  (load-edn-only
   "create-task-plan.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "create-task-plan")))
     (let [steps (get-in definitions ["create-task-plan" :steps])]
       (testing "has 1 step with correct name and type"
         (is (= 1 (count steps)))
         (is (= ["create-plan"] (mapv :name steps)))
         (is (= [:session] (mapv :type steps)))
         (is (seq (:contributions (first steps)))
             "create-plan step should have contributions"))
       (testing "create-plan step has {{input}} wired to :workflow-input"
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))))))

;;; ---------------------------------------------------------------------------
;;; review-step

(deftest review-step-test
  (load-edn-only
   "review-step.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "review-step")))
     (let [steps (get-in definitions ["review-step" :steps])]
       (testing "has 3 steps with correct names and types"
         (is (= 3 (count steps)))
         (is (= ["review" "follow-up" "review-status"] (mapv :name steps)))
         (is (= [:session :session :session] (mapv :type steps))))
       (testing "steps have {{input}} wired to :workflow-input"
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))
       (testing "review step has {{skill}} wired to :workflow-input"
         (let [review-step (first (filter #(= "review" (:name %)) steps))]
           (is (some (fn [c]
                       (and (= :template (:type c))
                            (= {:from :workflow-input :path [:skill]}
                               (get-in c [:vars "skill"]))))
                     (:contributions review-step))
               "review step should have {{skill}} wired to :workflow-input")))
       (let [status-step (first (filter #(= "review-status" (:name %)) steps))]
         (testing "review-status judge has REPEAT/DONE routing"
           (is (= #{"REPEAT" "DONE"} (set (keys (:on status-step)))))
           (is (some? (:judge status-step))))
         (testing "review-status judge has :outputs with judge-routing-result schema-id"
           (is (contains? (:judge status-step) :outputs))
           (is (= :psi.workflow/judge-routing-result
                  (get-in status-step [:judge :outputs :routing-result :schema-id])))))))))

;;; ---------------------------------------------------------------------------
;;; implement-task

(deftest implement-task-test
  (load-edn-only
   "implement-task.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "implement-task")))
     (let [steps (get-in definitions ["implement-task" :steps])]
       (testing "has 2 steps with correct names and types"
         (is (= 2 (count steps)))
         (is (= ["implement-pass" "final-summary"] (mapv :name steps)))
         (is (= [:session :session] (mapv :type steps))))
       (testing "actor steps have {{input}} wired to :workflow-input"
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))
       (let [pass-step (first (filter #(= "implement-pass" (:name %)) steps))]
         (testing "implement-pass judge has REPEAT/DONE routing"
           (is (= #{"REPEAT" "DONE"} (set (keys (:on pass-step)))))
           (is (some? (:judge pass-step))))
         (testing "implement-pass judge has :outputs with judge-routing-result schema-id"
           (is (contains? (:judge pass-step) :outputs))
           (is (= :psi.workflow/judge-routing-result
                  (get-in pass-step [:judge :outputs :routing-result :schema-id])))))))))

;;; ---------------------------------------------------------------------------
;;; review-implementation-in-worktree

(deftest review-implementation-in-worktree-test
  (load-edn-only
   "review-implementation-in-worktree.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "review-implementation-in-worktree")))
     (let [steps (get-in definitions ["review-implementation-in-worktree" :steps])]
       (testing "has a delegate step targeting review-task-implementation"
         (let [delegate-step (first (filter #(= :delegate (:type %)) steps))]
           (is (some? delegate-step) "should have a delegate step")
           (is (= "review-task-implementation" (:target delegate-step)))))
       (testing "summary step body names review-task-docs"
         (let [summary-step (first (filter #(= "summary" (:name %)) steps))
               summary-text (->> (:contributions summary-step)
                                 (filter #(= :template (:type %)))
                                 (map :text)
                                 (apply str))]
           (is (some? summary-step) "should have a summary step")
           (is (.contains summary-text "review-task-docs")
               "summary step body should name review-task-docs")))))))
