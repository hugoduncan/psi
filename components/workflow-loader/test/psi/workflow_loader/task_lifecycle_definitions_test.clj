(ns psi.workflow-loader.task-lifecycle-definitions-test
  "Loader/compiler test for the task-lifecycle workflow definition.

   Split out of workflow-definitions-test to keep each test file within the
   800-line component limit. Asserts step count/order/types, the pre-plan
   scope-question gate, the design/plan/implementation review gates, and the
   handback summaries."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.workflow-test-support
    :refer [load-edn-only
            step-template-text]]))

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
           implementation-gate-step (get step-by-name "check-implementation-status")
           implementation-blocked-step (get step-by-name "final-summary-implementation-blocked")
           status-step (get step-by-name "check-implementation-review-status")
           extraction-step (get step-by-name "extract-task-knowledge")
           success-summary-step (get step-by-name "final-summary-after-extraction")
           skip-summary-step (get step-by-name "final-summary-without-extraction")
           design-not-converged-step (get step-by-name "final-summary-design-not-converged")
           plan-not-converged-step (get step-by-name "final-summary-plan-not-converged")
           skip-summary-text (step-template-text skip-summary-step)]
       (testing "has 17 steps, with an implementation-status gate before review and extraction guarded after implementation review"
         (is (= 17 (count steps)))
         (is (= ["review-task-design"
                 "check-scope-question-status"
                 "check-design-review-status"
                 "create-task-plan"
                 "review-task-plan"
                 "check-plan-review-status"
                 "implement-task"
                 "check-implementation-status"
                 "review-task-implementation"
                 "check-implementation-review-status"
                 "extract-task-knowledge"
                 "final-summary-after-extraction"
                 "final-summary-without-extraction"
                 "final-summary-design-not-converged"
                 "final-summary-plan-not-converged"
                 "final-summary-implementation-blocked"
                 "final-summary-scope-question-open"]
                (mapv :name steps)))
         (is (= [:delegate :invoke :invoke :delegate :delegate :invoke :delegate :invoke
                 :delegate :invoke :delegate :session :session :session :session :session :session]
                (mapv :type steps))))
       (testing "the lifecycle delegate steps target their workflows in order"
         (is (= ["review-task-design-core"
                 "create-task-plan"
                 "review-task-plan-core"
                 "implement-task"
                 "review-task-implementation-core"
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
       (testing "the implementation-status gate permits review only after a completed implementation"
         (is (= {:type :invoke
                 :operation "workflow/exact-marker-routing"
                 :args {:text {:from {:step "implement-task"
                                      :yield :text}}
                        :marker-label "IMPLEMENTATION_STATUS"
                        :allowed-routes ["IMPLEMENTATION_COMPLETE"
                                         "IMPLEMENTATION_BLOCKED"]}}
                (:judge implementation-gate-step)))
         (is (= {"IMPLEMENTATION_COMPLETE" {:goto "review-task-implementation"}
                 "IMPLEMENTATION_BLOCKED" {:goto "final-summary-implementation-blocked"}}
                (:on implementation-gate-step))))
       (testing "the implementation-blocked handback stops before review and extraction"
         (is (= ["read" "bash"] (:tools implementation-blocked-step)))
         (is (some #(= {:type :source :from {:step "implement-task" :yield :text}} %)
                   (:contributions implementation-blocked-step)))
         (let [text (step-template-text implementation-blocked-step)]
           (is (.contains text "last complete record in file order"))
           (is (.contains text "review-task-implementation` and `extract-task-knowledge` did not run"))
           (is (.contains text "re-invoke `task-lifecycle`")))
         (is (= {"DONE" {:goto :done}} (:on implementation-blocked-step))))
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
         (is (= (repeat 17 {})
                (mapv #(select-keys % [:yields :terminal-contract]) steps))))))))
