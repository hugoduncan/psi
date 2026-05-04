(ns psi.agent-session.workflow-step-prep
  "Shared pure-ish workflow step preparation helpers used by both the execution
   wrapper namespace and the Phase A statechart runtime. Centralizes step input
   materialization, prompt rendering, and child-session configuration shaping so
   prompt/config semantics stay aligned across workflow paths."
  (:require
   [clojure.string :as str]
   [psi.agent-session.persistence :as persistence]
   [psi.agent-session.session-state :as session-state]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.tool-defs :as tool-defs]
   [psi.agent-session.workflow-judge :as workflow-judge]
   [psi.agent-session.workflow-statechart :as workflow-statechart]))

(defn- authored-step-def
  [workflow-run step-id]
  (get-in workflow-run [:effective-definition :steps step-id]))

(defn- effective-step-def
  [workflow-run step-id]
  (get (workflow-statechart/effective-steps (:effective-definition workflow-run)) step-id))

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
          accepted-result (get-in workflow-run [:step-runs step-id :accepted-result])]
      (get-path* accepted-result more))

    :workflow-runtime
    (get-path* {:run-id (:run-id workflow-run)
                :current-step-id (:current-step-id workflow-run)
                :status (:status workflow-run)}
               path)

    nil))

(defn- source-spec-value
  [workflow-run {:keys [from path]}]
  (let [base (cond
               (= :workflow-input from)
               (:workflow-input workflow-run)

               (= :workflow-original from)
               (or (get-in (:workflow-input workflow-run) [:original])
                   (:workflow-input workflow-run))

               (and (map? from) (:output from))
               (let [accepted (get-in workflow-run [:step-runs (:step from) :accepted-result])]
                 (case (:output from)
                   :result accepted
                   :final-llm-reply (get-in accepted [:outputs :text])
                   :transcript (get-in accepted [:outputs :transcript])
                   (get-in accepted [:outputs (:output from)])))

               (and (map? from) (:yield from))
               (let [accepted (get-in workflow-run [:step-runs (:step from) :accepted-result])]
                 (case (:yield from)
                   :text (get-in accepted [:outputs :text])
                   :data (get-in accepted [:outputs :data])
                   :reason (get-in accepted [:blocked :reason])
                   :message (get-in accepted [:blocked :message])
                   :details (get-in accepted [:blocked :details])
                   nil))

               :else nil)]
    (if (seq path)
      (get-path* base path)
      base)))

(defn materialize-step-inputs
  [workflow-run step-id]
  (let [step-def (effective-step-def workflow-run step-id)
        compat-bindings (:input-bindings step-def)
        ir-template-vars (some->> (get-in step-def [:session :contributions])
                                  (filter #(= :template (:type %)))
                                  last
                                  :vars)]
    (if (seq ir-template-vars)
      (into {}
            (map (fn [[var-name source-spec]]
                   [(keyword var-name) (source-spec-value workflow-run source-spec)]))
            ir-template-vars)
      (reduce-kv (fn [acc k ref]
                   (assoc acc k (binding-source-value workflow-run ref)))
                 {}
                 (or compat-bindings {})))))

(defn materialize-step-session-preload
  "Materialize compiled `:session-preload` entries into canonical child-session
   preloaded messages.

   Semantics:
   - value preload entries become synthetic messages using the compiled preload role
   - value preload is intentionally constrained to text-like projections (`:text`)
     so non-text values are not silently stringified from broader structures

   Canonical transcript/message source of truth:
   - value preload entries read from the workflow run's canonical binding sources
   - transcript preload entries read from the step execution session's canonical
     persisted journal via `persistence/messages-from-entries-in`
   - transcript projection is delegated to `workflow-judge/project-messages` so
     workflow judge and workflow step preload share one deterministic projection
     implementation"
  [ctx workflow-run step-id]
  (let [preload-spec (get-in workflow-run [:effective-definition :steps step-id :session-preload])]
    (when (seq preload-spec)
      (->> preload-spec
           (mapcat (fn [entry]
                     (case (:kind entry)
                       :value
                       (let [value (binding-source-value workflow-run (:binding entry))]
                         (if (some? value)
                           [{:role (:role entry)
                             :content (str value)}]
                           []))

                       :session-transcript
                       (let [attempts (get-in workflow-run [:step-runs (:step-id entry) :attempts])
                             session-id (some-> attempts last :execution-session-id)
                             messages (if session-id
                                        (vec (persistence/messages-from-entries-in ctx session-id))
                                        [])]
                         (workflow-judge/project-messages messages (:projection entry)))

                       [])))
           vec
           not-empty))))

(defn render-prompt-template
  [prompt-template step-inputs]
  (let [input-text (or (:input step-inputs) "")
        original-text (or (:original step-inputs) "")]
    (-> (or prompt-template "$INPUT")
        (str/replace "$INPUT" (str input-text))
        (str/replace "$ORIGINAL" (str original-text)))))

(defn- render-template-contribution
  [workflow-run contribution]
  (let [vars (:vars contribution)
        values (into {}
                     (map (fn [[var-name source-spec]]
                            [var-name (source-spec-value workflow-run source-spec)]))
                     vars)]
    (reduce-kv (fn [text var-name value]
                 (str/replace text
                              (str "{{" var-name "}}")
                              (str (or value ""))))
               (:text contribution)
               values)))

(defn step-prompt
  [workflow-run step-id]
  (let [step-def (effective-step-def workflow-run step-id)
        step-inputs (materialize-step-inputs workflow-run step-id)
        template-contribution (some->> (get-in step-def [:session :contributions])
                                       (filter #(= :template (:type %)))
                                       last)
        compat-prompt-template (:prompt-template step-def)]
    {:step-inputs step-inputs
     :prompt (if template-contribution
               (render-template-contribution workflow-run template-contribution)
               (render-prompt-template compat-prompt-template step-inputs))}))

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

(defn- step-meta-for
  [ctx workflow-run step-id]
  (let [step-def (effective-step-def workflow-run step-id)
        authored-step (authored-step-def workflow-run step-id)
        profile (or (get-in step-def [:compat :executor :profile])
                    (get-in step-def [:executor :profile]))
        run-meta (get-in workflow-run [:effective-definition :workflow-file-meta])
        delegated-workflow? (and profile
                                 (not= profile (:definition-id (:effective-definition workflow-run))))
        base-meta (if delegated-workflow?
                    (let [ref-def (get-in @(:state* ctx) [:workflows :definitions profile])]
                      (or (:workflow-file-meta ref-def) {}))
                    (or run-meta {}))
        framing-prompt (when delegated-workflow? (:framing-prompt run-meta))
        step-overrides (or (:session-overrides authored-step) {})]
    {:step-def step-def
     :base-meta base-meta
     :framing-prompt framing-prompt
     :step-overrides step-overrides}))

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
  (let [{:keys [base-meta framing-prompt step-overrides]} (step-meta-for ctx workflow-run step-id)
        parent-session-id (or parent-session-id
                              (some->> (session-state/list-context-sessions-in ctx) first :session-id))
        parent-session    (session-state/get-session-data-in ctx parent-session-id)
        parent-session-model (:model parent-session)
        developer-prompt  (or (:system-prompt step-overrides)
                              (:system-prompt base-meta))]
    {:developer-prompt (compose-system-prompt developer-prompt framing-prompt)
     :prompt-mode (:prompt-mode parent-session)
     :tool-defs (if (contains? step-overrides :tools)
                  (resolve-step-tool-defs ctx parent-session-id (:tools step-overrides))
                  (resolve-step-tool-defs ctx parent-session-id (:tools base-meta)))
     :thinking-level (if (contains? step-overrides :thinking-level)
                       (:thinking-level step-overrides)
                       (or (:thinking-level base-meta) :off))
     :skills (if (contains? step-overrides :skills)
               (resolve-step-skills ctx parent-session-id (:skills step-overrides))
               (resolve-step-skills ctx parent-session-id (:skills base-meta)))
     :model (or (:model step-overrides)
                (:model base-meta)
                parent-session-model)
     :prompt-component-selection (:prompt-component-selection step-overrides)}))
