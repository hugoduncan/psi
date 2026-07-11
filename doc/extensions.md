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

## Built-in workflow capability

Workflow definition discovery from `.psi/workflows/` (`.md` single-step prompt workflows and `.edn` multi-step orchestration workflows), canonical definition registration, and the unified delegation surface are now built-in core behavior rather than an installable extension.

Canonical higher-core ownership now lives under:

- `psi.agent-session.workflow.core`
- `psi.agent-session.workflow.delivery`
- `psi.agent-session.workflow.text`
- `psi.agent-session.workflow.orchestration`
- `psi.agent-session.workflow.display`

Built-in workflow surface:

- Tool: `delegate`
  - actions: `run`, `list`, `continue`, `remove`
  - omitted `action` defaults to `run`
  - `list` is scoped to the invoking session and shows active or retained delegated workflow runs owned by that session, with canonical workflow status as the primary status and delegate/background attempt status shown separately when available
  - ids returned by `list` are canonical workflow run ids usable with `continue` when the workflow status supports continuation, and with `remove` while the run exists
  - `continue` pushes a stopped run forward with a new prompt
  - `remove` requests canonical workflow removal through dispatch. Live top-level removal is cancel-then-remove: psi cancels the run/subtree, terminalizes workflow background-job projections, interrupts the worker before dropping its runtime handle, then removes the canonical run record. Live nested sub-run removal aborts that sub-run's child turn without interrupting the shared parent worker, so the parent observes a cancelled/removed failed delegate step and continues. Terminal/absent removal is idempotent cleanup. Worker interrupt, child-turn abort, background-job terminalization, and handle cleanup are dispatch-owned runtime effects, not command-layer active-job pre-cleanup.
  - run options include `workflow`, `mode` (`sync|async`), `fork_session`, `timeout_ms`, `include_result_in_context`
- Psi-tool:
  - `workflow` with `op=cancel-run` stops an in-flight workflow while retaining its run record; see `doc/workflows.md` for cancellation/removal details and guarantees
- Commands:
  - `/delegate <workflow> <prompt>`
  - `/delegate-reload` — reloads workflow definitions and retires removed definitions
- Config:
  - `.psi/workflows/` with `.md` single-step prompt workflows and `.edn` multi-step orchestration workflows
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
- widget/UI consumers: `psi.agent-session.workflow.display/merged-display` + `display-lines`
- CLI/list consumers: `psi.agent-session.workflow.display/text-lines`

Current in-repo examples:
- `extensions.mcp-tasks-run` — widget + list output reuse `:run/display`
- built-in workflow surfaces — widget + `action=list` reuse unified workflow run display

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
  - fails if any file under `components/`, `bases/`, or `extensions/` in a `src/` or `test/` path exceeds 800 lines
  - explicitly recorded legacy oversized extension files are ratcheted: they pass at their recorded line counts, but fail if they grow
- `bb commit-check:dispatch-architecture`
  - fails on dispatch effect parity drift in `agent-session`
  - reports advisory warnings for handler side-effect candidates and direct canonical state writes outside an allowlist

### `extensions/metrics/src/psi/metrics/extension.clj` (`psi.metrics.extension`)

Purpose: accumulate persistent per-capability usage counters and persist them to
`.psi/metrics.edn` in the session worktree (`worktree/.psi/metrics.edn`).

- Triggers (events subscribed):
  - `tool_call` — increments per-tool `:invocations`
  - `tool_result` — when `:is-error`, increments per-tool `:errors` and the
    matching `:error-reasons` reason count (first error line, trimmed/truncated)
  - `session_turn_finished` — accrues per-model token totals from session usage
  - `provider_request_started` / `provider_retry_scheduled` /
    `provider_request_finished` — provider request/retry/outcome counters
- Persistence:
  - `worktree/.psi/metrics.edn`, written atomically via a temp file
  - schema-validated on load; invalid files are logged and ignored
- Deterministic operation:
  - `metrics/summary` — returns the current metrics map
- Command:
  - `/metrics` — renders a markdown usage summary
- Persisted shape (`metrics.edn`):
  - `:tools {tool-name {:invocations :int :errors :int :error-reasons {reason :int}}}`
  - `:workflows` / `:commands` / `:operations` `{name {:invocations :int}}`
  - `:tokens {model {:input :output :cache-read :cache-write}}`
  - `:providers {provider {:requests :successes :failures :final-failures
    :retries :retry-backoff-ms :error-types {kind :int}
    :models {model {…same counters…}}}}`
  - `:updated-at` timestamp

  The `:tools` map is populated for interactive/batch tool execution by the
  `tool_call` / `tool_result` extension-bus bridge in
  `psi.agent-session.tool-runtime-adapter/emit-tool-lifecycle!` (see task 198);
  before that bridge existed `:tools` was always `{}`.

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
  - `/psl` lists active PSL workflows by rendering that public display through `psi.agent-session.workflow.display/text-lines`
- Widget: shows `⊕ PSL` header with workflow display lines for active runs

### `extensions/context-manager/src/extensions/context_manager.clj` (`extensions.context-manager`)

Purpose: subscribes to `session_turn_finished` events and logs session-id and
turn-id via `(:log api)` (idempotent init for reload safety), and registers two
pre-turn turn augmenters under the `:psi.capability/turn-augmentation` grant.

- Turn augmenters (via `:register-turn-augmenter`):
  - `project-context` — emits a minimal `Project context` block reporting the
    effective working directory when available.
  - `entity-resolution` — automatic pre-turn entity resolution. For each
    eligible parent turn it runs a bounded local-model helper session (created
    with the existing `bash` tool only) that applies the `entity-resolution`
    skill's method (Method steps 1–5) to the user text plus a rendered
    conversation-history excerpt, and injects any confidently-resolved
    `surface → canonical (evidence)` mappings as a `Resolved entities` context
    block before the current user message.
    - Local-first: selects a single top-ranked local model (strong
      `:locality :local`, `:latency-tier :low`, `:cost-tier #{:zero :low}`,
      inheriting the parent session's model as context); never falls back to a
      cloud model.
    - `bash` is granted for read-only evidence gathering only; the helper is
      instructed not to mutate files or cause side effects. The helper run is
      bounded (round-cap prompt guidance plus a wall-clock budget); exceeding
      the bound is treated as a failed run.
    - Data-only and non-interactive: never mutates the submitted prompt or
      parent session; omits `:source` (core injects provenance); reports helper
      child-session ids in `:turn-augmentation/child-session-ids` and tracks
      them to avoid augmenting its own helper turns.
    - Returns a well-formed no-op for: tracked helper sessions, blank effective
      cwd, slash-command-only prompts (pre-filter, before spending a helper
      run), no confident mapping parsed, no local model available, and
      failed/empty/timed-out helper runs. Confidence is model self-gated — the
      model emits a line only for mappings it judges confident; the augmenter
      accepts every well-formed parsed line and drops the confidence token from
      the rendered block.
- Post-turn tooling-friction analyzer (task 239) — from the same
  `session_turn_finished` handler, after logging session-id/turn-id it spawns
  a fire-and-forget `future` that analyzes the last 4 turns for friction
  fixable by tooling/dependency changes (not project bugs or feature work),
  and automatically opens Munera tasks for newly identified issues.
  - Scope: every session (top-level, delegated, workflow, helper) except this
    analyzer's own helper sessions and other known helper/infra sessions
    (e.g. entity-resolution helper sessions, the auto-session-name
    extension's `"auto-session-name"` helper sessions, and workflow-runtime
    step-attempt child sessions named `"workflow <step-id> attempt"`) — a
    recursion guard.
  - Fire-and-forget: runs on its own thread outside the turn/dispatch
    critical path; the handler always returns promptly regardless of
    analysis outcome.
  - Single bounded, no-tools local-model helper session (selected the same
    local-first way as `entity-resolution`) performs both friction detection
    and duplicate matching (against all open tasks and the 20
    most-recently-closed tasks) as two phases of the same call.
  - Non-duplicate issues are capped at 2 created tasks per analysis run
    (remaining issues recur and are caught on a later turn); each created
    task is `munera/open/NNN-slug/design.md` only (per munera's
    design.md-only creation convention), written to the analyzed session's
    own worktree, and clearly marked as auto-generated with the observed
    friction, evidence (turn references), and suggested tooling/dependency
    change.
  - Every failure path (no local model available, missing worktree, helper
    failure/timeout, malformed or empty helper output) logs a diagnostic and
    no-ops — no task is created and the turn is never disrupted. A turn with
    an empty/blank recent-history excerpt (e.g. a freshly created session's
    first turn) is also short-circuited before the helper session runs — no
    local-model work is spent analyzing an empty conversation.
  - Per-session in-flight guard: a second `session_turn_finished` analysis
    for the same session is skipped/no-op'd (with a diagnostic) while a
    prior analysis run for that same session is still in flight, avoiding
    duplicate-task races that the dedup pass alone can't catch (dedup only
    checks tasks that existed before the run started).
- No commands, tools, or prompt contributions besides the augmenters above.

### `extensions/hello-ext/src/extensions/hello_ext.clj` (`extensions.hello-ext`)

Purpose: minimal example extension used in docs/tests.

- Commands:
  - `/hello`
  - `/hello-plan` (demo tool chaining)
- Tools:
  - `hello-upper`
  - `hello-wrap`

### `extensions/ramora/src/extensions/ramora.clj` (`extensions.ramora`)

Purpose: prompt-contribution-only extension that injects the Ramora protocol
(lambda-form project knowledge organization) into the system prompt.

- Prompt contribution:
  - ID: `ramora-protocol`
  - Section: `Ramora Protocol`
  - Priority: 52 (after mementum=50, munera=51)
  - In lambda mode: raw `protocol.txt` content
  - In prose mode: engage prefix + `protocol.txt` content
- No commands, tools, operations, or event handlers

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

The current `auto-session-name` extension is the reference example: it runs only
for top-level user-interactive source sessions, explicitly excluding delegated
workflow sessions, workflow-step sessions, nested workflow sessions, and its own
helper sessions. For eligible source sessions it queries the source session
model context, builds its own explicit helper-model request, and passes the
resulting candidate explicitly into `psi.extension/run-agent-loop-in-session`.

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
| `"tool_result"`           | `{:type :tool-name :tool-call-id :input :content :details :is-error}` | modify  | `:input` matches the `tool_call` event on both paths. See [Tool Wrapping](#tool-wrapping) |

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
`:is-error` to modify the result. The `"tool_result"` event also carries
`:input` (the parsed tool arguments, matching the `"tool_call"` event) and
`:details`, so a handler can correlate the result with its originating call.

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

The `:ui` API is for contributing content to an attached UI.  Extensions
that need to discover what UI behaviour is available should query the
core graph instead of branching on concrete UI type.  Use the runtime
`[:psi.ui/type :psi.ui/available? :psi.ui/capabilities :psi.ui/actions
:psi.ui/make-visible-action]` surface to inspect UI capability/action
data.  `:psi.ui/type`, `:ui-type`, and `:psi.agent-session/ui-type` remain
diagnostic compatibility data; they are not the normative contract for
deciding whether a UI action can be invoked.

### Queryable UI capabilities and actions

UI capabilities are core-owned, runtime-scoped EQL data derived from the
active UI adapter on demand.  They are not stored as extension state and
should be treated as the current advertised behaviour of the attached UI.

Query the capability/action surface before deciding UI behaviour:

```clojure
(let [ui-state ((:query api) [:psi.ui/type
                             :psi.ui/available?
                             :psi.ui/capabilities
                             :psi.ui/actions
                             :psi.ui/make-visible-action])
      can-make-visible? (contains? (set (:psi.ui/capabilities ui-state))
                                   :psi.ui.capability/make-visible)
      make-visible      (:psi.ui/make-visible-action ui-state)]
  (when (and can-make-visible?
             (:psi.ui.action/available? make-visible))
    ;; Task 190 exposes descriptor discovery only. Until
    ;; 191-ui-action-invocation lands, callers may present or record this
    ;; descriptor, but must not submit it as an executable UI request.
    ;; Do not call frontend namespaces directly.
    (:psi.ui.action/invocation make-visible)))
```

Capability keywords use the `:psi.ui.capability/...` namespace.  Action
descriptors use fully namespaced `:psi.ui.action/...` keys and contain pure,
serialisable data.  A supported make-visible descriptor looks like:

```clojure
{:psi.ui.action/id :psi.ui.action/make-visible
 :psi.ui.action/capability :psi.ui.capability/make-visible
 :psi.ui.action/label "Show Psi UI"
 :psi.ui.action/description "Bring the active Psi UI to the foreground."
 :psi.ui.action/available? true
 :psi.ui.action/invocation {:psi.ui.invocation/kind :emacs-command
                            :psi.ui.invocation/command "psi-emacs-show-active"}}
```

Unavailable cases are explicit.  `:psi.ui/actions` contains only currently
available action descriptors, while `:psi.ui/make-visible-action` always
returns a descriptor-shaped value so callers can inspect a stable shape:

```clojure
{:psi.ui.action/id :psi.ui.action/make-visible
 :psi.ui.action/capability :psi.ui.capability/make-visible
 :psi.ui.action/label "Show Psi UI"
 :psi.ui.action/description "Bring the active Psi UI to the foreground."
 :psi.ui.action/available? false
 :psi.ui.action/unavailable-reason :psi.ui.unavailable.reason/no-attached-ui
 :psi.ui.action/unavailable-message "No attached UI adapter can make itself visible."}
```

Known unavailable reasons are:

- `:psi.ui.unavailable.reason/no-provider` — no adapter capability provider is installed.
- `:psi.ui.unavailable.reason/no-attached-ui` — a provider exists, but no usable UI is attached.
- `:psi.ui.unavailable.reason/unsupported-capability` — an attached UI does not support the requested capability.
- `:psi.ui.unavailable.reason/provider-error` — the provider failed or returned invalid data.

Provider-error cases may also expose `:psi.ui/diagnostic` as bounded
troubleshooting text.  Extensions should branch on capability membership,
`:psi.ui.action/available?`, and unavailable reason keywords, not on
`:psi.ui/diagnostic` or UI type.

Task 190 is query/descriptor-only: it does not implement side-effecting
submission of a descriptor through the core UI action request path.  The planned
request path is owned by `191-ui-action-invocation`; until that lands,
extensions may inspect, display, or store descriptor data, but must not assume a
supported API exists to execute `:psi.ui.action/invocation` values.


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

;; UI capability/action surface
(s/query-in ctx [:psi.ui/type
                 :psi.ui/available?
                 :psi.ui/capabilities
                 :psi.ui/actions
                 :psi.ui/make-visible-action
                 :psi.ui/diagnostic])

;; UI contribution snapshot state
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

**UI capability/action surface** (`:psi.ui/*`):

| Attribute                         | Type       | Description                          |
|-----------------------------------|------------|--------------------------------------|
| `:psi.ui/type`                    | `keyword?` | Active UI adapter identity for diagnostics/compatibility, not behaviour branching |
| `:psi.ui/available?`              | `boolean`  | True when a concrete UI adapter is attached |
| `:psi.ui/capabilities`            | `[keyword]`| Current UI capability keywords such as `:psi.ui.capability/make-visible` |
| `:psi.ui/actions`                 | `[map]`    | Currently available pure-data UI action descriptors |
| `:psi.ui/make-visible-action`     | `map`      | Stable make-visible descriptor; available when supported, otherwise unavailable with reason/message |
| `:psi.ui/diagnostic`              | `string?`  | Optional bounded provider-error diagnostic text |

**UI contribution snapshot state** (`:psi.ui/*`):

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
| `psi.agent-session.extensions`| agent-session   | Registry, loading, event dispatch, tool-result filtering |
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
