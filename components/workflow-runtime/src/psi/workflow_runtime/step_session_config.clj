(ns psi.workflow-runtime.step-session-config
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

(defn- resolve-step-skills
  [ctx parent-session-id skill-config]
  (let [session-skills (vec (or (:skills (execution-adapter/get-session-data ctx parent-session-id)) []))]
    (when (some? skill-config)
      (mapv (fn [skill]
              (cond
                (map? skill) skill
                (string? skill)
                (or (execution-adapter/find-skill ctx session-skills skill)
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
  (let [session-tool-defs (vec (or (:tool-defs (execution-adapter/get-session-data ctx parent-session-id)) []))]
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
                              (some->> (execution-adapter/list-context-sessions ctx) first :session-id))
        parent-session (execution-adapter/get-session-data ctx parent-session-id)
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
