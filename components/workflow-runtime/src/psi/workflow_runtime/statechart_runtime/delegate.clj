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

(defn- delegate-run-runtime-result
  [delegate-run delegate-run-id target-name boundary]
  (if (nil? delegate-run)
    {:pending-kind :failure
     :payload {:message "Delegated workflow cancelled or removed"
               :delegate-run-id delegate-run-id
               :target target-name
               :details {:status :removed}}}
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

(defn delegate-step-runtime-result
  "Resolve a delegate step by creating + driving a child workflow run.

   `resolve-inherited-defaults-fn` is an injected closure
   `(ctx parent-session-id workflow-run step-id) → snapshot` (mirroring the
   existing injected `create-workflow-context-fn`/`send-and-drain-fn` params).
   It derives the delegating step's effective-config inherited-defaults snapshot
   (run snapshot ⊕ step overrides), captured at sub-delegation creation
   (task 207, Decision 3/7a). `delegate.clj` does NOT require
   `workflow-step-session-config` — that reverse require is a certain cycle
   (P1) — so the resolver is reached via this injected fn, bound by the caller
   (which depends on both components)."
  [create-workflow-context-fn send-and-drain-fn resolve-inherited-defaults-fn ctx parent-session-id step-id step-def workflow-run]
  (let [delegate-spec (:delegate step-def)
        target (:target delegate-spec)
        {:keys [target-name]} (resolve-delegate-target-definition ctx workflow-run target)
        prompt-string (workflow-source-resolution/render-delegate-prompt-string workflow-run (:prompt-string delegate-spec))
        context (workflow-source-resolution/resolve-delegate-context workflow-run (:context delegate-spec))
        parent-run-id (:run-id workflow-run)
        inherited-defaults (when resolve-inherited-defaults-fn
                             (resolve-inherited-defaults-fn ctx parent-session-id workflow-run step-id))
        run-opts (cond-> {:definition-id target-name
                          :parent-session-id parent-session-id
                          :delegating-run-id parent-run-id
                          :workflow-input prompt-string
                          :workflow-original context}
                   (contains? workflow-run :session-profile-snapshot)
                   (assoc :session-profile-snapshot (:session-profile-snapshot workflow-run))
                   inherited-defaults (assoc :inherited-defaults inherited-defaults))
        delegate-run-id
        (loop []
          (let [state-map @(:state* ctx)]
            (if (or (nil? (workflow-runtime/workflow-run-in state-map parent-run-id))
                    (= :cancelled (:status (workflow-runtime/workflow-run-in state-map parent-run-id))))
              nil
              (let [[state' delegate-run-id _] (workflow-runtime/create-run state-map run-opts)]
                (if (compare-and-set! (:state* ctx) state-map state')
                  delegate-run-id
                  (recur))))))]
    (if-not delegate-run-id
      {:pending-kind :failure
       :payload {:message "Delegating workflow cancelled or removed before child workflow start"
                 :target target-name
                 :details {:status :cancelled}}}
      (let [delegate-wf-ctx (create-workflow-context-fn ctx parent-session-id delegate-run-id)
            _ (send-and-drain-fn delegate-wf-ctx (:wm delegate-wf-ctx) :workflow/start nil)
            delegate-run (workflow-runtime/workflow-run-in @(:state* ctx) delegate-run-id)
            boundary {:delegate {:target target-name
                                 :resolved-target target-name
                                 :run-id delegate-run-id
                                 :step-id step-id
                                 :prompt-string prompt-string
                                 :context context}}]
        (delegate-run-runtime-result delegate-run delegate-run-id target-name boundary)))))
