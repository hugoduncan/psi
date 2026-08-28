(ns psi.agent-session.workflow-implementation-routing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.turn]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.deterministic-operation-registry.registry]
   [psi.workflow-loader.compiler :as workflow-compiler]
   [psi.workflow-loader.parser :as workflow-parser]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.terminal-contract :as terminal-contract]))

(defn- register-review-routing-ops!
  [ctx]
  (workflow-core/init {:register-operation (fn [operation]
                                             (psi.deterministic-operation-registry.registry/register-operation-in!
                                              (:deterministic-operation-registry ctx)
                                              operation))
                       :register-tool (fn [_] nil)
                       :register-command (fn [& _] nil)
                       :on (fn [& _] nil)
                       :query (fn [& _] nil)
                       :query-session (fn [& _] nil)
                       :mutate (fn [& _] nil)
                       :mutate-session (fn [& _] nil)}))

(def implement-task-definition
  {:definition-id "implement-task-proof"
   :name "implement-task-proof"
   :steps [{:name "implement-pass"
            :type :session
            :contributions [{:type :template
                             :text "Implement {{input}}"
                             :vars {"input" {:from :workflow-input :path [:input]}}}]
            :judge {:type :invoke
                    :operation "workflow/exact-marker-routing"
                    :args {:text {:from {:step "implement-pass" :output :final-llm-reply}}
                           :marker-label "PASS_STATUS"
                           :allowed-routes ["MORE_WORK_REMAINS"
                                            "IMPLEMENTATION_COMPLETE"
                                            "IMPLEMENTATION_BLOCKED"]}}
            :on {"MORE_WORK_REMAINS" {:goto "implement-pass" :max-iterations 20}
                 "IMPLEMENTATION_COMPLETE" {:goto "final-summary-complete"}
                 "IMPLEMENTATION_BLOCKED" {:goto "final-summary-blocked"}}}
           ;; Deliberately before the complete summary: terminal projection must
           ;; use the executed terminal step rather than declaration order.
           {:name "final-summary-blocked"
            :type :session
            :contributions [{:type :template :text "Blocked summary"}]
            :judge {:type :invoke
                    :operation "workflow/constant-routing"
                    :args {:route "DONE"}}
            :on {"DONE" {:goto :done}}}
           {:name "final-summary-complete"
            :type :session
            :contributions [{:type :template :text "Complete summary"}]
            :judge {:type :invoke
                    :operation "workflow/constant-routing"
                    :args {:route "DONE"}}
            :on {"DONE" {:goto :done}}}]})
(defn- create-implement-task-run!
  [ctx definition run-id task-path]
  (swap! (:state* ctx)
         (fn [state]
           (let [[s _ _] (workflow-runtime/create-run state {:definition definition
                                                             :run-id run-id
                                                             :workflow-input {:input task-path}})]
             s))))

(defn- checked-in-implement-task-definition
  [worktree]
  (let [path (str worktree "/.psi/workflows/implement-task.edn")
        parsed (workflow-parser/parse-edn-workflow-file (slurp path))
        {:keys [definition error]} (workflow-compiler/compile-workflow-file
                                    (assoc parsed :source-path path))]
    (when error
      (throw (ex-info "Checked-in implement-task definition did not compile"
                      {:error error})))
    ;; The definition is checked in and compiled as production does; this
    ;; state-based fixture has no project-profile registry, so remove only the
    ;; environment-dependent session-profile selection before execution.
    (update definition :steps
            (fn [steps]
              (mapv #(if (= "implement-pass" (:name %))
                       (dissoc % :session-profile)
                       %)
                    steps)))))

(defn- artifact-content
  [block]
  (str "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
       "- blocker: " (:blocker block) "\n"
       "- required-human-action: " (:required-human-action block) "\n"
       "<!-- IMPLEMENTATION_BLOCKER: END -->\n"))

(deftest implement-task-implementation-complete-routes-to-final-summary-test
  (testing "IMPLEMENTATION_COMPLETE terminates the implementation loop deterministically"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-implement-task-run! ctx implement-task-definition "run-implement-complete" "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx child-session-id prompt]
                      (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text
                                   :text (case prompt
                                           "Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                                           "No work remains\n\nPASS_STATUS: IMPLEMENTATION_COMPLETE"

                                           "Complete summary"
                                           "complete summary")}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-implement-complete")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-implement-complete")]
          (is (= :completed (:status result)))
          (is (= :completed (:status run)))
          (is (= 1 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (= 1 (count (get-in run [:step-runs "final-summary-complete" :attempts]))))
          (is (= {:status :ok :data "IMPLEMENTATION_COMPLETE" :summary "IMPLEMENTATION_COMPLETE"}
                 (get-in run [:step-runs "implement-pass" :attempts 0 :judge-output :routing-result])))
          (is (= ["Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                  "Complete summary"]
                 (mapv :prompt @prompts*))))))))

(deftest implement-task-implementation-blocked-routes-to-blocked-summary-test
  ;; Tests the authored blocked route terminates without another pass and that
  ;; terminal projection selects this earlier-declared terminal branch.
  (testing "IMPLEMENTATION_BLOCKED reaches the blocked handback"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (register-review-routing-ops! ctx)
      (create-implement-task-run! ctx implement-task-definition "run-implement-blocked" "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _child-session-id prompt]
                      (swap! prompts* conj prompt)
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text
                                   :text (case prompt
                                           "Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                                           "Need a human decision\nPASS_STATUS: IMPLEMENTATION_BLOCKED"
                                           "Blocked summary"
                                           "blocked handback")}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-implement-blocked")
              run (workflow-runtime/workflow-run-in @(:state* ctx) "run-implement-blocked")]
          (is (= :completed (:status result)))
          (is (= 1 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (= 1 (count (get-in run [:step-runs "final-summary-blocked" :attempts]))))
          (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))))
          (is (= "final-summary-blocked" (get-in run [:terminal-outcome :step-id])))
          (is (= "blocked handback" (terminal-contract/terminal-yielded-text run)))
          (is (= ["Implement munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"
                  "Blocked summary"]
                 @prompts*)))))))

(deftest checked-in-implement-task-blocked-route-validates-persisted-blocker-test
  ;; Tests the checked-in topology validates its artifact before its only blocked handback.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx session-id]
      (let [task-path "munera/open/230-x"
            run-id "checked-in-implement-blocked"
            replies* (atom 0)
            prompts* (atom [])]
        (test-support/write-task-artifact!
         worktree task-path "implementation.md"
         (artifact-content {:blocker "earlier decision"
                            :required-human-action "ignore this record"}))
        (register-review-routing-ops! ctx)
        (create-implement-task-run! ctx
                                    (checked-in-implement-task-definition (System/getProperty "user.dir"))
                                    run-id task-path)
        (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                      (fn [_ctx _child-session-id prompt]
                        (swap! prompts* conj prompt)
                        (let [reply-number (swap! replies* inc)]
                          (when (= 1 reply-number)
                            (spit (str worktree "/" task-path "/implementation.md")
                                  (str (artifact-content {:blocker "earlier decision"
                                                          :required-human-action "ignore this record"})
                                       (artifact-content {:blocker "awaiting product decision"
                                                          :required-human-action "choose the retention policy"}))))
                          {:execution-result/assistant-message
                           {:role "assistant"
                            :content [{:type :text
                                       :text (case reply-number
                                               1 "PASS_STATUS: IMPLEMENTATION_BLOCKED"
                                               2 (do
                                                   (spit (str worktree "/" task-path "/implementation.md")
                                                         (artifact-content
                                                          {:blocker "intervening edit"
                                                           :required-human-action "ignore validated record"}))
                                                   "blocked handback"))}]
                            :stop-reason :stop}}))]
          (let [result (workflow-execution/execute-run! ctx session-id run-id)
                run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
            (is (= :completed (:status result)) (pr-str run))
            (is (= {:outcome :completed :step-id "final-summary-blocked"}
                   (select-keys (:terminal-outcome run) [:outcome :step-id :reason])))
            (is (= 1 (count (get-in run [:step-runs "implement-pass" :attempts]))))
            (is (= 1 (count (get-in run [:step-runs "validate-implementation-blocker" :attempts]))))
            (is (= 1 (count (get-in run [:step-runs "final-summary-blocked" :attempts]))))
            (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))))
            (is (= "final-summary-blocked" (get-in run [:terminal-outcome :step-id])))
            (is (= "blocked handback" (terminal-contract/terminal-yielded-text run)))
            (is (= "DONE"
                   (get-in run [:step-runs "validate-implementation-blocker"
                                :accepted-result :outputs :data])))
            (is (some #(.contains % "- blocker: awaiting product decision")
                      @prompts*))
            (is (some #(.contains % "- required-human-action: choose the retention policy")
                      @prompts*))
            (is (not-any? #(.contains % "intervening edit") @prompts*))))))))

(deftest checked-in-implement-task-blocked-route-rejects-stale-blocker-test
  (test-support/with-temp-worktree-session
    (fn [worktree ctx session-id]
      (let [task-path "munera/open/230-x"
            run-id "checked-in-implement-stale-blocker"
            content (artifact-content {:blocker "earlier decision"
                                       :required-human-action "ignore this record"})]
        (test-support/write-task-artifact! worktree task-path "implementation.md" content)
        (register-review-routing-ops! ctx)
        (create-implement-task-run! ctx
                                    (checked-in-implement-task-definition (System/getProperty "user.dir"))
                                    run-id task-path)
        (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                      (fn [_ctx _child-session-id _prompt]
                        {:execution-result/assistant-message
                         {:role "assistant"
                          :content [{:type :text :text "PASS_STATUS: IMPLEMENTATION_BLOCKED"}]
                          :stop-reason :stop}})]
          (let [result (workflow-execution/execute-run! ctx session-id run-id)
                run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
            (is (= :failed (:status result)) (pr-str run))
            (is (= :missing-fresh-final-complete-block
                   (get-in run [:step-runs "validate-implementation-blocker"
                                :attempts 0 :execution-error :reason])) (pr-str run))
            (is (zero? (count (get-in run [:step-runs "final-summary-blocked" :attempts])))
                (pr-str run))))))))

(deftest checked-in-implement-task-blocked-route-rejects-invalid-blockers-test
  ;; Tests the real blocked route fails at validation rather than inventing a handback.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx session-id]
      (doseq [[label content] [["missing" "implementation notes only"]
                               ["malformed"
                                "<!-- IMPLEMENTATION_BLOCKER: START -->\n- blocker: missing action\n<!-- IMPLEMENTATION_BLOCKER: END -->\n"]]]
        (let [task-path "munera/open/230-x"
              run-id (str "checked-in-implement-blocked-" label)]
          (test-support/write-task-artifact! worktree task-path "implementation.md" content)
          (register-review-routing-ops! ctx)
          (create-implement-task-run! ctx
                                      (checked-in-implement-task-definition (System/getProperty "user.dir"))
                                      run-id task-path)
          (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                        (fn [_ctx _child-session-id _prompt]
                          {:execution-result/assistant-message
                           {:role "assistant"
                            :content [{:type :text :text "PASS_STATUS: IMPLEMENTATION_BLOCKED"}]
                            :stop-reason :stop}})]
            (let [result (workflow-execution/execute-run! ctx session-id run-id)
                  run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
              (is (= :failed (:status result)) label)
              (is (= :missing-fresh-final-complete-block
                     (get-in run [:step-runs "validate-implementation-blocker"
                                  :attempts 0 :execution-error :reason])) label)
              (is (= 1 (count (get-in run [:step-runs "validate-implementation-blocker" :attempts]))) label)
              (is (zero? (count (get-in run [:step-runs "final-summary-blocked" :attempts]))) label)
              (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))) label))))))))

(deftest implement-task-invalid-statuses-and-repeat-limit-fail-test
  ;; Tests invalid implementation markers fail at the authored exact-marker gate
  ;; and the existing repeat bound remains twenty passes.
  (testing "malformed, duplicate, and unsupported PASS_STATUS markers fail without a summary"
    (doseq [[label reply reason]
            [["malformed" "PASS_STATUS:IMPLEMENTATION_BLOCKED" :malformed-route-marker]
             ["duplicate" "PASS_STATUS: IMPLEMENTATION_COMPLETE\nPASS_STATUS: IMPLEMENTATION_BLOCKED" :ambiguous-route-marker]
             ["unsupported" "PASS_STATUS: UNKNOWN_OUTCOME" :unsupported-route-marker]]]
      (let [[ctx session-id] (support/create-session-context {:persist? false})
            run-id (str "run-implement-" label)]
        (register-review-routing-ops! ctx)
        (create-implement-task-run! ctx implement-task-definition run-id "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows")
        (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                      (fn [_ctx _child-session-id _prompt]
                        {:execution-result/assistant-message
                         {:role "assistant"
                          :content [{:type :text :text reply}]
                          :stop-reason :stop}})]
          (let [result (workflow-execution/execute-run! ctx session-id run-id)
                run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
            (is (= :failed (:status result)) label)
            (is (= reason (get-in run [:terminal-outcome :reason])) label)
            (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))) label)
            (is (zero? (count (get-in run [:step-runs "final-summary-blocked" :attempts]))) label))))))
  (testing "the twenty-pass MORE_WORK_REMAINS bound still fails before pass twenty-one"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          run-id "run-implement-repeat-limit"]
      (register-review-routing-ops! ctx)
      (create-implement-task-run! ctx implement-task-definition run-id "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows")
      (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                    (fn [_ctx _child-session-id _prompt]
                      {:execution-result/assistant-message
                       {:role "assistant"
                        :content [{:type :text :text "PASS_STATUS: MORE_WORK_REMAINS"}]
                        :stop-reason :stop}})]
        (let [result (workflow-execution/execute-run! ctx session-id run-id)
              run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
          (is (= :failed (:status result)))
          (is (= :iteration-exhausted (get-in run [:terminal-outcome :reason])))
          (is (= 20 (count (get-in run [:step-runs "implement-pass" :attempts]))))
          (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))))
          (is (zero? (count (get-in run [:step-runs "final-summary-blocked" :attempts])))))))))

(deftest checked-in-implement-task-more-work-repeats-then-completes-test
  ;; Tests the checked-in artifact-capture loop and normal terminal contract.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx session-id]
      (let [task-path "munera/open/230-x"
            run-id "checked-in-implement-more-work"
            replies* (atom 0)]
        (test-support/write-task-artifact! worktree task-path "implementation.md" "initial notes\n")
        (register-review-routing-ops! ctx)
        (create-implement-task-run! ctx
                                    (checked-in-implement-task-definition (System/getProperty "user.dir"))
                                    run-id task-path)
        (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                      (fn [_ctx _child-session-id _prompt]
                        {:execution-result/assistant-message
                         {:role "assistant"
                          :content [{:type :text
                                     :text (case (swap! replies* inc)
                                             1 "PASS_STATUS: MORE_WORK_REMAINS"
                                             2 "PASS_STATUS: IMPLEMENTATION_COMPLETE"
                                             3 "complete handback")}]
                          :stop-reason :stop}})]
          (let [result (workflow-execution/execute-run! ctx session-id run-id)
                run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
            (is (= :completed (:status result)) (pr-str run))
            (is (= :completed (:status run)) (pr-str run))
            (is (= 2 (count (get-in run [:step-runs "capture-implementation-before-pass" :attempts]))))
            (is (= 2 (count (get-in run [:step-runs "implement-pass" :attempts]))))
            (is (= {:status :ok :data "MORE_WORK_REMAINS" :summary "MORE_WORK_REMAINS"}
                   (get-in run [:step-runs "implement-pass" :attempts 0 :judge-output :routing-result])))
            (is (= {:status :ok :data "IMPLEMENTATION_COMPLETE" :summary "IMPLEMENTATION_COMPLETE"}
                   (get-in run [:step-runs "implement-pass" :attempts 1 :judge-output :routing-result])))
            (is (= 1 (count (get-in run [:step-runs "final-summary-complete" :attempts]))))
            (is (zero? (count (get-in run [:step-runs "validate-implementation-blocker" :attempts]))))
            (is (zero? (count (get-in run [:step-runs "final-summary-blocked" :attempts]))))
            (is (= "final-summary-complete" (get-in run [:terminal-outcome :step-id])))
            (is (= "complete handback" (terminal-contract/terminal-yielded-text run)))))))))
