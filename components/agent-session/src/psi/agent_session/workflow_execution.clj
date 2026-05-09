(ns psi.agent-session.workflow-execution
  "Higher workflow execution façade for canonical deterministic workflow runs.

   This slice owns the session-facing execution entrypoints that run and resume
   canonical workflow runs through the Phase A statechart runtime. Lower step
   preparation helpers live under `psi.workflow-runtime.step-materialization`
   and `psi.workflow-runtime.step-session-config`."
  (:require
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.statechart-runtime :as workflow-statechart-runtime]))

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
