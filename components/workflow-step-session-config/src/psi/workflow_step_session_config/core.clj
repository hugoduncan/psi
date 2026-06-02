(ns psi.workflow-step-session-config.core
  "Workflow child-session config shaping helpers for canonical deterministic
   workflow runs.

   Owns lower workflow-domain session-config behavior: parent session lookup,
   inherited tool/skill/model shaping, workflow meta merge rules, and final
   child-session prompt/config derivation."
  (:require
   [psi.ai.model-registry :as model-registry]
   [psi.ai.model-selection :as model-selection]
   [psi.session-state.state :as ss]
   [psi.skill-registry.registry :as skill-registry]
   [psi.skill-registry.root-storage :as skill-storage]
   [psi.tool-registry.defs :as tool-defs]
   [psi.workflow-registry.registry :as registry]
   [psi.workflow-runtime.execution-adapter :as execution-adapter]
   [psi.workflow-runtime.statechart :as workflow-statechart]))

;;; Inherited-defaults snapshot field-set authority (Decision 8a).
;;;
;;; The workflow inherited-defaults snapshot is a narrow resolved-default set:
;;; the fields a step inherits when it gives no override of its own. Its source
;;; keys span two `session-state/init` authorities so the snapshot field list is
;;; validated against (not re-enumerated independently from) the canonical
;;; child-session inheritance constants.

(def inherited-defaults-source-keys
  "Authority source keys for the inherited-defaults snapshot (Decision 8a).

   `:from-common` keys must each be members of
   `session-init/common-inherited-fields`; `:from-model` keys must each be
   members of `session-init/model-identity-fields`. The resolved snapshot maps
   the raw `:tool-ids`/`:skill-ids` source keys to resolved `:tool-defs`/
   `:skills`."
  {:from-common #{:prompt-mode :speed-mode :effort-override :tool-ids :skill-ids}
   :from-model #{:model :thinking-level}})

(def inherited-defaults-snapshot-keys
  "The resolved key set produced by an inherited-defaults snapshot.

   Derived from `inherited-defaults-source-keys` with the resolved-vs-raw
   substitution `:tool-ids`→`:tool-defs`, `:skill-ids`→`:skills`."
  #{:model :prompt-mode :tool-defs :skills :thinking-level :speed-mode :effort-override})

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
    (skill-registry/all-skills
     (mapv (fn [skill]
             (cond
               (map? skill) skill
               (string? skill)
               (or (execution-adapter/find-skill ctx session-skills skill)
                   (placeholder-skill skill))
               :else skill))
           skill-config))))

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

(defn- explicit-model-id->session-model
  [model-id]
  (when-let [model (some (fn [candidate]
                           (when (= model-id (:id candidate))
                             candidate))
                         (model-registry/all-models-seq))]
    {:provider (name (:provider model))
     :id (:id model)}))

(defn- model-query->selection-request
  [model-spec parent-session-model]
  {:mode :resolve
   :required (vec (or (:require model-spec) []))
   :strong-preferences (vec (or (:prefer model-spec) []))
   :weak-preferences (vec (or (:weak-preferences model-spec) []))
   :context {:session-model {:provider (some-> (:provider parent-session-model) keyword)
                             :id (:id parent-session-model)}}})

(defn- candidate->session-model
  [candidate]
  {:provider (name (:provider candidate))
   :id (:id candidate)})

(defn- resolved-model-query
  [model-spec parent-session-model]
  (let [result (model-selection/resolve-selection
                {:request (model-query->selection-request model-spec parent-session-model)})
        ranked-candidates (mapv candidate->session-model (get-in result [:ranking :ranked]))]
    {:model (first ranked-candidates)
     :model-fallback {:type :ranked-model-candidates
                      :selection-outcome (:outcome result)
                      :selection-reason (:reason result)
                      :candidates ranked-candidates}}))

(defn- resolved-step-model-config
  [model-spec parent-session-model]
  (cond
    (nil? model-spec)
    {}

    (and (map? model-spec) (= :model-query (:type model-spec)))
    (resolved-model-query model-spec parent-session-model)

    (string? model-spec)
    {:model (or (explicit-model-id->session-model model-spec)
                model-spec)}

    :else
    {:model model-spec}))

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
        session-skills (skill-storage/all-skills @(:state* ctx) parent-session)
        tool-source (ss/agent-tool-source-in ctx authoritative-parent-session-id)
        session-tool-defs (tool-defs/resolve-tool-defs tool-source (:tool-ids parent-session))
        session-spec (:session step-def)
        developer-prompt (or (:system-prompt session-spec)
                             (:system-prompt base-meta))
        step-model-config (when (contains? session-spec :model)
                            (resolved-step-model-config (:model session-spec) parent-session-model))
        base-model-config (when (contains? base-meta :model)
                            (resolved-step-model-config (:model base-meta) parent-session-model))
        resolved-model (cond
                         (contains? step-model-config :model)
                         (:model step-model-config)

                         (contains? session-spec :model)
                         nil

                         parent-session-model
                         parent-session-model

                         (contains? base-model-config :model)
                         (:model base-model-config)

                         :else
                         (:model base-meta))
        model-fallback (or (:model-fallback step-model-config)
                           (:model-fallback base-model-config))]
    (cond->
     (merge
      {:developer-prompt (compose-system-prompt developer-prompt framing-prompt)
       :prompt-mode parent-session-prompt-mode
       :response-mode (or (:response-mode session-spec) :streaming)
       :tool-defs (resolve-step-tool-defs session-tool-defs (:tools session-spec))
       :thinking-level (or (:thinking-level session-spec)
                           (:thinking-level base-meta)
                           :off)
       :skills (resolve-step-skills ctx session-skills (:skills session-spec))
       :model resolved-model
       :prompt-component-selection (:prompt-component-selection session-spec)}
      (resolved-logprob-config session-spec))

      (contains? session-spec :temperature)
      (assoc :temperature (:temperature session-spec))

      model-fallback
      (assoc :model-fallback model-fallback))))
