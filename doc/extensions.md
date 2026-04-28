# Psi Extensions

Extensions customise psi's behaviour: add tools, intercept events,
wrap tool execution, contribute UI elements, and register custom
renderers.

An extension is a `.clj` file with a namespace that exports an `init`
function.  The loader calls `init` with an API map — the extension
calls registration functions on that map to declare what it provides.

## Quick Start

Create `~/.psi/agent/extensions/hello_ext.clj`:

```clojure
(ns my.hello-ext)

(defn init [api]
  ;; Register a slash command (ext-path is injected automatically)
  ((:mutate api) 'psi.extension/register-command
   {:name "hello"
    :opts {:description "Say hello"
           :handler     (fn [_args] (println "Hello from extension!"))}})

  ;; Listen to events
  ((:mutate api) 'psi.extension/register-handler
   {:event-name "session_switch"
    :handler-fn (fn [ev] (println "Session switched:" (:reason ev)))})

  ;; Show a status line in the TUI footer
  (when-let [ui (:ui api)]
    ((:set-status ui) "hello-ext loaded")))
```

Psi discovers and loads it automatically on startup.

## Testing Extensions (Nullable API)

Psi includes a **nullable ExtensionAPI test fixture** for fast,
state-based extension tests without mocks/spies.

Location:
- `components/extension-test-helpers/src/psi/extension_test_helpers/nullable_api.clj`

Main entry points:
- `create-nullable-extension-api` → returns `{:api .. :state atom}`
- `with-user-dir` → macro to run tests with a temporary `user.dir`

The nullable API keeps in-memory state for:
- registered tools/commands/handlers/flags/shortcuts
- workflow type and workflow mutations
- query/mutation calls
- UI calls (`:notify`, `:set-widget`, `:clear-widget`, `:set-status`)

This enables **narrow tests** that assert outcomes/state, e.g. "did
`init` register the expected commands".

Example:

```clojure
(ns extensions.hello-ext-test
  (:require
   [clojure.test :refer [deftest is]]
   [extensions.hello-ext :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest init-registers-hello-command
  (let [{:keys [api state]} (nullable/create-nullable-extension-api
                             {:path "/test/hello_ext.clj"})]
    (sut/init api)
    (is (= "hello" (get-in @state [:commands "hello" :name])))
    (is (= 1 (count (get-in @state [:handlers "session_switch"]))))))
```

For cwd-sensitive extensions (e.g. reading project-local `.psi/` config), wrap with
`with-user-dir`:

```clojure
(nullable/with-user-dir (.getAbsolutePath tmp-dir)
  (sut/init api)
  ...)
```

## Built-in extensions in this repo (`extensions/` local-root projects)

These extensions ship with the project as per-extension local-root libraries and
are activated in this repo through `.psi/extensions.edn`.

### `extensions/workflow-loader/src/extensions/workflow_loader.clj` (`extensions.workflow-loader`)

Purpose: discover workflow definitions from `.psi/workflows/`, compile/register
canonical workflow definitions, and expose a unified delegation surface for both
single-step profiles and multi-step orchestrations.

- Tool: `delegate`
  - actions: `run`, `list`, `continue`, `remove`
  - omitted `action` defaults to `run`
  - `continue` pushes a stopped run forward with a new prompt
  - `remove` deletes a run (it does not cancel and retain it)
  - run options include `workflow`, `mode` (`sync|async`), `fork_session`, `timeout_ms`, `include_result_in_context`
- Commands:
  - `/delegate <workflow> <prompt>`
  - `/delegate-reload` — reloads workflow definitions and retires removed definitions
- Config:
  - `.psi/workflows/*.md`
  - multi-step workflow files now author cross-step data flow through `:session`
    - `:session :input` owns `$INPUT`
    - `:session :reference` owns `$ORIGINAL`
    - `:session :preload` adds child-session context without implicitly changing `$INPUT` or `$ORIGINAL`
    - `{:step "..." ...}` references author-facing step `:name`, not delegated workflow names

### `extensions/mcp-tasks-run/src/extensions/mcp_tasks_run.clj` (`extensions.mcp-tasks-run`)

Purpose: run mcp-tasks task/story workflows with sub-agent execution per step.

- Command:
  - `/mcp-tasks-run <task-id>`
  - `/mcp-tasks-run list`
  - `/mcp-tasks-run pause <run-id>`
  - `/mcp-tasks-run resume <run-id> [merge|<answer>]`
  - `/mcp-tasks-run cancel <run-id>`
  - `/mcp-tasks-run retry <run-id>`

## Workflow display convention

Workflow-backed extensions should prefer projecting reusable display/read-model
fields through `:public-data-fn` instead of having each widget/command derive
its own formatting from private runtime state.

Preferred display-map keys:
- `:top-line` — primary summary line
- `:detail-line` — optional secondary line
- `:question-lines` — optional follow-up lines/questions
- `:action-line` — optional fallback action/help line

The display payload itself may live under an extension-specific namespaced key,
for example:
- `:run/display`
- `:delegate/display`

Preferred helper path:
- widget/UI consumers: `extensions.workflow-display/merged-display` + `display-lines`
- CLI/list consumers: `extensions.workflow-display/text-lines`

Current in-repo examples:
- `extensions.mcp-tasks-run` — widget + list output reuse `:run/display`
- `extensions.workflow-loader` — widget + `action=list` reuse unified workflow run display

### `extensions/commit-checks/src/extensions/commit_checks.clj` (`extensions.commit-checks`)

Purpose: run project-local external checks after a new local commit and inject failures back into the session as a prompt.

- Trigger: `git_commit_created` event
- Config:
  - `.psi/commit-checks.edn`
- Behavior:
  - reads config from the session `:workspace-dir`
  - runs each configured command with `babashka.process`
  - command form is a non-empty vector of strings under `:cmd`
  - collects only failing commands (non-zero exit or timeout)
  - injects one combined follow-up prompt with the failing outputs
- Event payload relied on:
  - `:session-id`
  - `:workspace-dir`
  - `:head`

Example config:

```clojure
{:enabled true
 :prompt-header "Commit checks failed after the latest commit. Diagnose and fix the problems with minimal changes."
 :max-output-chars 12000
 :commands
 [{:id "rama-cc"
   :cmd ["bb" "commit-check:rama-cc"]
   :timeout-ms 20000}
  {:id "file-lengths"
   :cmd ["bb" "commit-check:file-lengths"]
   :timeout-ms 20000}]}
```

The example project config in this repo defines these bb tasks:
- `bb commit-check:rama-cc`
  - runs `rama-cc components/ --threshold 21 --fail-above 20`
  - then runs `rama-cc bases/ --threshold 21 --fail-above 20`
- `bb commit-check:file-lengths`
  - fails if any file under `components/` or `bases/` in a `src/` or `test/` path exceeds 800 lines
- `bb commit-check:dispatch-architecture`
  - fails on dispatch effect parity drift in `agent-session`
  - reports advisory warnings for handler side-effect candidates and direct canonical state writes outside an allowlist

### `extensions/plan-state-learning/src/extensions/plan_state_learning.clj` (`extensions.plan-state-learning`)

Purpose: automate munera + mementum working-memory follow-up after non-PSL commits.

- Trigger: `git_commit_created` event
- Behavior:
  - skips self-commits with marker `[psi:psl-auto]`
  - creates PSL workflow
  - runs agent to update/commit `munera/plan.md` and `mementum/state.md`
  - may suggest memory/knowledge follow-ups, but does not auto-write gated mementum artifacts
- Workflow public data:
  - exposes `:psl/display` using the shared workflow display-map convention
  - `/psl` lists active PSL workflows by rendering that public display through `extensions.workflow-display/text-lines`
- Widget: shows `⊕ PSL` header with workflow display lines for active runs

### `extensions/hello-ext/src/extensions/hello_ext.clj` (`extensions.hello-ext`)

Purpose: minimal example extension used in docs/tests.

- Commands:
  - `/hello`
  - `/hello-plan` (demo tool chaining)
- Tools:
  - `hello-upper`
  - `hello-wrap`

## Install manifests

Psi now supports launcher-owned `extensions.edn` install manifests for explicit
user/project extension configuration.

See:
- [`doc/extensions-install.md`](extensions-install.md)

Canonical ownership:
- launcher owns startup dependency availability
- runtime owns post-startup extension behavior, introspection, and reload/apply convenience behavior

Current behavior:
- user/project manifests participate in startup basis construction before `psi.main` starts
- recognized psi-owned extension libs can use concise manifest entries such as `{}` and receive launcher defaults plus deterministic `:psi/init` inference
- manifest-backed `:local/root`, git, and mvn extension entries are startup-activatable when their expanded dependency entries are valid
- local-root installs activate from resolved source file paths
- non-file-backed git/mvn installs activate by resolving and calling `:psi/init`, not by source-file path discovery
- non-file-backed manifest installs register in the live extension registry under stable identities of the form `manifest:{lib}`
- startup summary fields reflect actual activation attempts and results
- reload/apply uses the same manifest-aware activation layer as startup, while still reporting `:restart-required` when dependency realization cannot be completed safely in-process
- this repo’s built-in extensions now load from `.psi/extensions.edn` local-root entries rather than `.psi/extensions/` symlinks

## Discovery

Legacy extension file discovery still exists for explicit file-path based extension loading,
but the canonical install/config surface is now `extensions.edn`.

## Extension API

The `init` function receives a map with these keys:

### Registration

| Key                  | Signature                                 | Description                          |
|----------------------|-------------------------------------------|--------------------------------------|
| `:on`                | `(fn [event-name handler-fn])`            | Subscribe to a named event           |
| `:register-tool`     | `(fn [tool-map])`                         | Register a tool for the agent        |
| `:register-command`  | `(fn [name opts])`                        | Register a `/name` slash command     |
| `:register-flag`     | `(fn [name opts])`                        | Register a toggleable flag           |
| `:register-shortcut` | `(fn [key opts])`                         | Register a keyboard shortcut         |

Common extension events emitted by the runtime include:
- `git_commit_created` — emitted only for normal local commit creation
  - suppressed for merges, rebases, amend, reset, checkout, cherry-pick, and transient git operations
  - payload includes `:session-id`, `:workspace-dir`, `:cwd`, `:head`, `:previous-head`, `:reason`, `:classification`, `:timestamp`

### Runtime Surface

For helper/background workflows, prefer explicit session-targeted access when an
extension is acting on a source session other than the ambient one:

- `(:query-session api) session-id eql-query`
- `(:mutate-session api) session-id op-sym params`

This is especially important for delayed/scheduled work and helper-session
patterns.

| Key      | Signature                   | Description                                  |
|----------|-----------------------------|----------------------------------------------|
| `:query`       | `(fn [eql-query])`          | Run an EQL query through the session runtime |
| `:mutate`      | `(fn [op-sym params])`      | Run an EQL mutation through the runtime      |
| `:create-session` | `(fn [opts])`            | Create a new active context-peer session        |
| `:switch-session` | `(fn [session-id])`      | Switch to an existing context session by id     |
| `:get-api-key` | `(fn [provider])`           | Resolve provider API key (narrow capability) |

#### Managed services

Extensions can use the managed-service surface for long-lived subprocesses or
similar runtime-owned helpers:

- `(:ensure-service api) {:key ... :type :subprocess :spec ...}`
- `(:stop-service api) service-key`
- `(:service-request api) {:key ... :request-id ... :payload ... :timeout-ms ...}`
- `(:service-notify api) {:key ... :payload ...}`
- `(:list-services api)`

Design guidance:
- treat the managed-service core as protocol-agnostic lifecycle and transport ownership
- prefer integration-local adapters for protocol semantics layered on top of this core
- do not expand the generic managed-service core with protocol-specific behavior unless there is clear multi-integration justification
- if a future integration needs JSON-RPC or similar framing/projection behavior, implement that adapter in the integration layer and prove it with integration-local tests

`(:mutate api)` is extension-scoped for `psi.extension/*` mutations:

- If `op-sym` is in the `psi.extension` namespace (or a sub-namespace like
  `psi.extension.workflow`) and `params` is a map, psi automatically injects
  `:ext-path` for the current extension when it is missing.
- Non-`psi.extension/*` mutations are passed through unchanged.
- If `:ext-path` is explicitly provided, it is respected.

Example (no explicit `:ext-path` required):

```clojure
((:mutate api) 'psi.extension/register-command
 {:name "hello"
  :opts {:description "Say hello"
         :handler (fn [_] (println "hi"))}})
```

#### Programmatic tool plans (`psi.extension/run-tool-plan`)

Use `psi.extension/run-tool-plan` when an extension needs deterministic,
programmatic tool orchestration (instead of asking the LLM to decide tool
calls).

Canonical helper:

```clojure
(defn run-tool-plan!
  [api steps]
  ((:mutate api) 'psi.extension/run-tool-plan
   {:steps          steps
    :stop-on-error? true}))
```

Example chain (step 2 uses step 1 output):

```clojure
(let [result (run-tool-plan!
              api
              [{:id :s1
                :tool "hello-upper"
                :args {:text "hello from plan"}}
               {:id :s2
                :tool "hello-wrap"
                :args {:text   [:from :s1 :content]
                       :prefix "["
                       :suffix "]"}}])]
  (when-not (:psi.extension.tool-plan/succeeded? result)
    (throw (ex-info "tool plan failed"
                    {:error (:psi.extension.tool-plan/error result)})))
  (get-in result [:psi.extension.tool-plan/result-by-id :s2 :content]))
;; => "[HELLO FROM PLAN]"
```

`[:from <step-id> <path...>]` references resolve against the prior step's tool
result map (typically `:content`, `:is-error`, and optional `:details`).

Built-in tool execution mutations are also available for direct programmatic
use:

- `psi.extension.tool/read`   (`:path`, optional `:offset`, `:limit`)
- `psi.extension.tool/bash`   (`:command`, optional `:timeout`)
- `psi.extension.tool/write`  (`:path`, `:content`)
- `psi.extension.tool/update` (`:path`, `:oldText`, `:newText`) — backed by `edit`
- `psi.extension.tool/chain`  (alias of `psi.extension/run-tool-plan`)

### Session Actions

| Key                  | Signature                                 | Description                          |
|----------------------|-------------------------------------------|--------------------------------------|
| `:notify`            | `(fn [content opts?])`                    | Emit a UI/transcript-visible message that is excluded from future LLM-visible conversation assembly |
| `:append-message`    | `(fn [role content])`                     | Append a synthetic conversation-visible message that becomes part of future LLM-visible conversation assembly |
| `:send-user-message` | `(fn [content opts?])`                    | Send a user message                  |
| `:append-entry`      | `(fn [custom-type data?])`                | Append a custom journal entry        |
| `:set-session-name`  | `(fn [name])`                             | Set the session name                 |
| `:create-session`    | `(fn [opts])`                             | Create a new active context-peer session |
| `:switch-session`    | `(fn [session-id])`                       | Switch to an existing context session by id |
| `:get-session-name`  | `(fn [])`                                 | Get the current session name         |
| `:set-label`         | `(fn [entry-id label])`                   | Label a journal entry                |
| `:get-active-tools`  | `(fn [])`                                 | Get active tool names                |
| `:set-active-tools`  | `(fn [tool-names])`                       | Filter active tools by name          |
| `:get-model`         | `(fn [])`                                 | Get the current model map            |
| `:set-model`         | `(fn [model])`                            | Set the model                        |
| `:is-idle`           | `(fn [])`                                 | True when the session is idle        |
| `:abort`             | `(fn [])`                                 | Abort the current agent run          |
| `:compact`           | `(fn [opts?])`                            | Trigger manual compaction            |
| `:get-system-prompt` | `(fn [])`                                 | Get the current system prompt        |
| `:register-prompt-contribution` | `(fn [id contribution])`          | Register/update an extension-owned prompt contribution |
| `:update-prompt-contribution`   | `(fn [id patch])`                  | Patch an extension-owned prompt contribution |
| `:unregister-prompt-contribution` | `(fn [id])`                      | Remove an extension-owned prompt contribution |
| `:list-prompt-contributions`    | `(fn [])`                          | List this extension's prompt contributions |

`:create-session` and `:switch-session` are thin extension-facing wrappers over the session lifecycle surface.
Use them when an extension needs to create a distinct context session (for example, a new worktree-bound session) or move routing to an existing resumable context session by id.

When a helper/background workflow needs model choice, extensions should prefer
shared resolution via `psi.ai.model-selection/resolve-selection` rather than
embedding provider/id fallback chains locally. Extensions do not need a
core-defined role to do this: they may submit a fully explicit request, or
construct their own local preset/request builder.

The current `auto-session-name` extension is the reference example: it queries
the source session model context, builds its own explicit helper-model request,
and passes the resulting candidate explicitly into
`psi.extension/run-agent-loop-in-session`.

Example:

```clojure
;; Create a new worktree-bound session and make it active
((:create-session api)
 {:session-name "Fix footer"
  :worktree-path "/repo/fix-footer"
  :system-prompt ((:query api) [:psi.agent-session/system-prompt])})

;; Later, switch back by known session id
((:switch-session api) "session-uuid")
```

### Prompt Contributions

Extensions can contribute deterministic prompt fragments that are merged into
system prompt assembly as an extension-managed layer.

```clojure
;; Register or replace a contribution owned by this extension
((:register-prompt-contribution api) "domain-hints"
 {:section  "Domain Hints"
  :content  "Prefer stable IDs over names when correlating entities."
  :priority 200
  :enabled  true})

;; Patch selected fields
((:update-prompt-contribution api) "domain-hints"
 {:content "Prefer stable IDs; validate cross-reference integrity."
  :enabled true})

;; List this extension's contributions
((:list-prompt-contributions api))

;; Remove when no longer needed
((:unregister-prompt-contribution api) "domain-hints")
```

Guidance:
- Keep contributions concise and task-relevant.
- Use stable `id` values so reloads update instead of duplicating.
- This mechanism is domain-agnostic (not specific to any one use case).

### Inter-Extension Communication

| Key       | Value                                                 |
|-----------|-------------------------------------------------------|
| `:events` | `{:emit (fn [channel data]) :on (fn [channel handler-fn])}` |

`:on` returns a zero-arg unsubscribe function.

### UI Context

| Key   | Value                                                    |
|-------|----------------------------------------------------------|
| `:ui` | UI context map (see [UI Extension Points](#ui-extension-points)), or `nil` when headless |

### Identity

| Key     | Value                          |
|---------|--------------------------------|
| `:path` | Absolute path of this extension file |

## Events

Extensions subscribe to named events via `(:on api)`.  Handlers fire in
registration order (first registered, first called).  All handlers fire
for every event — this is broadcast semantics, not first-match.

### Event List

| Event                     | Data                                      | Cancel? | Notes                                     |
|---------------------------|-------------------------------------------|---------|--------------------------------------------|
| `"session_switch"`        | `{:reason :new\|:resume}`                 | —       | After session switch                       |
| `"session_before_switch"` | `{:reason :new\|:resume}`                 | ✓       | Return `{:cancel true}` to block           |
| `"session_before_compact"`| `{:preparation ... :custom-instructions}` | ✓       | Return `{:result CompactionResult}` to override |
| `"session_compact"`       | `{}`                                      | —       | After compaction completes                 |
| `"session_before_fork"`   | `{:entry-id ...}`                         | —       | Before forking from an entry               |
| `"session_fork"`          | `{}`                                      | —       | After fork completes                       |
| `"model_select"`          | `{:model ... :source :set}`               | —       | After model change                         |
| `"tool_call"`             | `{:type :tool-name :tool-call-id :input}` | block   | See [Tool Wrapping](#tool-wrapping)        |
| `"tool_result"`           | `{:type :tool-name :content :is-error}`   | modify  | See [Tool Wrapping](#tool-wrapping)        |

**Cancel semantics**: If *any* handler returns `{:cancel true}`, the
associated action is blocked.  Remaining handlers still fire.

## Tool Registration

Extensions register tools that become available to the agent:

```clojure
((:register-tool api)
 {:name        "search-docs"
  :description "Search project documentation"
  :parameters  [{:name "query" :type "string" :required true}]
  :execute     (fn [args]
                 {:content (str "Found: " (:query args))
                  :is-error false})})
```

## Tool Wrapping

Extensions can intercept tool execution without registering new tools.
Subscribe to `"tool_call"` (before) and `"tool_result"` (after):

```clojure
;; Block dangerous commands
((:on api) "tool_call"
 (fn [{:keys [tool-name input]}]
   (when (and (= tool-name "bash")
              (clojure.string/includes? (:command input) "rm -rf"))
     {:block true :reason "Dangerous command blocked"})))

;; Modify results
((:on api) "tool_result"
 (fn [{:keys [tool-name content]}]
   (when (= tool-name "bash")
     {:content (str content "\n[logged by extension]")})))
```

A `"tool_call"` handler returning `{:block true}` prevents execution.
A `"tool_result"` handler may return `:content`, `:details`, or
`:is-error` to modify the result.

## Flags

Extensions register named flags with defaults:

```clojure
((:register-flag api) "verbose"
 {:description "Enable verbose output"
  :default     false})

;; Read anywhere
((:get-flag api) "verbose") ;; => false
```

Flag values persist across extension reloads.

## UI Extension Points

When psi runs with a TUI (`--tui`), the API includes a `:ui` key with
methods for dialogs, widgets, status lines, notifications, and custom
renderers.  In headless mode, `:ui` is `nil` — extensions should check
before calling.

```clojure
(when-let [ui (:ui api)]
  ;; safe to use ui methods
  )
```

### Dialogs

Dialogs block the calling thread until the user responds.  Only one
dialog is active at a time; others queue FIFO.

```clojure
(when-let [ui (:ui api)]
  ;; Confirm dialog — returns true/false
  (let [ok? ((:confirm ui) "Delete file?" "Are you sure?")]
    (when ok? (delete-file!)))

  ;; Select dialog — returns selected :value string, or nil
  (let [choice ((:select ui) "Pick format"
                 [{:value "json" :label "JSON" :description "Standard format"}
                  {:value "edn"  :label "EDN"  :description "Clojure format"}])]
    (when choice (export! choice)))

  ;; Input dialog — returns entered text, or nil
  (let [name ((:input ui) "Project name" "my-project")]
    (when name (create-project! name))))
```

**Headless fallback**: When there is no TUI, `:ui` is nil.  If an
extension calls dialog functions on a nil atom directly (via the
lower-level API), confirm returns `false`, select and input return
`nil`.

### Widgets

Widgets are persistent content blocks rendered above or below the
editor.  Each widget is keyed by `[extension-id widget-id]` to prevent
collisions.

```clojure
(when-let [ui (:ui api)]
  ;; Add a widget above the editor
  ((:set-widget ui) "token-counter" :above-editor
   ["Tokens: 1,234 / 100,000"
    "Context: 1.2%"])

  ;; Update it later
  ((:set-widget ui) "token-counter" :above-editor
   ["Tokens: 5,678 / 100,000"
    "Context: 5.7%"])

  ;; Remove it
  ((:clear-widget ui) "token-counter"))
```

Placements: `:above-editor`, `:below-editor`.

### Status Lines

Each extension gets one persistent status line in the footer:

```clojure
(when-let [ui (:ui api)]
  ((:set-status ui) "✓ Connected to database")
  ;; Later:
  ((:clear-status ui)))
```

### Notifications

Non-blocking toasts that auto-dismiss after 5 seconds.  At most 3
visible at a time; older ones are dismissed when new ones arrive.

```clojure
(when-let [ui (:ui api)]
  ((:notify ui) "File saved successfully" :info)
  ((:notify ui) "Rate limit approaching" :warning)
  ((:notify ui) "Connection lost" :error))
```

Levels: `:info`, `:warning`, `:error`.

### Custom Renderers

Extensions can override how tool calls and results are displayed, and
add renderers for custom message types.

```clojure
(when-let [ui (:ui api)]
  ;; Custom tool renderer
  ((:register-tool-renderer ui) "search_docs"
   ;; render-call-fn: (fn [args] → ANSI string)
   (fn [args] (str "🔍 Searching: " (:query args)))
   ;; render-result-fn: (fn [result opts] → ANSI string)
   (fn [result _opts] (str "📄 " (:content result))))

  ;; Custom message renderer
  ((:register-message-renderer ui) "code-review"
   ;; render-fn: (fn [message opts] → ANSI string)
   (fn [msg _opts] (str "📝 Review: " (:summary msg)))))
```

Render functions return ANSI strings.

### UI Method Summary

| Method                       | Signature                                          | Returns        |
|------------------------------|----------------------------------------------------|----------------|
| `:confirm`                   | `(fn [title message])`                             | `boolean`      |
| `:select`                    | `(fn [title options])`                             | `string?`      |
| `:input`                     | `(fn [title placeholder?])`                        | `string?`      |
| `:set-widget`                | `(fn [widget-id placement content])`               | —              |
| `:clear-widget`              | `(fn [widget-id])`                                 | —              |
| `:set-status`                | `(fn [text])`                                      | —              |
| `:clear-status`              | `(fn [])`                                          | —              |
| `:notify`                    | `(fn [message level])`                             | —              |
| `:register-tool-renderer`    | `(fn [tool-name render-call-fn render-result-fn])` | —              |
| `:register-message-renderer` | `(fn [custom-type render-fn])`                     | —              |

## EQL Introspection

All extension and UI state is queryable via EQL from a connected nREPL:

```clojure
(require '[psi.agent-session.core :as s])
(def ctx (:ctx @psi.app-runtime/session-state))

;; Extension registry
(s/query-in ctx [:psi.extension/paths
                 :psi.extension/count
                 :psi.extension/handler-events
                 :psi.extension/tool-names
                 :psi.extension/command-names
                 :psi.extension/flag-names
                 :psi.extension/flag-values
                 :psi.extension/details])

;; UI state
(s/query-in ctx [:psi.ui/dialog-queue-empty?
                 :psi.ui/active-dialog
                 :psi.ui/pending-dialog-count
                 :psi.ui/widgets
                 :psi.ui/statuses
                 :psi.ui/visible-notifications
                 :psi.ui/tool-renderers
                 :psi.ui/message-renderers])
```

### EQL Attributes

**Extension registry** (`:psi.extension/*`):

| Attribute                      | Type           | Description                        |
|--------------------------------|----------------|------------------------------------|
| `:psi.extension/paths`         | `[string]`     | Registered extension file paths    |
| `:psi.extension/count`         | `int`          | Number of loaded extensions        |
| `:psi.extension/handler-events`| `[string]`     | Event names with handlers          |
| `:psi.extension/handler-count` | `int`          | Total handler registrations        |
| `:psi.extension/tools`         | `[map]`        | Tool definitions (sans `:execute`) |
| `:psi.extension/tool-names`    | `[string]`     | Registered tool names              |
| `:psi.extension/commands`      | `[map]`        | Commands (sans `:handler`)         |
| `:psi.extension/command-names` | `[string]`     | Registered command names           |
| `:psi.extension/flags`         | `[map]`        | Flag definitions with current values |
| `:psi.extension/flag-names`    | `[string]`     | Registered flag names              |
| `:psi.extension/flag-values`   | `{name value}` | Current flag values                |
| `:psi.extension/details`       | `[map]`        | Per-extension detail maps          |

**UI state** (`:psi.ui/*`):

| Attribute                          | Type       | Description                          |
|------------------------------------|------------|--------------------------------------|
| `:psi.ui/dialog-queue-empty?`      | `boolean`  | True when no dialogs active/pending  |
| `:psi.ui/active-dialog`            | `map?`     | Current dialog (sans promise)        |
| `:psi.ui/pending-dialog-count`     | `int`      | Queued dialogs waiting               |
| `:psi.ui/widgets`                  | `[map]`    | All widget entries                   |
| `:psi.ui/statuses`                 | `[map]`    | All status line entries              |
| `:psi.ui/visible-notifications`    | `[map]`    | Non-dismissed notifications (max 3)  |
| `:psi.ui/tool-renderers`           | `[map]`    | Tool renderer metadata               |
| `:psi.ui/message-renderers`        | `[map]`    | Message renderer metadata            |

## Extension Lifecycle

1. **Discovery** — paths collected from standard locations + CLI flags
2. **Load** — each `.clj` file is `load-file`d, `ns` form is read to resolve the namespace
3. **Init** — the namespace's `init` var is called with the API map
4. **Active** — handlers fire on events, UI contributions render in TUI
5. **Reload** — `reload-extensions-in!` unregisters all, clears UI state, re-discovers and re-loads

On reload, all extension registrations (handlers, tools, commands, flags,
shortcuts) and all UI contributions (widgets, status lines, notifications,
renderers) are cleared.  Active and pending dialogs are cancelled (promises
deliver `nil`).  Flag *values* are preserved across reloads.

## Implementation

The extension system spans two components:

| Namespace                     | Component       | Role                                     |
|-------------------------------|-----------------|------------------------------------------|
| `psi.agent-session.extensions`| agent-session   | Registry, loading, event dispatch, tool wrapping |
| `psi.tui.extension-ui`       | tui             | UI state atom, dialogs, widgets, renderers |
| `psi.agent-session.resolvers` | agent-session  | EQL resolvers (`:psi.extension/*`, `:psi.ui/*`) |
| `psi.agent-session.core`     | agent-session   | Context wiring, `make-extension-action-fns` |

`extension-ui` lives in the `tui` component because `tui/app.clj` needs
to require it for rendering, and `agent-session` depends on `tui` (not
vice versa).

## Example: Full Extension

```clojure
(ns my.code-stats-ext
  (:require [clojure.string :as str]))

(defn init [api]
  (let [counter (atom 0)]

    ;; Track tool calls
    ((:on api) "tool_call"
     (fn [{:keys [tool-name]}]
       (swap! counter inc)
       ;; Update widget if TUI is active
       (when-let [ui (:ui api)]
         ((:set-widget ui) "stats" :below-editor
          [(str "Tool calls: " @counter)]))))

    ;; Register a command to show stats
    ((:register-command api) "stats"
     {:description "Show tool call count"
      :handler     (fn [_args]
                     (println "Total tool calls:" @counter))})

    ;; Register a flag
    ((:register-flag api) "stats-verbose"
     {:description "Show detailed tool stats"
      :default     false})

    ;; Notify on load
    (when-let [ui (:ui api)]
      ((:notify ui) "Code stats extension loaded" :info))

    ;; Inter-extension communication
    ((:on (:events api)) "stats-request"
     (fn [_data]
       ((:emit (:events api)) "stats-response" {:count @counter})))))
```

## Spec

See [`spec/extension-system.allium`](../spec/extension-system.allium)
for the extension system behavioural specification and
[`spec/ui-extension-points.allium`](../spec/ui-extension-points.allium)
for the UI extension points specification.
