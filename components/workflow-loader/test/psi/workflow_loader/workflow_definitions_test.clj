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

(defn- step-template-text
  "Concatenated text of all template contributions in step."
  [step]
  (->> (:contributions step)
       (filter #(= :template (:type %)))
       (map :text)
       (apply str)))

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
   ["review-task-design-architecture-review.md"
    "review-task-design-ambiguity-review.md"
    "review-task-design-inconsistency-review.md"
    "review-follow-up-design.md"]
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "review-task-design")))
     (let [steps (get-in definitions ["review-task-design" :steps])]
       (testing "has 8 steps with correct names and types"
         (is (= 8 (count steps)))
         (is (= ["architecture-review"
                 "architecture-follow-up"
                 "ambiguity-review"
                 "ambiguity-follow-up"
                 "inconsistency-review"
                 "inconsistency-follow-up"
                 "clarity-status"
                 "final-summary"]
                (mapv :name steps)))
         (is (= [:session :session :session :session :session :session :invoke :session]
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
           (is (seq (:contributions final-step)) "final-summary step should have inline contributions")
           (testing "final-summary sources the architecture-review yield"
             (is (some #(= {:type :source :from {:step "architecture-review" :yield :text}} %)
                       (:contributions final-step))
                 "final-summary :contributions includes the architecture-review :yield :text source"))))
       (let [step-by-name (into {} (map (juxt :name identity) steps))
             architecture-review (get step-by-name "architecture-review")
             architecture-follow-up (get step-by-name "architecture-follow-up")
             ambiguity-review (get step-by-name "ambiguity-review")
             ambiguity-follow-up (get step-by-name "ambiguity-follow-up")
             inconsistency-review (get step-by-name "inconsistency-review")
             inconsistency-follow-up (get step-by-name "inconsistency-follow-up")
             clarity-step (get step-by-name "clarity-status")]
         (testing "per-reviewer follow-up steps route conditionally from deterministic PASS_STATUS"
           (is (= (pass-status-judge-from-step "architecture-review" ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"])
                  (:judge architecture-review)))
           (is (= {"REPEAT" {:goto "architecture-follow-up"}
                   "DONE" {:goto "ambiguity-review"}}
                  (:on architecture-review)))
           (is (= (constant-routing-judge "DONE")
                  (:judge architecture-follow-up)))
           (is (= {"DONE" {:goto "ambiguity-review"}} (:on architecture-follow-up)))
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
         (testing "both follow-up steps share the design-profile follow-up body"
           (doseq [follow-up [architecture-follow-up ambiguity-follow-up inconsistency-follow-up]]
             (let [text (step-template-text follow-up)]
               (is (.contains text "design-steps.md")
                   (str (:name follow-up) " uses the design profile (design-steps.md)"))
               (is (.contains text "Do not touch plan.md or steps.md")
                   (str (:name follow-up) " forbids plan.md/steps.md"))
               ;; Negative discriminator symmetric with the steps-profile tests'
               ;; (not (.contains text "design-steps.md")) guard (TS3). The
               ;; design profile (A3/R1) never edits real source and never
               ;; treats design.md as read-only context, so these steps-profile
               ;; clauses must be absent. Without the guard, wiring the
               ;; steps-profile body into the design host — or broadening the
               ;; design body to permit code/test/doc edits — would pass
               ;; silently.
               (is (not (.contains text "code, tests, and docs"))
                   (str (:name follow-up)
                        " does not carry the steps-profile code/test/doc"
                        " broadening clause"))
               (is (not (.contains text "design.md as read-only context"))
                   (str (:name follow-up)
                        " does not treat design.md as read-only context")))))
         (testing "follow-up steps carry the predate-exclusion guard"
           ;; Locks in the design Concepts predate guard so a future edit cannot
           ;; silently regress it (T1).
           (doseq [follow-up [architecture-follow-up ambiguity-follow-up inconsistency-follow-up]]
             (is (.contains (step-template-text follow-up)
                            "predate the preceding review pass")
                 (str (:name follow-up) " carries the predate-exclusion guard"))))
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
    "review-task-plan-inconsistency-review.md"
    "review-follow-up-steps.md"]
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
         (testing "both follow-up steps share the steps-profile follow-up body"
           (doseq [follow-up [ambiguity-follow-up inconsistency-follow-up]]
             (let [text (step-template-text follow-up)]
               ;; Anchor on the steps-profile-unique "design.md as read-only
               ;; context" clause: the design profile *writes* design.md, so it
               ;; cannot satisfy this — a failure means a non-steps profile was
               ;; wired in (TS1). The paired negative still catches
               ;; design-steps.md.
               (is (.contains text "design.md as read-only context")
                   (str (:name follow-up) " uses the steps profile (design.md read-only)"))
               (is (not (.contains text "design-steps.md"))
                   (str (:name follow-up) " does not target design-steps.md")))))
         (testing "follow-up steps carry the predate-exclusion guard"
           ;; Locks in the design Concepts predate guard (T1).
           (doseq [follow-up [ambiguity-follow-up inconsistency-follow-up]]
             (is (.contains (step-template-text follow-up)
                            "predate the preceding review pass")
                 (str (:name follow-up) " carries the predate-exclusion guard"))))
         (testing "follow-up steps permit editing referenced code/tests/docs"
           ;; Guards the R1 broadening (AC4 implementation-follow-up scope) so a
           ;; regress to plan/steps-only wording cannot pass (T2).
           (doseq [follow-up [ambiguity-follow-up inconsistency-follow-up]]
             (is (.contains (step-template-text follow-up) "code, tests, and docs")
                 (str (:name follow-up) " permits editing referenced code/tests/docs"))))
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
    (doseq [filename ["review-task-design-architecture-review.md"
                      "review-task-design-ambiguity-review.md"
                      "review-task-design-inconsistency-review.md"
                      "review-follow-up-design.md"]]
      (let [content (slurp-workflow-file filename)]
        (is (.contains content "design-steps.md") filename))))
  (testing "plan review prompts target steps.md rather than design-steps.md"
    (doseq [filename ["review-task-plan-ambiguity-review.md"
                      "review-task-plan-inconsistency-review.md"
                      "review-follow-up-steps.md"]]
      (let [content (slurp-workflow-file filename)]
        ;; Standalone (non-"design-") steps.md reference: a bare substring
        ;; check passes trivially on "design-steps.md", so anchor on a
        ;; non-"-" boundary to give the positive independent signal (TS2).
        (is (re-find #"(^|[^-])steps\.md" content) filename)
        (is (not (.contains content "design-steps.md")) filename))))
  (testing "rewired host edns leave no orphan references to removed per-aspect files"
    ;; AC3 regression guard (T3): the four removed per-aspect follow-up files
    ;; must not be referenced by any rewired host workflow edn.
    (doseq [edn-filename ["review-task-design.edn"
                          "review-task-plan.edn"
                          "review-step.edn"]
            removed-filename ["review-task-design-ambiguity-follow-up.md"
                              "review-task-design-inconsistency-follow-up.md"
                              "review-task-plan-ambiguity-follow-up.md"
                              "review-task-plan-inconsistency-follow-up.md"]]
      (is (not (.contains (slurp-workflow-file edn-filename) removed-filename))
          (str edn-filename " must not reference removed " removed-filename)))))

(deftest architecture-review-prompt-contract-test
  ;; AC2a contract guard for review-task-design-architecture-review.md.
  ;; Relocated here (SH2) from review-task-prompt-artifact-targets-test, whose
  ;; scope is artifact ownership (design-steps.md vs steps.md); a menu/skill
  ;; regression should fail under a name that describes the violated AC2a
  ;; contract. The prompt must (a) load the review-task-architecture skill (not
  ;; task-design) and (b) *end with* the two-line PASS_STATUS menu. I1 flagged
  ;; the menu convention as contradiction-prone.
  (let [content (slurp-workflow-file "review-task-design-architecture-review.md")]
    (testing "loads the review-task-architecture skill (not task-design)"
      (is (.contains content "review-task-architecture")
          "architecture-review prompt loads the review-task-architecture skill"))
    (testing "ends with the contiguous two-line PASS_STATUS menu (SH1)"
      ;; Enforce the *ends-with* contract, not mere presence: the menu lead-in
      ;; and the two PASS_STATUS lines must form a contiguous, terminal block.
      ;; Trailing whitespace is trimmed before anchoring to end-of-string so a
      ;; future edit that appends prose after the menu, or splits the lead-in
      ;; from the status lines, fails this guard.
      (is (re-find #"(?s)End your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: REVIEW_COMPLETE\s*\z"
                   content)
          (str "architecture-review prompt must end with the contiguous "
               "two-line PASS_STATUS menu (lead-in + ACTIONABLE_FEEDBACK + "
               "REVIEW_COMPLETE), with nothing but whitespace after it")))))

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
  (load-edn-with-md-refs
   "review-step.edn"
   ["review-follow-up-steps.md"]
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
       (testing "follow-up step uses the shared steps-profile follow-up body"
         (let [text (step-template-text follow-up-step)]
           ;; Steps-profile-unique anchor (TS1): the design profile writes
           ;; design.md, so "design.md as read-only context" discriminates the
           ;; profiles where a bare "steps.md" substring cannot.
           (is (.contains text "design.md as read-only context")
               "follow-up uses the steps profile (design.md read-only)")
           (is (not (.contains text "design-steps.md"))
               "follow-up does not target design-steps.md")
           (is (.contains text "predate the preceding review pass")
               "follow-up carries the predate-exclusion guard (T1)")
           (is (.contains text "code, tests, and docs")
               "follow-up permits editing referenced code/tests/docs (T2)")))
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
   ["review-task-design-architecture-review.md"
    "review-task-design-ambiguity-review.md"
    "review-task-design-inconsistency-review.md"
    "review-follow-up-design.md"]
   (fn [_]
     (load-edn-with-md-refs
      "review-task-plan.edn"
      ["review-task-plan-ambiguity-review.md"
       "review-task-plan-inconsistency-review.md"
       "review-follow-up-steps.md"]
      (fn [_]
        (with-workflow-dir
          {"review-step.edn" (slurp-workflow-file "review-step.edn")
           "review-task-implementation.edn" (slurp-workflow-file "review-task-implementation.edn")
           "review-implementation-in-worktree.edn" (slurp-workflow-file "review-implementation-in-worktree.edn")
           "review-design-turn.edn" (slurp-workflow-file "review-design-turn.edn")
           "review-task-design.edn" (slurp-workflow-file "review-task-design.edn")
           "review-task-design-architecture-review.md" (slurp-workflow-file "review-task-design-architecture-review.md")
           "review-task-design-ambiguity-review.md" (slurp-workflow-file "review-task-design-ambiguity-review.md")
           "review-task-design-inconsistency-review.md" (slurp-workflow-file "review-task-design-inconsistency-review.md")
           "review-task-plan.edn" (slurp-workflow-file "review-task-plan.edn")
           "review-task-plan-ambiguity-review.md" (slurp-workflow-file "review-task-plan-ambiguity-review.md")
           "review-task-plan-inconsistency-review.md" (slurp-workflow-file "review-task-plan-inconsistency-review.md")
           "review-follow-up-design.md" (slurp-workflow-file "review-follow-up-design.md")
           "review-follow-up-steps.md" (slurp-workflow-file "review-follow-up-steps.md")}
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
     (let [steps (get-in definitions ["task-lifecycle" :steps])
           expected-targets ["review-task-design"
                             "create-task-plan"
                             "review-task-plan"
                             "implement-task"
                             "review-task-implementation"]]
       (testing "has 5 delegate steps with correct names, types, and targets"
         (is (= 5 (count steps)))
         (is (= expected-targets (mapv :name steps)))
         (is (= [:delegate :delegate :delegate :delegate :delegate]
                (mapv :type steps)))
         (is (= expected-targets (mapv :target steps))))
       (testing "every step threads the task id via the :map :prompt-string"
         (is (= (repeat 5 {:type :map
                           :fields {:input {:from :workflow-input
                                            :path [:input]}}})
                (mapv :prompt-string steps))))
       (testing "every step carries only :workflow-original context (no prior-step yield)"
         (is (= (repeat 5 [{:type :source :from :workflow-original}])
                (mapv :context steps))))
       (testing "no step declares :yields or :terminal-contract (terminal relies on propagated session default yield)"
         (is (= (repeat 5 {})
                (mapv #(select-keys % [:yields :terminal-contract]) steps))))))))

;;; ---------------------------------------------------------------------------
;;; task-lifecycle-in-worktree (Slice 2 of task 204)

(deftest task-lifecycle-in-worktree-test
  (load-edn-only
   "task-lifecycle-in-worktree.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "task-lifecycle-in-worktree")))
     (let [steps (get-in definitions ["task-lifecycle-in-worktree" :steps])
           step-by-name (into {} (map (juxt :name identity) steps))
           resolve-step (get step-by-name "resolve-worktree")
           lifecycle-step (get step-by-name "lifecycle")
           summary-step (get step-by-name "summary")]
       (testing "is a three-step resolve-worktree -> lifecycle -> summary adapter"
         (is (= 3 (count steps)))
         (is (= ["resolve-worktree" "lifecycle" "summary"] (mapv :name steps)))
         (is (= [:session :delegate :session] (mapv :type steps))))
       (testing "resolve-worktree :session step includes the work-on tool and {{input}} wiring"
         (is (some #{"work-on"} (:tools resolve-step))
             "resolve-worktree tools include work-on")
         (is (step-has-input-var-wired? resolve-step)
             "resolve-worktree has {{input}} wired to :workflow-input"))
       (testing "lifecycle :delegate targets task-lifecycle with :input from resolve-worktree :yield :text"
         (is (= :delegate (:type lifecycle-step)))
         (is (= "task-lifecycle" (:target lifecycle-step)))
         (is (= {:type :map
                 :fields {:input {:from {:step "resolve-worktree" :yield :text}}}}
                (:prompt-string lifecycle-step))))
       ;; TR14 (pass 12, test-shaper): lock the wrapper lifecycle delegate's
       ;; :context — deliberately ONLY :workflow-original, NOT the resolve-worktree
       ;; yield (inner task-lifecycle reads the path via :prompt-string :input;
       ;; re-injecting the handoff would pollute the context). Mirrors
       ;; task-lifecycle-test + TR13. A regress adding {:step "resolve-worktree"
       ;; :yield :text} or dropping :workflow-original previously passed green.
       (testing "lifecycle :delegate :context is only :workflow-original (no prior-step yield) (TR14)"
         (is (= [{:type :source :from :workflow-original}]
                (:context lifecycle-step))))
       (testing "trailing summary :session step is present (terminal user-facing summary)"
         (is (some? summary-step))
         (is (= :session (:type summary-step)))
         (is (seq (:contributions summary-step))))
       ;; F1 (implementation review): early-stop is prompt-only and the grammar
       ;; has no conditional/skip step, so a no-target handoff must be detected
       ;; and short-circuited at the prompt level in both session steps.
       (testing "resolve-worktree prompt short-circuits a no-target handoff without calling work-on"
         (let [resolve-text (step-template-text resolve-step)]
           (is (.contains resolve-text "NO_TARGET")
               "resolve-worktree emits the NO_TARGET sentinel on a no-target handoff")
           (is (re-find #"(?i)do not call `?work-on" resolve-text)
               "resolve-worktree does not call work-on when no handoff fields are present")))
       (testing "summary prompt detects NO_TARGET and reports a clean nothing-to-do result"
         (let [summary-text (step-template-text summary-step)]
           (is (.contains summary-text "NO_TARGET")
               "summary detects the NO_TARGET sentinel from resolve-worktree")
           (is (some? (some #(= {:step "resolve-worktree" :yield :text}
                                (:from %))
                            (:contributions summary-step)))
               "summary sources the resolve-worktree :yield :text so it can detect NO_TARGET")))
       ;; TR10 (pass 8): lock the summary's positive-path terminal contract
       ;; (symmetric to TR7). On a real munera/... path summary inspects the task
       ;; artifacts and reports the design → plan → implement → review run, the
       ;; artifacts updated, and the closed/open outcome, sourcing lifecycle yield.
       (testing "summary prompt reports the positive-path lifecycle terminal contract (TR10)"
         (let [summary-text (step-template-text summary-step)]
           (is (re-find #"(?i)independently inspect that specific task" summary-text)
               "summary independently inspects the resolved task's artifacts on a target-present run")
           (is (.contains summary-text "completed cleanly (design → plan → implement → review)")
               "summary reports whether the task-lifecycle run completed cleanly (design → plan → implement → review)")
           (is (re-find #"(?i)task artifact files updated" summary-text)
               "summary reports the task artifact files updated")
           (is (re-find #"(?i)closed \(moved to munera/closed/\) or remains open" summary-text)
               "summary reports whether the task was closed or remains open")))
       (testing "summary sources the lifecycle step :yield :text to report lifecycle outcomes (TR10)"
         (is (some? (some #(= {:step "lifecycle" :yield :text}
                              (:from %))
                          (:contributions summary-step)))
             "summary sources the lifecycle :yield :text so it can report the lifecycle run outcomes"))
       ;; TR7: lock the positive (target-present) branch — the cross-:delegate
       ;; worktree-continuity mechanism (Locked decision 11): the wrapper
       ;; re-calls work-on before sub-delegating. The NO_TARGET-only locks above
       ;; let a regress dropping this pass green.
       (testing "resolve-worktree prompt re-calls work-on and yields only the task path on a target-present handoff (TR7)"
         (let [resolve-text (step-template-text resolve-step)]
           (is (re-find #"(?i)call `?work-on`? with the extracted worktree path"
                        resolve-text)
               "resolve-worktree calls work-on with the extracted worktree path when both handoff fields are present")
           (is (re-find #"(?i)respond with ONLY the Munera task path" resolve-text)
               "resolve-worktree yields only the bare Munera task path on the positive path")
           (is (re-find #"(?i)on a single line" resolve-text)
               "resolve-worktree constrains the positive-path yield to a single line")))))))

;;; ---------------------------------------------------------------------------
;;; reduce-incidental-complexity (Slice 3 of task 204)

(deftest reduce-incidental-complexity-test
  (load-edn-only
   "reduce-incidental-complexity.edn"
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "reduce-incidental-complexity")))
     (let [steps (get-in definitions ["reduce-incidental-complexity" :steps])
           step-by-name (into {} (map (juxt :name identity) steps))
           select-step (get step-by-name "select-and-create")
           delegate-step (get step-by-name "lifecycle-in-worktree")
           select-text (step-template-text select-step)]
       (testing "is a two-step select-and-create (:session) -> lifecycle-in-worktree (:delegate)"
         (is (= 2 (count steps)))
         (is (= ["select-and-create" "lifecycle-in-worktree"] (mapv :name steps)))
         (is (= [:session :delegate] (mapv :type steps))))
       (testing "select-and-create :session step carries work-on tool + incidental-complexity-finder skill"
         (is (some #{"work-on"} (:tools select-step))
             "select-and-create tools include work-on")
         (is (some #{"incidental-complexity-finder"} (:skills select-step))
             "select-and-create skills include incidental-complexity-finder"))
       ;; TR15 (test review pass 13, test-shaper): lock the entry-point input
       ;; flow — step 1 wires "input" to the bare top-level :workflow-input (NO
       ;; :path, distinct from the wrapper steps' {:path [:input]} :map-field
       ;; selector). Previously uncovered: a regress dropping :vars/{{input}} or
       ;; mis-wiring passed green. (step-has-input-var-wired? can't be reused —
       ;; it requires the :path [:input] shape.)
       (testing "select-and-create wires {{input}} to the bare :workflow-input (TR15)"
         (let [tmpl (first (filter #(= :template (:type %))
                                   (:contributions select-step)))]
           (is (.contains (:text tmpl) "{{input}}")
               "select-and-create prompt references the {{input}} template var")
           (is (= {:from :workflow-input}
                  (get-in tmpl [:vars "input"]))
               "select-and-create wires input to the bare top-level :workflow-input (no :path)")))
       (testing "lifecycle-in-worktree :delegate targets the wrapper with :input from select-and-create :yield :text"
         (is (= :delegate (:type delegate-step)))
         (is (= "task-lifecycle-in-worktree" (:target delegate-step)))
         (is (= {:type :map
                 :fields {:input {:from {:step "select-and-create" :yield :text}}}}
                (:prompt-string delegate-step))))
       ;; TR13 (pass 11, test-shaper): lock the delegate's :context — the second
       ;; source propagates the step-1 handoff into the delegated wrapper
       ;; (companion to :input, Locked decision 11). The prior :type/:target/
       ;; :prompt-string-only locks let a regress dropping it pass green.
       (testing "lifecycle-in-worktree :delegate :context propagates workflow-original + the select-and-create handoff yield (TR13)"
         (is (= [{:type :source :from :workflow-original}
                 {:type :source :from {:step "select-and-create" :yield :text}}]
                (:context delegate-step))))
       (testing "select-and-create prompt emits the worktree_path:/munera_task_path: handoff fields"
         (is (.contains select-text "worktree_path:")
             "step-1 prompt emits worktree_path: handoff field")
         (is (.contains select-text "munera_task_path:")
             "step-1 prompt emits munera_task_path: handoff field"))
       (testing "select-and-create prompt encodes the early-stop-on-no-target intent"
         (is (re-find #"(?i)no unit qualif" select-text)
             "step-1 prompt encodes early-stop when no unit qualifies")
         (is (.contains select-text "Do NOT create a worktree")
             "step-1 prompt forbids creating a worktree on early stop"))
       (testing "select-and-create prompt embeds the enforcing gate flags + both baselines"
         (is (.contains select-text
                        "--fail-on new-cycles,new-high-findings --max-new-medium-findings 0")
             "step-1 prompt embeds the enforcing gate flags")
         (is (.contains select-text "before-local.json")
             "step-1 prompt names the before-local.json baseline (A5)")
         (is (.contains select-text "before-diagnose.edn")
             "step-1 prompt names the before-diagnose.edn baseline (A3)"))
       ;; TR11 (pass 9, test-shaper): A3 baseline paths must be WORKTREE-ROOT-
       ;; RELATIVE (`munera/open/NNN-slug/...`), NOT a bare filename (which does
       ;; not resolve from the worktree-root cwd where Phase 1 runs gate). The
       ;; bare-filename locks above don't anchor this, so the R3-warned regress
       ;; (`--baseline before-diagnose.edn`) would pass green.
       (testing "select-and-create prompt resolves A3/A5 baselines by worktree-relative path (TR11)"
         (is (.contains select-text
                        "--baseline munera/open/NNN-slug/before-diagnose.edn")
             "step-1 gate command embeds the worktree-root-relative A3 baseline path (not a bare filename)")
         (is (.contains select-text
                        "the stored `munera/open/NNN-slug/before-local.json`")
             "step-1 prompt names the worktree-relative A5 before-local.json comparison path (not a bare filename)"))
       ;; TR2: the step-7 two-phase behaviour-preserving contract is the design's
       ;; substantive acceptance, not just the gate flags/filenames locked above.
       ;; Lock its shape so a paraphrase/regress of the Phase-0 gate, the
       ;; behaviour-identical constraint, or the F3 A5/A2 key can't pass green.
       (testing "select-and-create prompt embeds the Phase-0 characterization-test gate"
         (is (.contains select-text
                        "These tests must be GREEN against the unmodified code before any refactoring begins")
             "step-1 prompt requires a green characterization net before refactor (Phase 0)")
         (is (re-find #"(?i)add characterization tests" select-text)
             "step-1 prompt instructs adding characterization tests when coverage is insufficient"))
       (testing "select-and-create prompt embeds the behaviour-identical constraint"
         (is (.contains select-text
                        "behaviour is identical — meta/spec are unchanged; existing test expectations are not weakened")
             "step-1 prompt states the behaviour-identical / meta-spec-unchanged constraint"))
       (testing "select-and-create prompt keys A5/A2 acceptance on (ns, var, arity, line) (F3 lock)"
         ;; F3 re-keyed A5/A2 onto the selector's unique (ns, var, arity, line)
         ;; join key; a regress back to (ns, var, arity) in the generated
         ;; contract must not pass green.
         (is (.contains select-text "keyed by `(ns, var, arity, line)`")
             "step-1 prompt keys the A5 burden-reduction acceptance on (ns, var, arity, line)")
         (is (.contains select-text "identified by `(ns, var, arity, line)`")
             "step-1 prompt keys the A2 touched-set identity on (ns, var, arity, line)"))
       ;; TR8 (pass 6): the distinguishing endpoint — no push/PR (Locked
       ;; decisions 7 & 8: full lifecycle on a local worktree branch, not a
       ;; complexity-reduction-pr clone) — was unlocked. A regress adding a
       ;; push/PR step must not pass green.
       (testing "select-and-create prompt locks the no-push/PR endpoint constraint (TR8)"
         (is (.contains select-text "Do NOT push or open a PR")
             "step-1 prompt forbids pushing or opening a PR")
         (is (.contains select-text
                        "ends with a completed, reviewed task on the local worktree branch")
             "step-1 prompt states the local-worktree-branch endpoint (no push/PR)"))))))
