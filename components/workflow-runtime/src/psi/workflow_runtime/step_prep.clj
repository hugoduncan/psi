(ns psi.workflow-runtime.step-prep
  "Shared pure-ish workflow step preparation helpers used by both the execution
   wrapper namespace and the Phase A statechart runtime. Centralizes step input
   materialization, prompt rendering, and child-session configuration shaping so
   prompt/config semantics stay aligned across workflow paths."
  (:require
   [psi.tool-registry.defs :as tool-defs]
   [psi.workflow-registry.registry :as registry]
   [psi.workflow-runtime.source-resolution :as workflow-source-resolution]
   [psi.workflow-runtime.statechart :as workflow-statechart]))

(defn- effective-step-def
  [workflow-run step-id]
  (get (workflow-statechart/effective-steps (:effective-definition workflow-run)) step-id))

(def binding-source-value workflow-source-resolution/resolve-binding-ref)

(defn materialize-step-inputs
  [workflow-run step-id]
  (let [step-def (effective-step-def workflow-run step-id)
        ir-template-vars (some->> (get-in step-def [:session :contributions])
                                  (filter #(= :template (:type %)))
                                  last
                                  :vars)]
    (into {}
          (map (fn [[var-name source-spec]]
                 [(keyword var-name) (workflow-source-resolution/apply-source-spec workflow-run source-spec)]))
          ir-template-vars)))

(def render-template-contribution
  workflow-source-resolution/render-template-contribution)

(defn- text-message
  [text]
  {:role "user"
   :content (str text)})

(defn- conversation-message?
  [x]
  (and (map? x)
       (string? (:role x))
       (contains? x :content)))

(defn- contribution-value->messages
  [value]
  (cond
    (nil? value)
    []

    (conversation-message? value)
    [value]

    (and (sequential? value)
         (every? conversation-message? value))
    (vec value)

    :else
    [(text-message value)]))

(defn- materialize-session-contribution
  [workflow-run contribution]
  (case (:type contribution)
    :source (contribution-value->messages
             (workflow-source-resolution/apply-source-spec workflow-run contribution))
    :template [(text-message
                (render-template-contribution workflow-run contribution))]
    []))

(defn materialize-step-session-conversation
  "Materialize canonical IR `:session :contributions` into ordered child-session
   conversation messages.

   Semantics:
   - `:template` contributions become synthetic user text messages
   - `:source` contributions preserve canonical conversation messages when the
     resolved value is already message-shaped, otherwise they become synthetic
     user text messages via deterministic stringification
   - author order is preserved exactly across contributions"
  [workflow-run step-id]
  (let [contributions (get-in (effective-step-def workflow-run step-id)
                              [:session :contributions])]
    (some->> contributions
             (mapcat #(materialize-session-contribution workflow-run %))
             vec
             not-empty)))

(defn- prompt-text-from-message
  [message]
  (when (= "user" (:role message))
    (let [content (:content message)]
      (cond
        (string? content)
        content

        (and (vector? content)
             (seq content)
             (every? #(= :text (:type %)) content))
        (apply str (map :text content))

        :else nil))))

(defn split-step-session-conversation
  "Split a materialized child-session conversation into canonical preloaded
   messages plus the final prompt text submitted through the normal prompt path.

   When the last materialized message is a user text message, it becomes the
   actual prompt submission and all prior messages preload the child session.
   Otherwise the whole conversation is preloaded and the execution prompt is the
   empty string so execution still routes through the canonical prompt path."
  [messages]
  (let [messages' (vec (or messages []))
        last-msg (peek messages')
        prompt (prompt-text-from-message last-msg)]
    (if (some? prompt)
      {:preloaded-messages (not-empty (pop messages'))
       :prompt prompt}
      {:preloaded-messages (not-empty messages')
       :prompt ""})))

(defn step-prompt
  [workflow-run step-id]
  (let [step-inputs (materialize-step-inputs workflow-run step-id)
        session-conversation (materialize-step-session-conversation workflow-run step-id)]
    {:step-inputs step-inputs
     :prompt (:prompt (split-step-session-conversation session-conversation))}))

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
  (let [session-skills (vec (or (:skills ((:get-session-data-fn ctx) ctx parent-session-id)) []))]
    (when (some? skill-config)
      (mapv (fn [skill]
              (cond
                (map? skill) skill
                (string? skill)
                (or ((:find-skill-fn ctx) session-skills skill)
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
  (let [session-tool-defs (vec (or (:tool-defs ((:get-session-data-fn ctx) ctx parent-session-id)) []))]
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

(defn- step-meta-for
  [ctx workflow-run step-id]
  (let [step-def (effective-step-def workflow-run step-id)
        run-meta (or (get-in workflow-run [:effective-definition :workflow-file-meta]) {})
        source-definition-id (:source-definition-id workflow-run)
        source-definition (when source-definition-id
                            (registry/workflow-definition @(:state* ctx) source-definition-id))
        source-meta (or (get-in source-definition [:workflow-file-meta]) {})
        base-meta (merge source-meta run-meta)
        framing-prompt (:framing-prompt run-meta)]
    {:step-def step-def
     :base-meta base-meta
     :framing-prompt framing-prompt}))

(defn resolve-step-session-config
  "Resolve child session configuration for a workflow step.

   For single-step workflows, uses the run's own :workflow-file-meta.
   For multi-step workflows, looks up the referenced workflow's definition from
   registered definitions to get that step's :workflow-file-meta.

   Prompt semantics:
   - workflow-authored prompt text is resolved here as a composed instruction /
     developer layer for the child session
   - it is not the implicit full replacement for the child base system prompt
   - the child base system prompt is still rebuilt from structured session state
     downstream during child-session initialization"
  [ctx parent-session-id workflow-run step-id]
  (let [{:keys [step-def base-meta framing-prompt]} (step-meta-for ctx workflow-run step-id)
        parent-session-id (or parent-session-id
                              (some->> ((:list-context-sessions-fn ctx) ctx) first :session-id))
        parent-session ((:get-session-data-fn ctx) ctx parent-session-id)
        parent-session-model (:model parent-session)
        session-spec (:session step-def)
        developer-prompt (or (:system-prompt session-spec)
                             (:system-prompt base-meta))]
    {:developer-prompt (compose-system-prompt developer-prompt framing-prompt)
     :prompt-mode (:prompt-mode parent-session)
     :tool-defs (resolve-step-tool-defs ctx parent-session-id (:tools session-spec))
     :thinking-level (or (:thinking-level session-spec)
                         (:thinking-level base-meta)
                         :off)
     :skills (resolve-step-skills ctx parent-session-id (:skills session-spec))
     :model (or (:model session-spec)
                (:model base-meta)
                parent-session-model)
     :prompt-component-selection (:prompt-component-selection session-spec)}))
