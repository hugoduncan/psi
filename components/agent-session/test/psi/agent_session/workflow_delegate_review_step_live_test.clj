(ns psi.agent-session.workflow-delegate-review-step-live-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [psi.ai.model-registry :as model-registry]
   [psi.agent-session.context :as context]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.turn]
   [psi.agent-session.workflow-test-support :as workflow-test-support]
   [psi.command-registry.registry :as command-registry]
   [psi.deterministic-operation-registry.registry :as op-reg]
   [psi.deterministic-operation-runtime.core :as deterministic-op-runtime]
   [psi.session-state.state :as ss]
   [psi.shared-config.session-profiles :as session-profiles]
   [psi.test-support.repo-root :as test-repo-root]
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

(defn- repo-root
  "Repo root, shared with workflow-test-support — see its docstring."
  []
  (test-repo-root/repo-root))

(defn- committed-project-models-path
  "Absolute path of the committed .psi/models.edn, resolved from the repo
  root. Fails loud (throws) if the committed .psi/project.edn (whose
  session profiles this test snapshots) or .psi/models.edn is absent, so
  the review-2/18/28/38 durable lock cannot silently degrade to a no-op
  from a wrong cwd."
  []
  (let [root        (repo-root)
        project-edn (java.io.File. root ".psi/project.edn")
        models-edn  (java.io.File. root ".psi/models.edn")]
    (when-not (.exists project-edn)
      (throw (ex-info "committed .psi/project.edn missing — the delegate-review live test's durable lock requires it (the session profiles this test snapshots resolve against the committed deepseek model)"
                      {:path (.getAbsolutePath project-edn)})))
    (when-not (.exists models-edn)
      (throw (ex-info "committed .psi/models.edn missing — the delegate-review live test's durable lock requires it (session profiles reference deepseek/deepseek-v4-flash)"
                      {:path (.getAbsolutePath models-edn)})))
    (.getAbsolutePath models-edn)))

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
                                     :models [{:id "test-model"}]}}})
          ;; The committed .psi/project.edn session profiles reference
          ;; deepseek/deepseek-v4-flash (the committed default); mirror the
          ;; production bootstrap (app-runtime/psi-tool/dispatch-effects load
          ;; <cwd>/.psi/models.edn) so those profiles resolve against the
          ;; committed project models file. This makes the test a durable
          ;; lock for the review-2/18/28/38 regression class: a committed
          ;; profile referencing a model NOT present in committed model
          ;; sources fails here deterministically. Review 39: the committed
          ;; path is resolved via the repo-root walk-up (not user.dir) and
          ;; fails loud on absence, so the lock cannot silently vanish from
          ;; a component-local cwd.
          project-models-path (committed-project-models-path)]
      (try
        (model-registry/init! {:user-models-path    models-path
                               :project-models-path project-models-path})
        (let [[ctx session-id]
              #_{:clj-kondo/ignore [:invalid-arity]}
              (workflow-test-support/create-tui-context+session
               mutations/all-mutations
               {:session-defaults {:model {:provider "local" :id "test-model" :reasoning false}}})]
          ;; Durable-lock fail-loud assertions (reviews 40 + 45): the session
          ;; worktree must resolve the committed .psi/project.edn (deepseek
          ;; default profiles), and ALL SEVEN committed session profiles must
          ;; be present, valid, and resolve to the committed deepseek model.
          ;; Review 45 extended the lock from :reviewing-implementation alone:
          ;; a single-profile regression (one profile removed, retargeted at
          ;; a nonexistent/typo'd model or provider, an invalid
          ;; :thinking-level, or re-pointed at the commented anthropic/openai
          ;; map) previously passed bb test green and failed only at
          ;; delegated-workflow runtime. From a component-local cwd this
          ;; fails loud instead of running with nil profiles and an unrelated
          ;; "Unknown workflow" error.
          (let [worktree-path      (ss/session-worktree-path-in ctx session-id)
                snapshot           (session-profiles/profile-snapshot worktree-path)
                committed-profiles [:designing :fixing-design :planning
                                    :fixing-plan :implementing
                                    :reviewing-implementation
                                    :fixing-implementation]]
            (doseq [profile-name committed-profiles]
              (let [profile (get-in snapshot [:profiles profile-name])]
                (is (contains? (:profiles snapshot) profile-name)
                    (str "session-profile snapshot must contain the committed deepseek "
                         profile-name " profile — worktree " worktree-path
                         " profiles " (pr-str (keys (:profiles snapshot)))))
                (is (true? (:valid? profile))
                    (str profile-name " must resolve validly — "
                         (pr-str (:diagnostics profile))))
                (is (= {:provider "deepseek" :id "deepseek-v4-flash"}
                       (select-keys (get-in profile [:settings :model])
                                    [:provider :id]))
                    (str "the " profile-name " profile must resolve to the committed "
                         "deepseek/deepseek-v4-flash model (durable lock)")))))
          (workflow-test-support/init-built-in-workflow! ctx session-id)
          (try
            (workflow-test-support/load-all-workflow-definitions! ctx)
            (with-redefs [psi.agent-session.turn/prompt-execution-result-in!
                          (fn [_ctx child-session-id prompt]
                            (let [reply (cond
                                          (str/includes? (str/lower-case prompt) "end your response with exactly one of:")
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
                    terminal-status #_{:clj-kondo/ignore [:unresolved-var]} (workflow-test-support/poll-until
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
                (is (= ["review-task-implementation-core" "final-summary"]
                       (->> (:step-order (:effective-definition run))
                            (filter #(get-in run [:step-runs % :accepted-result]))
                            vec)))))
            (finally
              (context/shutdown-context! ctx))))
        (finally
          (.delete (java.io.File. models-path)))))))

