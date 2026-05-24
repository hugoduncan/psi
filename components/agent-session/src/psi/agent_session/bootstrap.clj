(ns psi.agent-session.bootstrap
  "Session bootstrap resource loading.
   Provides load-startup-resources-in! for registering templates, skills,
   tools, and extensions via dispatch. Called by app-runtime and tests."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as extension-runtime-fns]
   [psi.session-state.state :as ss]
   [psi.skill-registry.root-storage :as skill-storage]))

(defn load-startup-resources-in!
  "Load startup prompt templates, skills, tools, and extensions via direct
   dispatch calls and runtime extension loading.

   opts keys:
   :templates          — vector of prompt template maps
   :skills             — vector of skill maps
   :tools              — vector of tool maps
   :extension-paths    — vector of extension file paths
   :extension-targets  — vector of activation targets {:kind :path|:init-var ...}

   Returns {:prompt-count int :skill-count int :tool-count int :extension-results [result-map ...]}."
  [ctx session-id {:keys [templates skills tools extension-paths extension-targets]
                   :or   {templates [] skills [] tools [] extension-paths [] extension-targets []}}]
  (doseq [t templates]
    (session/dispatch-in! ctx :session/register-prompt-template
                          {:session-id session-id :template t}
                          {:origin :core}))
  (ss/apply-root-state-update-in! ctx
                                  (fn [root-state]
                                    (let [root-state' (skill-storage/ensure-skill-registry root-state)
                                          root-state'' (reduce (fn [state skill]
                                                                 (:root-state (skill-storage/set-skills-in-root-state state session-id [skill])))
                                                               root-state'
                                                               skills)]
                                      (assoc-in root-state''
                                                (conj (ss/session-data-path session-id) :skill-ids)
                                                (mapv :name skills)))))
  (doseq [tool tools]
    (session/dispatch-in! ctx :session/add-tool
                          {:session-id session-id :tool tool}
                          {:origin :core}))
  (let [path-results (mapv (fn [p]
                             (let [{:keys [loaded? error]} (ext-rt/add-extension-in! ctx session-id p)]
                               {:psi.extension/loaded? loaded?
                                :psi.extension/path    p
                                :psi.extension/error   error}))
                           extension-paths)
        runtime-fns  (extension-runtime-fns/make-extension-runtime-fns ctx session-id nil)
        init-results (mapv (fn [{:keys [id init-var]}]
                             (let [{:keys [extension error]}
                                   (ext/load-extension-init-in! (:extension-registry ctx)
                                                                id
                                                                init-var
                                                                runtime-fns)]
                               {:psi.extension/loaded? (some? extension)
                                :psi.extension/path    id
                                :psi.extension/error   error}))
                           (filter #(= :init-var (:kind %)) extension-targets))
        ext-results  (vec (concat path-results init-results))]
    {:prompt-count      (count (:prompt-templates (ss/get-session-data-in ctx session-id)))
     :skill-count       (count (:skill-ids (ss/get-session-data-in ctx session-id)))
     :tool-count        (count (:tools (agent/get-data-in (ss/agent-ctx-in ctx session-id))))
     :extension-results ext-results}))

;; bootstrap-in! and refresh-active-tools-in! removed (task 161).
;; Startup flow inlined into psi.app-runtime/adopt-startup-plan-into-session!.
;; Tests use load-startup-resources-in! + direct dispatch calls.
