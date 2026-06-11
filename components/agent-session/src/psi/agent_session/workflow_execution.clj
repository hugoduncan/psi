(ns psi.agent-session.workflow-execution
  "Higher workflow execution façade for canonical deterministic workflow runs.

   This slice owns the session-facing execution entrypoints that run and resume
   canonical workflow runs through the Phase A statechart runtime. Lower step
   preparation helpers live under `psi.workflow-step-materialization.core`
   and `psi.workflow-step-session-config.core`."
  (:require
   [psi.workflow-runtime.core :as workflow-runtime]
   [psi.workflow-runtime.statechart-runtime :as workflow-statechart-runtime]))

(defn- execution-result
  [run-id workflow-run]
  (let [status (:status workflow-run)]
    {:run-id run-id
     :status status
     :steps-executed (if workflow-run
                       (->> (:step-order (:effective-definition workflow-run))
                            (mapcat (fn [step-id]
                                      (map (fn [attempt]
                                             {:step-id step-id
                                              :attempt-id (:attempt-id attempt)
                                              :execution-session-id (:execution-session-id attempt)
                                              :status (:status attempt)
                                              :error (get-in attempt [:execution-error :message])})
                                           (get-in workflow-run [:step-runs step-id :attempts]))))
                            vec)
                       [])
     :terminal? (or (nil? workflow-run) (contains? #{:completed :failed :cancelled} status))
     :blocked? (= :blocked status)}))

(defn- interrupt-stopped-run!
  [ctx run-id]
  (when (workflow-statechart-runtime/workflow-stopped? ctx run-id)
    (throw (InterruptedException. (str "Workflow execution stopped: " run-id)))))

(defn- execute-statechart!
  [ctx parent-session-id run-id event]
  (try
    (let [_ (interrupt-stopped-run! ctx run-id)
          wf-ctx (workflow-statechart-runtime/create-workflow-context ctx parent-session-id run-id)
          _ (workflow-statechart-runtime/send-and-drain! wf-ctx (:wm wf-ctx) event nil)
          workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
      (execution-result run-id workflow-run))
    (catch InterruptedException _
      (Thread/interrupted)
      (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)]
        (execution-result run-id workflow-run)))))

(defn execute-run!
  "Execute a workflow run via the Phase A hierarchical statechart runtime.

   Returns {:run-id ... :status ... :steps-executed [...] :terminal? bool :blocked? bool}."
  [ctx parent-session-id run-id]
  (execute-statechart! ctx parent-session-id run-id :workflow/start))

(defn resume-and-execute-run!
  "Resume a blocked run and continue execution via the Phase A statechart runtime."
  [ctx parent-session-id run-id]
  (execute-statechart! ctx parent-session-id run-id :workflow/resume))
