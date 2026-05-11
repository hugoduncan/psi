(ns psi.workflow-step-session-config.core
  "Workflow child-session config shaping helpers for canonical deterministic
   workflow runs.

   Owns lower workflow-domain session-config behavior: parent session lookup,
   inherited tool/skill/model shaping, workflow meta merge rules, and final
   child-session prompt/config derivation."
  (:require
   [psi.tool-registry.defs :as tool-defs]
   [psi.workflow-registry.registry :as registry]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]
   [psi.workflow-runtime.statechart :as workflow-statechart]))

(defn- effective-step-def
  [workflow-run step-id]
  (get (workflow-statechart/effective-steps (:effective-definition workflow-run)) step-id))

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

(defn- placeholder-skill
  [skill-name]
  {:name skill-name
   :description ""
   :file-path ""
   :base-dir ""
   :source :project
   :disable-model-invocation false})

(defn- resolve-step-skills
  [ctx session-skills skill-config]
  (when (some? skill-config)
    (mapv (fn [skill]
            (cond
              (map? skill) skill
              (string? skill)
              (or (execution-adapter/find-skill ctx session-skills skill)
                  (placeholder-skill skill))
              :else skill))
          skill-config)))

(defn- resolve-step-tool-defs
  [session-tool-defs tool-config]
  (when (some? tool-config)
    (mapv (fn [tool]
            (cond
              (map? tool)
              (tool-defs/normalize-tool-def tool)

              (string? tool)
              (or (some #(when (= tool (:name %)) %) session-tool-defs)
                  (tool-defs/normalize-tool-def {:name tool}))

              :else tool))
          tool-config)))

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

(defn- resolved-logprob-config
  [session-spec]
  (let [enabled? (true? (:logprobs session-spec))]
    (cond-> {:logprobs enabled?}
      (and enabled? (contains? session-spec :top-logprobs))
      (assoc :top-logprobs (:top-logprobs session-spec)))))

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
        authoritative-parent-session-id (or parent-session-id
                                            (:parent-session-id workflow-run)
                                            (some->> (execution-adapter/list-context-sessions ctx) first :session-id))
        parent-session (execution-adapter/get-session-data ctx authoritative-parent-session-id)
        parent-session-model (:model parent-session)
        parent-session-prompt-mode (:prompt-mode parent-session)
        session-skills (vec (or (:skills parent-session) []))
        session-tool-defs (vec (or (:tool-defs parent-session) []))
        session-spec (:session step-def)
        developer-prompt (or (:system-prompt session-spec)
                             (:system-prompt base-meta))]
    (merge
     {:developer-prompt (compose-system-prompt developer-prompt framing-prompt)
      :prompt-mode parent-session-prompt-mode
      :response-mode (or (:response-mode session-spec) :streaming)
      :tool-defs (resolve-step-tool-defs session-tool-defs (:tools session-spec))
      :thinking-level (or (:thinking-level session-spec)
                          (:thinking-level base-meta)
                          :off)
      :skills (resolve-step-skills ctx session-skills (:skills session-spec))
      :model (or (:model session-spec)
                 parent-session-model
                 (:model base-meta))
      :prompt-component-selection (:prompt-component-selection session-spec)}
     (resolved-logprob-config session-spec))))
