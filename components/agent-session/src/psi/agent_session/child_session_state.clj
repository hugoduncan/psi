(ns psi.agent-session.child-session-state
  "Higher-level child-session initialization that intentionally remains above the
   lower `psi.session-state` boundary because it derives prompt/tool/skill state
   through agent-session prompt assembly semantics."
  (:require
   [psi.prompt-assets.system-prompt]
   [psi.session-persistence.core :as persistence]
   [psi.session-state.init :as init]
   [psi.session-state.model :as session-data]
   [psi.session-state.state :as state]))

(defn- default-child-system-prompt-build-opts
  [parent-sd resolved-tool-defs resolved-skills normalized-selection]
  (let [cwd (:worktree-path parent-sd)
        base-opts (merge {:cwd                       cwd
                          :context-files             (when cwd
                                                       (psi.prompt-assets.system-prompt/discover-context-files cwd))
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

(defn- derive-child-prompt-state
  [parent-sd {:keys [system-prompt tool-defs prompt-component-selection skills]}]
  (let [normalized-selection (psi.prompt-assets.system-prompt/normalize-prompt-component-selection prompt-component-selection)
        parent-tool-defs     (or tool-defs (:tool-defs parent-sd))
        parent-skills        (or skills (:skills parent-sd))
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
        build-opts           (default-child-system-prompt-build-opts
                              parent-sd resolved-tool-defs resolved-skills normalized-selection)
        resolved-base-prompt (or system-prompt
                                 (psi.prompt-assets.system-prompt/build-system-prompt build-opts)
                                 (:base-system-prompt parent-sd))]
    {:prompt-component-selection normalized-selection
     :tool-defs                  resolved-tool-defs
     :skills                     resolved-skills
     :system-prompt-build-opts   build-opts
     :base-system-prompt         resolved-base-prompt
     :system-prompt              (or system-prompt resolved-base-prompt (:system-prompt parent-sd))}))

(defn child-session-base-state
  [parent-sd {:keys [child-session-id session-name thinking-level model prompt-mode developer-prompt developer-prompt-source cache-breakpoints workflow-run-id workflow-step-id workflow-attempt-id workflow-owned?] :as child-opts}]
  (let [{:keys [prompt-component-selection tool-defs skills system-prompt-build-opts base-system-prompt system-prompt]}
        (derive-child-prompt-state parent-sd child-opts)
        normalized-developer-prompt-source (let [source (or developer-prompt-source (:developer-prompt-source parent-sd))]
                                             (when (not= :fallback source)
                                               source))
        ts (java.time.Instant/now)]
    (merge (session-data/initial-session
            {:worktree-path (:worktree-path parent-sd)})
           {:session-id                 child-session-id
            :session-name               session-name
            :spawn-mode                 :agent
            :parent-session-id          (:session-id parent-sd)
            :workflow-run-id            workflow-run-id
            :workflow-step-id           workflow-step-id
            :workflow-attempt-id        workflow-attempt-id
            :workflow-owned?            (boolean workflow-owned?)
            :system-prompt              system-prompt
            :base-system-prompt         base-system-prompt
            :prompt-mode                (or prompt-mode (:prompt-mode parent-sd))
            :developer-prompt           (or developer-prompt (:developer-prompt parent-sd))
            :developer-prompt-source    normalized-developer-prompt-source
            :thinking-level             (or thinking-level :off)
            :tool-defs                  tool-defs
            :skills                     skills
            :system-prompt-build-opts   system-prompt-build-opts
            :cache-breakpoints          (or cache-breakpoints
                                            (:cache-breakpoints parent-sd)
                                            (:cache-breakpoints (session-data/initial-session)))
            :prompt-component-selection prompt-component-selection
            :prompt-contributions       (vec (or (:prompt-contributions parent-sd) []))
            :model                      (or model (:model parent-sd))
            :created-at                 ts
            :updated-at                 ts})))

(defn initialize-child-session-state
  [state* parent-sd {:keys [child-session-id preloaded-messages] :as child-opts}]
  (let [child-sd (child-session-base-state parent-sd child-opts)]
    (-> state*
        (assoc-in (state/session-data-path child-session-id) child-sd)
        (assoc-in [:agent-session :sessions child-session-id :persistence]
                  (persistence/persistence-state
                   {:journal (mapv persistence/message-entry (or preloaded-messages []))}))
        (init/initialize-session-slots child-session-id []))))
