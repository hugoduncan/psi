(ns psi.agent-session.workflow-execution
  "Impure execution helpers for canonical deterministic workflow runs.

   This slice bridges canonical workflow definitions/runs to actual bounded
   session execution for workflow attempts. It now provides:
   - materialize step inputs from canonical bindings
   - render legacy-compatible prompt templates
   - resolve step session config from workflow-file-meta
   - create one attempt child session for the current step
   - prompt that session
   - record a canonical structured result envelope back onto the workflow run
   - loop execution across sequential steps until terminal or blocked state
   - resume a blocked run and continue execution with a fresh attempt

   Canonical execution note:
   - Phase A statechart execution is the sole canonical workflow-run execution path
   - workflow step session preload parity is therefore intentionally centralized in
     `workflow-statechart-runtime` step entry via
     `workflow-step-prep/materialize-step-session-preload`
   - if a future canonical runner is introduced, it must route through the same
     preload materialization semantics rather than re-deriving them locally"
  (:require
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-statechart-runtime :as workflow-statechart-runtime]
   [psi.agent-session.workflow-step-prep :as workflow-step-prep]))

(def binding-source-value workflow-step-prep/binding-source-value)
(def materialize-step-inputs workflow-step-prep/materialize-step-inputs)
(def render-prompt-template workflow-step-prep/render-prompt-template)
(def step-prompt workflow-step-prep/step-prompt)

(defn resolve-step-session-config
  "Resolve child session configuration for a workflow step.

   For single-step workflows, uses the run's own :workflow-file-meta.
   For multi-step workflows, looks up the referenced workflow's definition
   from registered definitions to get that step's :workflow-file-meta.

   Returns a map with composed prompt/config for child session creation."
  ([ctx workflow-run step-id]
   (resolve-step-session-config ctx nil workflow-run step-id))
  ([ctx parent-session-id workflow-run step-id]
   (workflow-step-prep/resolve-step-session-config ctx parent-session-id workflow-run step-id)))

(defn- execution-result
  [run-id workflow-run]
  {:run-id run-id
   :status (:status workflow-run)
   :steps-executed (->> (:step-order (:effective-definition workflow-run))
                        (mapcat (fn [step-id]
                                  (map (fn [attempt]
                                         {:step-id step-id
                                          :attempt-id (:attempt-id attempt)
                                          :execution-session-id (:execution-session-id attempt)
                                          :status (:status attempt)
                                          :error (get-in attempt [:execution-error :message])})
                                       (get-in workflow-run [:step-runs step-id :attempts]))))
                        vec)
   :terminal? (contains? #{:completed :failed :cancelled} (:status workflow-run))
   :blocked? (= :blocked (:status workflow-run))})

(defn execute-run!
  "Execute a workflow run via the Phase A hierarchical statechart runtime.

   Returns {:run-id ... :status ... :steps-executed [...] :terminal? bool :blocked? bool}."
  [ctx parent-session-id run-id]
  (let [wf-ctx (workflow-statechart-runtime/create-workflow-context ctx parent-session-id run-id)
        _ (workflow-statechart-runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/start nil)
        workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
    (execution-result run-id workflow-run)))

(defn resume-and-execute-run!
  "Resume a blocked run and continue execution via the Phase A statechart runtime."
  [ctx parent-session-id run-id]
  (let [wf-ctx (workflow-statechart-runtime/create-workflow-context ctx parent-session-id run-id)
        _ (workflow-statechart-runtime/send-and-drain! wf-ctx (:wm wf-ctx) :workflow/resume nil)
        workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
    (execution-result run-id workflow-run)))
