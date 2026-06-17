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

(defn- count-substring
  [^String s ^String sub]
  (loop [from 0 n 0]
    (let [idx (.indexOf s sub from)]
      (if (neg? idx)
        n
        (recur (+ idx (count sub)) (inc n))))))

(defn- assert-sole-final-pass-status-line
  "DI-4 point 4: the summary template body must end with the exact, column-0,
   single-space `PASS_STATUS: <token>` line and contain exactly one
   `PASS_STATUS:` occurrence (no echoed review-reply status lines)."
  [text token]
  (is (= 1 (count-substring text "PASS_STATUS:"))
      (str "template should contain exactly one PASS_STATUS: occurrence for " token))
  (is (.endsWith ^String text (str "\nPASS_STATUS: " token))
      (str "template should end with the sole column-0 PASS_STATUS: " token " line")))

(defn- assert-review-summary-handback
  "Shared assertions for a review workflow's converged `final-summary` and its
   `final-summary-not-converged` handback (DI-1/DI-4): both explicit-terminal,
   each template body carries its sole final PASS_STATUS line, and the
   not-converged summary sources the same per-prompt review outputs."
  [final-step not-converged-step source-refs]
  (is (= (constant-routing-judge "DONE") (:judge final-step)))
  (is (= {"DONE" {:goto :done}} (:on final-step)))
  (is (= (constant-routing-judge "DONE") (:judge not-converged-step)))
  (is (= {"DONE" {:goto :done}} (:on not-converged-step)))
  (assert-sole-final-pass-status-line (step-template-text final-step) "REVIEW_COMPLETE")
  (assert-sole-final-pass-status-line (step-template-text not-converged-step) "ACTIONABLE_FEEDBACK")
  (doseq [source-ref source-refs]
    (is (some #(= {:type :source :from source-ref} %)
              (:contributions not-converged-step))
        (str "final-summary-not-converged should include " source-ref))))

;;; ---------------------------------------------------------------------------
;;; review-task-design

(defn- prompt-group-template-text
  [prompt-group]
  (->> (:contributions prompt-group)
       (filter #(= :template (:type %)))
       (map :text)
       (apply str)))

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
       (testing "has merged review topology with a not-converged handback before the converged summary (DI-2)"
         (is (= 4 (count steps)))
         (is (= ["design-review" "design-follow-up"
                 "final-summary-not-converged" "final-summary"]
                (mapv :name steps)))
         (is (= [:session :session :session :session]
                (mapv :type steps))))
       (let [step-by-name (into {} (map (juxt :name identity) steps))
             design-review (get step-by-name "design-review")
             design-follow-up (get step-by-name "design-follow-up")
             not-converged-step (get step-by-name "final-summary-not-converged")
             final-step (get step-by-name "final-summary")]
         (testing "design-review carries shared step-level capabilities"
           (is (= ["read" "bash" "edit" "write"]
                  (:tools design-review)))
           (is (= ["work-independently" "review-task-architecture" "task-design"]
                  (:skills design-review))))
         (testing "design-review contains ordered prompt groups imported from the review prompt workflows"
           (is (= ["architecture" "ambiguity" "inconsistency"]
                  (mapv :name (:prompts design-review))))
           (is (.contains (slurp-workflow-file "review-task-design.edn")
                          ":prompt-workflow \"review-task-design-architecture-review.md\""))
           (is (.contains (slurp-workflow-file "review-task-design.edn")
                          ":prompt-workflow \"review-task-design-ambiguity-review.md\""))
           (is (.contains (slurp-workflow-file "review-task-design.edn")
                          ":prompt-workflow \"review-task-design-inconsistency-review.md\""))
           (is (.contains (prompt-group-template-text (first (:prompts design-review)))
                          "first turn of the shared `design-review` multi-prompt session"))
           (is (.contains (prompt-group-template-text (second (:prompts design-review)))
                          "Use the already-loaded task design.md"))
           (is (.contains (prompt-group-template-text (nth (:prompts design-review) 2))
                          "Use the already-loaded task design.md")))
         (testing "design-review routes directly from validated per-prompt output refs"
           (is (= {:type :invoke
                   :operation "workflow/pass-feedback-routing"
                   :args {:architecture-text {:from {:step "design-review"
                                                     :prompt "architecture"
                                                     :output :final-llm-reply}}
                          :ambiguity-text {:from {:step "design-review"
                                                  :prompt "ambiguity"
                                                  :output :final-llm-reply}}
                          :inconsistency-text {:from {:step "design-review"
                                                      :prompt "inconsistency"
                                                      :output :final-llm-reply}}}}
                  (:judge design-review)))
           (is (= {"REPEAT" {:goto "design-follow-up"}
                   "DONE" {:goto "final-summary"}}
                  (:on design-review))))
         (testing "design-follow-up uses the shared design profile and loops to the review-pass target with author-routed exhaustion"
           (is (= (constant-routing-judge "DONE")
                  (:judge design-follow-up)))
           (is (= {"DONE" {:goto "design-review"
                           :max-iterations 3
                           :on-max-iterations "final-summary-not-converged"}}
                  (:on design-follow-up)))
           (let [text (step-template-text design-follow-up)]
             (is (.contains text "design-steps.md"))
             (is (.contains text "immediately preceding whole `design-review` batch"))
             (is (.contains text "git diff <baseline>..HEAD -- <task>/design-steps.md"))
             (is (.contains text "Do not touch plan.md or steps.md"))
             (is (not (.contains text "code, tests, and docs")))
             (is (not (.contains text "design.md as read-only context")))))
         (testing "final-summary sources each review text through per-prompt outputs"
           (is (some? final-step) "final-summary step should exist")
           (is (seq (:contributions final-step)) "final-summary step should have inline contributions")
           (doseq [source-ref [{:step "design-review" :prompt "architecture" :output :final-llm-reply}
                               {:step "design-review" :prompt "ambiguity" :output :final-llm-reply}
                               {:step "design-review" :prompt "inconsistency" :output :final-llm-reply}]]
             (is (some #(= {:type :source :from source-ref} %)
                       (:contributions final-step))
                 (str "final-summary should include " source-ref)))
           (is (not-any? #(and (map? (:from %))
                               (contains? (:from %) :yield))
                         (filter #(= :source (:type %)) (:contributions final-step)))
               "final-summary must not use per-prompt :yield refs"))
         (testing "converged + not-converged summaries: terminal, DI-4 PASS_STATUS, shared sources"
           (assert-review-summary-handback
            final-step not-converged-step
            [{:step "design-review" :prompt "architecture" :output :final-llm-reply}
             {:step "design-review" :prompt "ambiguity" :output :final-llm-reply}
             {:step "design-review" :prompt "inconsistency" :output :final-llm-reply}]))
         (testing "removed per-phase topology step names are absent"
           (doseq [removed ["architecture-review" "architecture-follow-up"
                            "ambiguity-review" "ambiguity-follow-up"
                            "inconsistency-review" "inconsistency-follow-up"
                            "clarity-status"]]
             (is (not (contains? step-by-name removed))
                 (str removed " should not remain as a step")))))))))
;;; ---------------------------------------------------------------------------
;;; review-task-plan

(deftest review-task-plan-test
  (load-edn-with-md-refs
   "review-task-plan.edn"
   ["review-task-plan-ambiguity-review.md"
    "review-task-plan-inconsistency-review.md"
    "review-follow-up-plan.md"]
   (fn [{:keys [definitions errors]}]
     (testing "loads without error"
       (is (empty? errors))
       (is (contains? definitions "review-task-plan")))
     (let [steps (get-in definitions ["review-task-plan" :steps])]
       (testing "has merged review topology with a not-converged handback before the converged summary (DI-2)"
         (is (= 4 (count steps)))
         (is (= ["plan-review" "plan-follow-up"
                 "final-summary-not-converged" "final-summary"]
                (mapv :name steps)))
         (is (= [:session :session :session :session]
                (mapv :type steps))))
       (let [step-by-name (into {} (map (juxt :name identity) steps))
             plan-review (get step-by-name "plan-review")
             plan-follow-up (get step-by-name "plan-follow-up")
             not-converged-step (get step-by-name "final-summary-not-converged")
             final-step (get step-by-name "final-summary")]
         (testing "plan-review carries shared step-level capabilities"
           (is (= ["read" "bash" "edit" "write"]
                  (:tools plan-review)))
           (is (= ["work-independently" "task-design"]
                  (:skills plan-review))))
         (testing "plan-review contains ordered prompt groups imported from the review prompt workflows"
           (is (= ["ambiguity" "inconsistency"]
                  (mapv :name (:prompts plan-review))))
           (is (.contains (slurp-workflow-file "review-task-plan.edn")
                          ":prompt-workflow \"review-task-plan-ambiguity-review.md\""))
           (is (.contains (slurp-workflow-file "review-task-plan.edn")
                          ":prompt-workflow \"review-task-plan-inconsistency-review.md\""))
           (is (.contains (prompt-group-template-text (first (:prompts plan-review)))
                          "first turn of the shared `plan-review` multi-prompt session"))
           (is (.contains (prompt-group-template-text (second (:prompts plan-review)))
                          "Use the already-loaded task plan.md")))
         (testing "plan-review routes directly from validated per-prompt output refs"
           (is (= {:type :invoke
                   :operation "workflow/pass-feedback-routing"
                   :args {:ambiguity-text {:from {:step "plan-review"
                                                  :prompt "ambiguity"
                                                  :output :final-llm-reply}}
                          :inconsistency-text {:from {:step "plan-review"
                                                      :prompt "inconsistency"
                                                      :output :final-llm-reply}}}}
                  (:judge plan-review)))
           (is (= {"REPEAT" {:goto "plan-follow-up"}
                   "DONE" {:goto "final-summary"}}
                  (:on plan-review))))
         (testing "plan-follow-up uses the batch plan profile and loops to the review-pass target with author-routed exhaustion"
           (is (= (constant-routing-judge "DONE")
                  (:judge plan-follow-up)))
           (is (= {"DONE" {:goto "plan-review"
                           :max-iterations 5
                           :on-max-iterations "final-summary-not-converged"}}
                  (:on plan-follow-up)))
           (let [text (step-template-text plan-follow-up)]
             ;; #177 routes plan-review follow-ups through shared design-steps.md.
             (is (.contains text "design-steps.md"))
             (is (.contains text "immediately preceding whole `plan-review` batch"))
             (is (.contains text "git diff <baseline>..HEAD -- <task>/design-steps.md"))
             (is (.contains text "predate the preceding review pass"))
             (is (.contains text "code, tests, and docs"))
             (is (.contains text "design.md as read-only context"))))
         (testing "final-summary sources each review text through per-prompt outputs"
           (is (some? final-step) "final-summary step should exist")
           (is (seq (:contributions final-step)) "final-summary step should have inline contributions")
           (doseq [source-ref [{:step "plan-review" :prompt "ambiguity" :output :final-llm-reply}
                               {:step "plan-review" :prompt "inconsistency" :output :final-llm-reply}]]
             (is (some #(= {:type :source :from source-ref} %)
                       (:contributions final-step))
                 (str "final-summary should include " source-ref)))
           (is (not-any? #(and (map? (:from %))
                               (contains? (:from %) :yield))
                         (filter #(= :source (:type %)) (:contributions final-step)))
               "final-summary must not use per-prompt :yield refs"))
         (testing "converged + not-converged summaries: terminal, DI-4 PASS_STATUS, shared sources"
           (assert-review-summary-handback
            final-step not-converged-step
            [{:step "plan-review" :prompt "ambiguity" :output :final-llm-reply}
             {:step "plan-review" :prompt "inconsistency" :output :final-llm-reply}]))
         (testing "removed per-phase topology step names are absent"
           (doseq [removed ["ambiguity-review" "ambiguity-follow-up"
                            "inconsistency-review" "inconsistency-follow-up"
                            "clarity-status"]]
             (is (not (contains? step-by-name removed))
                 (str removed " should not remain as a step")))))))))

;;; ---------------------------------------------------------------------------
;;; review task prompt artifact targets

(deftest review-task-prompt-artifact-targets-test
  ;; Artifact ownership: per #177 both design and plan review write follow-up
  ;; items to the shared design-steps.md (steps.md is read-only task context).
  (testing "design review prompts target design-steps.md"
    (doseq [filename ["review-task-design-architecture-review.md"
                      "review-task-design-ambiguity-review.md"
                      "review-task-design-inconsistency-review.md"
                      "review-follow-up-design.md"]]
      (let [content (slurp-workflow-file filename)]
        (is (.contains content "design-steps.md") filename))))
  (testing "plan review prompts also target the shared design-steps.md (#177)"
    (doseq [filename ["review-task-plan-ambiguity-review.md"
                      "review-task-plan-inconsistency-review.md"
                      "review-follow-up-plan.md"]]
      (let [content (slurp-workflow-file filename)]
        (is (.contains content "design-steps.md") filename))))
  (testing "the shared steps follow-up profile still owns steps.md"
    (let [content (slurp-workflow-file "review-follow-up-steps.md")]
      (is (re-find #"(^|[^-])steps\.md" content))
      (is (not (.contains content "design-steps.md")))))
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
          (str edn-filename " must not reference removed " removed-filename))))
  (testing "deterministic clarity-status leaves no stale prompt workflows"
    ;; The design/plan host workflows now encode clarity-status as deterministic
    ;; invoke routing. Stale prompt workflows would still be delegate-visible and
    ;; could instruct the old artifact re-read control behaviour.
    (doseq [removed-filename ["review-task-design-clarity-status.md"
                              "review-task-plan-clarity-status.md"]]
      (is (not (.exists (io/file (System/getProperty "user.dir")
                                 ".psi/workflows"
                                 removed-filename)))
          (str removed-filename " must not exist as a standalone prompt workflow")))
    (doseq [edn-filename ["review-task-design.edn" "review-task-plan.edn"]
            removed-filename ["review-task-design-clarity-status.md"
                              "review-task-plan-clarity-status.md"]]
      (is (not (.contains (slurp-workflow-file edn-filename) removed-filename))
          (str edn-filename " must not reference stale " removed-filename)))))

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
         (is (= {"REPEAT" {:goto "review" :max-iterations 10}}
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
       "review-follow-up-plan.md"]
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
           "review-follow-up-plan.md" (slurp-workflow-file "review-follow-up-plan.md")
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
        (testing "locks slug/path normalization and task-resolution boundaries"
          (doseq [needle ["exact `NNN-slug`"
                          "exact `munera/open/NNN-slug`"
                          "exact `munera/closed/NNN-slug`"
                          "Reject any other path/string shape"
                          "zero matches or more than one match"
                          "Direct standalone invocations may extract from either location"]]
            (is (.contains text needle) needle)))
        (testing "leaves lifecycle proof gating to task-lifecycle"
          (doseq [needle ["`task-lifecycle` owns review-complete gating"
                          "Do not re-check lifecycle proof here"
                          "not an authorization condition to evaluate here"
                          "lifecycle/review outcome supplied in the dedicated `{{implementation_review_yield}}` section"]]
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
                          "whether it was under `munera/open/` or `munera/closed/`"
                          "extracted memories/knowledge"
                          "updated or skipped duplicates"
                          "zero-extraction success"
                          "whether lifecycle implementation-review outcome/provenance was supplied"
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
           step-by-name (into {} (map (juxt :name identity) steps))
           ;; Select delegate steps by type, not position, so the inserted
           ;; :invoke gates (which carry no :target/:prompt-string/:context)
           ;; do not break these assertions (DI-5).
           delegate-steps (filterv #(= :delegate (:type %)) steps)
           standard-prompt {:type :map
                            :fields {:input {:from :workflow-input
                                             :path [:input]}}}
           extraction-prompt {:type :map
                              :fields {:input {:from :workflow-input
                                               :path [:input]}
                                       :implementation-review-yield
                                       {:from {:step "review-task-implementation"
                                               :yield :text}}}}
           scope-gate-step (get step-by-name "check-scope-question-status")
           scope-question-open-step (get step-by-name "final-summary-scope-question-open")
           design-gate-step (get step-by-name "check-design-review-status")
           plan-gate-step (get step-by-name "check-plan-review-status")
           status-step (get step-by-name "check-implementation-review-status")
           extraction-step (get step-by-name "extract-task-knowledge")
           success-summary-step (get step-by-name "final-summary-after-extraction")
           skip-summary-step (get step-by-name "final-summary-without-extraction")
           design-not-converged-step (get step-by-name "final-summary-design-not-converged")
           plan-not-converged-step (get step-by-name "final-summary-plan-not-converged")
           skip-summary-text (step-template-text skip-summary-step)]
       (testing "has 15 steps, with the pre-plan scope-question gate, design/plan review gates, and extraction guarded after implementation review"
         (is (= 15 (count steps)))
         (is (= ["review-task-design"
                 "check-scope-question-status"
                 "check-design-review-status"
                 "create-task-plan"
                 "review-task-plan"
                 "check-plan-review-status"
                 "implement-task"
                 "review-task-implementation"
                 "check-implementation-review-status"
                 "extract-task-knowledge"
                 "final-summary-after-extraction"
                 "final-summary-without-extraction"
                 "final-summary-design-not-converged"
                 "final-summary-plan-not-converged"
                 "final-summary-scope-question-open"]
                (mapv :name steps)))
         (is (= [:delegate :invoke :invoke :delegate :delegate :invoke :delegate :delegate
                 :invoke :delegate :session :session :session :session :session]
                (mapv :type steps))))
       (testing "the lifecycle delegate steps target their workflows in order"
         (is (= ["review-task-design"
                 "create-task-plan"
                 "review-task-plan"
                 "implement-task"
                 "review-task-implementation"
                 "extract-task-knowledge"]
                (mapv :target delegate-steps))))
       (testing "the delegate steps thread the same task input unchanged (extraction adds the review yield)"
         (is (= (concat (repeat 5 standard-prompt) [extraction-prompt])
                (mapv :prompt-string delegate-steps))))
       (testing "the pre-plan scope-question gate scans design-steps.md and routes open questions to handback"
         (is (= {:type :invoke
                 :operation "workflow/scope-question-gate-routing"
                 :args {:task-path {:from :workflow-input
                                    :path [:input]}
                        :artifact "design-steps.md"
                        :marker "SCOPE_QUESTION:"
                        :proceed-route "DONE"
                        :open-route "SCOPE_QUESTION_OPEN"}}
                (:judge scope-gate-step)))
         (is (= {"DONE" {:goto "check-design-review-status"}
                 "SCOPE_QUESTION_OPEN" {:goto "final-summary-scope-question-open"}}
                (:on scope-gate-step))))
       (testing "the design gate routes converged design to plan and unconverged design to handback"
         (is (= {:type :invoke
                 :operation "workflow/pass-status-routing"
                 :args {:text {:from {:step "review-task-design"
                                      :yield :text}}
                        :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}
                (:judge design-gate-step)))
         (is (= {"DONE" {:goto "create-task-plan"}
                 "REPEAT" {:goto "final-summary-design-not-converged"}}
                (:on design-gate-step))))
       (testing "the plan gate routes converged plan to implementation and unconverged plan to handback"
         (is (= {:type :invoke
                 :operation "workflow/pass-status-routing"
                 :args {:text {:from {:step "review-task-plan"
                                      :yield :text}}
                        :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}
                (:judge plan-gate-step)))
         (is (= {"DONE" {:goto "implement-task"}
                 "REPEAT" {:goto "final-summary-plan-not-converged"}}
                (:on plan-gate-step))))
       (testing "the status step owns the extraction gate"
         (is (= {:type :invoke
                 :operation "workflow/pass-status-routing"
                 :args {:text {:from {:step "review-task-implementation"
                                      :yield :text}}
                        :allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]}}
                (:judge status-step)))
         (is (= {"DONE" {:goto "extract-task-knowledge"}
                 "REPEAT" {:goto "final-summary-without-extraction"}}
                (:on status-step))))
       (testing "the extraction step threads task input plus a labeled implementation-review yield"
         (is (= extraction-prompt (:prompt-string extraction-step))))
       (testing "the extraction step routes to the extraction success summary"
         (is (= {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "DONE"}}
                (:judge extraction-step)))
         (is (= {"DONE" {:goto "final-summary-after-extraction"}}
                (:on extraction-step))))
       (testing "delegate steps keep their original context only"
         (is (= (repeat 6 [{:type :source :from :workflow-original}])
                (mapv :context delegate-steps))))
       (testing "non-review-complete summary explains extraction was skipped"
         (is (= ["read" "bash"] (:tools skip-summary-step)))
         (is (.contains skip-summary-text "extract-task-knowledge was not invoked"))
         (is (.contains skip-summary-text "PASS_STATUS: REVIEW_COMPLETE"))
         (is (.contains skip-summary-text "Do not extract or write mementum knowledge here"))
         (is (= {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "DONE"}}
                (:judge skip-summary-step)))
         (is (= {"DONE" {:goto :done}} (:on skip-summary-step))))
       (testing "successful extraction summary terminates the success path"
         (is (= {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "DONE"}}
                (:judge success-summary-step)))
         (is (= {"DONE" {:goto :done}} (:on success-summary-step))))
       (testing "the design-not-converged handback terminates without extraction"
         (is (= ["read" "bash"] (:tools design-not-converged-step)))
         (is (some #(= {:type :source :from {:step "review-task-design" :yield :text}} %)
                   (:contributions design-not-converged-step)))
         (let [text (step-template-text design-not-converged-step)]
           (is (.contains text "stopped at the design stage"))
           (is (.contains text "Do not proceed to plan creation")))
         (is (= {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "DONE"}}
                (:judge design-not-converged-step)))
         (is (= {"DONE" {:goto :done}} (:on design-not-converged-step))))
       (testing "the plan-not-converged handback terminates without extraction"
         (is (= ["read" "bash"] (:tools plan-not-converged-step)))
         (is (some #(= {:type :source :from {:step "review-task-plan" :yield :text}} %)
                   (:contributions plan-not-converged-step)))
         (let [text (step-template-text plan-not-converged-step)]
           (is (.contains text "stopped at the plan stage"))
           (is (.contains text "Do not proceed to implementation")))
         (is (= {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "DONE"}}
                (:judge plan-not-converged-step)))
         (is (= {"DONE" {:goto :done}} (:on plan-not-converged-step))))
       (testing "the scope-question-open handback names the open question and stops before plan creation"
         (is (= ["read" "bash"] (:tools scope-question-open-step)))
         (let [text (step-template-text scope-question-open-step)]
           (is (.contains text "SCOPE_QUESTION:"))
           (is (.contains text "before plan creation"))
           (is (.contains text "design-steps.md"))
           (is (.contains text "re-invoke `task-lifecycle`"))
           (is (.contains text "Do not proceed to plan creation")))
         (is (= {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "DONE"}}
                (:judge scope-question-open-step)))
         (is (= {"DONE" {:goto :done}} (:on scope-question-open-step))))
       (testing "no step declares :yields or :terminal-contract (terminal relies on propagated session default yield)"
         (is (= (repeat 15 {})
                (mapv #(select-keys % [:yields :terminal-contract]) steps))))))))
