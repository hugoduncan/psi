(ns psi.agent-session.workflow-implementation-callers-test
  (:require
   [clojure.test :refer [deftest is]]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-execution-test-support :as support]
   [psi.agent-session.workflow.core :as workflow-core]
   [psi.deterministic-operation-registry.registry]
   [psi.workflow-loader.compiler :as workflow-compiler]
   [psi.workflow-loader.parser :as workflow-parser]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-runtime.core :as workflow-runtime]))

(defn- register-routing-ops!
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

(defn- session-step
  [name prompt]
  {:name name
   :type :session
   :contributions [{:type :template :text prompt}]})

(defn- terminal-session-step
  [name prompt]
  (assoc (session-step name prompt)
         :judge {:type :invoke
                 :operation "workflow/constant-routing"
                 :args {:route "DONE"}}
         :on {"DONE" {:goto :done}}))

(defn- child-definition
  [name prompt]
  {:definition-id name
   :name name
   :steps [(session-step "run" prompt)]})

(defn- checked-in-workflow-definition
  [worktree workflow-name]
  (let [path (str worktree "/.psi/workflows/" workflow-name ".edn")
        parsed (workflow-parser/parse-edn-workflow-file (slurp path))
        {:keys [definition error]} (workflow-compiler/compile-workflow-file
                                    (assoc parsed :source-path path))]
    (when error
      (throw (ex-info (str "Checked-in " workflow-name " definition did not compile")
                      {:error error})))
    definition))

(defn- checked-in-implement-task-in-worktree-definition
  [worktree]
  (checked-in-workflow-definition worktree "implement-task-in-worktree"))

(defn- checked-in-blocked-caller-definition
  [worktree workflow-name delegate-step-name blocked-step-name]
  (let [definition (checked-in-workflow-definition worktree workflow-name)
        by-name (into {} (map (juxt :name identity)) (:steps definition))
        gate (-> (get by-name "check-implementation-status")
                 (assoc-in [:on "IMPLEMENTATION_COMPLETE" :goto] blocked-step-name))
        blocked (-> (get by-name blocked-step-name)
                    (assoc :contributions
                           [{:type :source :from {:step delegate-step-name :yield :text}}
                            (-> (last (:contributions (get by-name blocked-step-name)))
                                (assoc :vars {}))]))]
    {:definition-id (str workflow-name "-blocked-snapshot-proof")
     :name (str workflow-name "-blocked-snapshot-proof")
     :steps [{:name delegate-step-name
              :type :delegate
              :target "implement-task"
              :prompt-string "implement"
              :context []}
             gate
             blocked]}))

(defn- checked-in-gh-issue-implement-gate-definition
  [worktree]
  (let [definition (checked-in-workflow-definition worktree "gh-issue-implement")
        steps (:steps definition)
        gate-start (first (keep-indexed (fn [index step]
                                          (when (= "implement" (:name step)) index))
                                        steps))]
    (-> definition
        (assoc :definition-id "gh-issue-implement-gate-proof"
               :name "gh-issue-implement-gate-proof")
        (assoc :steps (mapv (fn [step]
                              (case (:name step)
                                "implement" (assoc step :prompt-string "implement" :context [])
                                "review" (assoc step :context [])
                                "push" (assoc step :context [])
                                "implementation-blocked" (assoc step
                                                                :contributions [{:type :template
                                                                                 :text "outer blocked handback"}])
                                "edit-labels" (assoc step :args {:number 1
                                                                 :remove ["implement"]
                                                                 :add ["review"]
                                                                 :target "pr"})
                                step))
                            (subvec steps gate-start))))))

(defn- create-lifecycle-run!
  [ctx definition run-id workflow-input]
  (swap! (:state* ctx)
         (fn [state]
           (first (workflow-runtime/create-run state
                                               {:definition definition
                                                :run-id run-id
                                                :workflow-input workflow-input})))))

(defn- implementation-caller-definition
  [name downstream-step]
  {:definition-id name
   :name name
   :steps [{:name "implement-task"
            :type :delegate
            :target "implement-task-proof"
            :prompt-string "implement task"
            :context []}
           {:name "check-implementation-status"
            :type :invoke
            :operation "workflow/constant-routing"
            :args {:route "DONE"}
            :judge {:type :invoke
                    :operation "workflow/exact-marker-routing"
                    :args {:text {:from {:step "implement-task" :yield :text}}
                           :marker-label "IMPLEMENTATION_STATUS"
                           :allowed-routes ["IMPLEMENTATION_COMPLETE"
                                            "IMPLEMENTATION_BLOCKED"]}}
            :on {"IMPLEMENTATION_COMPLETE" {:goto downstream-step}
                 "IMPLEMENTATION_BLOCKED" {:goto "implementation-blocked"}}}
           {:name downstream-step
            :type :delegate
            :target "downstream-proof"
            :prompt-string "downstream"
            :context []}
           (terminal-session-step "implementation-blocked" "blocked handback")]})

(defn- execute-implementation-caller!
  [caller-name downstream-step]
  (let [[ctx session-id] (support/create-session-context {:persist? false})
        definition (implementation-caller-definition caller-name downstream-step)
        definitions [(child-definition "implement-task-proof"
                                       "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
                     (child-definition "downstream-proof" "downstream ran")
                     definition]]
    (register-routing-ops! ctx)
    (swap! (:state* ctx)
           (fn [state]
             (reduce (fn [next-state child-definition]
                       (first (workflow-registry/register-definition next-state child-definition)))
                     state
                     definitions)))
    (create-lifecycle-run! ctx definition (str caller-name "-blocked") {})
    (test-support/with-workflow-prompt-execution-result [ctx]
      (fn [_ctx _child-session-id prompt]
        {:execution-result/assistant-message
         {:role "assistant"
          :content [{:type :text :text prompt}]
          :stop-reason :stop}})
      (let [result (workflow-execution/execute-run! ctx session-id (str caller-name "-blocked"))]
        [result (workflow-runtime/workflow-run-in @(:state* ctx) (str caller-name "-blocked"))]))))

(deftest implementation-blocked-stops-other-caller-downstream-work-test
  ;; Tests caller status gates stop validation and review paths when the
  ;; delegated implementation handback is explicitly blocked.
  (doseq [[caller-name downstream-step]
          [["reduce-architectural-complexity-proof" "validation-capture"]
           ["reduce-incidental-complexity-proof" "review-task-implementation"]]]
    (let [[result run] (execute-implementation-caller! caller-name downstream-step)]
      (is (= :completed (:status result)) caller-name)
      (is (= "implementation-blocked" (get-in run [:terminal-outcome :step-id])) caller-name)
      (is (= 1 (count (get-in run [:step-runs "implementation-blocked" :attempts]))) caller-name)
      (is (zero? (count (get-in run [:step-runs downstream-step :attempts]))) caller-name))))

(defn- checked-in-outer-orchestration-prompt-type
  [prompt delegated-yield]
  (cond
    (= delegated-yield prompt) :delegated-implementation
    (= "outer blocked handback" prompt) :gh-issue-blocked-summary
    (.contains prompt "Extract the worktree path") :wrapper-resolve
    (.contains prompt "Produce the user-facing blocked handback for the Munera task lifecycle") :lifecycle-blocked-summary
    (.contains prompt "Produce the user-facing final result for the Munera task") :wrapper-complete-summary
    (.contains prompt "Produce the user-facing blocked handback for the Munera task") :wrapper-blocked-summary
    (.contains prompt "Produce the terminal blocked handback for `reduce-architectural-complexity`") :architectural-blocked-summary
    (.contains prompt "Produce the terminal blocked handback for `reduce-incidental-complexity`") :incidental-blocked-summary
    (.contains prompt "Produce the user-facing blocked handback for the PR implementation workflow") :gh-issue-blocked-summary
    :else (throw (ex-info "Unexpected checked-in outer-orchestration prompt"
                          {:unexpected-prompt prompt}))))

(deftest checked-in-outer-orchestration-prompt-dispatch-fails-fast-test
  ;; Tests wrapper/caller topology drift exposes the unmatched authored prompt
  ;; directly instead of returning a plausible terminal handback.
  (let [prompt "Unexpected authored outer-orchestration step"
        error (try
                (checked-in-outer-orchestration-prompt-type prompt "delegated yield")
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= "Unexpected checked-in outer-orchestration prompt" (ex-message error)))
    (is (= {:unexpected-prompt prompt} (ex-data error)))))

(deftest checked-in-implement-task-in-worktree-blocked-route-test
  ;; Tests the loadable checked-in wrapper routes its delegated blocked export
  ;; only to the wrapper handback, never to its normal summary.
  (let [[ctx session-id] (support/create-session-context {:persist? false})
        source-worktree (System/getProperty "user.dir")
        wrapper (checked-in-implement-task-in-worktree-definition source-worktree)
        implement-task (child-definition "implement-task"
                                         (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                                              "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                                              "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"))
        run-id "checked-in-implement-task-in-worktree-blocked"]
    (register-routing-ops! ctx)
    (swap! (:state* ctx)
           (fn [state]
             (reduce (fn [next-state definition]
                       (first (workflow-registry/register-definition next-state definition)))
                     state
                     [implement-task wrapper])))
    (create-lifecycle-run! ctx wrapper run-id {:input "worktree_path: /tmp/worktree\nmunera_task_path: munera/open/256-task"})
    (test-support/with-workflow-prompt-execution-result [ctx]
      (fn [_ctx _child-session-id prompt]
        {:execution-result/assistant-message
         {:role "assistant"
          :content [{:type :text
                     :text (case (checked-in-outer-orchestration-prompt-type
                                  prompt
                                  (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                                       "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                                       "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"))
                             :wrapper-resolve
                             "munera/open/256-task"

                             :delegated-implementation
                             prompt

                             :wrapper-blocked-summary
                             (str "wrapper blocked handback\n"
                                  "IMPLEMENTATION_BLOCKER: validated blocker\n"
                                  "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                                  "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"))}]
          :stop-reason :stop}})
      (let [result (workflow-execution/execute-run! ctx session-id run-id)
            run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
        (is (= :completed (:status result)) (pr-str run))
        (is (= "summary-implementation-blocked"
               (get-in run [:terminal-outcome :step-id])))
        (is (= 1 (count (get-in run [:step-runs "summary-implementation-blocked" :attempts]))))
        (is (zero? (count (get-in run [:step-runs "summary" :attempts]))))))))

(defn- execute-checked-in-worktree-wrapper-complete!
  [summary-reply run-suffix]
  (let [[ctx session-id] (support/create-session-context {:persist? false})
        source-worktree (System/getProperty "user.dir")
        wrapper (checked-in-implement-task-in-worktree-definition source-worktree)
        implement-task (child-definition "implement-task"
                                         "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
        run-id (str "checked-in-implement-task-in-worktree-complete-" run-suffix)]
    (register-routing-ops! ctx)
    (swap! (:state* ctx)
           (fn [state]
             (reduce (fn [next-state definition]
                       (first (workflow-registry/register-definition next-state definition)))
                     state
                     [implement-task wrapper])))
    (create-lifecycle-run! ctx wrapper run-id
                           {:input (str "worktree_path: /tmp/worktree\n"
                                        "munera_task_path: munera/open/256-task")})
    (test-support/with-workflow-prompt-execution-result [ctx]
      (fn [_ctx _child-session-id prompt]
        {:execution-result/assistant-message
         {:role "assistant"
          :content [{:type :text
                     :text (case (checked-in-outer-orchestration-prompt-type
                                  prompt
                                  "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
                             :wrapper-resolve "munera/open/256-task"
                             :delegated-implementation prompt
                             :wrapper-complete-summary summary-reply)}]
          :stop-reason :stop}})
      (let [result (workflow-execution/execute-run! ctx session-id run-id)]
        [result (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]))))

(deftest checked-in-implement-task-in-worktree-complete-handback-test
  ;; Tests the checked-in wrapper accepts and projects exactly one normal
  ;; terminal status after delegated implementation completes.
  (let [handback (str "wrapper complete handback\n"
                      "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
        [result run] (execute-checked-in-worktree-wrapper-complete! handback "accepted")]
    (is (= :completed (:status result)) (pr-str run))
    (is (= "summary" (get-in run [:terminal-outcome :step-id])))
    (is (= handback
           (get-in run [:step-runs "summary" :accepted-result :outputs :final-llm-reply])))
    (is (zero? (count (get-in run [:step-runs "summary-implementation-blocked" :attempts]))))))

(deftest checked-in-implement-task-in-worktree-invalid-complete-handbacks-fail-test
  ;; Tests missing, malformed, duplicate, and branch-mismatched normal exports
  ;; fail at the checked-in wrapper summary judge.
  (doseq [[label handback reason]
          [["missing status" "wrapper complete handback" :missing-route-marker]
           ["malformed status"
            "IMPLEMENTATION_STATUS:IMPLEMENTATION_COMPLETE"
            :malformed-route-marker]
           ["duplicate status"
            (str "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE\n"
                 "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
            :ambiguous-route-marker]
           ["branch mismatch"
            "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"
            :unsupported-route-marker]
           ["blocked-only blocker"
            (str "IMPLEMENTATION_BLOCKER: stale blocker\n"
                 "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
            :unexpected-route-field]
           ["blocked-only action"
            (str "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: stale action\n"
                 "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")
            :unexpected-route-field]]]
    (let [[result run] (execute-checked-in-worktree-wrapper-complete! handback label)]
      (is (= :failed (:status result)) label)
      (is (= reason (get-in run [:terminal-outcome :reason])) label)
      (is (= 1 (count (get-in run [:step-runs "summary" :attempts]))) label)
      (is (zero? (count (get-in run [:step-runs "summary-implementation-blocked" :attempts])))
          label))))

(deftest checked-in-caller-handbacks-propagate-validated-blocker-test
  ;; Tests every checked-in direct caller handback preserves the delegated
  ;; validated snapshot after the task artifact changes.
  (doseq [[workflow-name delegate-step blocked-step]
          [["task-lifecycle" "implement-task" "final-summary-implementation-blocked"]
           ["implement-task-in-worktree" "implement" "summary-implementation-blocked"]
           ["reduce-architectural-complexity" "implement-task" "terminal-stop-implementation-blocked"]
           ["reduce-incidental-complexity" "implement-task" "terminal-stop-implementation-blocked"]]]
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          source-worktree (System/getProperty "user.dir")
          definition (checked-in-blocked-caller-definition source-worktree workflow-name
                                                           delegate-step blocked-step)
          blocked-yield (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                             "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                             "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
          implementation (child-definition "implement-task" blocked-yield)
          run-id (str workflow-name "-blocked-snapshot")
          prompts* (atom [])]
      (register-routing-ops! ctx)
      (swap! (:state* ctx)
             (fn [state]
               (reduce (fn [next-state child-definition]
                         (first (workflow-registry/register-definition next-state child-definition)))
                       state
                       [implementation definition])))
      (create-lifecycle-run! ctx definition run-id {:input "munera/open/256-task"})
      (test-support/with-workflow-prompt-execution-result [ctx]
        (fn [_ctx _child-session-id prompt]
          (swap! prompts* conj prompt)
          {:execution-result/assistant-message
           {:role "assistant"
            :content [{:type :text
                       :text (case (checked-in-outer-orchestration-prompt-type
                                    prompt blocked-yield)
                               :delegated-implementation
                               prompt

                               (:lifecycle-blocked-summary
                                :wrapper-blocked-summary
                                :architectural-blocked-summary
                                :incidental-blocked-summary)
                               (str "blocked caller handback\n"
                                    "IMPLEMENTATION_BLOCKER: validated blocker\n"
                                    "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                                    "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"))}]
            :stop-reason :stop}})
        (let [result (workflow-execution/execute-run! ctx session-id run-id)
              run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
              prompts @prompts*]
          (is (= :completed (:status result)) workflow-name)
          (is (= blocked-step (get-in run [:terminal-outcome :step-id])) workflow-name)
          (is (= (str "blocked caller handback\n"
                      "IMPLEMENTATION_BLOCKER: validated blocker\n"
                      "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                      "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
                 (get-in run [:step-runs blocked-step :accepted-result :outputs :final-llm-reply]))
              workflow-name)
          (is (some #(.contains % "IMPLEMENTATION_BLOCKER: validated blocker") prompts)
              workflow-name)
          (is (some #(.contains % "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action")
                    prompts)
              workflow-name)
          (is (some #(or (.contains % "Do not re-read or select a blocker record")
                         (.contains % "do not re-read or select a blocker record"))
                    prompts)
              workflow-name))))))

(deftest checked-in-gh-issue-implement-blocked-route-test
  ;; Tests the checked-in outer orchestration consumes the wrapper's blocked
  ;; export and terminates before review, push, or label editing.
  (let [[ctx session-id] (support/create-session-context {:persist? false})
        source-worktree (System/getProperty "user.dir")
        definition (checked-in-gh-issue-implement-gate-definition source-worktree)
        blocked-yield (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                           "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                           "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
        wrapper (child-definition "implement-task-in-worktree" blocked-yield)
        review (child-definition "review-implementation-in-worktree" "review ran")
        builder (child-definition "builder" "push ran")
        run-id "checked-in-gh-issue-implement-blocked"]
    (register-routing-ops! ctx)
    (swap! (:state* ctx)
           (fn [state]
             (reduce (fn [next-state child-definition]
                       (first (workflow-registry/register-definition next-state child-definition)))
                     state
                     [wrapper review builder definition])))
    (create-lifecycle-run! ctx definition run-id {})
    (test-support/with-workflow-prompt-execution-result [ctx]
      (fn [_ctx _child-session-id prompt]
        {:execution-result/assistant-message
         {:role "assistant"
          :content [{:type :text
                     :text (case (checked-in-outer-orchestration-prompt-type
                                  prompt blocked-yield)
                             :delegated-implementation
                             prompt

                             :gh-issue-blocked-summary
                             (str "outer blocked handback\n"
                                  "IMPLEMENTATION_BLOCKER: validated blocker\n"
                                  "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                                  "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED"))}]
          :stop-reason :stop}})
      (let [result (workflow-execution/execute-run! ctx session-id run-id)
            run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
        (is (= :completed (:status result)) (pr-str run))
        (is (= "implementation-blocked" (get-in run [:terminal-outcome :step-id])))
        (is (= 1 (count (get-in run [:step-runs "implementation-blocked" :attempts]))))
        (is (= (str "outer blocked handback\n"
                    "IMPLEMENTATION_BLOCKER: validated blocker\n"
                    "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                    "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
               (get-in run [:step-runs "implementation-blocked"
                            :accepted-result :outputs :final-llm-reply])))
        (is (zero? (count (get-in run [:step-runs "review" :attempts]))))
        (is (zero? (count (get-in run [:step-runs "push" :attempts]))))
        (is (zero? (count (get-in run [:step-runs "edit-labels" :attempts]))))))))

(deftest checked-in-gh-issue-implement-invalid-blocked-handbacks-fail-test
  ;; Tests the checked-in outer blocked step rejects invalid exports while
  ;; review, push, and label editing remain unreachable.
  (doseq [[label handback reason]
          [["missing status"
            (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                 "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action")
            :missing-route-marker]
           ["malformed status"
            (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                 "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                 "IMPLEMENTATION_STATUS:IMPLEMENTATION_BLOCKED")
            :malformed-route-marker]
           ["duplicate blocker"
            (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                 "IMPLEMENTATION_BLOCKER: validated blocker\n"
                 "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                 "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
            :ambiguous-route-field]
           ["snapshot mismatch"
            (str "IMPLEMENTATION_BLOCKER: changed blocker\n"
                 "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                 "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
            :mismatched-route-field]]]
    (let [[ctx session-id] (support/create-session-context {:persist? false})
          source-worktree (System/getProperty "user.dir")
          definition (checked-in-gh-issue-implement-gate-definition source-worktree)
          blocked-yield (str "IMPLEMENTATION_BLOCKER: validated blocker\n"
                             "IMPLEMENTATION_REQUIRED_HUMAN_ACTION: validated action\n"
                             "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
          wrapper (child-definition "implement-task-in-worktree" blocked-yield)
          review (child-definition "review-implementation-in-worktree" "review ran")
          builder (child-definition "builder" "push ran")
          run-id (str "checked-in-gh-issue-implement-invalid-" label)]
      (register-routing-ops! ctx)
      (swap! (:state* ctx)
             (fn [state]
               (reduce (fn [next-state child-definition]
                         (first (workflow-registry/register-definition next-state child-definition)))
                       state
                       [wrapper review builder definition])))
      (create-lifecycle-run! ctx definition run-id {})
      (test-support/with-workflow-prompt-execution-result [ctx]
        (fn [_ctx _child-session-id prompt]
          {:execution-result/assistant-message
           {:role "assistant"
            :content [{:type :text
                       :text (case (checked-in-outer-orchestration-prompt-type
                                    prompt blocked-yield)
                               :delegated-implementation prompt
                               :gh-issue-blocked-summary handback)}]
            :stop-reason :stop}})
        (let [result (workflow-execution/execute-run! ctx session-id run-id)
              run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
          (is (= :failed (:status result)) label)
          (is (= reason (get-in run [:terminal-outcome :reason])) label)
          (is (zero? (count (get-in run [:step-runs "review" :attempts]))) label)
          (is (zero? (count (get-in run [:step-runs "push" :attempts]))) label)
          (is (zero? (count (get-in run [:step-runs "edit-labels" :attempts]))) label))))))

