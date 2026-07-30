(ns psi.agent-session.mutations.canonical-workflows
  "Pathom mutations for the canonical deterministic workflow runtime.

   These expose workflow definition registration, run creation, execution,
   resume, and cancellation as Pathom mutations callable through the
   extension API's `mutate!`."
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.workflow-run-retention :as workflow-run-retention]
   [psi.session-state.state :as session-state]
   [psi.shared-config.session-profiles :as session-profiles]
   [psi.workflow-runtime.ir :as workflow-ir]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-registry.registry :as workflow-registry]
   [psi.workflow-step-session-config.core :as workflow-step-session-config]))

(defn- terminal-outcome-error-message
  "Extract a human-readable error message from a workflow run's terminal-outcome."
  [terminal-outcome]
  (when terminal-outcome
    (case (:reason terminal-outcome)
      :iteration-limit-reached
      (str "Iteration limit reached at step '" (:step-id terminal-outcome) "'"
           " after " (:iteration-count terminal-outcome)
           " of " (:max-iterations terminal-outcome) " iterations"
           (when-let [signal (:last-judge-signal terminal-outcome)]
             (str " (last signal: " signal ")"))
           (when-let [text (not-empty (:last-result-text terminal-outcome))]
             (str "\n\nLast result:\n"
                  (if (> (count text) 2000)
                    (str (subs text 0 2000) "\n... [truncated]")
                    text))))
      :judge-no-match
      (str "Judge signal did not match any route at step '" (:step-id terminal-outcome) "'"
           (when-let [output (:judge-output terminal-outcome)]
             (str ": " output)))
      ;; generic fallback
      (str "Workflow failed: " (some-> (:reason terminal-outcome) name)
           " at step '" (:step-id terminal-outcome) "'"))))

(defn- run-failure-error
  "Extract an error message for a failed workflow run, checking step errors first,
   then terminal-outcome."
  [exec-result final-run]
  (or (some :error (:steps-executed exec-result))
      (terminal-outcome-error-message (:terminal-outcome final-run))))

(defn- session-profile-snapshot
  [agent-session-ctx session-id]
  (when (and session-id (session-state/get-session-data-in agent-session-ctx session-id))
    (session-profiles/profile-snapshot
     (session-state/session-worktree-path-in agent-session-ctx session-id))))

(pco/defmutation register-workflow-definition
  "Register a canonical workflow definition into root state."
  [_ {:keys [psi/agent-session-ctx definition]}]
  {::pco/op-name 'psi.workflow/register-definition
   ::pco/params  [:psi/agent-session-ctx :definition]
   ::pco/output  [:psi.workflow/definition-id
                  :psi.workflow/registered?
                  :psi.workflow/error]}
  (try
    (let [[new-state definition-id _stored]
          (workflow-registry/register-definition @(:state* agent-session-ctx) definition)]
      (reset! (:state* agent-session-ctx) new-state)
      {:psi.workflow/definition-id definition-id
       :psi.workflow/registered? true
       :psi.workflow/error nil})
    (catch Exception e
      {:psi.workflow/definition-id nil
       :psi.workflow/registered? false
       :psi.workflow/error (ex-message e)})))

(pco/defmutation remove-workflow-definition
  "Remove a registered canonical workflow definition from root state."
  [_ {:keys [psi/agent-session-ctx definition-id]}]
  {::pco/op-name 'psi.workflow/remove-definition
   ::pco/params  [:psi/agent-session-ctx :definition-id]
   ::pco/output  [:psi.workflow/definition-id
                  :psi.workflow/removed?
                  :psi.workflow/error]}
  (try
    (let [[new-state _removed-definition]
          (workflow-registry/remove-definition @(:state* agent-session-ctx) definition-id)]
      (reset! (:state* agent-session-ctx) new-state)
      {:psi.workflow/definition-id definition-id
       :psi.workflow/removed? true
       :psi.workflow/error nil})
    (catch Exception e
      {:psi.workflow/definition-id definition-id
       :psi.workflow/removed? false
       :psi.workflow/error (ex-message e)})))

(pco/defmutation create-workflow-run
  "Create a canonical workflow run from a registered definition."
  [_ {:keys [psi/agent-session-ctx session-id definition-id workflow-input run-id]}]
  {::pco/op-name 'psi.workflow/create-run
   ::pco/params  [:psi/agent-session-ctx :definition-id]
   ::pco/output  [:psi.workflow/run-id
                  :psi.workflow/status
                  :psi.workflow/error]}
  (try
    (let [inherited-defaults (when session-id
                               (workflow-step-session-config/resolve-inherited-defaults-snapshot
                                agent-session-ctx session-id))
          profile-snapshot (session-profile-snapshot agent-session-ctx session-id)
          [new-state created-run-id workflow-run]
          (workflow-runtime/create-run @(:state* agent-session-ctx)
                                       (cond-> {:definition-id definition-id}
                                         session-id (assoc :parent-session-id session-id)
                                         inherited-defaults (assoc :inherited-defaults inherited-defaults)
                                         profile-snapshot (assoc :session-profile-snapshot profile-snapshot)
                                         workflow-input (assoc :workflow-input workflow-input)
                                         run-id (assoc :run-id run-id)))]
      (reset! (:state* agent-session-ctx) new-state)
      {:psi.workflow/run-id created-run-id
       :psi.workflow/status (:status workflow-run)
       :psi.workflow/error nil})
    (catch Exception e
      {:psi.workflow/run-id nil
       :psi.workflow/status nil
       :psi.workflow/error (ex-message e)})))

(pco/defmutation execute-workflow-run
  "Execute a canonical workflow run sequentially to terminal or blocked status.
   Requires the session-id of the parent session for child-session creation."
  [_ {:keys [psi/agent-session-ctx session-id run-id]}]
  {::pco/op-name 'psi.workflow/execute-run
   ::pco/params  [:psi/agent-session-ctx :session-id :run-id]
   ::pco/output  [:psi.workflow/run-id
                  :psi.workflow/status
                  :psi.workflow/steps-executed
                  :psi.workflow/terminal?
                  :psi.workflow/blocked?
                  :psi.workflow/result
                  :psi.workflow/error]}
  (try
    (let [execute-fn (:execute-workflow-run-fn agent-session-ctx)
          exec-result (execute-fn agent-session-ctx session-id run-id)
          _ (workflow-run-retention/apply-retention-cleanup! agent-session-ctx run-id)
          final-run (workflow-runtime/workflow-run-in @(:state* agent-session-ctx) run-id)
          ;; Extract terminal yielded text from the last completed step's
          ;; canonical output surface, but treat blank text as missing so callers
          ;; can suppress empty transcript injection and still distinguish a real
          ;; final reply.
          result-text (when (= :completed (:status final-run))
                        (let [terminal-step-id (or (get-in final-run [:terminal-outcome :step-id])
                                                   (last (:step-order (:effective-definition final-run))))
                              step-def (some #(when (= terminal-step-id (:name %)) %)
                                             (get-in final-run [:effective-definition :canonical-ir :steps]))
                              accepted-result (get-in final-run [:step-runs terminal-step-id :accepted-result])
                              text-yield (workflow-ir/step-yield-field-value step-def accepted-result :text)]
                          (cond
                            (string? text-yield)
                            (some-> text-yield str/trim not-empty)

                            (some? text-yield)
                            (pr-str text-yield)

                            :else nil)))]
      {:psi.workflow/run-id run-id
       :psi.workflow/status (:status exec-result)
       :psi.workflow/steps-executed (:steps-executed exec-result)
       :psi.workflow/terminal? (:terminal? exec-result)
       :psi.workflow/blocked? (:blocked? exec-result)
       :psi.workflow/result result-text
       :psi.workflow/error (when (= :failed (:status exec-result))
                             (run-failure-error exec-result final-run))})
    (catch Exception e
      {:psi.workflow/run-id run-id
       :psi.workflow/status nil
       :psi.workflow/steps-executed nil
       :psi.workflow/terminal? nil
       :psi.workflow/blocked? nil
       :psi.workflow/result nil
       :psi.workflow/error (ex-message e)})))

(pco/defmutation resume-workflow-run
  "Resume a blocked canonical workflow run and continue execution.

   When `workflow-input` is provided, it replaces the run's top-level workflow
   input before resuming so continue-with-new-prompt flows can reuse the same
   blocked run."
  [_ {:keys [psi/agent-session-ctx session-id run-id workflow-input]}]
  {::pco/op-name 'psi.workflow/resume-run
   ::pco/params  [:psi/agent-session-ctx :session-id :run-id]
   ::pco/output  [:psi.workflow/run-id
                  :psi.workflow/status
                  :psi.workflow/steps-executed
                  :psi.workflow/terminal?
                  :psi.workflow/blocked?
                  :psi.workflow/error]}
  (try
    (when workflow-input
      (let [[new-state _updated-run]
            (workflow-runtime/update-run-workflow-input @(:state* agent-session-ctx) run-id workflow-input)]
        (reset! (:state* agent-session-ctx) new-state)))
    (let [resume-fn (:resume-and-execute-workflow-run-fn agent-session-ctx)
          exec-result (resume-fn agent-session-ctx session-id run-id)
          _ (workflow-run-retention/apply-retention-cleanup! agent-session-ctx run-id)
          final-run (workflow-runtime/workflow-run-in @(:state* agent-session-ctx) run-id)]
      {:psi.workflow/run-id run-id
       :psi.workflow/status (:status exec-result)
       :psi.workflow/steps-executed (:steps-executed exec-result)
       :psi.workflow/terminal? (:terminal? exec-result)
       :psi.workflow/blocked? (:blocked? exec-result)
       :psi.workflow/error (when (= :failed (:status exec-result))
                             (run-failure-error exec-result final-run))})
    (catch Exception e
      {:psi.workflow/run-id run-id
       :psi.workflow/status nil
       :psi.workflow/steps-executed nil
       :psi.workflow/terminal? nil
       :psi.workflow/blocked? nil
       :psi.workflow/error (ex-message e)})))

(pco/defmutation cancel-workflow-run
  "Cancel an active canonical workflow run."
  [_ {:keys [psi/agent-session-ctx run-id reason session-id]}]
  {::pco/op-name 'psi.workflow/cancel-run
   ::pco/params  [:psi/agent-session-ctx :run-id]
   ::pco/output  [:psi.workflow/run-id
                  :psi.workflow/status
                  :psi.workflow/cancelled?
                  :psi.workflow/found?
                  :psi.workflow/noop?
                  :psi.workflow/error]}
  (try
    (let [result (dispatch/dispatch! agent-session-ctx
                                     :psi.workflow/cancel-run
                                     (cond-> {:run-id run-id}
                                       reason (assoc :reason reason)
                                       session-id (assoc :session-id session-id))
                                     {:origin :core})]
      (workflow-run-retention/apply-retention-cleanup! agent-session-ctx run-id)
      result)
    (catch Exception e
      {:psi.workflow/run-id run-id
       :psi.workflow/status nil
       :psi.workflow/cancelled? false
       :psi.workflow/noop? false
       :psi.workflow/error (ex-message e)})))

(pco/defmutation remove-workflow-run
  "Remove a canonical workflow run from root state."
  [_ {:keys [psi/agent-session-ctx run-id reason session-id]}]
  {::pco/op-name 'psi.workflow/remove-run
   ::pco/params  [:psi/agent-session-ctx :run-id]
   ::pco/output  [:psi.workflow/run-id
                  :psi.workflow/removed?
                  :psi.workflow/found?
                  :psi.workflow/noop?
                  :psi.workflow/cancelled?
                  :psi.workflow/error]}
  (try
    (dispatch/dispatch! agent-session-ctx
                        :psi.workflow/remove-run
                        (cond-> {:run-id run-id}
                          reason (assoc :reason reason)
                          session-id (assoc :session-id session-id))
                        {:origin :core})
    (catch Exception e
      {:psi.workflow/run-id run-id
       :psi.workflow/removed? false
       :psi.workflow/noop? false
       :psi.workflow/error (ex-message e)})))

(pco/defmutation list-workflow-definitions
  "List all registered canonical workflow definitions."
  [_ {:keys [psi/agent-session-ctx]}]
  {::pco/op-name 'psi.workflow/list-definitions
   ::pco/params  [:psi/agent-session-ctx]
   ::pco/output  [:psi.workflow/definitions
                  :psi.workflow/definition-count]}
  (let [definitions (workflow-registry/list-definitions @(:state* agent-session-ctx))]
    {:psi.workflow/definitions (mapv (fn [d]
                                       {:definition-id (:definition-id d)
                                        :name (:name d)
                                        :summary (:summary d)
                                        :step-count (count (:step-order d))})
                                     definitions)
     :psi.workflow/definition-count (count definitions)}))

(pco/defmutation list-workflow-runs
  "List all canonical workflow runs."
  [_ {:keys [psi/agent-session-ctx]}]
  {::pco/op-name 'psi.workflow/list-runs
   ::pco/params  [:psi/agent-session-ctx]
   ::pco/output  [:psi.workflow/runs
                  :psi.workflow/run-count]}
  (let [runs (workflow-runtime/list-workflow-runs @(:state* agent-session-ctx))]
    {:psi.workflow/runs (mapv (fn [r]
                                {:run-id (:run-id r)
                                 :status (:status r)
                                 :source-definition-id (:source-definition-id r)
                                 :current-step-id (:current-step-id r)
                                 :created-at (:created-at r)})
                              runs)
     :psi.workflow/run-count (count runs)}))

(def all-mutations
  [register-workflow-definition
   remove-workflow-definition
   create-workflow-run
   execute-workflow-run
   resume-workflow-run
   cancel-workflow-run
   remove-workflow-run
   list-workflow-definitions
   list-workflow-runs])
