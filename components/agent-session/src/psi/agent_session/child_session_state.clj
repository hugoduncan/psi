(ns psi.agent-session.child-session-state
  "Higher-level child-session initialization that intentionally remains above the
   lower `psi.session-state` boundary because it derives prompt/tool/skill state
   through agent-session prompt assembly semantics."
  (:require
   [psi.prompt-assets.system-prompt]
   [psi.prompt-registry.root-storage :as prompt-storage]
   [psi.session-persistence.core :as persistence]
   [psi.session-state.init :as init]
   [psi.session-state.model :as session-data]
   [psi.session-state.state :as state]
   [psi.skill-registry.root-storage :as skill-storage]
   [psi.tool-registry.defs :as tool-defs]))

;;; Child-session field inheritance classification
;;;
;;; The init.clj lifecycle paths (new, resume, fork) use shared constants
;;; (common-inherited-fields, prompt-state-fields, model-identity-fields) to
;;; compose their select-keys vectors. The child-session path constructs fields
;;; explicitly with per-field logic (fallbacks, derivation, opts), so it does
;;; not use select-keys composition. This comment documents the child-session's
;;; relationship to those three constant groups.
;;;
;;; common-inherited-fields (19 keys in init.clj):
;;;   Inherited from parent (9 of 19):
;;;     :skill-ids              — derived via derive-child-prompt-state from parent skills
;;;     :tool-ids               — derived via derive-child-prompt-state (or explicit child opts)
;;;     :prompt-contribution-ids — resolved from parent via prompt-storage/prompt-ids
;;;     :prompt-mode            — non-workflow: (or prompt-mode (:prompt-mode parent-sd));
;;;                               workflow-owned: snapshot value, else initial-session default — no live parent-sd fallback (task 207, R4)
;;;     :speed-mode             — non-workflow: (or speed-mode (:speed-mode parent-sd));
;;;                               workflow-owned: snapshot value, else initial-session default (task 207, R4)
;;;     :effort-override        — non-workflow: (or effort-override (:effort-override parent-sd));
;;;                               workflow-owned: snapshot value, else initial-session default (task 207, R4)
;;;     :developer-prompt       — (or developer-prompt (:developer-prompt parent-sd))
;;;     :developer-prompt-source — (or developer-prompt-source (:developer-prompt-source parent-sd))
;;;     :cache-breakpoints      — (or cache-breakpoints (:cache-breakpoints parent-sd) default)
;;;   Not inherited — intentional defaults (10 of 19):
;;;     :nucleus-prelude-override — consumed during prompt derivation inside
;;;                                 default-child-system-prompt-build-opts; flows into the child's
;;;                                 system-prompt-build-opts rather than being carried as a standalone field
;;;     :prompt-templates       — child sessions don't inherit registered prompt templates (default [])
;;;     :extensions             — child sessions don't inherit active extensions (default {})
;;;     :auto-retry-enabled     — child sessions use config default, not parent's setting
;;;     :auto-compaction-enabled — child sessions default to false (ephemeral, no compaction)
;;;     :scoped-models          — child sessions don't inherit per-scope model overrides (default [])
;;;     :tool-output-overrides  — child sessions don't inherit per-tool output limits (default {})
;;;     :ui-type                — child sessions default to :console (agent-driven, not user-facing)
;;;     :context-tokens         — runtime-derived, starts nil
;;;     :context-window         — runtime-derived, starts nil
;;;
;;; prompt-state-fields (4 keys in init.clj):
;;;   All 4 are derived (not carried as-is from parent):
;;;     :base-system-prompt         — derived via derive-child-prompt-state
;;;     :system-prompt              — derived via derive-child-prompt-state
;;;     :system-prompt-build-opts   — derived via default-child-system-prompt-build-opts
;;;     :prompt-component-selection — normalized from child opts via derive-child-prompt-state
;;;
;;; model-identity-fields (2 keys in init.clj):
;;;     :model          — non-workflow: (or model (:model parent-sd)) — falls back to parent;
;;;                       workflow-owned: snapshot value, else initial-session default — no live parent-sd fallback (task 207, R4)
;;;     :thinking-level — (or thinking-level :off) — defaults to :off, not direct parent inheritance

(defn- default-child-system-prompt-build-opts
  [parent-sd resolved-tool-defs resolved-skills normalized-selection]
  (let [cwd (:worktree-path parent-sd)
        base-opts (merge {:cwd                       cwd
                          :context-files             (when cwd
                                                       (psi.prompt-assets.system-prompt/discover-context-files cwd))
                          :tool-defs                 resolved-tool-defs
                          :selected-tools            (mapv :name resolved-tool-defs)
                          :skills                    resolved-skills
                          :prompt-mode               (:prompt-mode parent-sd :lambda)
                          :nucleus-prelude-override  (:nucleus-prelude-override parent-sd)}
                         (:system-prompt-build-opts parent-sd))]
    (cond-> base-opts
      normalized-selection
      (assoc :include-preamble?         (:include-preamble? normalized-selection)
             :include-runtime-metadata? (:include-runtime-metadata? normalized-selection)
             :include-context-files?    (:include-context-files? normalized-selection)))))

(defn- parent-tool-source
  "Get the tool-source (all known tool-def maps) from the parent session's agent data."
  [root-state parent-sd]
  (some-> (get-in root-state [:agent-session :sessions (:session-id parent-sd) :agent-ctx])
          :data-atom deref :tools))

(defn- derive-child-prompt-state
  [root-state parent-sd {:keys [system-prompt tool-ids prompt-component-selection skills]}]
  (let [normalized-selection (psi.prompt-assets.system-prompt/normalize-prompt-component-selection prompt-component-selection)
        tool-source          (parent-tool-source root-state parent-sd)
        child-tool-ids       (or tool-ids (:tool-ids parent-sd))
        parent-tool-defs     (tool-defs/resolve-tool-defs tool-source child-tool-ids)
        explicit-skills?     (some? skills)
        parent-skills        (if explicit-skills?
                               (vec skills)
                               (skill-storage/all-skills root-state parent-sd))
        resolved-tool-defs   (if normalized-selection
                               (psi.prompt-assets.system-prompt/filter-tool-defs
                                parent-tool-defs
                                normalized-selection)
                               (vec (or parent-tool-defs [])))
        resolved-skills      (if normalized-selection
                               (psi.prompt-assets.system-prompt/filter-skills
                                parent-skills
                                normalized-selection)
                               (vec (or parent-skills [])))
        root-state*          (if explicit-skills?
                               (:root-state (skill-storage/set-skills-in-root-state
                                             (skill-storage/ensure-skill-registry root-state)
                                             (:session-id parent-sd)
                                             resolved-skills))
                               root-state)
        build-opts           (default-child-system-prompt-build-opts
                              parent-sd resolved-tool-defs resolved-skills normalized-selection)
        resolved-base-prompt (or system-prompt
                                 (psi.prompt-assets.system-prompt/build-system-prompt build-opts)
                                 (:base-system-prompt parent-sd))]
    {:root-state                 root-state*
     :prompt-component-selection normalized-selection
     :tool-ids                   (mapv :name resolved-tool-defs)
     :skill-ids                  (mapv :name resolved-skills)
     :system-prompt-build-opts   build-opts
     :base-system-prompt         resolved-base-prompt
     :system-prompt              (or system-prompt resolved-base-prompt (:system-prompt parent-sd))}))

(defn- child-session-base-state*
  [root-state parent-sd {:keys [child-session-id session-name thinking-level speed-mode effort-override temperature model prompt-mode response-mode logprobs top-logprobs developer-prompt developer-prompt-source cache-breakpoints workflow-run-id workflow-step-id workflow-attempt-id workflow-owned?] :as child-opts}]
  (let [{:keys [root-state prompt-component-selection tool-ids skill-ids system-prompt-build-opts base-system-prompt system-prompt]}
        (derive-child-prompt-state root-state parent-sd child-opts)
        normalized-developer-prompt-source (let [source (or developer-prompt-source (:developer-prompt-source parent-sd))]
                                             (when (not= :fallback source)
                                               source))
        workflow-owned?' (boolean workflow-owned?)
        ;; Workflow inherited-defaults snapshot isolation (task 207, R4):
        ;; for workflow-owned children the snapshot-governed inherited fields
        ;; (:model :prompt-mode :speed-mode :effort-override) come from the
        ;; resolver's already-snapshotted value and must NOT fall back to the
        ;; LIVE parent session. parent-sd is read mid-run in :session/create-child
        ;; (session_lifecycle.clj), so a nil-at-invoke snapshot value falling back
        ;; to parent-sd would leak a post-invoke live mutation, breaking
        ;; Decision 2 / AC3. The supplied (snapshot) value is authoritative
        ;; (explicit override precedence preserved by the resolver); when it is
        ;; nil the field uses the fresh initial-session default, never the live
        ;; parent. Non-workflow children keep the live parent-sd fallback.
        defaults (session-data/initial-session)
        inherited-default (fn [supplied parent-value default-value]
                            (if workflow-owned?'
                              (or supplied default-value)
                              (or supplied parent-value)))
        child-model (inherited-default model (:model parent-sd) (:model defaults))
        child-prompt-mode (inherited-default prompt-mode (:prompt-mode parent-sd) (:prompt-mode defaults))
        child-speed-mode (inherited-default speed-mode (:speed-mode parent-sd) (:speed-mode defaults))
        child-effort-override (inherited-default effort-override (:effort-override parent-sd) (:effort-override defaults))
        ts (java.time.Instant/now)
        session-data
        (merge (session-data/initial-session
                {:worktree-path (:worktree-path parent-sd)})
               (cond-> {:session-id                 child-session-id
                        :session-name               session-name
                        :spawn-mode                 :agent
                        :parent-session-id          (:session-id parent-sd)
                        :workflow-run-id            workflow-run-id
                        :workflow-step-id           workflow-step-id
                        :workflow-attempt-id        workflow-attempt-id
                        :workflow-owned?            workflow-owned?'
                        :response-mode              response-mode
                        :logprobs-enabled           (boolean logprobs)
                        :system-prompt              system-prompt
                        :base-system-prompt         base-system-prompt
                        :prompt-mode                child-prompt-mode
                        :developer-prompt           (or developer-prompt (:developer-prompt parent-sd))
                        :developer-prompt-source    normalized-developer-prompt-source
                        :thinking-level             (or thinking-level :off)
                        :tool-ids                   tool-ids
                        :skill-ids                  skill-ids
                        :system-prompt-build-opts   system-prompt-build-opts
                        :cache-breakpoints          (or cache-breakpoints
                                                        (:cache-breakpoints parent-sd)
                                                        (:cache-breakpoints (session-data/initial-session)))
                        :prompt-component-selection prompt-component-selection
                        :prompt-contribution-ids    (prompt-storage/prompt-ids parent-sd)
                        :model                      child-model
                        :created-at                 ts
                        :updated-at                 ts}
                 (some? top-logprobs)
                 (assoc :top-logprobs top-logprobs)

                 ;; Workflow inherited-defaults snapshot (task 207): apply the
                 ;; resolved speed-mode/effort-override. Non-workflow children
                 ;; fall back to the parent session's value when no override is
                 ;; supplied; workflow children use the snapshot value only (R4).
                 (some? child-speed-mode)
                 (assoc :speed-mode child-speed-mode)

                 (some? child-effort-override)
                 (assoc :effort-override child-effort-override)

                 (some? temperature)
                 (assoc :temperature temperature)))]
    {:root-state root-state
     :session-data session-data}))

(defn child-session-base-state
  [root-state parent-sd child-opts]
  (:session-data (child-session-base-state* root-state parent-sd child-opts)))

(defn initialize-child-session-state
  [state* parent-sd {:keys [child-session-id preloaded-messages] :as child-opts}]
  (let [{:keys [root-state session-data]} (child-session-base-state* state* parent-sd child-opts)]
    (-> root-state
        (assoc-in (state/session-data-path child-session-id) session-data)
        (assoc-in [:agent-session :sessions child-session-id :persistence]
                  (persistence/persistence-state
                   {:journal (mapv persistence/message-entry (or preloaded-messages []))}))
        (init/initialize-session-slots child-session-id []))))
