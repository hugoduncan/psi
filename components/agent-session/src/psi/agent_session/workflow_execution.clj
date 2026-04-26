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
   - resume a blocked run and continue execution with a fresh attempt"
  (:require
   [clojure.string :as str]
   [psi.agent-session.session-state :as session-state]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.tool-defs :as tool-defs]
   [psi.agent-session.workflow-runtime :as workflow-runtime]
   [psi.agent-session.workflow-statechart-runtime :as workflow-statechart-runtime]))

(defn- get-path*
  [m path]
  (reduce (fn [acc k]
            (when (some? acc)
              (get acc k)))
          m
          path))

(defn binding-source-value
  [workflow-run {:keys [source path]}]
  (case source
    :workflow-input
    (get-path* (:workflow-input workflow-run) path)

    :step-output
    (let [[step-id & more] path
          accepted-result  (get-in workflow-run [:step-runs step-id :accepted-result])]
      (get-path* accepted-result more))

    :workflow-runtime
    (get-path* {:run-id (:run-id workflow-run)
                :current-step-id (:current-step-id workflow-run)
                :status (:status workflow-run)}
               path)

    nil))

(defn materialize-step-inputs
  [workflow-run step-id]
  (let [bindings (get-in workflow-run [:effective-definition :steps step-id :input-bindings])]
    (reduce-kv (fn [acc k ref]
                 (assoc acc k (binding-source-value workflow-run ref)))
               {}
               (or bindings {}))))

(defn render-prompt-template
  [prompt-template step-inputs]
  (let [input-text    (or (:input step-inputs) "")
        original-text (or (:original step-inputs) "")]
    (-> (or prompt-template "$INPUT")
        (str/replace "$INPUT" (str input-text))
        (str/replace "$ORIGINAL" (str original-text)))))

(defn step-prompt
  [workflow-run step-id]
  (let [step-def     (get-in workflow-run [:effective-definition :steps step-id])
        step-inputs  (materialize-step-inputs workflow-run step-id)]
    {:step-inputs step-inputs
     :prompt     (render-prompt-template (:prompt-template step-def) step-inputs)}))

(defn- compose-system-prompt
  [base-system-prompt framing-prompt]
  (cond
    (and (seq base-system-prompt) (seq framing-prompt))
    (str base-system-prompt "\n\n" framing-prompt)

    (seq base-system-prompt)
    base-system-prompt

    (seq framing-prompt)
    framing-prompt

    :else nil))

(defn- resolve-step-skills
  [ctx parent-session-id skill-config]
  (let [session-skills (vec (or (:skills (session-state/get-session-data-in ctx parent-session-id)) []))]
    (when (some? skill-config)
      (mapv (fn [skill]
              (cond
                (map? skill) skill
                (string? skill)
                (or (skills/find-skill session-skills skill)
                    {:name skill
                     :description ""
                     :file-path ""
                     :base-dir ""
                     :source :project
                     :disable-model-invocation false})
                :else skill))
            skill-config))))

(defn- resolve-step-tool-defs
  [ctx parent-session-id tool-config]
  (let [session-tool-defs (vec (or (:tool-defs (session-state/get-session-data-in ctx parent-session-id)) []))]
    (when (some? tool-config)
      (mapv (fn [tool]
              (cond
                (map? tool)
                (tool-defs/normalize-tool-def tool)

                (string? tool)
                (or (some #(when (= tool (:name %)) %) session-tool-defs)
                    (tool-defs/normalize-tool-def {:name tool}))

                :else tool))
            tool-config))))

(defn resolve-step-session-config
  "Resolve child session configuration for a workflow step.

   For single-step workflows, uses the run's own :workflow-file-meta.
   For multi-step workflows, looks up the referenced workflow's definition
   from registered definitions to get that step's :workflow-file-meta.

   Returns a map with composed prompt/config for child session creation."
  ([ctx workflow-run step-id]
   (resolve-step-session-config ctx nil workflow-run step-id))
  ([ctx parent-session-id workflow-run step-id]
   (let [step-def  (get-in workflow-run [:effective-definition :steps step-id])
         profile   (get-in step-def [:executor :profile])
         run-meta  (get-in workflow-run [:effective-definition :workflow-file-meta])
         delegated-workflow? (and profile
                                  (not= profile (:definition-id (:effective-definition workflow-run))))
         step-meta (if delegated-workflow?
                     (let [ref-def (get-in @(:state* ctx)
                                           [:workflows :definitions profile])]
                       (or (:workflow-file-meta ref-def) {}))
                     (or run-meta {}))
         framing-prompt (when delegated-workflow? (:framing-prompt run-meta))
         parent-session-id (or parent-session-id
                               (some->> (session-state/list-context-sessions-in ctx) first :session-id))]
     {:base-system-prompt (:system-prompt step-meta)
      :framing-prompt framing-prompt
      :system-prompt  (compose-system-prompt (:system-prompt step-meta) framing-prompt)
      :tool-defs      (resolve-step-tool-defs ctx parent-session-id (:tools step-meta))
      :thinking-level (or (:thinking-level step-meta) :off)
      :skills         (resolve-step-skills ctx parent-session-id (:skills step-meta))
      :model          (:model step-meta)})))

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
