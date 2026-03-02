(ns psi.agent-session.resolvers
  "Pathom3 EQL resolvers for the agent-session component.

   Attribute namespace: :psi.agent-session/
   Seed key:           :psi/agent-session-ctx   — the session context map

   All resolvers receive the session context via :psi/agent-session-ctx
   in the Pathom entity map and delegate to pure read fns in core.clj.

   Exposed attributes
   ──────────────────
   :psi.agent-session/session-id
   :psi.agent-session/session-file
   :psi.agent-session/session-name
   :psi.agent-session/model
   :psi.agent-session/thinking-level
   :psi.agent-session/is-streaming
   :psi.agent-session/is-compacting
   :psi.agent-session/is-idle
   :psi.agent-session/phase               — statechart phase keyword
   :psi.agent-session/system-prompt
   :psi.agent-session/developer-prompt
   :psi.agent-session/developer-prompt-source
   :psi.agent-session/prompt-layers
   :psi.agent-session/pending-message-count
   :psi.agent-session/has-pending-messages
   :psi.agent-session/retry-attempt
   :psi.agent-session/auto-retry-enabled
   :psi.agent-session/auto-compaction-enabled
   :psi.agent-session/scoped-models
   :psi.agent-session/skills
   :psi.agent-session/prompt-templates
   :psi.agent-session/extension-summary   — map describing registered extensions
   :psi.agent-session/session-entry-count
   :psi.agent-session/session-entries     — [{:psi.session-entry/*}] full journal contents
   :psi.agent-session/journal-flushed?    — true once initial bulk write has happened
   :psi.agent-session/context-tokens
   :psi.agent-session/context-window
   :psi.agent-session/context-fraction    — nil or 0.0–1.0
   :psi.agent-session/messages-count
   :psi.agent-session/tool-call-count
   :psi.agent-session/start-time
   :psi.agent-session/current-time
   :psi.agent-session/stats               — SessionStats snapshot
   :psi.agent-session/tool-call-history   — [{:psi.tool-call/*}], nested tool call entities
   :psi.agent-session/tool-call-history-count — number of tool calls

   Tool-output policy and telemetry
   ────────────────────────────────
   :psi.tool-output/default-max-lines
   :psi.tool-output/default-max-bytes
   :psi.tool-output/overrides
   :psi.tool-output/calls            — [{:psi.tool-output.call/*}]
   :psi.tool-output/stats            — {:total-context-bytes :by-tool :limit-hits-by-tool}

   Tool-output call entities (nested under :psi.tool-output/calls)
   ──────────────────────────────────────────────────────────────
   :psi.tool-output.call/tool-call-id
   :psi.tool-output.call/tool-name
   :psi.tool-output.call/timestamp
   :psi.tool-output.call/limit-hit?
   :psi.tool-output.call/truncated-by
   :psi.tool-output.call/effective-max-lines
   :psi.tool-output.call/effective-max-bytes
   :psi.tool-output.call/output-bytes
   :psi.tool-output.call/context-bytes-added

   Session entry entities (nested under session-entries)
   ──────────────────────────────────────────────────────
   :psi.session-entry/id
   :psi.session-entry/parent-id
   :psi.session-entry/timestamp
   :psi.session-entry/kind
   :psi.session-entry/data

   Session listing (cross-session)
   ────────────────────────────────
   :psi.session/list                      — [{:psi.session-info/*}] for current cwd
   :psi.session/list-all                  — [{:psi.session-info/*}] across all projects
   :psi.session-info/path
   :psi.session-info/id
   :psi.session-info/cwd
   :psi.session-info/name
   :psi.session-info/parent-session-path
   :psi.session-info/created
   :psi.session-info/modified
   :psi.session-info/message-count
   :psi.session-info/first-message
   :psi.session-info/all-messages-text

   Tool call entities (nested under tool-call-history)
   ───────────────────────────────────────────────────
   :psi.tool-call/id                      — unique call ID (from list resolver)
   :psi.tool-call/name                    — tool name (from list resolver)
   :psi.tool-call/arguments               — raw argument string (from list resolver)
   :psi.tool-call/result                  — result text (from detail resolver, lazy)
   :psi.tool-call/is-error                — error flag (from detail resolver, lazy)

   API error diagnostics (hierarchical)
   ────────────────────────────────────
   :psi.agent-session/api-error-count     — number of API errors in session
   :psi.agent-session/api-errors          — [{:psi.api-error/*}] error entities

   Level 1 (list, cheap — identity from assistant error messages):
   :psi.api-error/message-index           — position in agent-core message vec
   :psi.api-error/http-status             — HTTP status code (e.g. 400), or nil
   :psi.api-error/timestamp               — Instant of the error
   :psi.api-error/error-message-brief     — first 120 chars of error text

   Level 2 (detail, moderate — seeded by :psi.api-error/message-index):
   :psi.api-error/error-message-full      — complete error text
   :psi.api-error/request-id              — API request-id parsed from error text
   :psi.api-error/surrounding-messages    — [{:psi.context-message/*}] nearby messages

   Level 3 (request shape, expensive — seeded by :psi.api-error/message-index):
   :psi.api-error/request-shape           — {:psi.request-shape/*} at point of failure

   Context message entities (nested under surrounding-messages)
   ────────────────────────────────────────────────────────────
   :psi.context-message/index             — position in agent-core message vec
   :psi.context-message/role              — message role string
   :psi.context-message/content-types     — [:text :tool-call :error ...]
   :psi.context-message/snippet           — first 200 chars of primary content

   Request shape (shared between api-error and current diagnostics)
   ────────────────────────────────────────────────────────────────
   :psi.agent-session/request-shape       — {:psi.request-shape/*} for current state
   :psi.request-shape/message-count       — raw agent-core message count
   :psi.request-shape/system-prompt-chars — system prompt character count
   :psi.request-shape/message-chars       — total message characters (pr-str estimate)
   :psi.request-shape/tool-schema-chars   — tool definition characters
   :psi.request-shape/total-chars         — sum of above
   :psi.request-shape/estimated-tokens    — total-chars / 4 (rough estimate)
   :psi.request-shape/context-window      — model context window size
   :psi.request-shape/max-output-tokens   — model max output tokens
   :psi.request-shape/headroom-tokens     — context-window - estimated - max-output
   :psi.request-shape/role-distribution   — {role count}
   :psi.request-shape/tool-count          — number of tool definitions
   :psi.request-shape/tool-use-count      — tool_use blocks in assistant messages
   :psi.request-shape/tool-result-count   — toolResult messages
   :psi.request-shape/missing-tool-results — tool_use IDs without matching results
   :psi.request-shape/orphan-tool-results  — toolResult IDs without matching tool_use
   :psi.request-shape/alternation-valid?   — true if role alternation is correct
   :psi.request-shape/alternation-violations — count of consecutive same-role pairs
   :psi.request-shape/empty-content-count  — messages with empty content

   Prompt template introspection
   ─────────────────────────────
   :psi.prompt-template/summary           — overall template summary
   :psi.prompt-template/names             — vector of template name strings
   :psi.prompt-template/count             — number of discovered templates
   :psi.prompt-template/by-source         — templates grouped by source
   :psi.prompt-template/detail            — single enriched template (seed: :psi.prompt-template/name)

   Skill introspection
   ───────────────────
   :psi.skill/summary                     — overall skill summary
   :psi.skill/names                       — vector of skill name strings
   :psi.skill/count                       — total discovered skills
   :psi.skill/visible-count               — skills available to model
   :psi.skill/hidden-count                — skills with disable-model-invocation
   :psi.skill/by-source                   — skills grouped by source
   :psi.skill/detail                      — single enriched skill (seed: :psi.skill/name)

   Tool introspection
   ──────────────────
   :psi.tool/summary                      — overall tool summary
   :psi.tool/names                        — vector of active tool names
   :psi.tool/count                        — number of active tools
   :psi.tool/detail                       — single tool map (seed: :psi.tool/name)

   Extension UI state (read-only)
   ──────────────────────────────
   :psi.ui/dialog-queue-empty?            — true when no dialogs active or pending
   :psi.ui/active-dialog                  — current dialog map (sans promise), or nil
   :psi.ui/pending-dialog-count           — number of queued dialogs
   :psi.ui/widgets                        — vector of widget maps
   :psi.ui/statuses                       — vector of status entry maps
   :psi.ui/visible-notifications          — vector of non-dismissed notification maps
   :psi.ui/tool-renderers                 — vector of tool renderer metadata maps
   :psi.ui/message-renderers              — vector of message renderer metadata maps

   Session-derived usage and git attrs (read-only)
   ───────────────────────────────────────────────
   :psi.agent-session/cwd
   :psi.agent-session/git-branch          — nil when outside git, \"detached\" on detached HEAD
   :psi.agent-session/usage-input
   :psi.agent-session/usage-output
   :psi.agent-session/usage-cache-read
   :psi.agent-session/usage-cache-write
   :psi.agent-session/usage-cost-total
   :psi.agent-session/model-provider
   :psi.agent-session/model-id
   :psi.agent-session/model-reasoning

   Startup bootstrap introspection
   ──────────────────────────────
   :psi.startup/bootstrap-summary           — map of bootstrap execution details
   :psi.startup/bootstrap-timestamp         — Instant when bootstrap finished
   :psi.startup/prompt-count                — prompts loaded during bootstrap
   :psi.startup/skill-count                 — skills loaded during bootstrap
   :psi.startup/tool-count                  — tools loaded during bootstrap
   :psi.startup/extension-loaded-count      — extensions loaded successfully
   :psi.startup/extension-error-count       — extension load errors
   :psi.startup/extension-errors            — [{:path :error}] extension failures
   :psi.startup/mutations                   — mutation symbols used by bootstrap"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [psi.introspection.graph :as graph]
   [psi.memory.core :as memory]
   [psi.memory.resolvers :as memory-resolvers]
   [psi.query.registry :as registry]
   [psi.recursion.resolvers :as recursion-resolvers]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session :as session]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.workflows :as wf]
   [psi.tui.extension-ui :as ext-ui]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.turn-statechart :as turn-sc]
   [psi.agent-core.core :as agent]))

;; ── Core session fields ─────────────────────────────────

(pco/defresolver agent-session-identity
  "Resolve stable identity and naming fields."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/session-id
                 :psi.agent-session/session-file
                 :psi.agent-session/session-name]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/session-id   (:session-id sd)
     :psi.agent-session/session-file (:session-file sd)
     :psi.agent-session/session-name (:session-name sd)}))

;; ── Phase and streaming state ───────────────────────────

(pco/defresolver agent-session-phase
  "Resolve statechart phase and derived boolean flags."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/phase
                 :psi.agent-session/is-streaming
                 :psi.agent-session/is-compacting
                 :psi.agent-session/is-idle]}
  (let [{:keys [sc-env sc-session-id]} agent-session-ctx
        phase (sc/sc-phase sc-env sc-session-id)]
    {:psi.agent-session/phase        phase
     :psi.agent-session/is-streaming (= phase :streaming)
     :psi.agent-session/is-compacting (= phase :compacting)
     :psi.agent-session/is-idle      (= phase :idle)}))

;; ── Model and thinking level ────────────────────────────

(pco/defresolver agent-session-model
  "Resolve model and thinking level."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model
                 :psi.agent-session/thinking-level]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/model          (:model sd)
     :psi.agent-session/thinking-level (:thinking-level sd)}))

;; ── Queues and message counts ───────────────────────────

(pco/defresolver agent-session-queues
  "Resolve queue depths and prompt layers."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/system-prompt
                 :psi.agent-session/developer-prompt
                 :psi.agent-session/developer-prompt-source
                 :psi.agent-session/prompt-layers
                 :psi.agent-session/pending-message-count
                 :psi.agent-session/has-pending-messages
                 :psi.agent-session/steering-messages
                 :psi.agent-session/follow-up-messages]}
  (let [sd         @(:session-data-atom agent-session-ctx)
        sys        (:system-prompt sd)
        dev        (:developer-prompt sd)
        dev-source (:developer-prompt-source sd)]
    {:psi.agent-session/system-prompt          sys
     :psi.agent-session/developer-prompt       dev
     :psi.agent-session/developer-prompt-source dev-source
     :psi.agent-session/prompt-layers          {:system-prompt sys
                                                :developer-prompt dev
                                                :developer-prompt-source dev-source}
     :psi.agent-session/pending-message-count  (session/pending-message-count sd)
     :psi.agent-session/has-pending-messages   (session/has-pending-messages? sd)
     :psi.agent-session/steering-messages      (:steering-messages sd)
     :psi.agent-session/follow-up-messages     (:follow-up-messages sd)}))

;; ── Retry and compaction config ─────────────────────────

(pco/defresolver agent-session-retry-compact
  "Resolve retry and compaction operational fields."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/retry-attempt
                 :psi.agent-session/auto-retry-enabled
                 :psi.agent-session/auto-compaction-enabled
                 :psi.agent-session/scoped-models]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/retry-attempt           (:retry-attempt sd)
     :psi.agent-session/auto-retry-enabled      (:auto-retry-enabled sd)
     :psi.agent-session/auto-compaction-enabled (:auto-compaction-enabled sd)
     :psi.agent-session/scoped-models           (:scoped-models sd)}))

;; ── Resources ──────────────────────────────────────────

(pco/defresolver agent-session-resources
  "Resolve registered skills and prompt templates."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/skills
                 :psi.agent-session/prompt-templates]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/skills           (:skills sd)
     :psi.agent-session/prompt-templates (:prompt-templates sd)}))

;; ── Extension summary ───────────────────────────────────

(pco/defresolver agent-session-extensions
  "Resolve extension registry summary."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/extension-summary]}
  {:psi.agent-session/extension-summary
   (ext/summary-in (:extension-registry agent-session-ctx))})

;; ── Extension introspection ─────────────────────────────

(pco/defresolver extension-paths-resolver
  "Resolve list of registered extension paths."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/paths
                 :psi.extension/count]}
  (let [reg (:extension-registry agent-session-ctx)]
    {:psi.extension/paths (vec (ext/extensions-in reg))
     :psi.extension/count (ext/extension-count-in reg)}))

(pco/defresolver extension-handlers-resolver
  "Resolve handler event names and total handler count."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/handler-events
                 :psi.extension/handler-count]}
  (let [reg (:extension-registry agent-session-ctx)]
    {:psi.extension/handler-events (vec (ext/handler-event-names-in reg))
     :psi.extension/handler-count  (ext/handler-count-in reg)}))

(pco/defresolver extension-tools-resolver
  "Resolve all registered extension tools."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/tools
                 :psi.extension/tool-names]}
  (let [reg   (:extension-registry agent-session-ctx)
        tools (ext/all-tools-in reg)]
    {:psi.extension/tools      (mapv #(dissoc % :execute) tools)
     :psi.extension/tool-names (vec (ext/tool-names-in reg))}))

(pco/defresolver extension-commands-resolver
  "Resolve all registered extension commands."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/commands
                 :psi.extension/command-names]}
  (let [reg  (:extension-registry agent-session-ctx)
        cmds (ext/all-commands-in reg)]
    {:psi.extension/commands      (mapv #(dissoc % :handler) cmds)
     :psi.extension/command-names (vec (ext/command-names-in reg))}))

(pco/defresolver extension-flags-resolver
  "Resolve all registered extension flags with current values."
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
  "Resolve per-extension detail maps."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/details]}
  {:psi.extension/details
   (ext/extension-details-in (:extension-registry agent-session-ctx))})

(pco/defresolver extension-detail-by-path-resolver
  "Resolve detail for a single extension by path.
   Seed input: {:psi.extension/path \"path\"}"
  [{:keys [psi/agent-session-ctx psi.extension/path]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path]
   ::pco/output [:psi.extension/detail]}
  {:psi.extension/detail
   (ext/extension-detail-in (:extension-registry agent-session-ctx) path)})

;; ── Extension workflow introspection ───────────────────

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
  (let [created-at (:created-at workflow)
        finished-at (:finished-at workflow)
        elapsed-ms (when created-at
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

(pco/defresolver extension-workflow-summary-resolver
  "Resolve workflow counts and workflow type names across extensions."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension.workflow/count
                 :psi.extension.workflow/running-count
                 :psi.extension.workflow/type-names]}
  (let [reg (:workflow-registry agent-session-ctx)]
    {:psi.extension.workflow/count         (wf/workflow-count-in reg)
     :psi.extension.workflow/running-count (wf/running-count-in reg)
     :psi.extension.workflow/type-names    (wf/type-names-in reg)}))

(pco/defresolver extension-workflows-resolver
  "Resolve workflow instances.
   When :psi.extension/path is present in the seed/entity, results are filtered
   to that extension; otherwise all workflows are returned."
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.extension/workflows workflow-output}]}
  (let [agent-session-ctx (:psi/agent-session-ctx entity)
        path              (:psi.extension/path entity)
        reg               (:workflow-registry agent-session-ctx)]
    {:psi.extension/workflows
     (mapv workflow->eql (wf/workflows-in reg path))}))

(pco/defresolver extension-workflow-detail-resolver
  "Resolve one workflow instance by extension path + id.
   Seed input includes :psi.extension/path and :psi.extension.workflow/id."
  [{:keys [psi/agent-session-ctx psi.extension/path psi.extension.workflow/id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path :psi.extension.workflow/id]
   ::pco/output [:psi.extension.workflow/detail]}
  (let [reg (:workflow-registry agent-session-ctx)]
    {:psi.extension.workflow/detail
     (some-> (wf/workflow-in reg path id) workflow->eql)}))

;; ── Context usage ───────────────────────────────────────

(pco/defresolver agent-session-context-usage
  "Resolve context token usage and fraction."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/context-tokens
                 :psi.agent-session/context-window
                 :psi.agent-session/context-fraction]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/context-tokens   (:context-tokens sd)
     :psi.agent-session/context-window   (:context-window sd)
     :psi.agent-session/context-fraction (session/context-fraction-used sd)}))

;; ── Tool-output policy and telemetry ───────────────────

(defn- tool-output-call->eql
  [call]
  {:psi.tool-output.call/tool-call-id        (:tool-call-id call)
   :psi.tool-output.call/tool-name           (:tool-name call)
   :psi.tool-output.call/timestamp           (:timestamp call)
   :psi.tool-output.call/limit-hit?          (:limit-hit call)
   :psi.tool-output.call/truncated-by        (:truncated-by call)
   :psi.tool-output.call/effective-max-lines (:effective-max-lines call)
   :psi.tool-output.call/effective-max-bytes (:effective-max-bytes call)
   :psi.tool-output.call/output-bytes        (:output-bytes call)
   :psi.tool-output.call/context-bytes-added (:context-bytes-added call)})

(pco/defresolver tool-output-policy
  "Resolve tool-output defaults and per-tool overrides."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.tool-output/default-max-lines
                 :psi.tool-output/default-max-bytes
                 :psi.tool-output/overrides]}
  {:psi.tool-output/default-max-lines tool-output/default-max-lines
   :psi.tool-output/default-max-bytes tool-output/default-max-bytes
   :psi.tool-output/overrides         (or (:tool-output-overrides
                                           @(:session-data-atom agent-session-ctx))
                                          {})})

(pco/defresolver tool-output-calls
  "Resolve per-call tool-output telemetry records."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.tool-output/calls
                  [:psi.tool-output.call/tool-call-id
                   :psi.tool-output.call/tool-name
                   :psi.tool-output.call/timestamp
                   :psi.tool-output.call/limit-hit?
                   :psi.tool-output.call/truncated-by
                   :psi.tool-output.call/effective-max-lines
                   :psi.tool-output.call/effective-max-bytes
                   :psi.tool-output.call/output-bytes
                   :psi.tool-output.call/context-bytes-added]}]}
  {:psi.tool-output/calls
   (mapv tool-output-call->eql
         (or (:calls @(:tool-output-stats-atom agent-session-ctx)) []))})

(pco/defresolver tool-output-stats
  "Resolve aggregate tool-output telemetry."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.tool-output/stats]}
  {:psi.tool-output/stats
   (let [aggregates (:aggregates @(:tool-output-stats-atom agent-session-ctx))]
     {:total-context-bytes (or (:total-context-bytes aggregates) 0)
      :by-tool             (or (:by-tool aggregates) {})
      :limit-hits-by-tool  (or (:limit-hits-by-tool aggregates) {})})})

;; ── Journal ─────────────────────────────────────────────

(pco/defresolver agent-session-journal
  "Resolve session entry count, flush state, and full entry list."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/session-entry-count
                 :psi.agent-session/journal-flushed?
                 {:psi.agent-session/session-entries
                  [:psi.session-entry/id
                   :psi.session-entry/parent-id
                   :psi.session-entry/timestamp
                   :psi.session-entry/kind
                   :psi.session-entry/data]}]}
  (let [entries  @(:journal-atom agent-session-ctx)
        flushed? (:flushed? @(:flush-state-atom agent-session-ctx))]
    {:psi.agent-session/session-entry-count (count entries)
     :psi.agent-session/journal-flushed?    flushed?
     :psi.agent-session/session-entries
     (mapv (fn [e]
             {:psi.session-entry/id        (:id e)
              :psi.session-entry/parent-id (:parent-id e)
              :psi.session-entry/timestamp (:timestamp e)
              :psi.session-entry/kind      (:kind e)
              :psi.session-entry/data      (:data e)})
           entries)}))

;; ── Stats snapshot ──────────────────────────────────────

(defn- stats-snapshot
  "Build canonical session telemetry stats from current session/journal state."
  [agent-session-ctx]
  (let [sd      @(:session-data-atom agent-session-ctx)
        journal @(:journal-atom agent-session-ctx)
        msgs    (keep #(when (= :message (:kind %)) (get-in % [:data :message])) journal)]
    {:session-id         (:session-id sd)
     :session-file       (:session-file sd)
     :user-messages      (count (filter #(= "user" (:role %)) msgs))
     :assistant-messages (count (filter #(= "assistant" (:role %)) msgs))
     :tool-calls         (count (filter #(= "toolResult" (:role %)) msgs))
     :total-messages     (count msgs)
     :entry-count        (count journal)
     :context-tokens     (:context-tokens sd)
     :context-window     (:context-window sd)}))

(defn- canonical-start-time
  [agent-session-ctx]
  (let [sd      @(:session-data-atom agent-session-ctx)
        startup (:startup-bootstrap sd)
        journal @(:journal-atom agent-session-ctx)
        first-ts (:timestamp (first journal))]
    (or (:timestamp startup)
        first-ts
        (java.time.Instant/now))))

(pco/defresolver agent-session-canonical-telemetry
  "Resolve canonical top-level telemetry attrs from the same source as :psi.agent-session/stats.
   Time attrs intentionally return java.time.Instant for stable in-process representation."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/messages-count
                 :psi.agent-session/tool-call-count
                 :psi.agent-session/start-time
                 :psi.agent-session/current-time]}
  (let [stats (stats-snapshot agent-session-ctx)]
    {:psi.agent-session/messages-count  (:total-messages stats)
     :psi.agent-session/tool-call-count (:tool-calls stats)
     :psi.agent-session/start-time      (canonical-start-time agent-session-ctx)
     :psi.agent-session/current-time    (java.time.Instant/now)}))

(pco/defresolver agent-session-stats
  "Resolve a SessionStats snapshot."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/stats]}
  {:psi.agent-session/stats (stats-snapshot agent-session-ctx)})

;; ── Tool call history ───────────────────────────────────
;;
;; Two-level hierarchy:
;;   1. List resolver — extracts lightweight tool call identity from
;;      assistant messages (id, name, arguments). Cheap: no result loading.
;;   2. Detail resolver — given a :psi.tool-call/id, scans journal for the
;;      matching toolResult message. Only runs when result/error is queried.
;;
;; Usage:
;;   [:psi.agent-session/tool-call-history-count]
;;   [{:psi.agent-session/tool-call-history [:psi.tool-call/id :psi.tool-call/name]}]
;;   [{:psi.agent-session/tool-call-history [:psi.tool-call/name :psi.tool-call/result :psi.tool-call/is-error]}]

(defn- agent-core-messages
  "Extract the message vec from agent-core inside a session context."
  [agent-session-ctx]
  (:messages (psi.agent-core.core/get-data-in (:agent-ctx agent-session-ctx))))

(pco/defresolver agent-session-tool-calls
  "Resolve tool call list from assistant messages in agent-core.
   Each entry carries identity + the session context for downstream resolvers."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/tool-call-history-count
                 {:psi.agent-session/tool-call-history
                  [:psi.tool-call/id
                   :psi.tool-call/name
                   :psi.tool-call/arguments
                   :psi/agent-session-ctx]}]}
  (let [msgs  (agent-core-messages agent-session-ctx)
        calls (->> msgs
                   (filter #(= "assistant" (:role %)))
                   (mapcat (fn [msg]
                             (->> (:content msg)
                                  (filter #(= :tool-call (:type %)))
                                  (map (fn [tc]
                                         {:psi.tool-call/id         (:id tc)
                                          :psi.tool-call/name       (:name tc)
                                          :psi.tool-call/arguments  (:arguments tc)
                                          :psi/agent-session-ctx    agent-session-ctx})))))
                   vec)]
    {:psi.agent-session/tool-call-history       calls
     :psi.agent-session/tool-call-history-count (count calls)}))

(pco/defresolver tool-call-result
  "Resolve the result and error status for a single tool call.
   Scans agent-core messages for the matching toolResult."
  [{:keys [psi.tool-call/id psi/agent-session-ctx]}]
  {::pco/input  [:psi.tool-call/id :psi/agent-session-ctx]
   ::pco/output [:psi.tool-call/result
                 :psi.tool-call/is-error]}
  (let [result-msg (->> (agent-core-messages agent-session-ctx)
                        (filter #(= "toolResult" (:role %)))
                        (filter #(= id (:tool-call-id %)))
                        first)]
    {:psi.tool-call/result   (some #(when (= :text (:type %)) (:text %))
                                   (:content result-msg))
     :psi.tool-call/is-error (:is-error result-msg)}))

;; ── API error diagnostics (helpers) ─────────────────────

(defn- error-message-text
  "Extract the first :error block text from an assistant message."
  [msg]
  (some #(when (= :error (:type %)) (:text %)) (:content msg)))

(defn- parse-request-id
  "Extract request-id from a clj-http error text string."
  [error-text]
  (when error-text
    (second (re-find #"\"request-id\"\s+\"([^\"]+)\"" error-text))))

(defn- message-summary
  "Lightweight summary of an agent-core message for context display."
  [msg idx]
  (let [snippet (some (fn [c]
                        (case (:type c)
                          :text      (let [t (:text c)]
                                       (when (seq t)
                                         (subs t 0 (min 200 (count t)))))
                          :tool-call (str "[tool:" (:name c) "]")
                          :error     (let [t (:text c)]
                                       (when (seq t)
                                         (subs t 0 (min 200 (count t)))))
                          nil))
                      (:content msg))]
    {:psi.context-message/index         idx
     :psi.context-message/role          (:role msg)
     :psi.context-message/content-types (mapv :type (:content msg))
     :psi.context-message/snippet       (or snippet "")}))

(def ^:private request-shape-output
  "Shared output spec for :psi.request-shape/* attributes."
  [:psi.request-shape/message-count
   :psi.request-shape/system-prompt-chars
   :psi.request-shape/message-chars
   :psi.request-shape/tool-schema-chars
   :psi.request-shape/total-chars
   :psi.request-shape/estimated-tokens
   :psi.request-shape/context-window
   :psi.request-shape/max-output-tokens
   :psi.request-shape/headroom-tokens
   :psi.request-shape/role-distribution
   :psi.request-shape/tool-count
   :psi.request-shape/tool-use-count
   :psi.request-shape/tool-result-count
   :psi.request-shape/missing-tool-results
   :psi.request-shape/orphan-tool-results
   :psi.request-shape/alternation-valid?
   :psi.request-shape/alternation-violations
   :psi.request-shape/empty-content-count])

(defn- compute-request-shape
  "Compute request diagnostics from agent-core messages.
   Provider-agnostic: estimates tokens from serialized char count."
  [system-prompt messages tools context-window max-output-tokens]
  (let [;; Role counts
        role-counts   (frequencies (map :role messages))

        ;; Tool pairing
        tool-use-ids  (into #{}
                            (comp (filter #(= "assistant" (:role %)))
                                  (mapcat :content)
                                  (filter #(= :tool-call (:type %)))
                                  (map :id))
                            messages)
        tool-result-ids (into #{}
                              (comp (filter #(= "toolResult" (:role %)))
                                    (map :tool-call-id))
                              messages)

        ;; Size estimates (pr-str approximates wire format)
        sys-chars  (count (str system-prompt))
        msg-chars  (transduce (map #(count (pr-str %))) + 0 messages)
        tool-chars (transduce (map #(count (pr-str %))) + 0 tools)
        total      (+ sys-chars msg-chars tool-chars)
        est-tokens (quot total 4)
        headroom   (- context-window est-tokens max-output-tokens)

        ;; Alternation: map toolResult→user, deduplicate consecutive same roles
        api-roles  (->> messages
                        (keep #(case (:role %)
                                 "user"       "user"
                                 "assistant"  "assistant"
                                 "toolResult" "user"
                                 nil)))
        merged     (reduce (fn [acc r]
                             (if (= r (peek acc)) acc (conj acc r)))
                           [] api-roles)
        violations (count (filter (fn [[a b]] (= a b))
                                  (partition 2 1 merged)))

        ;; Empty content
        empty-ct   (count (filter #(empty? (:content %)) messages))]

    {:psi.request-shape/message-count          (count messages)
     :psi.request-shape/system-prompt-chars    sys-chars
     :psi.request-shape/message-chars          msg-chars
     :psi.request-shape/tool-schema-chars      tool-chars
     :psi.request-shape/total-chars            total
     :psi.request-shape/estimated-tokens       est-tokens
     :psi.request-shape/context-window         context-window
     :psi.request-shape/max-output-tokens      max-output-tokens
     :psi.request-shape/headroom-tokens        headroom
     :psi.request-shape/role-distribution      role-counts
     :psi.request-shape/tool-count             (count tools)
     :psi.request-shape/tool-use-count         (count tool-use-ids)
     :psi.request-shape/tool-result-count      (count tool-result-ids)
     :psi.request-shape/missing-tool-results   (count (set/difference tool-use-ids tool-result-ids))
     :psi.request-shape/orphan-tool-results    (count (set/difference tool-result-ids tool-use-ids))
     :psi.request-shape/alternation-valid?     (zero? violations)
     :psi.request-shape/alternation-violations violations
     :psi.request-shape/empty-content-count    empty-ct}))

(defn- resolve-context-window
  "Best-effort context window from session data or model config atom."
  [agent-session-ctx]
  (or (:context-window @(:session-data-atom agent-session-ctx))
      (some-> (:model-config-atom agent-session-ctx) deref :context-window)
      200000))

(defn- resolve-max-output-tokens
  "Best-effort max output tokens from session data or model config atom."
  [agent-session-ctx]
  (or (some-> (:model-config-atom agent-session-ctx) deref :max-tokens)
      16384))

;; ── Level 1: API error list (cheap) ────────────────────

(pco/defresolver api-error-list
  "Extract API errors from assistant messages with :stop-reason :error.
   Cheap: linear scan for error stop reason, no result loading."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/api-error-count
                 {:psi.agent-session/api-errors
                  [:psi.api-error/message-index
                   :psi.api-error/http-status
                   :psi.api-error/timestamp
                   :psi.api-error/error-message-brief
                   :psi/agent-session-ctx]}]}
  (let [msgs   (agent-core-messages agent-session-ctx)
        errors (->> msgs
                    (map-indexed vector)
                    (filter (fn [[_ m]]
                              (and (= "assistant" (:role m))
                                   (= :error (:stop-reason m)))))
                    (mapv (fn [[idx m]]
                            (let [err-text (error-message-text m)
                                  brief    (when err-text
                                             (subs err-text
                                                   0 (min 120 (count err-text))))]
                              {:psi.api-error/message-index      idx
                               :psi.api-error/http-status        (:http-status m)
                               :psi.api-error/timestamp          (:timestamp m)
                               :psi.api-error/error-message-brief brief
                               :psi/agent-session-ctx            agent-session-ctx}))))]
    {:psi.agent-session/api-error-count (count errors)
     :psi.agent-session/api-errors      errors}))

;; ── Level 2: API error detail (moderate) ────────────────

(pco/defresolver api-error-detail
  "Resolve full error text, request-id, and surrounding message context.
   Seeded by :psi.api-error/message-index from the list resolver."
  [{:keys [psi.api-error/message-index psi/agent-session-ctx]}]
  {::pco/input  [:psi.api-error/message-index :psi/agent-session-ctx]
   ::pco/output [:psi.api-error/error-message-full
                 :psi.api-error/request-id
                 {:psi.api-error/surrounding-messages
                  [:psi.context-message/index
                   :psi.context-message/role
                   :psi.context-message/content-types
                   :psi.context-message/snippet]}]}
  (let [msgs     (agent-core-messages agent-session-ctx)
        msg      (nth msgs message-index nil)
        err-text (when msg (error-message-text msg))
        ;; 5 before, 2 after the error
        start    (max 0 (- message-index 5))
        end      (min (count msgs) (+ message-index 3))
        surr     (mapv #(message-summary (nth msgs %) %) (range start end))]
    {:psi.api-error/error-message-full   err-text
     :psi.api-error/request-id           (parse-request-id err-text)
     :psi.api-error/surrounding-messages surr}))

;; ── Level 3: API error request shape (expensive) ────────

(pco/defresolver api-error-request-shape
  "Reconstruct the request shape at the point of an API error.
   Uses messages[0..message-index) — what was sent when the error occurred.
   Expensive: full message scan + size estimation."
  [{:keys [psi.api-error/message-index psi/agent-session-ctx]}]
  {::pco/input  [:psi.api-error/message-index :psi/agent-session-ctx]
   ::pco/output [{:psi.api-error/request-shape request-shape-output}]}
  (let [data      (agent/get-data-in (:agent-ctx agent-session-ctx))
        msgs      (:messages data)
        pre-error (subvec (vec msgs) 0 (min message-index (count msgs)))]
    {:psi.api-error/request-shape
     (compute-request-shape (:system-prompt data)
                            pre-error
                            (:tools data)
                            (resolve-context-window agent-session-ctx)
                            (resolve-max-output-tokens agent-session-ctx))}))

;; ── Current request shape (live diagnostics) ────────────

(pco/defresolver current-request-shape
  "Request shape for the current conversation state.
   Answers: 'if I send a prompt now, what does the context look like?'
   Expensive: full message scan + size estimation."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.agent-session/request-shape request-shape-output}]}
  (let [data (agent/get-data-in (:agent-ctx agent-session-ctx))]
    {:psi.agent-session/request-shape
     (compute-request-shape (:system-prompt data)
                            (:messages data)
                            (:tools data)
                            (resolve-context-window agent-session-ctx)
                            (resolve-max-output-tokens agent-session-ctx))}))

;; ── Turn streaming state ────────────────────────────────

(pco/defresolver agent-session-turn
  "Resolve per-turn streaming statechart state.
   Returns nil/empty values when no turn is active."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.turn/phase
                 :psi.turn/is-streaming
                 :psi.turn/text
                 :psi.turn/tool-calls
                 :psi.turn/tool-call-count
                 :psi.turn/final-message
                 :psi.turn/error-message
                 :psi.turn/is-text-accumulating
                 :psi.turn/is-tool-accumulating
                 :psi.turn/is-done
                 :psi.turn/is-error]}
  (if-let [turn-ctx (some-> (:turn-ctx-atom agent-session-ctx) deref)]
    (let [phase (turn-sc/turn-phase turn-ctx)
          td    (turn-sc/get-turn-data turn-ctx)]
      {:psi.turn/phase                phase
       :psi.turn/is-streaming         (boolean (#{:text-accumulating :tool-accumulating} phase))
       :psi.turn/text                 (:text-buffer td)
       :psi.turn/tool-calls           (vec (vals (:tool-calls td)))
       :psi.turn/tool-call-count      (count (:tool-calls td))
       :psi.turn/final-message        (:final-message td)
       :psi.turn/error-message        (:error-message td)
       :psi.turn/is-text-accumulating (= :text-accumulating phase)
       :psi.turn/is-tool-accumulating (= :tool-accumulating phase)
       :psi.turn/is-done              (= :done phase)
       :psi.turn/is-error             (= :error phase)})
    {:psi.turn/phase                nil
     :psi.turn/is-streaming         false
     :psi.turn/text                 nil
     :psi.turn/tool-calls           []
     :psi.turn/tool-call-count      0
     :psi.turn/final-message        nil
     :psi.turn/error-message        nil
     :psi.turn/is-text-accumulating false
     :psi.turn/is-tool-accumulating false
     :psi.turn/is-done              false
     :psi.turn/is-error             false}))

;; ── Prompt template introspection ────────────────────────

(pco/defresolver prompt-template-summary-resolver
  "Resolve prompt template summary: count, names, per-source grouping."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.prompt-template/summary
                 :psi.prompt-template/names
                 :psi.prompt-template/count
                 :psi.prompt-template/by-source]}
  (let [templates (:prompt-templates @(:session-data-atom agent-session-ctx))]
    {:psi.prompt-template/summary   (pt/template-summary templates)
     :psi.prompt-template/names     (pt/template-names templates)
     :psi.prompt-template/count     (count templates)
     :psi.prompt-template/by-source (pt/templates-by-source templates)}))

(pco/defresolver prompt-template-detail-resolver
  "Resolve a single enriched prompt template by name.
   Seed input: {:psi.prompt-template/name \"template-name\"}"
  [{:keys [psi/agent-session-ctx psi.prompt-template/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.prompt-template/name]
   ::pco/output [:psi.prompt-template/detail]}
  (let [templates (:prompt-templates @(:session-data-atom agent-session-ctx))
        tpl       (pt/find-template templates name)]
    {:psi.prompt-template/detail
     (when tpl (pt/enrich-template tpl))}))

;; ── Skill introspection ──────────────────────────────────

(pco/defresolver skill-summary-resolver
  "Resolve skill summary: count, visible/hidden counts, per-source grouping."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.skill/summary
                 :psi.skill/names
                 :psi.skill/count
                 :psi.skill/visible-count
                 :psi.skill/hidden-count
                 :psi.skill/by-source]}
  (let [all-skills (:skills @(:session-data-atom agent-session-ctx))
        summary    (skills/skill-summary all-skills)]
    {:psi.skill/summary       summary
     :psi.skill/names         (skills/skill-names all-skills)
     :psi.skill/count         (:skill-count summary)
     :psi.skill/visible-count (:visible-count summary)
     :psi.skill/hidden-count  (:hidden-count summary)
     :psi.skill/by-source     (skills/skills-by-source all-skills)}))

(pco/defresolver skill-detail-resolver
  "Resolve a single enriched skill by name.
   Seed input: {:psi.skill/name \"skill-name\"}"
  [{:keys [psi/agent-session-ctx psi.skill/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.skill/name]
   ::pco/output [:psi.skill/detail]}
  (let [all-skills (:skills @(:session-data-atom agent-session-ctx))
        skill      (skills/find-skill all-skills name)]
    {:psi.skill/detail
     (when skill (skills/enrich-skill skill))}))

;; ── Tool introspection ───────────────────────────────────

(pco/defresolver tool-summary-resolver
  "Resolve active tool summary: count and names."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.tool/summary
                 :psi.tool/names
                 :psi.tool/count]}
  (let [tools (:tools (agent/get-data-in (:agent-ctx agent-session-ctx)))
        names (mapv :name tools)]
    {:psi.tool/summary {:tool-count (count tools)
                        :tools      (mapv #(select-keys % [:name :label :description]) tools)}
     :psi.tool/names   names
     :psi.tool/count   (count tools)}))

(pco/defresolver tool-detail-resolver
  "Resolve a single active tool by name.
   Seed input: {:psi.tool/name \"tool-name\"}"
  [{:keys [psi/agent-session-ctx psi.tool/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.tool/name]
   ::pco/output [:psi.tool/detail]}
  (let [tools (:tools (agent/get-data-in (:agent-ctx agent-session-ctx)))
        tool  (first (filter #(= (:name %) name) tools))]
    {:psi.tool/detail tool}))

;; ── Session listing ─────────────────────────────────────

(defn- session-info->eql
  "Convert a SessionInfo map to :psi.session-info/* attributes."
  [info]
  {:psi.session-info/path                (:path info)
   :psi.session-info/id                  (:id info)
   :psi.session-info/cwd                 (:cwd info)
   :psi.session-info/name                (:name info)
   :psi.session-info/parent-session-path (:parent-session-path info)
   :psi.session-info/created             (:created info)
   :psi.session-info/modified            (:modified info)
   :psi.session-info/message-count       (:message-count info)
   :psi.session-info/first-message       (:first-message info)
   :psi.session-info/all-messages-text   (:all-messages-text info)})

(pco/defresolver session-list-resolver
  "Resolve all sessions for the current session's cwd, sorted by modified desc."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.session/list
                  [:psi.session-info/path
                   :psi.session-info/id
                   :psi.session-info/cwd
                   :psi.session-info/name
                   :psi.session-info/parent-session-path
                   :psi.session-info/created
                   :psi.session-info/modified
                   :psi.session-info/message-count
                   :psi.session-info/first-message
                   :psi.session-info/all-messages-text]}]}
  {:psi.session/list
   (mapv session-info->eql
         (persist/list-sessions
          (persist/session-dir-for (:cwd agent-session-ctx))))})

(pco/defresolver session-list-all-resolver
  "Resolve all sessions across all project directories, sorted by modified desc."
  [{_ctx :psi/agent-session-ctx}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.session/list-all
                  [:psi.session-info/path
                   :psi.session-info/id
                   :psi.session-info/cwd
                   :psi.session-info/name
                   :psi.session-info/parent-session-path
                   :psi.session-info/created
                   :psi.session-info/modified
                   :psi.session-info/message-count
                   :psi.session-info/first-message
                   :psi.session-info/all-messages-text]}]}
  {:psi.session/list-all
   (mapv session-info->eql (persist/list-all-sessions))})

;; ── Extension UI state ───────────────────────────────

(pco/defresolver extension-ui-resolver
  "Resolve extension UI state snapshot (read-only introspection)."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.ui/dialog-queue-empty?
                 :psi.ui/active-dialog
                 :psi.ui/pending-dialog-count
                 :psi.ui/widgets
                 :psi.ui/statuses
                 :psi.ui/visible-notifications
                 :psi.ui/tool-renderers
                 :psi.ui/message-renderers]}
  (let [snap (ext-ui/snapshot (:ui-state-atom agent-session-ctx))]
    (if snap
      {:psi.ui/dialog-queue-empty?   (:dialog-queue-empty? snap)
       :psi.ui/active-dialog         (:active-dialog snap)
       :psi.ui/pending-dialog-count  (:pending-dialog-count snap)
       :psi.ui/widgets               (:widgets snap)
       :psi.ui/statuses              (:statuses snap)
       :psi.ui/visible-notifications (:visible-notifications snap)
       :psi.ui/tool-renderers        (:tool-renderers snap)
       :psi.ui/message-renderers     (:message-renderers snap)}
      {:psi.ui/dialog-queue-empty?   true
       :psi.ui/active-dialog         nil
       :psi.ui/pending-dialog-count  0
       :psi.ui/widgets               []
       :psi.ui/statuses              []
       :psi.ui/visible-notifications []
       :psi.ui/tool-renderers        []
       :psi.ui/message-renderers     []})))

;; ── Footer data ───────────────────────────────────────

(defn- usage-number
  [usage k1 k2]
  (let [v (or (get usage k1) (get usage k2) 0)]
    (if (number? v) v 0)))

(defn- usage-cost-total
  [usage]
  (let [v (or (get-in usage [:cost :total])
              (:cost-total usage)
              0.0)]
    (if (number? v) v 0.0)))

(defn- session-usage-totals
  [agent-session-ctx]
  (reduce
   (fn [acc entry]
     (let [msg (get-in entry [:data :message])]
       (if (and (= :message (:kind entry))
                (= "assistant" (:role msg))
                (map? (:usage msg)))
         (let [u (:usage msg)]
           (-> acc
               (update :input + (usage-number u :input-tokens :input))
               (update :output + (usage-number u :output-tokens :output))
               (update :cache-read + (usage-number u :cache-read-tokens :cache-read))
               (update :cache-write + (usage-number u :cache-write-tokens :cache-write))
               (update :cost + (usage-cost-total u))))
         acc)))
   {:input 0 :output 0 :cache-read 0 :cache-write 0 :cost 0.0}
   @(:journal-atom agent-session-ctx)))

(defn- find-git-head-path
  [cwd]
  (loop [dir (when cwd (java.io.File. cwd))]
    (when dir
      (let [git-path (java.io.File. dir ".git")]
        (if (.exists git-path)
          (try
            (cond
              (.isDirectory git-path)
              (let [head (java.io.File. git-path "HEAD")]
                (when (.exists head)
                  (.getAbsolutePath head)))

              (.isFile git-path)
              (let [content (str/trim (slurp git-path))]
                (when (str/starts-with? content "gitdir: ")
                  (let [git-dir (subs content 8)
                        head    (java.io.File. (java.io.File. dir git-dir) "HEAD")]
                    (when (.exists head)
                      (.getAbsolutePath head)))))

              :else nil)
            (catch Exception _ nil))
          (recur (.getParentFile dir)))))))

(defn- git-branch-from-cwd
  [cwd]
  (try
    (when-let [head-path (find-git-head-path cwd)]
      (let [content (str/trim (slurp head-path))]
        (if (str/starts-with? content "ref: refs/heads/")
          (subs content 16)
          "detached")))
    (catch Exception _ nil)))

(pco/defresolver agent-session-cwd
  "Resolve working directory for the current session context."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/cwd]}
  {:psi.agent-session/cwd (:cwd agent-session-ctx)})

(pco/defresolver agent-session-git-branch
  "Resolve current git branch for :psi.agent-session/cwd.
   Returns nil outside git repos and \"detached\" for detached HEAD."
  [{:keys [psi.agent-session/cwd]}]
  {::pco/input  [:psi.agent-session/cwd]
   ::pco/output [:psi.agent-session/git-branch]}
  {:psi.agent-session/git-branch (git-branch-from-cwd cwd)})

(pco/defresolver agent-session-usage-input
  "Resolve cumulative input tokens across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-input]}
  {:psi.agent-session/usage-input (:input (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-usage-output
  "Resolve cumulative output tokens across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-output]}
  {:psi.agent-session/usage-output (:output (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-usage-cache-read
  "Resolve cumulative cache-read tokens across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-cache-read]}
  {:psi.agent-session/usage-cache-read (:cache-read (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-usage-cache-write
  "Resolve cumulative cache-write tokens across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-cache-write]}
  {:psi.agent-session/usage-cache-write (:cache-write (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-usage-cost-total
  "Resolve cumulative total cost across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-cost-total]}
  {:psi.agent-session/usage-cost-total (:cost (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-model-provider
  "Resolve active model provider string from session state."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model-provider]}
  {:psi.agent-session/model-provider (:provider (:model @(:session-data-atom agent-session-ctx)))})

(pco/defresolver agent-session-model-id
  "Resolve active model id from session state."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model-id]}
  {:psi.agent-session/model-id (:id (:model @(:session-data-atom agent-session-ctx)))})

(pco/defresolver agent-session-model-reasoning
  "Resolve whether the active model supports reasoning."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model-reasoning]}
  {:psi.agent-session/model-reasoning
   (boolean (:reasoning (:model @(:session-data-atom agent-session-ctx))))})

(pco/defresolver startup-bootstrap-resolver
  "Resolve startup bootstrap summary and derived fields."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.startup/bootstrap-summary
                 :psi.startup/bootstrap-timestamp
                 :psi.startup/prompt-count
                 :psi.startup/skill-count
                 :psi.startup/tool-count
                 :psi.startup/extension-loaded-count
                 :psi.startup/extension-error-count
                 :psi.startup/extension-errors
                 :psi.startup/mutations]}
  (let [summary (:startup-bootstrap @(:session-data-atom agent-session-ctx))]
    {:psi.startup/bootstrap-summary      summary
     :psi.startup/bootstrap-timestamp    (:timestamp summary)
     :psi.startup/prompt-count           (:prompt-count summary 0)
     :psi.startup/skill-count            (:skill-count summary 0)
     :psi.startup/tool-count             (:tool-count summary 0)
     :psi.startup/extension-loaded-count (:extension-loaded-count summary 0)
     :psi.startup/extension-error-count  (:extension-error-count summary 0)
     :psi.startup/extension-errors       (:extension-errors summary [])
     :psi.startup/mutations              (:mutations summary [])}))

;; ── Query graph bridge (seeded by :psi/agent-session-ctx) ────────────────

(declare all-resolvers)

(defn- operation-metadata
  []
  (let [registered-resolvers (registry/all-resolvers)
        resolver-source      (if (seq registered-resolvers)
                               registered-resolvers
                               all-resolvers)]
    {:resolver-ops (mapv #(graph/operation->metadata :resolver %) resolver-source)
     :mutation-ops (mapv #(graph/operation->metadata :mutation %) (registry/all-mutations))}))

(pco/defresolver query-graph-bridge
  "Resolve :psi.graph/* attrs from :psi/agent-session-ctx so eql_query can access
   graph capabilities without requiring a :psi/query-ctx seed."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.graph/nodes
                 :psi.graph/edges
                 :psi.graph/capabilities
                 :psi.graph/domain-coverage]}
  (let [_      agent-session-ctx
        cgraph (graph/derive-capability-graph (operation-metadata))]
    {:psi.graph/nodes           (:nodes cgraph)
     :psi.graph/edges           (:edges cgraph)
     :psi.graph/capabilities    (:capabilities cgraph)
     :psi.graph/domain-coverage (:domain-coverage cgraph)}))

;; ── All resolvers ───────────────────────────────────────

(def all-resolvers
  [agent-session-identity
   agent-session-phase
   agent-session-model
   agent-session-queues
   agent-session-retry-compact
   agent-session-resources
   agent-session-extensions
   agent-session-context-usage
   tool-output-policy
   tool-output-calls
   tool-output-stats
   agent-session-journal
   agent-session-canonical-telemetry
   agent-session-stats
   ;; Session-derived usage/git attrs
   agent-session-cwd
   agent-session-git-branch
   agent-session-usage-input
   agent-session-usage-output
   agent-session-usage-cache-read
   agent-session-usage-cache-write
   agent-session-usage-cost-total
   agent-session-model-provider
   agent-session-model-id
   agent-session-model-reasoning
   startup-bootstrap-resolver
   query-graph-bridge
   agent-session-tool-calls
   tool-call-result
   ;; API error diagnostics (hierarchical)
   api-error-list
   api-error-detail
   api-error-request-shape
   current-request-shape
   agent-session-turn
   prompt-template-summary-resolver
   prompt-template-detail-resolver
   skill-summary-resolver
   skill-detail-resolver
   tool-summary-resolver
   tool-detail-resolver
   ;; Extension introspection
   extension-paths-resolver
   extension-handlers-resolver
   extension-tools-resolver
   extension-commands-resolver
   extension-flags-resolver
   extension-details-resolver
   extension-detail-by-path-resolver
   extension-workflow-summary-resolver
   extension-workflows-resolver
   extension-workflow-detail-resolver
   ;; Extension UI
   extension-ui-resolver
   ;; Session listing
   session-list-resolver
   session-list-all-resolver])

;; ── Local Pathom env (for component-local queries) ──────

(defn build-env
  "Build a Pathom3 environment for querying an agent-session context.
   Includes memory + recursion resolvers locally so :psi.memory/* and
   :psi.recursion/* attrs are queryable via agent-session/query-in."
  []
  (->> (concat all-resolvers
               memory-resolvers/all-resolvers
               recursion-resolvers/all-resolvers)
       vec
       pci/register))

(def ^:private query-env (atom nil))

(defn- ensure-query-env! []
  (or @query-env (reset! query-env (build-env))))

(defn query-in
  "Run EQL `q` against `ctx` using this component's Pathom graph.
   Seeds memory + recursion contexts from the session when available."
  [ctx q]
  (p.eql/process (ensure-query-env!)
                 {:psi/agent-session-ctx ctx
                  :psi/memory-ctx        (or (:memory-ctx ctx)
                                             (memory/global-context))
                  :psi/recursion-ctx     (:recursion-ctx ctx)}
                 q))
