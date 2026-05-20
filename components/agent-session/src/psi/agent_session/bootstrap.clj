(ns psi.agent-session.bootstrap
  "Session bootstrap orchestration.
   Applies startup wiring (prompts, tools, extensions) to a session context.
   Called by main.clj and tests, not by core.clj."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as extension-runtime-fns]
   [psi.session-state.state :as ss]
   [psi.tool-registry.registry :as tool-registry]))

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
  (doseq [s skills]
    (session/dispatch-in! ctx :session/register-skill
                          {:session-id session-id :skill s}
                          {:origin :core}))
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
     :skill-count       (count (:skills (ss/get-session-data-in ctx session-id)))
     :tool-count        (count (:tools (agent/get-data-in (ss/agent-ctx-in ctx session-id))))
     :extension-results ext-results}))

(defn refresh-active-tools-in!
  "Merge extension-registry tools into the session's active tool set.
   Called after resource loading to ensure extension-contributed tools
   are included alongside base and startup tools."
  [ctx session-id]
  (let [ext-tools    (tool-registry/all-tools-in (:extension-registry ctx))
        active-tools (:tools (agent/get-data-in (ss/agent-ctx-in ctx session-id)))]
    (session/dispatch-in! ctx
                          :session/set-active-tools
                          {:session-id session-id
                           :tool-maps (into (vec active-tools) ext-tools)}
                          {:origin :core})))

(defn bootstrap-in!
  "Reusable session bootstrap for CLI/TUI and tests.

   Applies startup wiring to the current session data without creating a new
   session branch.

   Steps:
   1) register base tools and set system prompt via dispatch
   2) load prompts/skills/tools/extensions via direct dispatch and runtime calls
   3) when startup extensions were loaded here, merge extension tools into active tools
   4) persist startup summary to :startup-bootstrap in session data

   opts keys:
   :base-tools             — base tool schema vector (default [])
   :system-prompt          — prompt string (default empty string)
   :developer-prompt       — optional developer instruction string (default nil)
   :developer-prompt-source — :fallback | :env | :explicit (default :fallback)
   :templates              — prompt template maps (default [])
   :skills                 — skill maps (default [])
   :tools                  — tool maps (default [])
   :extension-paths        — extension file paths (default [])
   :extension-targets      — activation targets (default [])
   :refresh-active-tools?  — when true, merge extension-registry tools into the session tool set (default true)

   Returns startup summary map stored at :startup-bootstrap."
  [ctx session-id {:keys [base-tools system-prompt developer-prompt developer-prompt-source templates skills tools extension-paths extension-targets refresh-active-tools?]
                   :or   {base-tools             []
                          system-prompt          ""
                          developer-prompt       ::unset
                          developer-prompt-source :fallback
                          templates              []
                          skills                 []
                          tools                  []
                          extension-paths        []
                          extension-targets      []
                          refresh-active-tools?  true}}]
  (let [resolved-developer-prompt (if (= developer-prompt ::unset)
                                    nil
                                    developer-prompt)
        resolved-source (when-not (= developer-prompt ::unset)
                          developer-prompt-source)]
    (session/dispatch-in! ctx
                          :session/bootstrap-prompt-state
                          {:session-id              session-id
                           :system-prompt           system-prompt
                           :developer-prompt        resolved-developer-prompt
                           :developer-prompt-source resolved-source}
                          {:origin :core})
    (session/dispatch-in! ctx :session/refresh-system-prompt {:session-id session-id} {:origin :core}))
  (let [startup-tools (into (vec base-tools) (vec tools))
        {:keys [prompt-count skill-count tool-count extension-results]}
        (load-startup-resources-in!
         ctx session-id {:templates templates
                         :skills skills
                         :tools startup-tools
                         :extension-paths extension-paths
                         :extension-targets extension-targets})
        ext-errors (keep (fn [r]
                           (when-let [e (:psi.extension/error r)]
                             {:path  (:psi.extension/path r)
                              :error e}))
                         extension-results)
        _         (when refresh-active-tools?
                    (refresh-active-tools-in! ctx session-id))
        summary   {:timestamp              (java.time.Instant/now)
                   :prompt-count           prompt-count
                   :skill-count            skill-count
                   :tool-count             tool-count
                   :extension-loaded-count (count (filter :psi.extension/loaded? extension-results))
                   :extension-error-count  (count ext-errors)
                   :extension-errors       (vec ext-errors)}]
    (session/dispatch-in! ctx :session/set-startup-bootstrap-summary {:session-id session-id :summary summary} {:origin :core})
    summary))
