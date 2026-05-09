(ns psi.agent-session.workflow.bootstrap
  "Built-in workflow bootstrap/installation owner.

   Owns higher-core installation of built-in workflow registration into the
   live runtime/session assembly, while leaving workflow behavior itself in
   `psi.agent-session.workflow.core`."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.workflow.core :as workflow]
   [psi.agent-session.workflow.runtime-state :as runtime-state]
   [psi.session-state.state :as ss]
   [psi.tool-registry.registry :as tool-registry]))

(defn- refresh-active-tools!
  [ctx session-id]
  (let [active-tools (:tools (agent/get-data-in (ss/agent-ctx-in ctx session-id)))
        ext-tools    (tool-registry/all-tools-in (:extension-registry ctx))]
    (session/dispatch-in! ctx
                          :session/set-active-tools
                          {:session-id session-id
                           :tool-maps (into (vec active-tools) ext-tools)}
                          {:origin :core})))

(defn init-built-in!
  [ctx session-id]
  (let [reg         (:extension-registry ctx)
        runtime-fns (runtime-fns/make-extension-runtime-fns ctx session-id nil)
        _           (runtime-state/assoc-state! :ctx ctx)
        _           (ext/register-extension-in! reg runtime-state/built-in-workflow-path)
        api         (ext/create-extension-api reg runtime-state/built-in-workflow-path runtime-fns)]
    (runtime-fns/with-active-extension-session-id
      session-id
      #(binding [runtime-state/*active-workflow-session-id* session-id]
         (workflow/init api)
         (runtime-state/assoc-state! :ctx ctx :current-session-id session-id)
         (refresh-active-tools! ctx session-id)))
    {:path runtime-state/built-in-workflow-path
     :loaded-definitions (runtime-state/loaded-definitions)}))
