(ns extensions.dev-http
  "dev-http extension entry point.

   A dev-time, localhost-only HTTP side channel between the agent and the
   developer. The running integrant system, route registry, and api map are
   held in this extension's own atom (precedent: work-on, mcp-tasks-run) — not
   in core state and not as a core managed-service type.

   All reads/writes flow through the runtime ExtensionAPI map captured at
   `init`; the extension never reaches into core namespaces.")

(defonce ^:private state
  (atom {:api nil
         :system nil}))

(defn- capture-api!
  [api]
  (swap! state assoc :api api))

(defn init
  "Extension entry point. Receives the runtime ExtensionAPI map and captures it
   into the extension-local atom for later lifecycle/command use."
  [api]
  (capture-api! api)
  nil)
