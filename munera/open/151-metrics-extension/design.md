# Metrics Extension

**Issue:** [#75 — Add metrics extension](https://github.com/hugoduncan/psi/issues/75)

## Intent

A standard extension that observes system activity and maintains persistent usage counters for registered capabilities — tools, skills, commands, workflows, and deterministic operations. Counters survive process restarts so that usage patterns are visible over time.

## Problem Statement

Psi has no visibility into which capabilities are actually used, how often they succeed or fail, or how much they cost in tokens. The information exists transiently in the dispatch event log and extension event bus, but nothing aggregates or persists it. This makes it hard to identify unused registrations, error-prone tools, or token-expensive workflows.

## Scope

### In Scope

- Tool invocation counting (by tool name), including error counting (by tool name + error classification)
- Workflow execution counting (by workflow id)
- Command invocation counting (by command name)
- Deterministic operation invocation counting (by operation id)
- Token usage accumulation per model (input/output/cache-read/cache-write keyed by model-id)
- Project-scoped persistence (EDN file in `.psi/`)
- Deterministic operation `metrics/summary` returning current counter state
- Slash command `/metrics` rendering human-readable summary
- Malli schema for the metrics data shape

### Out of Scope

- User-global (cross-project) aggregation
- Skill activation tracking (no existing extension event emitted for skill selection; would require core changes)
- Per-session token breakdowns (the extension tracks per-model aggregate totals, not per-session; per-session detail is already available via the EQL resolver `agent-session-usage`)
- Cost tracking (requires model-specific pricing data not available to extensions)
- Historical time-series or bucketed metrics
- Metrics export (Prometheus, OpenTelemetry, etc.)

## Constraints

- **Extension boundary only** — no behavioral modifications to existing core components. The extension uses only the public extension API (`init` receives the `api` map). The only core file touched is the data-only `psi-owned-extension-catalog` map in `extension_installs.clj` to register the new extension (same pattern as all built-in extensions).
- **Event-driven** — observes existing extension events: `tool_call`, `tool_result`, `session_turn_finished`. No new hooks in core required.
- **Project-scoped persistence** — EDN file at `<worktree>/.psi/metrics.edn`. Loaded on init, flushed on mutation.
- **Minimal overhead** — counter increments are in-memory atom updates; persistence is out-of-band with write-coalescing so concurrent events (e.g., parallel tool calls) never serialize on disk I/O.
- **Schema-first** — malli schema defines the canonical metrics data shape; validated on load and available for consumers.

## Design

### Architecture Overview

The extension follows the standard psi extension pattern (see `extensions/logprobs/`, `extensions/commit-checks/`):

```
init(api) →
  subscribe("tool_call",            on-tool-call)
  subscribe("tool_result",          on-tool-result)
  subscribe("session_turn_finished", on-turn-finished)
  register-operation("metrics/summary", invoke-summary)
  register-command("metrics",       metrics-command-handler)
```

State is held in a single `(defonce store (atom nil))` containing both the metrics counters and the resolved persistence path. A separate `(defonce writing? (atom false))` gate serializes disk writes.

### Implementation Strategy

**Why this fits the architecture:** The extension API already provides `(:on api)` for event subscription, `(:register-operation api)` for deterministic operations, and `(:register-command api)` for slash commands. The commit-checks extension demonstrates the persistence pattern (EDN file in `.psi/`). The logprobs extension demonstrates the event-subscription + operation + command triple. This design composes both patterns.

### Data Shape

The metrics atom holds a map with this shape:

```clojure
{:tools       {"tool-name" {:invocations 5
                             :errors      3
                             :error-reasons {"parse-error" 2
                                             "timeout" 1}}}
 :workflows   {"workflow-id" {:invocations 3}}
 :commands    {"command-name" {:invocations 7}}
 :operations  {"operation-id" {:invocations 2}}
 :tokens      {"claude-sonnet-4-20250514" {:input       12500
                                            :output      3400
                                            :cache-read  8000
                                            :cache-write 1200}
               "gpt-4o"                  {:input       5000
                                            :output      1200
                                            :cache-read  0
                                            :cache-write 0}}
 :updated-at  "2026-05-14T10:00:00Z"}
```

#### Malli Schema

```clojure
(def counter-schema
  [:map
   [:invocations :int]])

(def tool-counter-schema
  [:map
   [:invocations :int]
   [:errors :int]
   [:error-reasons [:map-of :string :int]]])

(def token-totals-schema
  [:map
   [:input :int]
   [:output :int]
   [:cache-read :int]
   [:cache-write :int]])

(def metrics-schema
  [:map
   [:tools [:map-of :string tool-counter-schema]]
   [:workflows [:map-of :string counter-schema]]
   [:commands [:map-of :string counter-schema]]
   [:operations [:map-of :string counter-schema]]
   [:tokens [:map-of :string token-totals-schema]]
   [:updated-at [:maybe :string]]])
```

### Event Handling

#### `tool_call` event

Payload: `{:type "tool_call" :tool-name "..." :tool-call-id "..." :input {...}}`

Action: Increment `[:tools tool-name :invocations]`.

#### `tool_result` event

Payload: `{:type "tool_result" :tool-name "..." :tool-call-id "..." :input {...} :content "..." :is-error true/false}`

Action: When `:is-error` is truthy, increment `[:tools tool-name :errors]` and `[:tools tool-name :error-reasons reason]`. The error reason is derived from the `:content` string — extract the first line, truncated to 80 chars, as the reason key. This keeps reason keys short and greppable without requiring structured error taxonomies.

#### `session_turn_finished` event

Payload: `{:session-id "..." :turn-id "..." ...}`

Action: Query session token usage and model-id via the extension API's `:query-session` function: `((:query-session api) session-id [:psi.agent-session/usage-input :psi.agent-session/usage-output :psi.agent-session/usage-cache-read :psi.agent-session/usage-cache-write :psi.agent-session/model-id])`. Compute the delta from the last-known session totals (tracked in a transient in-memory map, not persisted) and add the delta to the `:tokens` counters under the model-id key. If model-id is nil, use `"unknown"` as the key.

**Why per-model:** Different models have different token economics and capabilities. Aggregate-only totals hide which models consume the most tokens. Per-model breakdown enables informed model selection and cost awareness.

**Why delta-based:** The EQL resolver returns cumulative session totals. The extension must track the last-seen totals per session in memory so it can compute the incremental contribution of each turn. The per-session tracking map is transient (not persisted) because it is only needed within a running process — on restart, the first turn for each session will be treated as a full delta, which is acceptable because sessions rarely span process restarts and the aggregate totals are approximate anyway.

**Alternative considered — enrich `session_turn_finished` payload:** This would require modifying `prompt-finish-base-result` in `turn/handlers.clj`, violating the "no core modifications" constraint. The EQL query approach is slightly less efficient but respects the extension boundary.

### Workflow Tracking

Workflow start/completion events are not currently emitted to the extension event bus. The workflow runtime uses internal statechart events (`:workflow/start`, `:workflow/complete`) but these are not dispatched through `ext/dispatch-in`.

**Decision:** Workflow tracking is included in the schema and data shape (so the surface is ready) but will not be populated in the initial version. A future core enhancement could emit `workflow_started` and `workflow_completed` extension events; the extension is already shaped to consume them.

### Command Tracking

Slash commands are not currently emitted as extension events. The extension registers its own `/metrics` command but cannot observe other command invocations without core changes.

**Decision:** Command tracking is included in the schema and data shape (so the surface is ready) but will initially only track the extension's own `/metrics` command via self-tracking. A future core enhancement could emit a `command_invoked` event to enable full command tracking.

### Deterministic Operation Tracking

Deterministic operations are not currently emitted as extension events either. The same forward-compatible approach applies: the schema includes `:operations`, but initial population is limited to self-tracking of `metrics/summary` invocations.

**Decision:** Track `metrics/summary` self-invocations. Future core event emission for operation invocations would automatically populate this counter category.

### Persistence

**File:** `<worktree>/.psi/metrics.edn`

**Load on init:** The extension needs the worktree path to locate the persistence file. It obtains this via `((:query api) [:psi.agent-session/worktree-path])` during init. If the query returns nil (no worktree), persistence is disabled and the extension operates in memory-only mode.

**Write strategy — out-of-band with write-coalescing:** Parallel tool calls produce concurrent `tool_call` and `tool_result` events, which means multiple threads may mutate the metrics atom concurrently. The atom `swap!` is thread-safe, but synchronous file writes after each `swap!` would serialize all event handlers on disk I/O and risk redundant writes.

Instead, persistence uses a dirty-flag + single-writer loop:

1. Each event handler calls `swap!` to update counters, then sets a `:dirty?` flag in the atom and calls `maybe-persist!`.
2. `maybe-persist!` uses `compare-and-set!` on a separate `(defonce writing? (atom false))` flag to ensure only one thread enters the write path at a time.
3. The writer atomically reads the current metrics snapshot and clears `:dirty?`, writes the snapshot to disk, then checks `:dirty?` again — if it was re-dirtied during the write (by another concurrent event), it loops and writes again.
4. When the writer finds `:dirty?` is false after a write, it resets `writing?` to false and exits.

This ensures: (a) no concurrent file writes, (b) no lost updates — if events arrive during a write, the loop catches them, (c) minimal I/O — rapid-fire events coalesce into a single write of the latest state.

The `/metrics` command also triggers a `maybe-persist!` to ensure the displayed data matches what's on disk.

**Atomic writes:** `spit` to a `.metrics.edn.tmp` file in the same directory, then `java.nio.file.Files/move` with `ATOMIC_MOVE` + `REPLACE_EXISTING`. This prevents partial writes from corrupting the file.

**Load validation:** On load, validate the EDN against the malli schema. If validation fails, log a warning and start with empty counters (the corrupt file is preserved for manual inspection).

### Initialization

```clojure
(defn init [api]
  (let [worktree-path (get ((:query api) [:psi.agent-session/worktree-path])
                           :psi.agent-session/worktree-path)
        initial-state (when-not @store
                        (load-metrics worktree-path))]
    ;; Only reset store on first init (defonce preserves across reloads).
    ;; On reload, just update worktree-path in case it changed.
    (if @store
      (swap! store assoc :worktree-path worktree-path)
      (reset! store {:metrics (or initial-state (empty-metrics))
                     :worktree-path worktree-path
                     :session-usage-cache {}
                     :dirty? false}))
    ((:on api) "tool_call" on-tool-call)
    ((:on api) "tool_result" on-tool-result)
    ((:on api) "session_turn_finished" (make-turn-finished-handler api))
    ((:register-operation api)
     {:id "metrics/summary"
      :description "Return current usage metrics for all tracked capabilities"
      :handler invoke-summary})
    (when-let [register-command (:register-command api)]
      (register-command "metrics"
                        {:description "Display usage metrics summary"
                         :handler (fn [args] (metrics-command-handler args api))}))
    nil))
```

The `session_turn_finished` handler is created via `make-turn-finished-handler` which closes over the `api` map to access the `:query` function for EQL usage lookups.

**Reload behavior:** On extension reload, the `defonce` atom is preserved. `init` detects the non-nil store and only updates the `:worktree-path` (in case the session switched worktrees). Accumulated counters and the session-usage-cache survive the reload.

### Operation Handler

`metrics/summary` returns the full metrics map:

```clojure
(defn invoke-summary [{:keys [_args]}]
  {:status :ok
   :data (get-metrics)})
```

No arguments required. Returns the full metrics map conforming to `metrics-schema`.

### Slash Command

`/metrics` renders a human-readable summary via `(:notify api)`:

```
## Usage Metrics

### Tools (5 tracked)
| Tool | Invocations | Errors |
|------|-------------|--------|
| read | 42 | 0 |
| bash | 38 | 3 |
| edit | 25 | 1 |
| write | 12 | 0 |
| delegate | 8 | 0 |

### Token Usage (by model)
| Model | Input | Output | Cache Read | Cache Write |
|-------|-------|--------|------------|-------------|
| claude-sonnet-4-20250514 | 125,000 | 34,000 | 80,000 | 12,000 |
| gpt-4o | 5,000 | 1,200 | 0 | 0 |

_Updated: 2026-05-14T10:00:00Z_
```

Workflow, command, and operation sections are included when non-empty.

### File Layout

```
extensions/metrics/
├── deps.edn
├── src/
│   └── psi/
│       └── metrics/
│           ├── extension.clj      ;; init + event handlers + operation + command
│           ├── schema.clj         ;; malli schemas
│           ├── persistence.clj    ;; load/save EDN, atomic write
│           └── counters.clj       ;; pure counter update functions
└── test/
    └── psi/
        └── metrics/
            ├── extension_test.clj
            ├── schema_test.clj
            ├── persistence_test.clj
            └── counters_test.clj
```

**Namespace convention:** Uses `psi.metrics.*` following the `psi.github.*` pattern for extensions that are part of the psi project (not third-party `extensions.*` flat namespaces).

### Wiring

The metrics extension follows the same standalone pattern as `psi/github` and `psi/logprobs` — it is **not** added to the central `extensions/deps.edn` (which only covers the simpler flat-namespace extensions). It has its own `deps.edn` with a `:test` alias and is run independently.

**Manifest registration (two places):**

1. **Catalog entry:** Add `'psi/metrics {:psi/init 'psi.metrics.extension/init :source-policies {:installed {:local/root "extensions/metrics"}}}` to `psi-owned-extension-catalog` in `components/agent-session/src/psi/agent_session/extension_installs.clj`. This is the same registration pattern used by all built-in extensions (github, logprobs, etc.). While technically touching a core component file, it is a data-only catalog addition — no behavioral changes.
2. **Project manifest:** Add `psi/metrics {}` to `.psi/extensions.edn` under `:deps` to enable the extension in this project.

**Tests:** Run via `clj -M:test` inside `extensions/metrics/` using the `:test` alias in `extensions/metrics/deps.edn`. No changes to the central `extensions/deps.edn` or `extensions/tests.edn`.

### Key Invariants

1. **Counter monotonicity:** Counters only increment, never decrement. The `updated-at` timestamp advances on every mutation.
2. **Schema conformance:** The persisted EDN always conforms to `metrics-schema`. Load rejects non-conforming data.
3. **Graceful degradation:** If persistence path is unavailable (no worktree), the extension operates in memory-only mode without error.
4. **Thread safety:** All counter mutations go through `swap!` on the store atom. Persistence writes are serialized via a `writing?` CAS gate — at most one thread writes at a time, and writes coalesce concurrent mutations.
5. **No behavioral core modifications:** The extension uses only the public extension API surface. The only core file touched is the data-only catalog registration.

### Edge Cases

- **No worktree:** Init succeeds, persistence disabled, counters are in-memory only.
- **Corrupt EDN file:** Log warning, start with empty counters, do not overwrite the corrupt file until the first successful mutation.
- **Missing `.psi/` directory:** Create it on first write (using `io/make-parents`).
- **Concurrent writes from parallel tool calls:** Parallel tool execution produces concurrent `tool_call`/`tool_result` events. The atom serializes in-memory counter updates. The `writing?` CAS gate ensures at most one disk write at a time; the dirty-check loop after each write coalesces rapid-fire mutations into minimal I/O. Atomic file rename prevents partial reads.
- **Extension reload:** `defonce` on the store atom preserves counters across reloads. The init function re-subscribes to events but does not reset counters.
- **EQL query failure on turn finished:** If the usage query fails (e.g., session already closed), skip token tracking for that turn and log at debug level.

### Verification Expectations

1. `init` subscribes to exactly three events and registers one operation and one command.
2. `on-tool-call` increments `[:tools name :invocations]` for any tool name.
3. `on-tool-result` with `:is-error true` increments `[:tools name :errors]` and `[:tools name :error-reasons reason]`.
4. `on-tool-result` with `:is-error false` does not increment error counters.
5. `invoke-summary` returns `{:status :ok :data <metrics-map>}` conforming to schema.
6. `/metrics` command calls `(:notify api)` with a markdown-formatted summary.
7. `load-metrics` returns empty counters for missing or corrupt files.
8. `save-metrics` writes valid EDN that round-trips through `load-metrics`.
9. Counter functions are pure and independently testable.
10. Token delta computation correctly handles first-seen sessions, cumulative totals, and per-model keying.
11. `maybe-persist!` coalesces concurrent mutations — only one thread writes at a time, and re-dirtied state triggers a follow-up write.

## Acceptance Criteria

1. Extension subscribes to `tool_call`, `tool_result`, and `session_turn_finished` events and increments appropriate counters.
2. Token usage is accumulated per model-id (queried via EQL on each `session_turn_finished`).
3. Counters persist to `<worktree>/.psi/metrics.edn` and restore on init.
4. Persistence uses out-of-band write-coalescing so parallel tool call events do not serialize on disk I/O.
5. `metrics/summary` deterministic operation returns current counter state conforming to malli schema.
6. `/metrics` slash command renders a human-readable markdown summary with per-model token breakdown.
7. Metrics data conforms to an explicit malli schema (validated on load, available for consumers).
8. No behavioral modifications to existing core components (data-only catalog entry is acceptable).
9. Extension operates gracefully when no worktree path is available (memory-only mode).
