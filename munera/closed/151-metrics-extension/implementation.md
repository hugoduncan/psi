# Implementation Notes — 151 Metrics Extension

## Provenance

- **GitHub Issue:** [#75 — Add metrics extension](https://github.com/hugoduncan/psi/issues/75)
- **Issue comment:** Refined task intent posted by hugoduncan on 2026-05-14

## Design Decisions

### Skill activation tracking excluded

The original issue mentions "skill activations" but no extension event is emitted when a skill is selected/activated. Adding one would require core modifications. Excluded from scope; schema is forward-compatible if the event is added later.

### Command and operation tracking partially deferred

Like skill activations, slash command and deterministic operation invocations are not emitted as extension events. The schema includes `:commands` and `:operations` categories, and the extension self-tracks its own `/metrics` and `metrics/summary` invocations. Full tracking requires future core event emission.

### Token tracking via EQL query delta

`session_turn_finished` does not carry token usage in its payload. Rather than modifying core `prompt-finish-base-result` in `turn/handlers.clj`, the extension queries the EQL surface `[:psi.agent-session/usage-input ...]` after each turn and computes the delta from a transient per-session cache. This respects the extension boundary constraint at the cost of one EQL query per turn completion.

### Persistence path from EQL query

The extension obtains the worktree path via `((:query api) [:psi.agent-session/worktree-path])` during init. This is the same pattern used by commit-checks (which reads `workspace-dir` from event payloads). If nil, persistence is disabled gracefully.

### Workflow tracking excluded from initial version

No workflow start/completion events are emitted to the extension event bus. The workflow runtime uses internal statechart events but does not dispatch through `ext/dispatch-in`. Schema includes `:workflows` for forward compatibility.

### Standalone extension — not in central extensions/deps.edn

`psi/github` and `psi/logprobs` are not present in `extensions/deps.edn` (the central file covering simpler flat-namespace extensions). They are standalone with their own `deps.edn` and `:test` alias. `psi/metrics` follows the same pattern: standalone `extensions/metrics/deps.edn`, tests run via `clj -M:test` inside that directory. No changes to `extensions/deps.edn` or `extensions/tests.edn`.

### Catalog registration is data-only

Adding the extension to `psi-owned-extension-catalog` in `extension_installs.clj` is technically touching a core component file, but it's a data-only catalog entry — the same pattern used by all other built-in extensions. No behavioral changes to core code.

### Namespace convention: `psi.metrics.*`

Follows `psi.github.*` pattern for project-owned extensions rather than the flat `extensions.*` convention used by simpler extensions. This allows clean multi-namespace decomposition.

### Atomic file writes

Uses write-to-temp + `Files/move` with `ATOMIC_MOVE` to prevent partial-read corruption. Same approach used in session persistence elsewhere in the codebase.

## PR #94 Feedback — 2026-05-14

### Per-model token usage (hugoduncan)

Feedback: "Token-usage metrics should be per model."

Changed `:tokens` from a flat `{:input N :output N ...}` map to `[:map-of :string token-totals-schema]` keyed by model-id. The `session_turn_finished` handler now queries `:psi.agent-session/model-id` alongside usage attrs and accumulates deltas under the model-id key. Falls back to `"unknown"` when model-id is nil.

### Parallel tool call write safety (hugoduncan)

Feedback: "Need to check if parallel tool calling would cause parallel metric file writes."

Confirmed: `tool-runtime/batch.clj` uses `ExecutorService.invokeAll` for multi-tool batches, producing concurrent `tool_call`/`tool_result` extension events. The atom `swap!` is thread-safe for in-memory counters, but synchronous file persist after each event would serialize event handlers on I/O and produce redundant writes.

### Out-of-band write-coalescing persistence (hugoduncan)

Feedback: "If needed we could have metrics written out of band, and check for the need to write again when a write completes."

Replaced synchronous persist with a dirty-flag + `writing?` CAS gate pattern: event handlers set `:dirty?` and call `maybe-persist!`; only one thread enters the write path at a time via `compare-and-set!` on `writing?`; after each write, the writer re-checks `:dirty?` and loops if concurrent events re-dirtied the state. This coalesces rapid-fire events into minimal disk I/O.

### `defonce` store preservation

The store atom uses `defonce` so extension reloads preserve accumulated counters. Init re-subscribes to events but does not reset the atom.

## Implementation Pass — 2026-05-13

### store and writing? atoms made non-private

The design specified `^:private` on the `store` and `writing?` `defonce` atoms. In practice, Clojure 1.12 enforces private var access at compile time even via `#'` in tests from a different namespace. Made both atoms non-private so tests can reset them between runs (same pattern as `auto-session-name` extension which also uses `@#'sut/state` on a private atom — but that works because it's in the same compilation unit). The logprobs extension uses `@#'logprobs/store` which works because the test and source are in the same top-level namespace. For `psi.metrics.*` multi-namespace layout, non-private is cleaner.

### :register-operation not in nullable extension API

The nullable extension API (`create-nullable-extension-api`) does not expose `:register-operation` as a direct key — it routes operations through the `mutate` path. The extension tests augment the nullable API with a local `ops` atom and a direct `:register-operation` fn, consistent with how logprobs tests build inline api maps. This is simpler and avoids coupling tests to the nullable API's internal mutation dispatch.

### /metrics command self-tracking

The command handler increments the command invocation counter for `"metrics"` on every invocation. This means there is no "empty metrics" state after the first `/metrics` call. Tests adjusted to reflect this: the "no events" case still shows the Commands section (with `metrics | 1`).

### tests.edn uses kaocha v1 format

Added `extensions/metrics/tests.edn` with kaocha v1 format, consistent with other extensions. `clj -M:test -m kaocha.runner -c tests.edn` runs the full suite.

## Refinement Pass — 2026-05-13

### Wiring correction: standalone extension

Verified against the actual repo: `psi/github` and `psi/logprobs` are absent from `extensions/deps.edn` — they are fully standalone extensions with their own deps.edn and test aliases. The design previously incorrectly specified adding `psi/metrics` to `extensions/deps.edn`. Corrected: metrics is standalone, wiring is catalog entry + project manifest only.

### API surface verified

- `(:query api)` — takes single EQL vector, returns result map. Used during `init` to obtain worktree path.
- `(:query-session api)` — takes `(session-id eql-query)`, returns result map. Used in `session_turn_finished` handler to obtain per-session usage attrs and model-id.
- `(:register-operation api)` — takes `{:id :description :handler}` map. Same as logprobs/github.
- `(:register-command api)` — optional key; takes `(name opts-map)`. Same as logprobs.
- `(:on api)` — takes `(event-name handler-fn)`. Same as logprobs.
- `(:notify api)` — takes `(content & [opts])`. Used in command handler.

### Test infrastructure

Tests use `psi.extension-test-helpers.nullable-api/create-nullable-extension-api` (same as work-on, github tests). The nullable API provides `:query-session`, `:on`, `:register-operation`, `:register-command`, `:notify` — all needed by metrics. No mocks required.

### Malli dependency

Malli (`metosin/malli`) is not a transitive dep of the extension API or logprobs. Must be declared explicitly in `extensions/metrics/deps.edn` alongside the extension-test-helpers in the `:test` alias.
