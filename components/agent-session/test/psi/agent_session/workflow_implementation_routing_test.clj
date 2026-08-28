(ns psi.agent-session.workflow-implementation-routing-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.agent-session.workflow-implementation-test-support :as implementation-support]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.terminal-contract :as terminal-contract]))

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
(defn- checked-in-implement-task-definition
  [worktree]
  ;; The definition is checked in and compiled as production does; this
  ;; state-based fixture has no project-profile registry, so remove only the
  ;; environment-dependent session-profile selection before execution.
  (update (implementation-support/checked-in-workflow-definition worktree "implement-task")
          :steps
          (fn [steps]
            (mapv #(if (= "implement-pass" (:name %))
                     (dissoc % :session-profile)
                     %)
                  steps))))

(defn- artifact-content
  [block]
  (str "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
       "- blocker: " (:blocker block) "\n"
       "- required-human-action: " (:required-human-action block) "\n"
       "<!-- IMPLEMENTATION_BLOCKER: END -->\n"))

(defn- implementation-pass-prompt?
  [prompt]
  (.contains prompt "Execute the next concrete implementation slice for the task."))

(defn- complete-summary-prompt?
  [prompt]
  (.contains prompt "Produce the user-facing final result for the specific Munera task"))

(defn- blocked-summary-prompt?
  [prompt]
  (.contains prompt "Produce the user-facing blocked handback for the specific Munera task"))

(defn- checked-in-implement-task-prompt-type
  [prompt]
  (cond
    (implementation-pass-prompt? prompt) :implementation-pass
    (complete-summary-prompt? prompt) :complete-summary
    (blocked-summary-prompt? prompt) :blocked-summary
    :else (throw (ex-info "Unexpected checked-in implement-task prompt"
                          {:unexpected-prompt prompt}))))

(deftest checked-in-implement-task-prompt-dispatch-fails-fast-test
  ;; Tests authored-topology drift fails at the response boundary with the
  ;; unmatched prompt available directly to the test failure report.
  (let [prompt "Unexpected authored workflow step"
        error (try
                (checked-in-implement-task-prompt-type prompt)
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]
    (is (= "Unexpected checked-in implement-task prompt" (ex-message error)))
    (is (= {:unexpected-prompt prompt} (ex-data error)))))

(deftest implement-task-implementation-complete-routes-to-final-summary-test
  (testing "IMPLEMENTATION_COMPLETE terminates the implementation loop deterministically"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          prompts* (atom [])]
      (implementation-support/register-routing-ops! ctx)
      (implementation-support/create-run! ctx implement-task-definition "run-implement-complete"
                                          {:input "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"})
      (test-support/with-workflow-prompt-execution-result [ctx]
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
            :stop-reason :stop}})
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
      (implementation-support/register-routing-ops! ctx)
      (implementation-support/create-run! ctx implement-task-definition "run-implement-blocked"
                                          {:input "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"})
      (test-support/with-workflow-prompt-execution-result [ctx]
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
            :stop-reason :stop}})
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
            prompts* (atom [])]
        (test-support/write-task-artifact!
         worktree task-path "implementation.md"
         (artifact-content {:blocker "earlier decision"
                            :required-human-action "ignore this record"}))
        (implementation-support/register-routing-ops! ctx)
        (implementation-support/create-run! ctx
                                            (checked-in-implement-task-definition (System/getProperty "user.dir"))
                                            run-id {:input task-path})
        (test-support/with-workflow-prompt-execution-result [ctx]
          (fn [_ctx _child-session-id prompt]
            (swap! prompts* conj prompt)
            (let [text (case (checked-in-implement-task-prompt-type prompt)
                         :implementation-pass
                         (do
                           (spit (str worktree "/" task-path "/implementation.md")
                                 (str (artifact-content {:blocker "earlier decision"
                                                         :required-human-action "ignore this record"})
                                      (artifact-content {:blocker "awaiting product decision"
                                                         :required-human-action "choose the retention policy"})))
                           "PASS_STATUS: IMPLEMENTATION_BLOCKED")

                         :blocked-summary
                         (do
                           (spit (str worktree "/" task-path "/implementation.md")
                                 (artifact-content
                                  {:blocker "intervening edit"
                                   :required-human-action "ignore validated record"}))
                           (str "blocked handback\n"
                                "IMPLEMENTATION_BLOCKER: awaiting product decision\n"
                                "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: choose the retention policy\n"
                                "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")))]
              {:execution-result/assistant-message
               {:role "assistant"
                :content [{:type :text :text text}]
                :stop-reason :stop}}))
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
            (is (= (str "blocked handback\n"
                        "IMPLEMENTATION_BLOCKER: awaiting product decision\n"
                        "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: choose the retention policy\n"
                        "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
                   (terminal-contract/terminal-yielded-text run)))
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
        (implementation-support/register-routing-ops! ctx)
        (implementation-support/create-run! ctx
                                            (checked-in-implement-task-definition (System/getProperty "user.dir"))
                                            run-id {:input task-path})
        (test-support/with-workflow-prompt-execution-result [ctx]
          (fn [_ctx _child-session-id _prompt]
            {:execution-result/assistant-message
             {:role "assistant"
              :content [{:type :text :text "PASS_STATUS: IMPLEMENTATION_BLOCKED"}]
              :stop-reason :stop}})
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
      (doseq [[label appended-content] [["missing" nil]
                                        ["malformed"
                                         (str "<!-- IMPLEMENTATION_BLOCKER: START -->\n"
                                              "- blocker: missing action\n"
                                              "<!-- IMPLEMENTATION_BLOCKER: END -->\n")]]]
        (let [task-path "munera/open/230-x"
              run-id (str "checked-in-implement-blocked-" label)
              implementation-path (str worktree "/" task-path "/implementation.md")]
          (test-support/write-task-artifact! worktree task-path "implementation.md" "implementation notes only\n")
          (implementation-support/register-routing-ops! ctx)
          (implementation-support/create-run! ctx
                                              (checked-in-implement-task-definition (System/getProperty "user.dir"))
                                              run-id {:input task-path})
          (test-support/with-workflow-prompt-execution-result [ctx]
            (fn [_ctx _child-session-id prompt]
              (when (and appended-content (implementation-pass-prompt? prompt))
                (spit implementation-path appended-content :append true))
              {:execution-result/assistant-message
               {:role "assistant"
                :content [{:type :text :text "PASS_STATUS: IMPLEMENTATION_BLOCKED"}]
                :stop-reason :stop}})
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
        (implementation-support/register-routing-ops! ctx)
        (implementation-support/create-run! ctx implement-task-definition run-id
                                            {:input "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"})
        (test-support/with-workflow-prompt-execution-result [ctx]
          (fn [_ctx _child-session-id _prompt]
            {:execution-result/assistant-message
             {:role "assistant"
              :content [{:type :text :text reply}]
              :stop-reason :stop}})
          (let [result (workflow-execution/execute-run! ctx session-id run-id)
                run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
            (is (= :failed (:status result)) label)
            (is (= reason (get-in run [:terminal-outcome :reason])) label)
            (is (zero? (count (get-in run [:step-runs "final-summary-complete" :attempts]))) label)
            (is (zero? (count (get-in run [:step-runs "final-summary-blocked" :attempts]))) label))))))
  (testing "the twenty-pass MORE_WORK_REMAINS bound still fails before pass twenty-one"
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          run-id "run-implement-repeat-limit"]
      (implementation-support/register-routing-ops! ctx)
      (implementation-support/create-run! ctx implement-task-definition run-id
                                          {:input "munera/open/190-conditional-review-follow-ups-for-design-and-plan-workflows"})
      (test-support/with-workflow-prompt-execution-result [ctx]
        (fn [_ctx _child-session-id _prompt]
          {:execution-result/assistant-message
           {:role "assistant"
            :content [{:type :text :text "PASS_STATUS: MORE_WORK_REMAINS"}]
            :stop-reason :stop}})
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
            implementation-pass-count* (atom 0)]
        (test-support/write-task-artifact! worktree task-path "implementation.md" "initial notes\n")
        (implementation-support/register-routing-ops! ctx)
        (implementation-support/create-run! ctx
                                            (checked-in-implement-task-definition (System/getProperty "user.dir"))
                                            run-id {:input task-path})
        (test-support/with-workflow-prompt-execution-result [ctx]
          (fn [_ctx _child-session-id prompt]
            (let [text (case (checked-in-implement-task-prompt-type prompt)
                         :implementation-pass
                         (case (swap! implementation-pass-count* inc)
                           1 "PASS_STATUS: MORE_WORK_REMAINS"
                           2 "PASS_STATUS: IMPLEMENTATION_COMPLETE")

                         :complete-summary
                         "complete handback\nIMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")]
              {:execution-result/assistant-message
               {:role "assistant"
                :content [{:type :text :text text}]
                :stop-reason :stop}}))
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
            (is (= "complete handback\nIMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE"
                   (terminal-contract/terminal-yielded-text run)))))))))

(deftest checked-in-implement-task-rejects-invalid-terminal-handbacks-test
  ;; Tests checked-in terminal judges reject prompt-noncompliant yielded text.
  (doseq [[label pass-reply summary-reply expected-reason]
          [["complete missing" "PASS_STATUS: IMPLEMENTATION_COMPLETE"
            "complete handback" :missing-route-marker]
           ["complete malformed" "PASS_STATUS: IMPLEMENTATION_COMPLETE"
            "IMPLEMENTATION_STATUS:IMPLEMENTATION_COMPLETE" :malformed-route-marker]
           ["complete duplicate" "PASS_STATUS: IMPLEMENTATION_COMPLETE"
            (str "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE\n"
                 "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
            :ambiguous-route-marker]
           ["complete branch mismatch" "PASS_STATUS: IMPLEMENTATION_COMPLETE"
            "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED" :unsupported-route-marker]
           ["complete with blocker" "PASS_STATUS: IMPLEMENTATION_COMPLETE"
            (str "IMPLEMENTATION_BLOCKER: stale blocker\n"
                 "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
            :unexpected-route-field]
           ["complete with required action" "PASS_STATUS: IMPLEMENTATION_COMPLETE"
            (str "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: stale action\n"
                 "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
            :unexpected-route-field]]]
    (test-support/with-temp-worktree-session
      (fn [worktree ctx session-id]
        (let [task-path "munera/open/230-x"
              run-id (str "checked-in-terminal-" label)]
          (test-support/write-task-artifact! worktree task-path "implementation.md" "notes\n")
          (implementation-support/register-routing-ops! ctx)
          (implementation-support/create-run! ctx
                                              (checked-in-implement-task-definition
                                               (System/getProperty "user.dir"))
                                              run-id {:input task-path})
          (test-support/with-workflow-prompt-execution-result [ctx]
            (fn [_ctx _child-session-id prompt]
              {:execution-result/assistant-message
               {:role "assistant"
                :content [{:type :text
                           :text (case (checked-in-implement-task-prompt-type prompt)
                                   :implementation-pass pass-reply
                                   :complete-summary summary-reply)}]
                :stop-reason :stop}})
            (let [result (workflow-execution/execute-run! ctx session-id run-id)
                  run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
              (is (= :failed (:status result)) label)
              (is (= expected-reason (get-in run [:terminal-outcome :reason])) label))))))))
