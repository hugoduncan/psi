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
