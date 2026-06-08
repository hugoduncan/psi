(ns psi.agent-session.workflow-delegate-review-step-live-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.context :as context]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.turn]
   [psi.agent-session.workflow-test-support :as workflow-test-support]
   [psi.command-registry.registry :as command-registry]
   [psi.deterministic-operation-registry.registry :as op-reg]
   [psi.deterministic-operation-runtime.core :as deterministic-op-runtime]
   [psi.workflow-runtime.core :as workflow-runtime]))

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (model-registry/init! {})))))

(defn- write-temp-models! [config]
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (spit tmp (pr-str config))
    (.getAbsolutePath tmp)))

(deftest init-built-in-workflow-registers-review-step-routing-operations-test
  (testing "built-in workflow bootstrap registers deterministic review-step routing operations"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (try
        (workflow-test-support/init-built-in-workflow! ctx session-id)
        (is (some? (op-reg/get-operation-in (:deterministic-operation-registry ctx)
                                            "workflow/pass-status-routing")))
        (is (some? (op-reg/get-operation-in (:deterministic-operation-registry ctx)
                                            "workflow/constant-routing")))
        (is (some? (op-reg/get-operation-in (:deterministic-operation-registry ctx)
                                            "workflow/munera-open-task-path-routing")))
        (is (some? (op-reg/get-operation-in (:deterministic-operation-registry ctx)
                                            "workflow/proof-sync-disposition-routing")))
        (is (some? (op-reg/get-operation-in (:deterministic-operation-registry ctx)
                                            "workflow/validation-capture-disposition-routing")))
        (is (= {:status :ok :data "DONE" :summary "DONE"}
               (op-reg/invoke-operation-in
                (:deterministic-operation-registry ctx)
                "workflow/munera-open-task-path-routing"
                {:args {:text "munera/open/219-simplify-rpc-session-family"}}
                deterministic-op-runtime/invoke-operation)))
        (finally
          (context/shutdown-context! ctx))))))

(defn- invoke-operation
  [ctx operation-id text]
  (op-reg/invoke-operation-in
   (:deterministic-operation-registry ctx)
   operation-id
   {:args {:text text}}
   deterministic-op-runtime/invoke-operation))

(defn- assert-munera-task-path-route
  [ctx text expected-route]
  (let [result (invoke-operation ctx "workflow/munera-open-task-path-routing" text)]
    (is (= :ok (:status result)) (pr-str result))
    (is (= expected-route (:data result)) (pr-str result))
    (is (= expected-route (:summary result)) (pr-str result))
    result))

(defn- assert-invalid-munera-task-path
  [ctx text]
  (let [result (assert-munera-task-path-route ctx text "REPEAT")]
    (is (= {:reason :invalid-munera-open-task-path
            :text text}
           (:details result))
        (pr-str result))))

(defn- assert-marker-route
  [ctx operation-id marker route]
  (is (= {:status :ok :data route :summary route}
         (invoke-operation ctx operation-id (str "Before prose\nPASS_STATUS: ACTIONABLE_FEEDBACK\n" marker ": " route "\nAfter prose")))))

(defn- assert-marker-error
  [ctx operation-id reason text]
  (let [result (invoke-operation ctx operation-id text)]
    (is (= :error (:status result)) (pr-str result))
    (is (= reason (:reason result)) (pr-str result))
    result))

(defn- assert-duplicate-marker-lines
  [ctx operation-id text expected-lines]
  (let [result (assert-marker-error ctx operation-id :ambiguous-route-marker text)]
    (is (= expected-lines
           (get-in result [:details :route-marker-lines]))
        (pr-str result))))

(deftest munera-open-task-path-routing-operation-test
  ;; Tests deterministic Munera task identity routing rejects anything except
  ;; one root-relative munera/open task path with no surrounding handoff prose.
  (testing "munera open task path routing"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (try
        (workflow-test-support/init-built-in-workflow! ctx session-id)
        (assert-munera-task-path-route ctx "munera/open/220-harden-simplification-workflow-proof-gates" "DONE")
        (doseq [invalid ["Here is the generated task.\nmunera/open/220-harden-simplification-workflow-proof-gates"
                         "munera/open/220-harden-simplification-workflow-proof-gates\nPASS_STATUS: REVIEW_COMPLETE"
                         "munera/open/220-harden-simplification-workflow-proof-gates\nmunera/open/221-other-task"
                         "munera/closed/220-harden-simplification-workflow-proof-gates"
                         "/Users/duncan/projects/hugoduncan/psi/reduce-architectural-complexity/munera/open/220-harden-simplification-workflow-proof-gates"
                         "munera_task_path: munera/open/220-harden-simplification-workflow-proof-gates"
                         "## Munera Task\n\nmunera_task_path: munera/open/220-harden-simplification-workflow-proof-gates\nPASS_STATUS: REVIEW_COMPLETE"
                         "munera/open/not-a-number-task"
                         "munera/open/220-Harden-Simplification-Workflow-Proof-Gates"
                         "munera/open/220_harden_simplification_workflow_proof_gates"
                         "munera/open/220-harden-simplification-workflow-proof-gates/"]]
          (assert-invalid-munera-task-path ctx invalid))
        (finally
          (context/shutdown-context! ctx))))))

(deftest proof-sync-disposition-routing-operation-test
  ;; Tests exact proof-sync route marker parsing through the registered
  ;; deterministic operation, including valid surrounding final-reply prose.
  (testing "proof-sync disposition routing"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (try
        (workflow-test-support/init-built-in-workflow! ctx session-id)
        (doseq [route ["COVERAGE_REVIEW" "VALIDATION_RECAPTURE" "BOOKKEEPING_FIXED_POINT"]]
          (assert-marker-route ctx "workflow/proof-sync-disposition-routing" "PROOF_SYNC_ROUTE" route))
        (assert-marker-error ctx "workflow/proof-sync-disposition-routing"
                             :missing-route-marker
                             "PASS_STATUS: ACTIONABLE_FEEDBACK")
        (assert-duplicate-marker-lines ctx
                                       "workflow/proof-sync-disposition-routing"
                                       "PROOF_SYNC_ROUTE: COVERAGE_REVIEW\nPROOF_SYNC_ROUTE: VALIDATION_RECAPTURE"
                                       ["PROOF_SYNC_ROUTE: COVERAGE_REVIEW"
                                        "PROOF_SYNC_ROUTE: VALIDATION_RECAPTURE"])
        (assert-marker-error ctx "workflow/proof-sync-disposition-routing"
                             :unsupported-route-marker
                             "PROOF_SYNC_ROUTE: TERMINAL_STOP")
        (assert-marker-error ctx "workflow/proof-sync-disposition-routing"
                             :malformed-route-marker
                             " PROOF_SYNC_ROUTE: COVERAGE_REVIEW")
        (assert-marker-error ctx "workflow/proof-sync-disposition-routing"
                             :malformed-route-marker
                             "PROOF_SYNC_ROUTE:COVERAGE_REVIEW")
        (assert-marker-error ctx "workflow/proof-sync-disposition-routing"
                             :malformed-route-marker
                             "PROOF_SYNC_ROUTE: COVERAGE_REVIEW ")
        (assert-marker-error ctx "workflow/proof-sync-disposition-routing"
                             :malformed-route-marker
                             "PROOF_SYNC_ROUTE: COVERAGE_REVIEW because tests changed")
        (finally
          (context/shutdown-context! ctx))))))

(deftest validation-capture-disposition-routing-operation-test
  ;; Tests exact validation-capture route marker parsing through the registered
  ;; deterministic operation, including repair versus terminal disposition.
  (testing "validation-capture disposition routing"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (try
        (workflow-test-support/init-built-in-workflow! ctx session-id)
        (doseq [route ["IMPLEMENTATION_REPAIR" "TERMINAL_STOP"]]
          (assert-marker-route ctx "workflow/validation-capture-disposition-routing" "VALIDATION_CAPTURE_ROUTE" route))
        (assert-marker-error ctx "workflow/validation-capture-disposition-routing"
                             :missing-route-marker
                             "PASS_STATUS: ACTIONABLE_FEEDBACK")
        (assert-duplicate-marker-lines ctx
                                       "workflow/validation-capture-disposition-routing"
                                       "VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR\nVALIDATION_CAPTURE_ROUTE: TERMINAL_STOP"
                                       ["VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR"
                                        "VALIDATION_CAPTURE_ROUTE: TERMINAL_STOP"])
        (assert-marker-error ctx "workflow/validation-capture-disposition-routing"
                             :unsupported-route-marker
                             "VALIDATION_CAPTURE_ROUTE: COVERAGE_REVIEW")
        (assert-marker-error ctx "workflow/validation-capture-disposition-routing"
                             :malformed-route-marker
                             " VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR")
        (assert-marker-error ctx "workflow/validation-capture-disposition-routing"
                             :malformed-route-marker
                             "VALIDATION_CAPTURE_ROUTE:IMPLEMENTATION_REPAIR")
        (assert-marker-error ctx "workflow/validation-capture-disposition-routing"
                             :malformed-route-marker
                             "VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR ")
        (assert-marker-error ctx "workflow/validation-capture-disposition-routing"
                             :malformed-route-marker
                             "VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR because gate failed")
        (finally
          (context/shutdown-context! ctx))))))

(deftest delegate-review-task-implementation-completes-with-nullable-local-model-test
  (testing "built-in /delegate completes review-task-implementation end-to-end with a nullable local test model and stubbed turn execution"
    (let [models-path (write-temp-models!
                       {:version 1
                        :providers {"local"
                                    {:base-url "http://localhost:8080/v1"
                                     :api :openai-completions
                                     :models [{:id "test-model"}]}}})]
      (try
        (model-registry/init! {:user-models-path models-path})
        (let [[ctx session-id]
              (workflow-test-support/create-tui-context+session
               mutations/all-mutations
               {:session-defaults {:model {:provider "local" :id "test-model" :reasoning false}}})]
          (workflow-test-support/init-built-in-workflow! ctx session-id)
          (try
            (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                          (fn [_ctx child-session-id prompt]
                            (let [reply (cond
                                          (str/includes? prompt "end your response with exactly one of:")
                                          "No new actionable feedback found.\n\nPASS_STATUS: REVIEW_COMPLETE"

                                          (str/includes? prompt "Execute the newly added actionable follow-up items")
                                          (throw (ex-info "follow-up should not execute on REVIEW_COMPLETE"
                                                          {:prompt prompt
                                                           :session-id child-session-id}))

                                          :else
                                          "ok")]
                              {:execution-result/assistant-message
                               {:role "assistant"
                                :content [{:type :text :text reply}]
                                :stop-reason :stop}}))]
              (let [cmd (command-registry/get-command-in (:extension-registry ctx) "delegate")
                    _ (is (some? cmd))
                    cmd-result ((:handler cmd) "review-task-implementation 189-deterministic-review-step-routing")
                    run-id (second (re-find #"run ([^\s]+)$" cmd-result))
                    terminal-status (workflow-test-support/poll-until
                                     #(some-> (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
                                              :status
                                              ({:completed :completed :failed :failed :blocked :blocked})))
                    run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
                (is (string? cmd-result))
                (is (str/includes? cmd-result "Delegated to review-task-implementation — run "))
                (is (some? run-id))
                (is (= :completed terminal-status)
                    (let [state @(:state* ctx)
                          delegate-run-id (get-in run [:step-runs "review-task-implementation" :attempts 0 :execution-error :delegate-run-id])
                          delegate-run (when delegate-run-id (workflow-runtime/workflow-run-in state delegate-run-id))]
                      (str "parent=" (pr-str run) "\nchild=" (pr-str delegate-run))))
                (is (= :completed (:status run))
                    (let [state @(:state* ctx)
                          delegate-run-id (get-in run [:step-runs "review-task-implementation" :attempts 0 :execution-error :delegate-run-id])
                          delegate-run (when delegate-run-id (workflow-runtime/workflow-run-in state delegate-run-id))]
                      (str "parent=" (pr-str run) "\nchild=" (pr-str delegate-run))))
                (is (= ["review-task-implementation"
                        "review-task-tests"
                        "review-test-shape"
                        "review-task-docs"
                        "review-code-shape"]
                       (->> (:step-order (:effective-definition run))
                            (filter #(get-in run [:step-runs % :accepted-result]))
                            vec)))))
            (finally
              (context/shutdown-context! ctx))))
        (finally
          (.delete (java.io.File. models-path)))))))
