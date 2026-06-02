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
        ;; Inherited-defaults snapshot, captured at invoke time (task 207).
        ;; When present, the seven inherited defaults are sourced from the
        ;; snapshot instead of live parent reads (per-field source swap, P5);
        ;; when absent (pre-existing runs) the live-read path is retained (AC 6).
        snapshot (:inherited-defaults workflow-run)
        snapshot? (some? snapshot)
        ;; The live parent read is only consumed by the no-snapshot
        ;; else-branches (R1); gate it on snapshot? so the snapshot path
        ;; performs no live parent re-read, matching the isolation intent.
        parent-session (when-not snapshot?
                         (execution-adapter/get-session-data ctx authoritative-parent-session-id))
        ;; Single source for the seven inherited defaults (CS1): each field is
        ;; resolved once, from the snapshot when present (isolation intent) or
        ;; the live parent otherwise. Downstream code reads `inherited` rather
        ;; than re-expressing the snapshot-vs-live choice per field, so the
        ;; `inherited-defaults-snapshot-keys` set is consumed as one unit and
        ;; adding/removing an inherited field touches this map alone.
        ;; `:tool-defs`/`:skills` are the resolved name-resolution POOLS.
        ;;
        ;; The live-read branch keeps the pre-task non-inheritance of
        ;; :thinking-level/:speed-mode/:effort-override (AC6 back-compat:
        ;; snapshot-less runs emit no speed/effort and fall thinking-level back
        ;; to base-meta/:off — only the snapshot path carries these three;
        ;; I1/P2), so it omits those keys (absent ⇒ nil in the consumers).
        inherited (if snapshot?
                    (select-keys snapshot inherited-defaults-snapshot-keys)
                    {:model (:model parent-session)
                     :prompt-mode (:prompt-mode parent-session)
                     :skills (skill-storage/all-skills @(:state* ctx) parent-session)
                     :tool-defs (let [tool-source (ss/agent-tool-source-in ctx authoritative-parent-session-id)]
                                  (tool-defs/resolve-tool-defs tool-source (:tool-ids parent-session)))})
        ;; parent-session-model is the inherited model WHOLESALE (P4): all four
        ;; consumers (step override, base-meta override, no-override fallback,
        ;; model-query selection context) see it.
        parent-session-model (:model inherited)
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
       :prompt-mode (:prompt-mode inherited)
       :response-mode (or (:response-mode session-spec) :streaming)
       :tool-defs (resolve-step-tool-defs (:tool-defs inherited) (:tools session-spec))
       ;; thinking-level precedence is uniform with :model (CS2): step override
       ;; → inherited default → base-meta → fallback, so the inherited parent
       ;; value dominates a static :workflow-file-meta default exactly as the
       ;; inherited model does (resolved-model cond), not the inverse.
       :thinking-level (or (:thinking-level session-spec)
                           (:thinking-level inherited)
                           (:thinking-level base-meta)
                           :off)
       :skills (resolve-step-skills ctx (:skills inherited) (:skills session-spec))
       :model resolved-model
       :prompt-component-selection (:prompt-component-selection session-spec)}
      (resolved-logprob-config session-spec))

      ;; speed-mode/effort-override flow from the inherited defaults into the
      ;; step's resolved config (the resolver emits neither today — I1/P2).
      (some? (:speed-mode inherited))
      (assoc :speed-mode (:speed-mode inherited))

      (some? (:effort-override inherited))
      (assoc :effort-override (:effort-override inherited))

      (contains? session-spec :temperature)
      (assoc :temperature (:temperature session-spec))

      model-fallback
      (assoc :model-fallback model-fallback))))

(defn resolve-inherited-defaults-snapshot
  "Top-level inherited-defaults snapshot resolver (Decisions 6a, 7a).

   Resolves the inheritable default session details from the live parent
   session at workflow-invoke time, producing the resolved snapshot map captured
   on the run's canonical state. Impure: performs ctx reads
   (`get-session-data`, `all-skills`, tool source resolution).

   The five model/prompt/tools/skills/thinking-level reads mirror
   `resolve-step-session-config`'s no-override path. It additionally reads
   `:speed-mode` and `:effort-override` from the parent session — two reads the
   resolver does not have today (Decision 1 / I1).

   Returns exactly `inherited-defaults-snapshot-keys`. `:model` is the parent's
   `{:provider :id}`-shaped value, copied verbatim (resolved P3)."
  [ctx parent-session-id]
  (let [parent-session (execution-adapter/get-session-data ctx parent-session-id)
        session-skills (skill-storage/all-skills @(:state* ctx) parent-session)
        tool-source (ss/agent-tool-source-in ctx parent-session-id)
        session-tool-defs (tool-defs/resolve-tool-defs tool-source (:tool-ids parent-session))]
    {:model (:model parent-session)
     :prompt-mode (:prompt-mode parent-session)
     :tool-defs session-tool-defs
     :skills session-skills
     :thinking-level (or (:thinking-level parent-session) :off)
     :speed-mode (:speed-mode parent-session)
     :effort-override (:effort-override parent-session)}))

(defn effective-config->snapshot
  "Nested inherited-defaults snapshot projection (Decisions 3, 7a).

   Pure projection: maps a delegating step's already-resolved effective config
   (run snapshot ⊕ step overrides, as produced by `resolve-step-session-config`)
   plus the parent run's snapshot into the inherited-defaults snapshot field set.
   No ctx reads.

   The five resolver-emitted inherited keys (`:model :prompt-mode :tool-defs
   :skills :thinking-level`) come from the effective config. `:speed-mode` and
   `:effort-override` come from `parent-snapshot` because
   `resolve-step-session-config` emits neither (resolved I1/P2) — a projection
   over the effective config alone would silently drop them under delegation.
   `:model` is the effective config's already `{:provider :id}`-shaped value
   (resolved P3)."
  [effective-config parent-snapshot]
  {:model (:model effective-config)
   :prompt-mode (:prompt-mode effective-config)
   :tool-defs (:tool-defs effective-config)
   :skills (:skills effective-config)
   :thinking-level (or (:thinking-level effective-config) :off)
   :speed-mode (:speed-mode parent-snapshot)
   :effort-override (:effort-override parent-snapshot)})
