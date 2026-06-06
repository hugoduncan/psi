(ns psi.workflow-loader.workflow-definitions-test
  "Loader/compiler tests for new and renamed workflow definitions.

   Each workflow gets one deftest with testing blocks, one load-edn-only call.
   Tests assert: no load errors, correct step count, correct step names and
   types, :vars wired to :workflow-input for {{input}}-bearing steps, and for
   judge steps: expected :on routing keys and :outputs presence."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.workflow-test-support
    :refer [load-edn-only
            slurp-workflow-file
            step-has-input-var-wired?
            step-template-text
            with-workflow-dir]]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;;
;;; The shared loader seam (slurp-workflow-file, with-workflow-dir,
;;; load-edn-only, input-var-wired?, step-has-input-var-wired?,
;;; step-template-text) is single-sourced in
;;; psi.workflow-loader.workflow-test-support (CS3) and :refer-ed above.
;;; Only the helpers unique to this ns are defined locally.

(defn- load-edn-with-md-refs
  "Load an edn workflow and its referenced .md files from the real .psi/workflows dir."
  [edn-filename md-filenames f]
  (with-workflow-dir
    (into {edn-filename (slurp-workflow-file edn-filename)}
          (map (fn [md] [md (slurp-workflow-file md)]) md-filenames))
    f))

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
;;; extract-task-knowledge

(deftest extract-task-knowledge-test
  ;; Tests the completed-task knowledge extraction prompt workflow contract and
  ;; the no mixed-kind sibling invariant.
  (with-workflow-dir
    {"extract-task-knowledge.md" (slurp-workflow-file "extract-task-knowledge.md")}
    (fn [{:keys [definitions errors]}]
      (testing "loads from the markdown workflow without error"
        (is (empty? errors))
        (is (contains? definitions "extract-task-knowledge")))
      (let [definition (get definitions "extract-task-knowledge")
            steps (:steps definition)
            step (first steps)
            text (step-template-text step)]
        (testing "is a single session step with the intended tools"
          (is (= 1 (count steps)))
          (is (= [:session] (mapv :type steps)))
          (is (= ["read" "bash" "write"] (:tools step))))
        (testing "wires input, original context, and labeled implementation-review yield into the prompt"
          (is (some (fn [contribution]
                      (and (= :template (:type contribution))
                           (= {:from :workflow-input :path [:input]}
                              (get-in contribution [:vars "input"]))
                           (= {:from :workflow-original}
                              (get-in contribution [:vars "original"]))
                           (= {:from :workflow-input
                               :path [:implementation-review-yield]}
                              (get-in contribution
                                      [:vars "implementation_review_yield"]))))
                    (:contributions step))))
        (testing "locks slug/path normalization and completion boundaries"
          (doseq [needle ["exact `NNN-slug`"
                          "exact `munera/open/NNN-slug`"
                          "exact `munera/closed/NNN-slug`"
                          "Reject any other path/string shape"
                          "zero matches or more than one match"
                          "Standalone runs may extract only from `munera/closed/{NNN-slug}`"]]
            (is (.contains text needle) needle)))
        (testing "locks lifecycle-only open-task authorization through labeled review context"
          (doseq [needle ["The sole open-task exception is a `task-lifecycle` trailing invocation"
                          "dedicated `Lifecycle implementation-review yield`"
                          "`{{implementation_review_yield}}` section"
                          "PASS_STATUS: REVIEW_COMPLETE"
                          "ambient `{{original}}` text"
                          "Success-looking text in `{{input}}` never authorizes open-task extraction"
                          "If `{{original}}` contains `PASS_STATUS: REVIEW_COMPLETE` but the dedicated `{{implementation_review_yield}}` section is absent"]]
            (is (.contains text needle) needle)))
        (testing "locks task-artifact inspection before mining knowledge (TT3)"
          (doseq [needle ["Before mining knowledge, read these task artifacts from the resolved task when present"
                          "`design.md`"
                          "`plan.md`"
                          "`steps.md`"
                          "`implementation.md`"]]
            (is (.contains text needle) needle)))
        (testing "locks task-scoped history lenses and no unrelated history roaming"
          (doseq [needle ["commits touching the resolved task directory"
                          "commits whose message mentions the task id or slug"
                          "commit SHAs explicitly recorded in the task artifacts"
                          "Do not roam unrelated repository history"]]
            (is (.contains text needle) needle)))
        (testing "locks mementum recall, dedupe, filters, and zero-extraction success"
          (doseq [needle ["search/read `mementum/memories/`"
                          "search/read `mementum/knowledge/`"
                          "update the existing page"
                          "Do not create duplicate memories or duplicate knowledge pages"
                          "gate-1"
                          "gate-2"
                          "useful to the project outside the task's own context"
                          "significant for future development of the project"
                          "uncertain -> skip"
                          "Zero extraction is a successful outcome"]]
            (is (.contains text needle) needle)))
        (testing "locks autonomous persistence and mementum write contracts (TT1)"
          (doseq [needle ["Do not request human approval"
                          "mementum/memories/{slug}.md"
                          "under 200 words"
                          "one insight per file"
                          "content beginning with the appropriate mementum symbol"
                          "mementum/knowledge/{topic}.md"
                          "required frontmatter (`title`, `status`"
                          "memory commit: `{symbol} {slug}`"
                          "knowledge commit: `💡 {description}`"
                          "update commit: `🔄 update: {slug}`"
                          "Before committing, inspect `git status --short`"
                          "Stage and commit only extraction-owned mementum paths"
                          "mementum/memories/` and `mementum/knowledge/"
                          "use explicit pathspecs such as `git add --"
                          "Never use `git add .` or `git add -A`"
                          "Leave unrelated dirty worktree changes untouched, unstaged, and uncommitted"
                          "If unrelated dirt overlaps the intended mementum paths"
                          "report the blocked mementum commit and the reason"
                          "Do not commit if nothing changed"]]
            (is (.contains text needle) needle)))
        (testing "locks final-summary reporting and review-outcome preservation (TT2)"
          (doseq [needle ["resolved task path"
                          "whether extraction was standalone or lifecycle-authorized"
                          "extracted memories/knowledge"
                          "updated or skipped duplicates"
                          "zero-extraction success"
                          "any lifecycle/review outcome supplied in the dedicated `{{implementation_review_yield}}` section"
                          "preserving the prior lifecycle/review outcome alongside the extraction result"]]
            (is (.contains text needle) needle))))))
  (testing "has no same-name .edn sibling"
    (is (not (.exists (io/file (System/getProperty "user.dir")
                               ".psi/workflows"
                               "extract-task-knowledge.edn"))))))

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
           first-five-targets ["review-task-design"
                               "create-task-plan"
                               "review-task-plan"
                               "implement-task"
                               "review-task-implementation"]
           expected-targets (conj first-five-targets "extract-task-knowledge")
           standard-prompt {:type :map
                            :fields {:input {:from :workflow-input
                                             :path [:input]}}}
           extraction-prompt {:type :map
                              :fields {:input {:from :workflow-input
                                               :path [:input]}
                                       :implementation-review-yield
                                       {:from {:step "review-task-implementation"
                                               :yield :text}}}}]
       (testing "has 6 delegate steps ending in extract-task-knowledge"
         (is (= 6 (count steps)))
         (is (= expected-targets (mapv :name steps)))
         (is (= (repeat 6 :delegate) (mapv :type steps)))
         (is (= expected-targets (mapv :target steps))))
       (testing "the first five lifecycle steps thread the same task input unchanged"
         (is (= (repeat 5 standard-prompt)
                (mapv :prompt-string (take 5 steps)))))
       (testing "the extraction step threads task input plus a labeled implementation-review yield"
         (is (= extraction-prompt (:prompt-string (last steps)))))
       (testing "the first five lifecycle steps keep their original context only"
         (is (= (repeat 5 [{:type :source :from :workflow-original}])
                (mapv :context (take 5 steps)))))
       (testing "the extraction step carries only ambient original context in delegate context"
         (is (= [{:type :source :from :workflow-original}]
                (:context (last steps)))))
       (testing "no step declares :yields or :terminal-contract (terminal relies on propagated session default yield)"
         (is (= (repeat 6 {})
                (mapv #(select-keys % [:yields :terminal-contract]) steps))))))))

