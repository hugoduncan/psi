(ns psi.agent-session.mutations.canonical-workflows
  "Pathom mutations for the canonical deterministic workflow runtime.

   These expose workflow definition registration, run creation, execution,
   resume, and cancellation as Pathom mutations callable through the
   extension API's `mutate!`."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.workflow-progression :as workflow-progression]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

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
          (workflow-runtime/register-definition @(:state* agent-session-ctx) definition)]
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
          (workflow-runtime/remove-definition @(:state* agent-session-ctx) definition-id)]
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
  [_ {:keys [psi/agent-session-ctx definition-id workflow-input run-id]}]
  {::pco/op-name 'psi.workflow/create-run
   ::pco/params  [:psi/agent-session-ctx :definition-id]
   ::pco/output  [:psi.workflow/run-id
                  :psi.workflow/status
                  :psi.workflow/error]}
  (try
    (let [[new-state created-run-id workflow-run]
          (workflow-runtime/create-run @(:state* agent-session-ctx)
                                       (cond-> {:definition-id definition-id}
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
          final-run (workflow-runtime/workflow-run-in @(:state* agent-session-ctx) run-id)
          ;; Extract terminal result text from last completed step
          result-text (when (= :completed (:status final-run))
                        (let [last-step-id (last (:step-order (:effective-definition final-run)))]
                          (get-in final-run [:step-runs last-step-id :accepted-result :outputs :text])))]
      {:psi.workflow/run-id run-id
       :psi.workflow/status (:status exec-result)
       :psi.workflow/steps-executed (:steps-executed exec-result)
       :psi.workflow/terminal? (:terminal? exec-result)
       :psi.workflow/blocked? (:blocked? exec-result)
       :psi.workflow/result result-text
       :psi.workflow/error nil})
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
          exec-result (resume-fn agent-session-ctx session-id run-id)]
      {:psi.workflow/run-id run-id
       :psi.workflow/status (:status exec-result)
       :psi.workflow/steps-executed (:steps-executed exec-result)
       :psi.workflow/terminal? (:terminal? exec-result)
       :psi.workflow/blocked? (:blocked? exec-result)
       :psi.workflow/error nil})
    (catch Exception e
      {:psi.workflow/run-id run-id
       :psi.workflow/status nil
       :psi.workflow/steps-executed nil
       :psi.workflow/terminal? nil
       :psi.workflow/blocked? nil
       :psi.workflow/error (ex-message e)})))

(pco/defmutation cancel-workflow-run
  "Cancel an active canonical workflow run."
  [_ {:keys [psi/agent-session-ctx run-id reason]}]
  {::pco/op-name 'psi.workflow/cancel-run
   ::pco/params  [:psi/agent-session-ctx :run-id]
   ::pco/output  [:psi.workflow/run-id
                  :psi.workflow/status
                  :psi.workflow/error]}
  (try
    (let [workflow-run (workflow-runtime/workflow-run-in @(:state* agent-session-ctx) run-id)]
      (when-not workflow-run
        (throw (ex-info "Workflow run not found" {:run-id run-id})))
      (when (contains? #{:completed :failed :cancelled} (:status workflow-run))
        (throw (ex-info "Workflow run is already terminal" {:run-id run-id :status (:status workflow-run)})))
      (let [new-state (workflow-progression/cancel-run @(:state* agent-session-ctx) run-id
                                                       (or reason "cancelled"))
            cancelled-run (workflow-runtime/workflow-run-in new-state run-id)]
        (reset! (:state* agent-session-ctx) new-state)
        {:psi.workflow/run-id run-id
         :psi.workflow/status (:status cancelled-run)
         :psi.workflow/error nil}))
    (catch Exception e
      {:psi.workflow/run-id run-id
       :psi.workflow/status nil
       :psi.workflow/error (ex-message e)})))

(pco/defmutation remove-workflow-run
  "Remove a canonical workflow run from root state."
  [_ {:keys [psi/agent-session-ctx run-id]}]
  {::pco/op-name 'psi.workflow/remove-run
   ::pco/params  [:psi/agent-session-ctx :run-id]
   ::pco/output  [:psi.workflow/run-id
                  :psi.workflow/removed?
                  :psi.workflow/error]}
  (try
    (let [[new-state _removed-run]
          (workflow-runtime/remove-run @(:state* agent-session-ctx) run-id)]
      (reset! (:state* agent-session-ctx) new-state)
      {:psi.workflow/run-id run-id
       :psi.workflow/removed? true
       :psi.workflow/error nil})
    (catch Exception e
      {:psi.workflow/run-id run-id
       :psi.workflow/removed? false
       :psi.workflow/error (ex-message e)})))

(pco/defmutation list-workflow-definitions
  "List all registered canonical workflow definitions."
  [_ {:keys [psi/agent-session-ctx]}]
  {::pco/op-name 'psi.workflow/list-definitions
   ::pco/params  [:psi/agent-session-ctx]
   ::pco/output  [:psi.workflow/definitions
                  :psi.workflow/definition-count]}
  (let [definitions (->> (get-in @(:state* agent-session-ctx) [:workflows :definitions])
                         vals
                         (sort-by :definition-id)
                         vec)]
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
