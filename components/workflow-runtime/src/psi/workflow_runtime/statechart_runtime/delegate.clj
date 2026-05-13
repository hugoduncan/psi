(ns psi.workflow-runtime.statechart-runtime.delegate
  (:require
   [psi.workflow-step-materialization.source-resolution :as workflow-source-resolution]
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.terminal-contract :as workflow-terminal-contract]
   [psi.workflow-registry.registry :as registry]))

(defn resolve-delegate-target-name
  [workflow-run target]
  (cond
    (string? target)
    target

    (workflow-source-resolution/source-spec? target)
    (:name (workflow-source-resolution/resolve-workflow-ref-source-spec workflow-run target))

    :else
    (throw (ex-info "Delegate target must be a workflow name string or workflow source-spec"
                    {:target target}))))

(defn resolve-delegate-target-definition
  [ctx workflow-run target]
  (let [target-name (resolve-delegate-target-name workflow-run target)
        definition (registry/workflow-definition @(:state* ctx) target-name)]
    (when-not definition
      (throw (ex-info "Delegated workflow definition not found"
                      {:target target-name
                       :delegate-target target})))
    {:target-name target-name
     :definition definition}))

(defn terminal-step-result-envelope
  [workflow-run]
  (workflow-terminal-contract/terminal-result-envelope workflow-run))

(defn delegate-step-runtime-result
  [create-workflow-context-fn send-and-drain-fn ctx parent-session-id step-id step-def workflow-run]
  (let [delegate-spec (:delegate step-def)
        target (:target delegate-spec)
        {:keys [target-name]} (resolve-delegate-target-definition ctx workflow-run target)
        prompt-string (workflow-source-resolution/render-delegate-prompt-string workflow-run (:prompt-string delegate-spec))
        context (workflow-source-resolution/resolve-delegate-context workflow-run (:context delegate-spec))
        [state' delegate-run-id _]
        (workflow-runtime/create-run @(:state* ctx)
                                     {:definition-id target-name
                                      :parent-session-id parent-session-id
                                      :workflow-input prompt-string
                                      :workflow-original context})
        _ (reset! (:state* ctx) state')
        delegate-wf-ctx (create-workflow-context-fn ctx parent-session-id delegate-run-id)
        _ (send-and-drain-fn delegate-wf-ctx (:wm delegate-wf-ctx) :workflow/start nil)
        delegate-run (workflow-runtime/workflow-run-in @(:state* ctx) delegate-run-id)
        boundary {:delegate {:target target-name
                             :resolved-target target-name
                             :run-id delegate-run-id
                             :step-id step-id
                             :prompt-string prompt-string
                             :context context}}]
    (case (:status delegate-run)
      :completed
      (let [contract-outputs (workflow-terminal-contract/terminal-contract-outputs delegate-run)]
        {:pending-kind :success
         :payload (cond-> (terminal-step-result-envelope delegate-run)
                    (seq contract-outputs) (update :outputs #(merge contract-outputs (or % {})))
                    true (update :diagnostics #(merge boundary (or % {}))))})

      :blocked
      {:pending-kind :blocked
       :payload {:outcome :blocked
                 :blocked {:delegate-run-id delegate-run-id
                           :target target-name
                           :step-id (get-in delegate-run [:blocked :step-id])}
                 :diagnostics boundary}}

      :failed
      {:pending-kind :failure
       :payload {:message "Delegated workflow failed"
                 :delegate-run-id delegate-run-id
                 :target target-name
                 :details (or (:terminal-outcome delegate-run)
                              {:status (:status delegate-run)})}}

      :cancelled
      {:pending-kind :failure
       :payload {:message "Delegated workflow cancelled"
                 :delegate-run-id delegate-run-id
                 :target target-name
                 :details (or (:terminal-outcome delegate-run)
                              {:status (:status delegate-run)})}}

      {:pending-kind :failure
       :payload {:message "Delegated workflow did not reach terminal or blocked status"
                 :delegate-run-id delegate-run-id
                 :target target-name
                 :details {:status (:status delegate-run)}}})))
