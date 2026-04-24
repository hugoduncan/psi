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
   [psi.agent-session.prompt-control :as prompt-control]
   [psi.agent-session.prompt-recording :as prompt-recording]
   [psi.agent-session.session-state :as session-state]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.tool-defs :as tool-defs]
   [psi.agent-session.workflow-attempts :as workflow-attempts]
   [psi.agent-session.workflow-progression :as workflow-progression]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

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

(defn- assistant-message-text
  [assistant-message]
  (or (some->> (:content assistant-message)
               (filter map?)
               (keep (fn [block]
                       (when (= :text (:type block))
                         (:text block))))
               seq
               (clojure.string/join "\n"))
      (when (string? (:content assistant-message))
        (:content assistant-message))
      ""))

(defn- assistant-error-message
  [assistant-message]
  (or (:error-message assistant-message)
      (some->> (:content assistant-message)
               (filter map?)
               (keep (fn [block]
                       (when (= :error (:type block))
                         (:text block))))
               seq
               (clojure.string/join "\n"))
      "Assistant turn ended in error"))

(defn- assistant-turn-classification
  [assistant-message]
  (prompt-recording/classify-assistant-message assistant-message))

(defn- execution-failure-payload
  [execution-session-id assistant-message]
  (let [{:keys [turn/outcome]} (assistant-turn-classification assistant-message)]
    (cond-> {:message (assistant-error-message assistant-message)}
      (:stop-reason assistant-message)
      (assoc :stop-reason (:stop-reason assistant-message))

      (= :turn.outcome/error outcome)
      (assoc :turn-outcome outcome)

      execution-session-id
      (assoc :session-id execution-session-id))))

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

(defn execute-current-step!
  "Execute the current workflow step as one bounded child-session attempt.

   Returns {:run-id ... :step-id ... :attempt-id ... :execution-session-id ... :status ...}
   after creating the attempt, prompting the child session, and recording the
   resulting canonical text envelope.

   Current slice treats the last assistant message text as canonical step output
   under `{:outcome :ok :outputs {:text ...}}`."
  [ctx parent-session-id run-id]
  (let [workflow-run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
        step-id      (:current-step-id workflow-run)
        {:keys [prompt]} (step-prompt workflow-run step-id)
        step-config  (resolve-step-session-config ctx parent-session-id workflow-run step-id)
        {:keys [attempt execution-session]}
        (workflow-attempts/create-step-attempt-session!
         ctx
         parent-session-id
         (cond-> {:workflow-run-id run-id
                  :workflow-step-id step-id
                  :session-name (str "workflow " step-id " attempt")
                  :tool-defs (:tool-defs step-config)
                  :thinking-level (:thinking-level step-config)}
           (:system-prompt step-config)
           (assoc :system-prompt (:system-prompt step-config))

           (:skills step-config)
           (assoc :skills (:skills step-config))

           (:model step-config)
           (assoc :model (:model step-config))))]
    (swap! (:state* ctx)
           (fn [state]
             (-> state
                 (update-in [:workflows :runs run-id]
                            #(workflow-attempts/append-attempt-to-run % step-id attempt))
                 (workflow-progression/start-latest-attempt run-id step-id))))
    (try
      (prompt-control/prompt-in! ctx (:session-id execution-session) prompt)
      (let [assistant-message (prompt-control/last-assistant-message-in ctx (:session-id execution-session))
            {:keys [turn/outcome]} (assistant-turn-classification assistant-message)
            failure-payload   (when (= :turn.outcome/error outcome)
                                (execution-failure-payload (:session-id execution-session) assistant-message))]
        (if failure-payload
          (do
            (swap! (:state* ctx) workflow-progression/record-execution-failure run-id step-id failure-payload)
            {:run-id run-id
             :step-id step-id
             :attempt-id (:attempt-id attempt)
             :execution-session-id (:session-id execution-session)
             :status (get-in @(:state* ctx) [:workflows :runs run-id :status])
             :error (:message failure-payload)})
          (let [envelope {:outcome :ok
                          :outputs {:text (assistant-message-text assistant-message)}}]
            (swap! (:state* ctx) workflow-progression/submit-result-envelope run-id step-id envelope)
            {:run-id run-id
             :step-id step-id
             :attempt-id (:attempt-id attempt)
             :execution-session-id (:session-id execution-session)
             :status (get-in @(:state* ctx) [:workflows :runs run-id :status])})))
      (catch Exception e
        (swap! (:state* ctx) workflow-progression/record-execution-failure run-id step-id {:message (ex-message e)})
        {:run-id run-id
         :step-id step-id
         :attempt-id (:attempt-id attempt)
         :execution-session-id (:session-id execution-session)
         :status (get-in @(:state* ctx) [:workflows :runs run-id :status])
         :error (ex-message e)}))))

(defn execute-run!
  "Execute a sequential workflow run until it reaches a terminal or blocked status.

   Returns {:run-id ... :status ... :steps-executed [...] :terminal? bool :blocked? bool}."
  [ctx parent-session-id run-id]
  (loop [steps-executed []]
    (let [run (workflow-runtime/workflow-run-in @(:state* ctx) run-id)
          status (:status run)]
      (cond
        (contains? #{:completed :failed :cancelled} status)
        {:run-id run-id
         :status status
         :steps-executed steps-executed
         :terminal? true
         :blocked? false}

        (= :blocked status)
        {:run-id run-id
         :status status
         :steps-executed steps-executed
         :terminal? false
         :blocked? true}

        :else
        (let [step-result (execute-current-step! ctx parent-session-id run-id)]
          (recur (conj steps-executed (select-keys step-result [:step-id :attempt-id :execution-session-id :status :error]))))))))

(defn resume-and-execute-run!
  "Resume a blocked run and continue sequential execution.

   Resuming clears blocked state via pure progression; the next loop iteration
   creates a fresh attempt for the current step before executing it."
  [ctx parent-session-id run-id]
  (swap! (:state* ctx) workflow-progression/resume-blocked-run run-id)
  (execute-run! ctx parent-session-id run-id))
