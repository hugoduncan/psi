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

(defn- load-edn-with-md-refs
  "Load an edn workflow and its referenced .md files from the real .psi/workflows dir."
  [edn-filename md-filenames f]
  (with-workflow-dir
    (into {edn-filename (slurp-workflow-file edn-filename)}
          (map (fn [md] [md (slurp-workflow-file md)]) md-filenames))
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

(defn- pass-status-judge-from-step
  ([step-name]
   (pass-status-judge-from-step step-name nil))
  ([step-name allowed-statuses]
   {:type :invoke
    :operation "workflow/pass-status-routing"
    :args (cond-> {:text {:from {:step step-name :output :final-llm-reply}}}
            allowed-statuses (assoc :allowed-statuses allowed-statuses))}))

(defn- constant-routing-judge
  [route]
  {:type :invoke
   :operation "workflow/constant-routing"
   :args {:route route}})

(defn- constant-routing-step
  [step-name route]
  {:name step-name
   :type :invoke
   :operation "workflow/constant-routing"
   :args {:route route}})

;;; ---------------------------------------------------------------------------
;;; review-task-design

(deftest review-task-design-test
  (load-edn-with-md-refs
   "review-task-design.edn"
   ["review-task-design-ambiguity-review.md"
    "review-task-design-ambiguity-follow-up.md"
    "review-task-design-inconsistency-review.md"
    "review-task-design-inconsistency-follow-up.md"]
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
         (is (= [:session :session :session :session :invoke :session]
                (mapv :type steps))))
       (let [wired-steps (filter #(= :session (:type %)) (remove #(= "final-summary" (:name %)) steps))
             final-step (first (filter #(= "final-summary" (:name %)) steps))]
         (testing "wired non-final session steps have {{input}} wired to :workflow-input"
           (doseq [step wired-steps]
             (is (step-has-input-var-wired? step)
                 (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))
         (testing "final-summary step is inline (not prompt-workflow wired)"
           ;; final-summary carries :source contributions and is intentionally kept inline
           (is (some? final-step) "final-summary step should exist")
           (is (seq (:contributions final-step)) "final-summary step should have inline contributions")))
       (let [step-by-name (into {} (map (juxt :name identity) steps))
             ambiguity-review (get step-by-name "ambiguity-review")
             ambiguity-follow-up (get step-by-name "ambiguity-follow-up")
             inconsistency-review (get step-by-name "inconsistency-review")
             inconsistency-follow-up (get step-by-name "inconsistency-follow-up")
             clarity-step (get step-by-name "clarity-status")]
         (testing "per-reviewer follow-up steps route conditionally from deterministic PASS_STATUS"
           (is (= (pass-status-judge-from-step "ambiguity-review" ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"])
                  (:judge ambiguity-review)))
           (is (= {"REPEAT" {:goto "ambiguity-follow-up"}
                   "DONE" {:goto "inconsistency-review"}}
                  (:on ambiguity-review)))
           (is (= (constant-routing-judge "DONE")
                  (:judge ambiguity-follow-up)))
           (is (= {"DONE" {:goto "inconsistency-review"}} (:on ambiguity-follow-up)))
           (is (= (pass-status-judge-from-step "inconsistency-review" ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"])
                  (:judge inconsistency-review)))
           (is (= {"REPEAT" {:goto "inconsistency-follow-up"}
                   "DONE" {:goto "clarity-status"}}
                  (:on inconsistency-review)))
           (is (= (constant-routing-judge "DONE")
                  (:judge inconsistency-follow-up)))
           (is (= {"DONE" {:goto "clarity-status"}} (:on inconsistency-follow-up))))
         (testing "clarity-status is deterministic invoke routing, not an LLM judge"
           (is (= (constant-routing-step "clarity-status" "DONE")
                  (select-keys clarity-step [:name :type :operation :args])))
           (is (nil? (:on clarity-step)))
           (is (nil? (:judge clarity-step)))))))))

;;; ---------------------------------------------------------------------------
;;; review-task-plan

(deftest review-task-plan-test
  (load-edn-with-md-refs
   "review-task-plan.edn"
   ["review-task-plan-ambiguity-review.md"
    "review-task-plan-ambiguity-follow-up.md"
    "review-task-plan-inconsistency-review.md"
    "review-task-plan-inconsistency-follow-up.md"]
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
         (is (= [:session :session :session :session :invoke :session]
                (mapv :type steps))))
       (let [wired-steps (filter #(= :session (:type %)) (remove #(= "final-summary" (:name %)) steps))
             final-step (first (filter #(= "final-summary" (:name %)) steps))]
         (testing "wired non-final session steps have {{input}} wired to :workflow-input"
           (doseq [step wired-steps]
             (is (step-has-input-var-wired? step)
                 (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))
         (testing "final-summary step is inline (not prompt-workflow wired)"
           ;; final-summary carries :source contributions and is intentionally kept inline
           (is (some? final-step) "final-summary step should exist")
           (is (seq (:contributions final-step)) "final-summary step should have inline contributions")))
       (let [step-by-name (into {} (map (juxt :name identity) steps))
             ambiguity-review (get step-by-name "ambiguity-review")
             ambiguity-follow-up (get step-by-name "ambiguity-follow-up")
             inconsistency-review (get step-by-name "inconsistency-review")
             inconsistency-follow-up (get step-by-name "inconsistency-follow-up")
             clarity-step (get step-by-name "clarity-status")]
         (testing "per-reviewer follow-up steps route conditionally from deterministic PASS_STATUS"
           (is (= (pass-status-judge-from-step "ambiguity-review" ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"])
                  (:judge ambiguity-review)))
           (is (= {"REPEAT" {:goto "ambiguity-follow-up"}
                   "DONE" {:goto "inconsistency-review"}}
                  (:on ambiguity-review)))
           (is (= (constant-routing-judge "DONE")
                  (:judge ambiguity-follow-up)))
           (is (= {"DONE" {:goto "inconsistency-review"}} (:on ambiguity-follow-up)))
           (is (= (pass-status-judge-from-step "inconsistency-review" ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"])
                  (:judge inconsistency-review)))
           (is (= {"REPEAT" {:goto "inconsistency-follow-up"}
                   "DONE" {:goto "clarity-status"}}
                  (:on inconsistency-review)))
           (is (= (constant-routing-judge "DONE")
                  (:judge inconsistency-follow-up)))
           (is (= {"DONE" {:goto "clarity-status"}} (:on inconsistency-follow-up))))
         (testing "clarity-status is deterministic invoke routing, not an LLM judge"
           (is (= (constant-routing-step "clarity-status" "DONE")
                  (select-keys clarity-step [:name :type :operation :args])))
           (is (nil? (:on clarity-step)))
           (is (nil? (:judge clarity-step)))))))))

;;; ---------------------------------------------------------------------------
;;; review task prompt artifact targets

(deftest review-task-prompt-artifact-targets-test
  ;; Tests review prompt artifact ownership: design review uses design-steps.md,
  ;; plan review uses steps.md and never design-steps.md.
  (testing "design review prompts target design-steps.md"
    (doseq [filename ["review-task-design-ambiguity-review.md"
                      "review-task-design-ambiguity-follow-up.md"
                      "review-task-design-inconsistency-review.md"
                      "review-task-design-inconsistency-follow-up.md"]]
      (let [content (slurp-workflow-file filename)]
        (is (.contains content "design-steps.md") filename))))
  (testing "plan review prompts target steps.md rather than design-steps.md"
    (doseq [filename ["review-task-plan-ambiguity-review.md"
                      "review-task-plan-ambiguity-follow-up.md"
                      "review-task-plan-inconsistency-review.md"
                      "review-task-plan-inconsistency-follow-up.md"]]
      (let [content (slurp-workflow-file filename)]
        (is (.contains content "steps.md") filename)
        (is (not (.contains content "design-steps.md")) filename)))))

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
  (load-edn-with-md-refs
   "create-task-plan.edn"
   ["create-task-plan-create-plan.md"]
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
     (let [steps (get-in definitions ["review-step" :steps])
           review-step (first (filter #(= "review" (:name %)) steps))
           follow-up-step (first (filter #(= "follow-up" (:name %)) steps))]
       (testing "has 2 steps with correct names and types"
         (is (= 2 (count steps)))
         (is (= ["review" "follow-up"] (mapv :name steps)))
         (is (= [:session :session] (mapv :type steps))))
       (testing "steps have {{input}} wired to :workflow-input"
         (doseq [step steps]
           (is (step-has-input-var-wired? step)
               (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))
       (testing "review step has {{skill}} wired to :workflow-input"
         (is (some (fn [c]
                     (and (= :template (:type c))
                          (= {:from :workflow-input :path [:skill]}
                             (get-in c [:vars "skill"]))))
                   (:contributions review-step))
             "review step should have {{skill}} wired to :workflow-input"))
       (testing "review step uses deterministic invoke routing from final-llm-reply"
         (is (= (pass-status-judge-from-step "review" ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"])
                (:judge review-step)))
         (is (= {"DONE" {:goto :done}
                 "REPEAT" {:goto "follow-up"}}
                (:on review-step))))
       (testing "follow-up step uses deterministic constant loopback judge"
         (is (= {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "REPEAT"}}
                (:judge follow-up-step)))
         (is (= {"REPEAT" {:goto "review" :max-iterations 6}}
                (:on follow-up-step))))
       (testing "legacy review-status session step is removed"
         (is (nil? (first (filter #(= "review-status" (:name %)) steps)))))))))

;;; ---------------------------------------------------------------------------
;;; implement-task

(deftest implement-task-test
  (load-edn-with-md-refs
   "implement-task.edn"
   ["implement-task-implement-pass.md"]
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "implement-task")))
     (let [steps (get-in definitions ["implement-task" :steps])]
       (testing "has 2 steps with correct names and types"
         (is (= 2 (count steps)))
         (is (= ["implement-pass" "final-summary"] (mapv :name steps)))
         (is (= [:session :session] (mapv :type steps))))
       (let [wired-steps (remove #(= "final-summary" (:name %)) steps)
             final-step (first (filter #(= "final-summary" (:name %)) steps))]
         (testing "wired (non-final-summary) steps have {{input}} wired to :workflow-input"
           (doseq [step wired-steps]
             (is (step-has-input-var-wired? step)
                 (str "step " (:name step) " should have {{input}} wired to :workflow-input"))))
         (testing "final-summary step is inline (not prompt-workflow wired)"
           ;; final-summary carries :source contributions with :workflow-original and
           ;; implement-pass step-output yield refs; intentionally kept inline
           (is (some? final-step) "final-summary step should exist")
           (is (seq (:contributions final-step)) "final-summary step should have inline contributions")))
       (let [pass-step (first (filter #(= "implement-pass" (:name %)) steps))]
         (testing "implement-pass routes deterministically from PASS_STATUS"
           (is (= #{"REPEAT" "DONE"} (set (keys (:on pass-step)))))
           (is (= {:type :invoke
                   :operation "workflow/pass-status-routing"
                   :args {:text {:from {:step "implement-pass" :output :final-llm-reply}}}}
                  (:judge pass-step)))))))))

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

;;; ---------------------------------------------------------------------------
;;; review workflow set bootstrap/load proof

(deftest review-workflow-set-loads-together-test
  (load-edn-with-md-refs
   "review-task-design.edn"
   ["review-task-design-ambiguity-review.md"
    "review-task-design-ambiguity-follow-up.md"
    "review-task-design-inconsistency-review.md"
    "review-task-design-inconsistency-follow-up.md"]
   (fn [_]
     (load-edn-with-md-refs
      "review-task-plan.edn"
      ["review-task-plan-ambiguity-review.md"
       "review-task-plan-ambiguity-follow-up.md"
       "review-task-plan-inconsistency-review.md"
       "review-task-plan-inconsistency-follow-up.md"]
      (fn [_]
        (with-workflow-dir
          {"review-step.edn" (slurp-workflow-file "review-step.edn")
           "review-task-implementation.edn" (slurp-workflow-file "review-task-implementation.edn")
           "review-implementation-in-worktree.edn" (slurp-workflow-file "review-implementation-in-worktree.edn")
           "review-design-turn.edn" (slurp-workflow-file "review-design-turn.edn")
           "review-task-design.edn" (slurp-workflow-file "review-task-design.edn")
           "review-task-design-ambiguity-review.md" (slurp-workflow-file "review-task-design-ambiguity-review.md")
           "review-task-design-ambiguity-follow-up.md" (slurp-workflow-file "review-task-design-ambiguity-follow-up.md")
           "review-task-design-inconsistency-review.md" (slurp-workflow-file "review-task-design-inconsistency-review.md")
           "review-task-design-inconsistency-follow-up.md" (slurp-workflow-file "review-task-design-inconsistency-follow-up.md")
           "review-task-plan.edn" (slurp-workflow-file "review-task-plan.edn")
           "review-task-plan-ambiguity-review.md" (slurp-workflow-file "review-task-plan-ambiguity-review.md")
           "review-task-plan-ambiguity-follow-up.md" (slurp-workflow-file "review-task-plan-ambiguity-follow-up.md")
           "review-task-plan-inconsistency-review.md" (slurp-workflow-file "review-task-plan-inconsistency-review.md")
           "review-task-plan-inconsistency-follow-up.md" (slurp-workflow-file "review-task-plan-inconsistency-follow-up.md")}
          (fn [{:keys [definitions errors]}]
            (testing "all review workflows load together without compilation errors"
              (is (empty? errors))
              (is (contains? definitions "review-step"))
              (is (contains? definitions "review-design-turn"))
              (is (contains? definitions "review-task-design"))
              (is (contains? definitions "review-task-plan"))
              (is (contains? definitions "review-task-implementation"))
              (is (contains? definitions "review-implementation-in-worktree"))))))))))

;;; ---------------------------------------------------------------------------
;;; task-lifecycle

(deftest task-lifecycle-test
  (load-edn-only
   "task-lifecycle.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "task-lifecycle")))
     (let [steps (get-in definitions ["task-lifecycle" :steps])]
       (testing "has 5 delegate steps with correct names, types, and targets"
         (is (= 5 (count steps)))
         (is (= ["review-task-design"
                 "create-task-plan"
                 "review-task-plan"
                 "implement-task"
                 "review-task-implementation"]
                (mapv :name steps)))
         (is (= [:delegate :delegate :delegate :delegate :delegate]
                (mapv :type steps)))
         (is (= ["review-task-design"
                 "create-task-plan"
                 "review-task-plan"
                 "implement-task"
                 "review-task-implementation"]
                (mapv :target steps))))
       (testing "every step threads the task id via the :map :prompt-string"
         (is (every? (fn [step]
                       (= {:type :map
                           :fields {:input {:from :workflow-input
                                            :path [:input]}}}
                          (:prompt-string step)))
                     steps)))
       (testing "every step carries only :workflow-original context (no prior-step yield)"
         (is (every? (fn [step]
                       (= [{:type :source :from :workflow-original}]
                          (:context step)))
                     steps)))
       (testing "no step declares :yields or :terminal-contract (terminal relies on propagated session default yield)"
         (is (every? (fn [step]
                       (and (not (contains? step :yields))
                            (not (contains? step :terminal-contract))))
                     steps))
         (let [terminal (last steps)]
           (is (not (contains? terminal :yields)))
           (is (not (contains? terminal :terminal-contract)))))))))
