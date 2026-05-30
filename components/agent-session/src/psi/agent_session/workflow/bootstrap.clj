(ns psi.agent-session.workflow.bootstrap
  "Built-in workflow bootstrap/installation owner.

   Owns higher-core installation of built-in workflow registration into the
   live runtime/session assembly, while leaving workflow behavior itself in
   `psi.agent-session.workflow.core`.

   Built-in workflow is installed through explicit built-in registration paths:
   - tools:    `tool-registry/register-built-in-tool-in!`
   - commands: `command-registry/register-built-in-command-in!`
   - prompt contributions: direct `:session/register-prompt-contribution` dispatch
   - lifecycle: `runtime-state/register-built-in-lifecycle-callback!`

   None of these paths require extension identity seeding or extension API
   construction.  `built-in:workflow` is retained only as the stable built-in
   provenance identifier, not as an extension registry key."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.workflow.core :as workflow]
   [psi.agent-session.workflow.runtime-state :as runtime-state]
   [psi.command-registry.registry :as command-registry]
   [psi.session-state.state :as ss]
   [psi.tool-registry.registry :as tool-registry]))

(defn- refresh-active-tools!
  [ctx session-id]
  (let [active-tools (:tools (agent/get-data-in (ss/agent-ctx-in ctx session-id)))
        all-tools    (tool-registry/all-tools-in (:extension-registry ctx))]
    (session/dispatch-in! ctx
                          :session/set-active-tools
                          {:session-id session-id
                           :tool-maps (into (vec active-tools) all-tools)}
                          {:origin :core})))

(defn- make-prompt-contribution-fn
  "Return a built-in-specific prompt contribution registration fn that dispatches
   directly to `:session/register-prompt-contribution` without going through the
   extension API."
  [ctx session-id]
  (fn [contribution]
    (let [id       (:id contribution)
          ext-path runtime-state/built-in-workflow-path]
      (dispatch/dispatch! ctx
                          :session/register-prompt-contribution
                          {:session-id   session-id
                           :ext-path     ext-path
                           :id           id
                           :contribution (dissoc contribution :id)}
                          {:origin :core}))))

(defn- make-built-in-api
  "Build a minimal built-in API map for `workflow/init` that uses built-in
   registration paths rather than extension API wrapping.

   The map keys match what `workflow/core/init` reads from its `api` argument:
   `:query`, `:query-session`, `:mutate`, `:mutate-session`, `:log`, `:notify`,
   `:append-message`, `:ui`, `:register-tool`, `:register-command`,
   `:register-prompt-contribution`, `:on`."
  [ctx session-id]
  (let [rfns          (runtime-fns/make-extension-runtime-fns ctx session-id nil)
        reg           (:extension-registry ctx)
        provenance-id runtime-state/built-in-workflow-path
        query-fn      (:query-fn rfns)
        mutate-fn     (:mutate-fn rfns)]
    {:query
     query-fn

     :query-session
     (fn [sid eql-query]
       (query-fn {:session-id sid :query eql-query}))

     :mutate
     mutate-fn

     :mutate-session
     (fn [sid op-sym params]
       (mutate-fn op-sym (assoc (or params {}) :session-id sid)))

     :log
     (:log-fn rfns)

     :notify
     (fn [msg opts]
       (mutate-fn 'psi.extension/notify
                  (cond-> {:message msg}
                    (map? opts) (merge opts))))

     :append-message
     (fn [role content]
       (mutate-fn 'psi.extension/append-message
                  {:role role :content content}))

     :ui
     (when-let [ui-ctx-fn (:ui-context-fn rfns)]
       (ui-ctx-fn provenance-id))

     :register-tool
     (fn [tool]
       (tool-registry/register-built-in-tool-in! reg provenance-id tool))

     :register-command
     (fn [name cmd]
       (command-registry/register-built-in-command-in!
        reg provenance-id (assoc cmd :name name)))

     :register-prompt-contribution
     (make-prompt-contribution-fn ctx session-id)

     :register-operation
     (fn [operation]
       (if-let [register-op (:register-deterministic-operation-fn rfns)]
         (register-op provenance-id operation)
         {:id (:id operation)}))

     :on
     (fn [event-name handler-fn]
       (runtime-state/register-built-in-lifecycle-callback! event-name handler-fn))}))

(defn init-built-in!
  [ctx session-id]
  (let [api (make-built-in-api ctx session-id)
        _   (runtime-state/assoc-state! :ctx ctx)]
    (runtime-fns/with-active-extension-session-id
      session-id
      #(binding [runtime-state/*active-workflow-session-id* session-id]
         (workflow/init api)
         (runtime-state/assoc-state! :ctx ctx :current-session-id session-id)
         (refresh-active-tools! ctx session-id)))
    {:path runtime-state/built-in-workflow-path
     :loaded-definitions (runtime-state/loaded-definitions)}))
