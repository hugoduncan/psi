(ns psi.agent-session.workflow-delegate-review-step-live-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.context :as context]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.mutations.canonical-workflows :as cwf-mutations]
   [psi.agent-session.test-support :as test-support]
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

(defn- assert-converged-standalone-surfaces-review-complete
  "DI-2: drive a converged standalone review run for `definition-id` through the
   `execute-workflow-run` mutation (stubbing the model reply at
   `prompt-execution-result-in!`) and assert the converged `final-summary` —
   ordered last per DI-2 — is the step whose yielded text surfaces as
   `:psi.workflow/result` via the standalone `(last :step-order)` path. The reply
   is stubbed, so this locks the ordering/plumbing invariant only, NOT the DI-4
   template wording (that is locked by the definition-level review-task-*-test).
   The single varying axis is `definition-id` / `run-id` / converged summary
   `reply-prefix`."
  [definition-id run-id reply-prefix]
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
                        (fn [_ctx _child-session-id prompt]
                          (let [reply (if (str/includes? prompt "Produce the user-facing final result")
                                        (str reply-prefix "\n\nPASS_STATUS: REVIEW_COMPLETE")
                                        "PASS_STATUS: REVIEW_COMPLETE")]
                            {:execution-result/assistant-message
                             {:role "assistant"
                              :content [{:type :text :text reply}]
                              :stop-reason :stop}}))]
            (cwf-mutations/create-workflow-run
             {} {:psi/agent-session-ctx ctx
                 :definition-id definition-id
                 :workflow-input {:input "munera/open/229-author-routed-workflow-exhaustion"}
                 :run-id run-id})
            (let [result (cwf-mutations/execute-workflow-run
                          {} {:psi/agent-session-ctx ctx
                              :session-id session-id
                              :run-id run-id})
                  run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
                  result-text (:psi.workflow/result result)]
              (is (= :completed (:psi.workflow/status result)) (pr-str run))
              (is (= "final-summary" (last (:step-order (:effective-definition run))))
                  "converged final-summary must be ordered last (DI-2)")
              (is (string? result-text))
              (is (= 1 (count (re-seq #"PASS_STATUS: REVIEW_COMPLETE" result-text)))
                  "standalone result text is the converged final-summary's single REVIEW_COMPLETE line")
              (is (str/includes? result-text reply-prefix)
                  "standalone result text is the converged final-summary's reply (prefix unique to that step), not some other step's bare REVIEW_COMPLETE")))
          (finally
            (context/shutdown-context! ctx))))
      (finally
        (.delete (java.io.File. models-path))))))

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
                                            "workflow/exact-marker-routing")))
        (is (some? (op-reg/get-operation-in (:deterministic-operation-registry ctx)
                                            "workflow/scope-question-gate-routing")))
        (is (nil? (op-reg/get-operation-in (:deterministic-operation-registry ctx)
                                           "workflow/proof-sync-disposition-routing")))
        (is (nil? (op-reg/get-operation-in (:deterministic-operation-registry ctx)
                                           "workflow/validation-capture-disposition-routing")))
        (is (= {:status :ok :data "DONE" :summary "DONE"}
               (op-reg/invoke-operation-in
                (:deterministic-operation-registry ctx)
                "workflow/munera-open-task-path-routing"
                {:args {:text "munera/open/219-simplify-rpc-session-family"}}
                deterministic-op-runtime/invoke-operation)))
        (finally
          (context/shutdown-context! ctx))))))

(deftest built-in-routing-operations-invoke-through-registry-test
  ;; Tests the live built-in operation registry seam with compact smoke cases;
  ;; pure parser edge cases live in psi.agent-session.workflow.routing-test.
  (testing "registered routing operations invoke through the deterministic operation registry"
    (let [[ctx session-id] (workflow-test-support/create-tui-context+session mutations/all-mutations)]
      (try
        (workflow-test-support/init-built-in-workflow! ctx session-id)
        (doseq [[operation-id args expected-route]
                [["workflow/pass-status-routing"
                  {:text "PASS_STATUS: REVIEW_COMPLETE"}
                  "DONE"]
                 ["workflow/munera-open-task-path-routing"
                  {:text "munera/open/220-harden-simplification-workflow-proof-gates"}
                  "DONE"]
                 ["workflow/exact-marker-routing"
                  {:text "QUALITY_GATE: APPROVE"
                   :marker-label "QUALITY_GATE"
                   :allowed-routes ["APPROVE" "REPAIR"]}
                  "APPROVE"]]]
          (is (= {:status :ok
                  :data expected-route
                  :summary expected-route}
                 (op-reg/invoke-operation-in
                  (:deterministic-operation-registry ctx)
                  operation-id
                  {:ctx ctx
                   :session-id session-id
                   :args args}
                  deterministic-op-runtime/invoke-operation))))
        (testing "scope-question gate smoke uses self-contained task artifacts"
          (test-support/with-temp-worktree-session
            (fn [worktree scope-ctx scope-session-id]
              (let [scope-gate-args {:artifact "design-steps.md"
                                     :marker "SCOPE_QUESTION:"
                                     :proceed-route "DONE"
                                     :open-route "SCOPE_QUESTION_OPEN"}
                    invoke-scope-gate (fn [task-path]
                                        (op-reg/invoke-operation-in
                                         (:deterministic-operation-registry scope-ctx)
                                         "workflow/scope-question-gate-routing"
                                         {:ctx scope-ctx
                                          :session-id scope-session-id
                                          :args (assoc scope-gate-args :task-path task-path)}
                                         deterministic-op-runtime/invoke-operation))]
                (workflow-test-support/init-built-in-workflow! scope-ctx scope-session-id)
                (is (= {:status :ok
                        :data "DONE"
                        :summary "DONE"}
                       (invoke-scope-gate "munera/open/999-bootstrap-smoke-absent")))
                (test-support/write-task-artifact!
                 worktree
                 "munera/open/999-bootstrap-smoke-open"
                 "design-steps.md"
                 "- [ ] SCOPE_QUESTION: bootstrap halt route?\n")
                (is (= {:status :ok
                        :data "SCOPE_QUESTION_OPEN"
                        :summary "SCOPE_QUESTION_OPEN"
                        :details {:open-questions ["bootstrap halt route?"]}}
                       (invoke-scope-gate "munera/open/999-bootstrap-smoke-open")))))))
        (is (= :invalid-route-marker-args
               (:reason
                (op-reg/invoke-operation-in
                 (:deterministic-operation-registry ctx)
                 "workflow/exact-marker-routing"
                 {:args {:text "QUALITY_GATE: APPROVE"
                         :marker-label "QUALITY_GATE"
                         :allowed-routes []}}
                 deterministic-op-runtime/invoke-operation))))
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

(deftest review-task-design-converged-standalone-surfaces-review-complete-result-test
  (testing "converged review-task-design surfaces the converged final-summary text as standalone result"
    (assert-converged-standalone-surfaces-review-complete
     "review-task-design" "run-design-converged" "Design review completed cleanly.")))

(deftest review-task-plan-converged-standalone-surfaces-review-complete-result-test
  (testing "converged review-task-plan surfaces the converged final-summary text as standalone result"
    (assert-converged-standalone-surfaces-review-complete
     "review-task-plan" "run-plan-converged" "Plan review completed cleanly.")))
