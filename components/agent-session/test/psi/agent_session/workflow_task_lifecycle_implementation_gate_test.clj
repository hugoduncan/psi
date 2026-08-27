(ns psi.agent-session.workflow-task-lifecycle-implementation-gate-test
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

(def lifecycle-definition
  {:definition-id "task-lifecycle-implementation-gate-proof"
   :name "task-lifecycle-implementation-gate-proof"
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
            :on {"IMPLEMENTATION_COMPLETE" {:goto "review-task-implementation"}
                 "IMPLEMENTATION_BLOCKED" {:goto "final-summary-implementation-blocked"}}}
           {:name "review-task-implementation"
            :type :delegate
            :target "review-task-implementation-proof"
            :prompt-string "review implementation"
            :context []}
           {:name "extract-task-knowledge"
            :type :delegate
            :target "extract-task-knowledge-proof"
            :prompt-string "extract knowledge"
            :context []}
           (terminal-session-step "final-summary-after-extraction" "complete lifecycle summary")
           (terminal-session-step "final-summary-implementation-blocked" "blocked lifecycle handback")]})

(defn- child-definition
  [name prompt]
  {:definition-id name
   :name name
   :steps [(session-step "run" prompt)]})

(defn- checked-in-lifecycle-definition
  [worktree]
  (let [path (str worktree "/.psi/workflows/task-lifecycle.edn")
        parsed (workflow-parser/parse-edn-workflow-file (slurp path))
        {:keys [definition error]} (workflow-compiler/compile-workflow-file
                                    (assoc parsed :source-path path))]
    (when error
      (throw (ex-info "Checked-in task-lifecycle definition did not compile"
                      {:error error})))
    definition))

(defn- register-definitions!
  [ctx implementation-status]
  (let [definitions [(child-definition "implement-task-proof" implementation-status)
                     (child-definition "review-task-implementation-proof" "review complete")
                     (child-definition "extract-task-knowledge-proof" "knowledge extracted")
                     lifecycle-definition]]
    (swap! (:state* ctx)
           (fn [state]
             (reduce (fn [next-state definition]
                       (first (workflow-registry/register-definition next-state definition)))
                     state
                     definitions)))))

(defn- create-lifecycle-run!
  [ctx definition run-id workflow-input]
  (swap! (:state* ctx)
         (fn [state]
           (first (workflow-runtime/create-run state
                                               {:definition definition
                                                :run-id run-id
                                                :workflow-input workflow-input})))))

(defn- execute-checked-in-blocked-lifecycle!
  [ctx session-id]
  (let [definition (checked-in-lifecycle-definition (System/getProperty "user.dir"))
        run-id "checked-in-lifecycle-blocked"
        task-path "munera/open/256-implementation-workflow-blocked-termination"
        definitions [(child-definition "review-task-design-core" "PASS_STATUS: REVIEW_COMPLETE")
                     (child-definition "create-task-plan" "plan created")
                     (child-definition "review-task-plan-core" "PASS_STATUS: REVIEW_COMPLETE")
                     (child-definition "implement-task" "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")
                     (child-definition "review-task-implementation-core" "implementation reviewed")
                     (child-definition "extract-task-knowledge" "knowledge extracted")
                     definition]]
    (register-routing-ops! ctx)
    (swap! (:state* ctx)
           (fn [state]
             (reduce (fn [next-state child-definition]
                       (first (workflow-registry/register-definition next-state child-definition)))
                     state
                     definitions)))
    (create-lifecycle-run! ctx definition run-id {:input task-path})
    (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                  (fn [_ctx _child-session-id prompt]
                    {:execution-result/assistant-message
                     {:role "assistant"
                      :content [{:type :text
                                 :text (if (.contains prompt "Produce the user-facing blocked handback")
                                         "lifecycle blocked handback"
                                         prompt)}]
                      :stop-reason :stop}})]
      (let [result (workflow-execution/execute-run! ctx session-id run-id)]
        [result (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]))))

(defn- execute-lifecycle!
  [implementation-status]
  (let [[ctx session-id] (support/create-session-context {:persist? false})
        run-id (str "lifecycle-" implementation-status)]
    (register-routing-ops! ctx)
    (register-definitions! ctx implementation-status)
    (create-lifecycle-run! ctx lifecycle-definition run-id {})
    (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                  (fn [_ctx _child-session-id prompt]
                    {:execution-result/assistant-message
                     {:role "assistant"
                      :content [{:type :text
                                 :text (case prompt
                                         "complete lifecycle summary" "lifecycle completed"
                                         "blocked lifecycle handback" "lifecycle blocked"
                                         prompt)}]
                      :stop-reason :stop}})]
      (let [result (workflow-execution/execute-run! ctx session-id run-id)]
        [result (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]))))

(deftest checked-in-lifecycle-blocked-route-stops-before-review-and-extraction-test
  ;; Tests the compiled authored lifecycle definition, rather than a synthetic
  ;; topology, routes a blocked implementation handback before either delegate.
  (test-support/with-temp-worktree-session
    (fn [worktree ctx session-id]
      (test-support/write-task-artifact! worktree
                                         "munera/open/256-implementation-workflow-blocked-termination"
                                         "design-steps.md"
                                         "- [x] SCOPE_QUESTION: resolved\n")
      (let [[result run] (execute-checked-in-blocked-lifecycle! ctx session-id)]
        (is (= :completed (:status result)) (pr-str run))
        (is (= 1 (count (get-in run [:step-runs "implement-task" :attempts]))))
        (is (= 1 (count (get-in run [:step-runs "check-implementation-status" :attempts]))))
        (is (= 1 (count (get-in run [:step-runs "final-summary-implementation-blocked" :attempts]))))
        (is (zero? (count (get-in run [:step-runs "review-task-implementation" :attempts]))))
        (is (zero? (count (get-in run [:step-runs "extract-task-knowledge" :attempts]))))
        (is (zero? (count (get-in run [:step-runs "final-summary-after-extraction" :attempts]))))
        (is (= "final-summary-implementation-blocked"
               (get-in run [:terminal-outcome :step-id])))))))

(deftest implementation-complete-reaches-review-and-extraction-test
  ;; Tests the lifecycle observes the delegated terminal status and permits its
  ;; downstream implementation stages only for completed implementation.
  (testing "IMPLEMENTATION_COMPLETE advances through review and extraction"
    (let [[result run] (execute-lifecycle! "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE")]
      (is (= :completed (:status result)))
      (is (= 1 (count (get-in run [:step-runs "implement-task" :attempts]))))
      (is (= 1 (count (get-in run [:step-runs "review-task-implementation" :attempts]))))
      (is (= 1 (count (get-in run [:step-runs "extract-task-knowledge" :attempts]))))
      (is (= 1 (count (get-in run [:step-runs "final-summary-after-extraction" :attempts]))))
      (is (zero? (count (get-in run [:step-runs "final-summary-implementation-blocked" :attempts])))))))

(deftest implementation-blocked-stops-before-review-and-extraction-test
  ;; Tests the blocked handback is a clean terminal lifecycle outcome rather
  ;; than an entry into review or knowledge extraction.
  (testing "IMPLEMENTATION_BLOCKED reaches only the lifecycle handback"
    (let [[result run] (execute-lifecycle! "IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED")]
      (is (= :completed (:status result)))
      (is (= 1 (count (get-in run [:step-runs "implement-task" :attempts]))))
      (is (= 1 (count (get-in run [:step-runs "final-summary-implementation-blocked" :attempts]))))
      (is (zero? (count (get-in run [:step-runs "review-task-implementation" :attempts]))))
      (is (zero? (count (get-in run [:step-runs "extract-task-knowledge" :attempts]))))
      (is (zero? (count (get-in run [:step-runs "final-summary-after-extraction" :attempts])))))))

(deftest invalid-exported-implementation-statuses-fail-before-lifecycle-branches-test
  ;; Tests malformed, duplicate, missing, and unsupported delegate exports do
  ;; not become a completion or a clean blocked handback.
  (testing "invalid IMPLEMENTATION_STATUS exports fail at the lifecycle gate"
    (doseq [[label status reason]
            [["missing" "implementation summary" :missing-route-marker]
             ["malformed" "IMPLEMENTATION_STATUS:IMPLEMENTATION_COMPLETE" :malformed-route-marker]
             ["duplicate" "IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE\nIMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED" :ambiguous-route-marker]
             ["unsupported" "IMPLEMENTATION_STATUS: UNKNOWN" :unsupported-route-marker]]]
      (let [[result run] (execute-lifecycle! status)]
        (is (= :failed (:status result)) label)
        (is (= reason (get-in run [:terminal-outcome :reason])) label)
        (is (zero? (count (get-in run [:step-runs "review-task-implementation" :attempts]))) label)
        (is (zero? (count (get-in run [:step-runs "extract-task-knowledge" :attempts]))) label)
        (is (zero? (count (get-in run [:step-runs "final-summary-implementation-blocked" :attempts]))) label)))))
