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

### Namespace convention: `psi.metrics.*`

Follows `psi.github.*` pattern for project-owned extensions rather than the flat `extensions.*` convention used by simpler extensions. This allows clean multi-namespace decomposition.

### Atomic file writes

Uses write-to-temp + `Files/move` with `ATOMIC_MOVE` to prevent partial-read corruption. Same approach used in session persistence elsewhere in the codebase.

### `defonce` store preservation

The store atom uses `defonce` so extension reloads preserve accumulated counters. Init re-subscribes to events but does not reset the atom.
