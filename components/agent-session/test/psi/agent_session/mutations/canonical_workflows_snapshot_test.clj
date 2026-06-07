(ns psi.agent-session.mutations.canonical-workflows-snapshot-test
  "Task 207 — continue-terminal-run fresh inherited-defaults snapshot capture.

   Split out of canonical-workflows-test to keep each test file under the
   commit-check file-length limit. Shares the `make-test-ctx` / `sample-definition`
   fixtures from that namespace."
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.mutations.canonical-workflows :as cwf-mutations]
   [psi.agent-session.mutations.canonical-workflows-test :as core-test]
   [psi.session-state.state :as ss]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.agent-session.workflow.orchestration :as orchestration]
   [psi.agent-session.workflow.runtime-state :as runtime-state]
   [psi.workflow-step-session-config.core :as workflow-step-session-config]))

(defn- production-like-mutate!
  "A `mutate!` closure mirroring the production `runtime-fns` mutate-fn /
   `run-extension-mutation-in!` session-id contract: for a session-scoped op
   the wrapper injects the active invoking session as `:session-id` when the
   caller did not pass one. `psi.workflow/create-run` is in that session-scoped
   set (task 207, `runtime-eql/session-scoped-extension-mutation-ops`), so the
   continue/invoke callers (`continue-terminal-run-async!`, `workflow/core.clj`
   invoke) — which pass no explicit `:session-id` — get the active workflow
   session injected, enabling invoke-time snapshot capture. This closure
   reproduces exactly that contract (inject from `*active-workflow-session-id*`
   when absent) and routes to the real `create-workflow-run` mutation."
  [ctx]
  (fn [op-sym params]
    (case op-sym
      psi.workflow/create-run
      (cwf-mutations/create-workflow-run
       {}
       (-> params
           (assoc :psi/agent-session-ctx ctx)
           (cond-> (not (contains? params :session-id))
             (assoc :session-id runtime-state/*active-workflow-session-id*))))
      (throw (ex-info "unexpected mutation" {:op op-sym})))))

(deftest continue-terminal-run-captures-fresh-snapshot-test
  ;; Task 207 — Test-review pass 4 (T4). Decision 5b: a terminal-run
  ;; continuation creates a NEW run that must capture a FRESH snapshot from the
  ;; continuing session — distinguishable from the original terminal run's
  ;; snapshot. This drives the real `continue-terminal-run-async!` path with a
  ;; production-like `mutate!` (no explicit `:session-id`, matching the
  ;; runtime-fns wrapper that injects the active session for the
  ;; session-scoped `psi.workflow/create-run`),
  ;; so it pins the session-id contract all top-level capture relies on rather
  ;; than the S4 tests that call the mutation directly with an explicit
  ;; `:session-id`.
  (testing "continue-terminal-run-async! captures a fresh snapshot reflecting
            the continuing session's CURRENT model (Decision 5b)"
    (let [ctx (core-test/make-test-ctx)
          sd (session/new-session-in! ctx nil {:session-name "continuer"})
          session-id (:session-id sd)
          _ (swap! (:state* ctx) update-in [:agent-session :sessions session-id :data]
                   merge {:model {:provider "anthropic" :id "claude-ORIGINAL"}})
          _ (cwf-mutations/register-workflow-definition
             {} {:psi/agent-session-ctx ctx :definition core-test/sample-definition})
          ;; Original invoke captured the ORIGINAL model snapshot.
          _ (cwf-mutations/create-workflow-run
             {} {:psi/agent-session-ctx ctx
                 :session-id session-id
                 :definition-id "test-workflow"
                 :workflow-input {:input "go"}
                 :run-id "terminal-run"})
          original-snapshot (get-in @(:state* ctx)
                                    [:workflows :runs "terminal-run" :inherited-defaults])
          ;; Mid-life: the continuing session switches model AFTER the original
          ;; invoke.
          _ (swap! (:state* ctx) update-in [:agent-session :sessions session-id :data]
                   merge {:model {:provider "anthropic" :id "claude-CHANGED"}})
          captured-new-run-id (atom nil)
          result (binding [runtime-state/*active-workflow-session-id* session-id]
                   (orchestration/continue-terminal-run-async!
                    {:mutate! (production-like-mutate! ctx)
                     :find-run-summary-fn (fn [_run-id]
                                            {:source-definition-id "test-workflow"})
                     :execute-async! (fn [new-run-id _session-id _def-id _include?]
                                       (reset! captured-new-run-id new-run-id))}
                    "terminal-run" session-id "continue please" false))
          new-run-id (:run-id result)
          new-snapshot (get-in @(:state* ctx)
                               [:workflows :runs new-run-id :inherited-defaults])]
      (is (= {:provider "anthropic" :id "claude-ORIGINAL"} (:model original-snapshot))
          "original terminal run captured the original model")
      (is (not= "terminal-run" new-run-id)
          "continuation creates a NEW run")
      (is (= {:provider "anthropic" :id "claude-CHANGED"} (:model new-snapshot))
          "continuation run captures a FRESH snapshot of the continuing session's current model")
      (is (not= (:model original-snapshot) (:model new-snapshot))
          "fresh snapshot is distinguishable from the original terminal run's snapshot"))))

(deftest create-workflow-run-captures-session-profile-snapshot-test
  ;; Task 217 — Pathom create-run captures effective session-profile resolution
  ;; once at top-level invocation and stores ignored-key-free records on the run.
  (testing "psi.workflow/create-run stores a profile snapshot from the invoking session worktree"
    (let [ctx (core-test/make-test-ctx)
          sd (session/new-session-in! ctx nil {:session-name "delegator"})
          session-id (:session-id sd)
          cwd (ss/session-worktree-path-in ctx session-id)
          _ (.mkdirs (java.io.File. cwd ".psi"))
          _ (spit (java.io.File. cwd ".psi/project.edn")
                  (pr-str {:agent-session
                           {:session-profiles
                            {:coding {:speed-mode :fast
                                      :temperature 0.1}
                             "oops" {:speed-mode :normal}
                             :empty {}}}}))
          _ (cwf-mutations/register-workflow-definition
             {} {:psi/agent-session-ctx ctx :definition core-test/sample-definition})
          result (cwf-mutations/create-workflow-run
                  {} {:psi/agent-session-ctx ctx
                      :session-id session-id
                      :definition-id "test-workflow"
                      :workflow-input {:input "go"}
                      :run-id "run-session-profile-snapshot"})
          snapshot (get-in @(:state* ctx)
                           [:workflows :runs "run-session-profile-snapshot" :session-profile-snapshot])]
      (is (nil? (:psi.workflow/error result)))
      (is (= [:coding] (:valid-profile-names snapshot)))
      (is (= [:empty "oops"] (:invalid-profile-names snapshot)))
      (is (= {:speed-mode :fast}
             (get-in snapshot [:profiles :coding :settings])))
      (is (= [:invalid-profile-name]
             (mapv :reason (get-in snapshot [:profiles "oops" :diagnostics])))
          "workflow snapshots report non-keyword profile names instead of throwing")
      (is (not (contains? (get-in snapshot [:profiles :coding]) :ignored-keys))
          "workflow snapshots omit ignored unknown config keys"))))

(deftest resumed-run-reuses-session-profile-snapshot-test
  ;; Task 217 — resume consumes the already-stored run snapshot; editing config
  ;; after create-run does not affect profile resolution.
  (testing "profile resolution for a resumed run ignores post-invoke config edits"
    (let [ctx (core-test/make-test-ctx)
          sd (session/new-session-in! ctx nil {:session-name "delegator"})
          session-id (:session-id sd)
          cwd (ss/session-worktree-path-in ctx session-id)
          profile-file (java.io.File. cwd ".psi/project.edn")
          definition (assoc-in core-test/sample-definition [:steps 0 :session-profile] :coding)
          _ (.mkdirs (.getParentFile profile-file))
          _ (spit profile-file
                  (pr-str {:agent-session {:session-profiles {:coding {:speed-mode :fast}}}}))
          _ (cwf-mutations/register-workflow-definition
             {} {:psi/agent-session-ctx ctx :definition definition})
          _ (cwf-mutations/create-workflow-run
             {} {:psi/agent-session-ctx ctx
                 :session-id session-id
                 :definition-id "test-workflow"
                 :workflow-input {:input "go"}
                 :run-id "run-resume-profile"})
          _ (spit profile-file
                  (pr-str {:agent-session {:session-profiles {:coding {:speed-mode :normal}}}}))
          [state' _] (workflow-runtime/resume-run @(:state* ctx) "run-resume-profile")
          _ (reset! (:state* ctx) state')
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) "run-resume-profile")
          config (workflow-step-session-config/resolve-step-session-config
                  ctx session-id workflow-run "step-1")]
      (is (= :fast (:speed-mode config))
          "resumed run reuses the stored :session-profile-snapshot, not edited config"))))
