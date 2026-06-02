(ns psi.agent-session.resolvers.extensions
  "Pathom3 resolvers for extension introspection, workflows, and UI state."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.extension-installs :as installs]
   [psi.agent-session.commands.builtin-specs :as builtin-specs]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extension-workflow-runtime :as extension-workflow-runtime]
   [psi.agent-session.resolvers.support :as support]
   [psi.agent-session.ui-capabilities :as ui-capabilities]
   [psi.command-registry.registry :as command-registry]
   [psi.session-state.state :as session-state]
   [psi.tool-registry.registry :as tool-registry]
   [psi.ui.state :as ui-state]))

;; ── Projection helpers ──────────────────────────────────

(def ^:private workflow-event-output
  [:psi.extension.workflow.event/name
   :psi.extension.workflow.event/data
   :psi.extension.workflow.event/timestamp])

(def ^:private workflow-output
  [:psi.extension.workflow/id
   :psi.extension/path
   :psi.extension.workflow/type
   :psi.extension.workflow/phase
   :psi.extension.workflow/configuration
   :psi.extension.workflow/running?
   :psi.extension.workflow/done?
   :psi.extension.workflow/error?
   :psi.extension.workflow/error-message
   :psi.extension.workflow/input
   :psi.extension.workflow/meta
   :psi.extension.workflow/data
   :psi.extension.workflow/result
   :psi.extension.workflow/created-at
   :psi.extension.workflow/started-at
   :psi.extension.workflow/updated-at
   :psi.extension.workflow/finished-at
   :psi.extension.workflow/elapsed-ms
   :psi.extension.workflow/event-count
   :psi.extension.workflow/last-event
   {:psi.extension.workflow/events workflow-event-output}])

(defn- event->eql
  [event]
  {:psi.extension.workflow.event/name      (:name event)
   :psi.extension.workflow.event/data      (:data event)
   :psi.extension.workflow.event/timestamp (:timestamp event)})

(defn- workflow->eql
  [workflow]
  (let [created-at  (:created-at workflow)
        finished-at (:finished-at workflow)
        elapsed-ms  (when created-at
                      (let [end (or finished-at (java.time.Instant/now))]
                        (- (.toEpochMilli ^java.time.Instant end)
                           (.toEpochMilli ^java.time.Instant created-at))))]
    {:psi.extension.workflow/id            (:id workflow)
     :psi.extension/path                   (:ext-path workflow)
     :psi.extension.workflow/type          (:type workflow)
     :psi.extension.workflow/phase         (:phase workflow)
     :psi.extension.workflow/configuration (:configuration workflow)
     :psi.extension.workflow/running?      (:running? workflow)
     :psi.extension.workflow/done?         (:done? workflow)
     :psi.extension.workflow/error?        (:error? workflow)
     :psi.extension.workflow/error-message (:error-message workflow)
     :psi.extension.workflow/input         (:input workflow)
     :psi.extension.workflow/meta          (:meta workflow)
     :psi.extension.workflow/data          (:data workflow)
     :psi.extension.workflow/result        (:result workflow)
     :psi.extension.workflow/created-at    created-at
     :psi.extension.workflow/started-at    (:started-at workflow)
     :psi.extension.workflow/updated-at    (:updated-at workflow)
     :psi.extension.workflow/finished-at   finished-at
     :psi.extension.workflow/elapsed-ms    elapsed-ms
     :psi.extension.workflow/event-count   (:event-count workflow)
     :psi.extension.workflow/last-event    (:last-event workflow)
     :psi.extension.workflow/events        (mapv event->eql (:events workflow))}))

(defn- extension-installs-state
  [agent-session-ctx]
  (let [cwd (:cwd agent-session-ctx)
        persisted (installs/extension-installs-state-in agent-session-ctx)]
    (if (seq persisted)
      persisted
      (installs/compute-install-state cwd))))

;; ── Extension registry resolvers ────────────────────────

(pco/defresolver extension-install-config-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extensions/user-manifest
                 :psi.extensions/project-manifest
                 :psi.extensions/effective
                 :psi.extensions/diagnostics
                 :psi.extensions/last-apply]}
  (let [state (extension-installs-state agent-session-ctx)]
    {:psi.extensions/user-manifest (or (:psi.extensions/user-manifest state) {:deps {}})
     :psi.extensions/project-manifest (or (:psi.extensions/project-manifest state) {:deps {}})
     :psi.extensions/effective (:psi.extensions/effective state)
     :psi.extensions/diagnostics (vec (or (:psi.extensions/diagnostics state) []))
     :psi.extensions/last-apply (:psi.extensions/last-apply state)}))

(pco/defresolver extension-paths-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/paths
                 :psi.extension/count]}
  (let [reg (:extension-registry agent-session-ctx)]
    {:psi.extension/paths (vec (ext/extensions-in reg))
     :psi.extension/count (ext/extension-count-in reg)}))

(pco/defresolver extension-handlers-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/handler-events
                 :psi.extension/handler-count]}
  (let [reg (:extension-registry agent-session-ctx)]
    {:psi.extension/handler-events (vec (ext/handler-event-names-in reg))
     :psi.extension/handler-count  (ext/handler-count-in reg)}))

(pco/defresolver extension-tools-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/tools
                 :psi.extension/tool-names]}
  (let [reg   (:extension-registry agent-session-ctx)
        tools (tool-registry/all-tools-in reg)]
    {:psi.extension/tools      (mapv #(dissoc % :execute) tools)
     :psi.extension/tool-names (vec (tool-registry/tool-names-in reg))}))

(pco/defresolver extension-commands-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/commands
                 :psi.extension/command-names]}
  (let [reg  (:extension-registry agent-session-ctx)
        cmds (command-registry/all-commands-in reg)]
    {:psi.extension/commands      (mapv #(dissoc % :handler) cmds)
     :psi.extension/command-names (vec (command-registry/command-names-in reg))}))

(pco/defresolver builtin-commands-resolver
  "Expose the backend's authoritative built-in slash-command surface, derived
   from the single `builtin-command-specs` table in `commands`. Mirrors
   `extension-commands-resolver`: bare names (no leading slash, UIs prefix), so
   TUI + Emacs consume built-ins exactly like `:psi.extension/command-names`."
  [_env]
  {::pco/input  []
   ::pco/output [:psi.agent-session/builtin-command-specs
                 :psi.agent-session/builtin-command-names]}
  (let [specs (builtin-specs/builtin-command-specs-for-resolver)]
    {:psi.agent-session/builtin-command-specs specs
     :psi.agent-session/builtin-command-names (mapv :name specs)}))

(pco/defresolver extension-flags-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/flags
                 :psi.extension/flag-names
                 :psi.extension/flag-values]}
  (let [reg   (:extension-registry agent-session-ctx)
        flags (ext/all-flags-in reg)]
    {:psi.extension/flags      flags
     :psi.extension/flag-names (vec (ext/flag-names-in reg))
     :psi.extension/flag-values (ext/all-flag-values-in reg)}))

(pco/defresolver extension-details-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/details]}
  {:psi.extension/details
   (ext/extension-details-in (:extension-registry agent-session-ctx))})

(pco/defresolver extension-prompt-contributions-resolver
  "Resolve extension-managed prompt contributions in deterministic render order."
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.extension/prompt-contributions
                 :psi.extension/prompt-contribution-count]}
  (let [contribs (->> (session-state/list-prompt-contributions-in agent-session-ctx session-id)
                      (mapv support/contribution->attrs))]
    {:psi.extension/prompt-contributions contribs
     :psi.extension/prompt-contribution-count (count contribs)}))

(pco/defresolver extension-detail-by-path-resolver
  "Resolve detail for a single extension by path."
  [{:keys [psi/agent-session-ctx psi.extension/path]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path]
   ::pco/output [:psi.extension/detail]}
  {:psi.extension/detail
   (ext/extension-detail-in (:extension-registry agent-session-ctx) path)})

;; ── Workflow resolvers ──────────────────────────────────

(pco/defresolver extension-workflow-summary-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension.workflow/count
                 :psi.extension.workflow/running-count
                 :psi.extension.workflow/type-names]}
  (let [reg (:workflow-registry agent-session-ctx)]
    {:psi.extension.workflow/count         (extension-workflow-runtime/workflow-count-in reg)
     :psi.extension.workflow/running-count (extension-workflow-runtime/running-count-in reg)
     :psi.extension.workflow/type-names    (extension-workflow-runtime/type-names-in reg)}))

(pco/defresolver extension-workflows-resolver
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.extension/workflows workflow-output}]}
  (let [agent-session-ctx (:psi/agent-session-ctx entity)
        path              (:psi.extension/path entity)
        reg               (:workflow-registry agent-session-ctx)]
    {:psi.extension/workflows
     (mapv workflow->eql (extension-workflow-runtime/workflows-in reg path))}))

(pco/defresolver extension-workflow-detail-resolver
  [{:keys [psi/agent-session-ctx psi.extension/path psi.extension.workflow/id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path :psi.extension.workflow/id]
   ::pco/output [:psi.extension.workflow/detail]}
  (let [reg (:workflow-registry agent-session-ctx)]
    {:psi.extension.workflow/detail
     (some-> (extension-workflow-runtime/workflow-in reg path id) workflow->eql)}))

;; ── Extension UI state ──────────────────────────────────

(pco/defresolver extension-ui-resolver
  "Resolve extension UI state snapshot (read-only introspection)."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.ui/dialog-queue-empty?
                 :psi.ui/active-dialog
                 :psi.ui/pending-dialog-count
                 :psi.ui/widgets
                 :psi.ui/widget-specs
                 :psi.ui/statuses
                 :psi.ui/visible-notifications
                 :psi.ui/tool-renderers
                 :psi.ui/message-renderers]}
  (let [snap (ui-state/snapshot-state (get-in @(:state* agent-session-ctx) [:ui :extension-ui]))]
    (if snap
      {:psi.ui/dialog-queue-empty?   (:dialog-queue-empty? snap)
       :psi.ui/active-dialog         (:active-dialog snap)
       :psi.ui/pending-dialog-count  (:pending-dialog-count snap)
       :psi.ui/widgets               (:widgets snap)
       :psi.ui/widget-specs          (:widget-specs snap)
       :psi.ui/statuses              (:statuses snap)
       :psi.ui/visible-notifications (:visible-notifications snap)
       :psi.ui/tool-renderers        (:tool-renderers snap)
       :psi.ui/message-renderers     (:message-renderers snap)}
      {:psi.ui/dialog-queue-empty?   true
       :psi.ui/active-dialog         nil
       :psi.ui/pending-dialog-count  0
       :psi.ui/widgets               []
       :psi.ui/widget-specs          []
       :psi.ui/statuses              []
       :psi.ui/visible-notifications []
       :psi.ui/tool-renderers        []
       :psi.ui/message-renderers     []})))

(pco/defresolver ui-capabilities-resolver
  "Resolve runtime UI capabilities and action descriptors."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.ui/type
                 :psi.ui/available?
                 :psi.ui/capabilities
                 :psi.ui/actions
                 :psi.ui/make-visible-action
                 :psi.ui/diagnostic]}
  (ui-capabilities/resolve-ui agent-session-ctx))

;; ── Resolver collection ─────────────────────────────────

(def resolvers
  [extension-install-config-resolver
   extension-paths-resolver
   extension-handlers-resolver
   extension-tools-resolver
   extension-commands-resolver
   builtin-commands-resolver
   extension-flags-resolver
   extension-details-resolver
   extension-prompt-contributions-resolver
   extension-detail-by-path-resolver
   extension-workflow-summary-resolver
   extension-workflows-resolver
   extension-workflow-detail-resolver
   extension-ui-resolver
   ui-capabilities-resolver])
