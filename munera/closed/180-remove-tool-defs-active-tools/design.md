# 180 — Remove :tool-defs and :active-tools

## Goal

Eliminate the two compatibility projection fields (`:tool-defs` and `:active-tools`) from session state. After task 179, `:tool-ids` is the authoritative tool membership field. The projections are redundant derivable state that adds maintenance cost, drift risk, and conceptual noise.

## Context

Task 179 introduced `:tool-ids` as the single authority and kept `:tool-defs`/`:active-tools` as derived projections for backward compatibility. This task completes the migration by:

1. Migrating all consumers of `:tool-defs` and `:active-tools` to derive what they need from `:tool-ids` + the tool registry (or normalized tool maps at the boundary).
2. Removing the fields from schema, defaults, lifecycle propagation, and `tool-authority-fields`.

## Constraints

- `:tool-ids` remains the sole authoritative membership field.
- Tool definition payloads (the full maps with `:description`, `:parameters`, etc.) must still be available where needed (prompt assembly, provider requests, child session bootstrapping) — but derived on demand from `:tool-ids` + a tool-def resolver rather than stored redundantly in session state.
- The workflow child-session contract currently accepts `:tool-defs` as an optional input; this must migrate to `:tool-ids` (or derive internally).
- No behavioural change to end users or AI providers — same tools appear in prompts, same tools are callable.

### Base tool derivation

Base tools (`read`, `bash`, `edit`, `write`, `psi-tool`) are assembled in `app_runtime.clj` and do **not** live in the tool-registry — they exist only as normalized maps in the runtime agent `data-atom`. Registering base tools in tool-registry remains out of scope (deferred per 179 design).

Therefore, removing `:tool-defs` from session state requires an alternative derivation source for base tools. The derivation API (see below) must be able to resolve **all** tool-ids — both extension tools (from tool-registry) and base tools (from the runtime agent data). The runtime agent's `data-atom` already holds the full merged tool set after startup; this is the derivation source.

### On-demand tool-def derivation API

A single derivation function must exist to resolve `tool-ids → tool definition maps`. Without a named API, each consumer would independently implement ad-hoc lookup logic — recreating the consistency problem that `tool-authority-fields` was introduced to solve.

**Function**: `psi.tool-registry.defs/resolve-tool-defs`
**Signature**: `(resolve-tool-defs tool-source tool-ids) → [tool-def-map ...]`
**Inputs**:
- `tool-source` — a seq/collection of all known tool definition maps (from the runtime agent data, which includes both base and extension tools)
- `tool-ids` — the authoritative `[:tool-ids ...]` vector from session state

**Semantics**: Filters `tool-source` to only those tools whose `:name` is in `tool-ids`, preserving `tool-ids` ordering. Returns `[]` for empty `tool-ids`.

**Rationale**: This is a pure function — it does not reach into an atom or registry. The caller provides the tool-source, which keeps the function testable and avoids coupling to runtime state. The runtime agent data (which already holds the merged base+extension tool set) is the canonical tool-source at call sites.

## Scope

### In scope

- Remove `:tool-defs` from session schema, `initial-session`, lifecycle `select-keys`.
- Remove `:active-tools` from `tool-authority-fields` and all read sites.
- Introduce `psi.tool-registry.defs/resolve-tool-defs` as the single derivation API for `tool-ids → tool-def maps`.
- Migrate all consumers that read `:tool-defs` from session state to instead derive tool definitions via `resolve-tool-defs` with a tool-source from the runtime agent data.
- Migrate workflow child-session contract from `[:tool-defs {:optional true} ...]` to `[:tool-ids {:optional true} ...]`.
- Migrate dispatch event fields (`:session/create-child` event, `mutations/session.clj`, `workflow_judge.clj`) from `:tool-defs` to `:tool-ids` in the event contract — these are event/contract fields, not session-state reads.
- Remove `tool-authority-fields` entirely — callers `assoc :tool-ids` directly (see design decision below).
- Remove or update tests that assert on `:tool-defs`/`:active-tools` presence in session state.

### Out of scope

- Registering base tools in tool-registry (deferred per 179 design — base tools still arrive as normalized maps at the runtime boundary and are available via the runtime agent data-atom).
- Changing the provider-facing tool format (still JSON Schema maps).
- Reworking how tools are discovered/registered by extensions.

## Consumers to migrate

### :active-tools (2 read sites)

| Location | Current use | Migration path |
|----------|-------------|----------------|
| `prompt_request.clj:279` | `:turn/active-tools (:active-tools session-data)` | Derive `(set (:tool-ids session-data))` |
| `turn_runtime/request.clj:92` | `:active-tools (:turn/active-tools normalized-turn)` | Unchanged — consumes turn data, not session state directly |

### :tool-defs — session-state read sites

| Location | Current use | Migration path |
|----------|-------------|----------------|
| `child_session_state.clj` | Resolve child tool-defs from parent; writes `:tool-defs tool-defs` into child session data map (line 98) | Derive from parent `:tool-ids` via `resolve-tool-defs` with tool-source from runtime agent data. Remove `:tool-defs tool-defs` from the `child-session-base-state*` data map — after `tool-authority-fields` removal, `derive-child-prompt-state` no longer returns `:tool-defs`, so this key must be removed (not left as `nil`). |
| `prompt_handlers.clj:45-55` | Live tool-defs for prompt | Derive from `:tool-ids` via `resolve-tool-defs` |
| `session_mutations.clj:512` | `:session/add-tool` reads current defs | Derive from `:tool-ids` via `resolve-tool-defs`, append new |
| `prompt_request.clj:284` | Filter tool-defs for system prompt | Derive from `:tool-ids` via `resolve-tool-defs` |
| `psi_tool.clj:515` | Check builtins | Derive from `:tool-ids` via `resolve-tool-defs` |
| `psi_tool_scheduler.clj:78` | `session-config-supported-keys` whitelist | Replace `:tool-defs` with `:tool-ids` in supported keys |
| `dispatch_handlers/scheduler.clj:59` | Destructures `:tool-defs` from session-config, normalizes, dispatches `:session/set-active-tools` | Accept `:tool-ids` (strings); derive tool-defs via `resolve-tool-defs` before dispatching `:session/set-active-tools` with `:tool-maps` |
| `scheduler_runtime.clj:44` | Tool count | `(count (:tool-ids ...))` |
| `session_runtime.clj:38` | Runtime tool list | Derive from `:tool-ids` via `resolve-tool-defs` |
| `session_state/init.clj` | Lifecycle select-keys | Remove |
| `session_state/model.clj` | Schema + defaults | Remove |
| `workflow_step_session_config/core.clj:166,196` | Reads parent session `:tool-defs` to resolve step tools | Read parent `:tool-ids` instead; derive tool-defs via `resolve-tool-defs` with tool-source from runtime agent data. Step-config continues to output `:tool-defs` (derived maps) — downstream pass-throughs unchanged. |

### :tool-defs — dispatch event/contract fields

These consumers use `:tool-defs` as a dispatch event parameter or contract field, not as a session-state read. Migration must change the event contract and schema, not just remove session-state reads.

| Location | Current use | Migration path |
|----------|-------------|----------------|
| `session_lifecycle.clj:126` | Destructures `:tool-defs` from `:session/create-child` event | Change event contract: accept `:tool-ids` instead of `:tool-defs`; handler passes `:tool-ids` to `child_session_state.clj` |
| `mutations/session.clj:89` | Passes `:tool-defs` as dispatch event param to `:session/create-child` | Pass `:tool-ids` instead; callers (extension `create-child-session`) provide `:tool-ids` |
| `workflow_judge.clj:61` | Passes `:tool-defs []` in child-session creation map | Pass `:tool-ids []` instead |
| `child_session_contract.clj:15` | Contract schema has `[:tool-defs {:optional true} [:maybe [:vector :map]]]` | Replace with `[:tool-ids {:optional true} [:maybe [:vector :string]]]` |
| `context.clj:134` | Destructures `:tool-defs` from validated contract request and passes to `:session/create-child` dispatch event | Pass `:tool-ids` instead of `:tool-defs`; upstream callers provide `:tool-ids` |
| `auto_session_name.clj:224` | Extension passes `:tool-defs []` to `psi.extension/create-child-session` | Pass `:tool-ids []` instead |

## Design decisions

### `tool-authority-fields` removal

Task 179 introduced `tool-authority-fields` as the single derivation site returning `{:tool-ids :active-tools :tool-defs}`. Callers (`set-active-tools`, `add-tool`, `derive-child-prompt-state`) `merge` the result into session state.

**Decision**: Remove `tool-authority-fields` entirely.

**Rationale**: Once `:tool-defs` and `:active-tools` are removed from session state, the helper would return only `{:tool-ids ids}` — a trivial single-key map. A function whose sole purpose is `{:tool-ids (mapv :name defs)}` adds indirection without value. Callers should `assoc :tool-ids` directly:

- `set-active-tools` handler: `(assoc % :tool-ids (mapv :name normalized))`
- `add-tool` handler: `(assoc % :tool-ids (mapv :name normalized))`
- `derive-child-prompt-state`: `(assoc result :tool-ids (mapv :name resolved-tool-defs))`

The `merge` pattern in callers changes to a direct `assoc :tool-ids` — simpler and explicit.

### Step-config output: `:tool-defs` (derived maps)

`workflow_step_session_config/core.clj:196` reads `:tool-defs` from the parent session and resolves step-specific tools via `resolve-step-tool-defs`. After migration, it reads `:tool-ids` from the parent session and derives tool-defs via `resolve-tool-defs` with a tool-source from the runtime agent data. The step-config map continues to output `:tool-defs` (derived full maps) — this is a local data structure, not session state. Downstream `statechart_runtime.clj:90` and `attempts.clj:70` are pass-throughs of step-config and do not need migration.

### Scheduler session-config: accept `:tool-ids` (strings), derive internally

`psi_tool_scheduler.clj:78` `session-config-supported-keys` and `dispatch_handlers/scheduler.clj:59` destructure `:tool-defs` from the AI-facing scheduler create API. This is a user-facing (AI-facing) contract.

**Decision**: The scheduler accepts `:tool-ids` (string tool names) instead of `:tool-defs` (full maps). The scheduler handler (`dispatch_handlers/scheduler.clj`) derives tool-defs internally via `resolve-tool-defs` when it needs to dispatch `:session/set-active-tools` with `:tool-maps`.

**Rationale**: The scheduler is a creation-time API — callers specify *which* tools, not *how* tools are defined. Tool-ids are the authoritative membership representation. Deriving full maps at the handler boundary is consistent with the overall design direction.

**Persisted-schedule compatibility**: Existing persisted schedules that contain `:tool-defs` in their session-config will fail validation after migration. This is acceptable — schedules are recreated by users/AI and are not long-lived persistent state. If compatibility is needed, the scheduler handler can accept both keys during a transition period, but the default path is clean migration.

### Removed from consumer table

- `workflow_runtime/attempts.clj:70` and `workflow_runtime/statechart_runtime.clj:90` — pass-throughs of step-config `:tool-defs`, not session-state reads. No migration needed.
- `system_prompt.clj:391-392` — parameter docstring describing the `:tool-defs` input to `build-system-prompt`. Not a session-state read. The callers that pass tool-defs to this function are already listed separately.
- `app_runtime.clj:364,468` — prompt build-opts construction sites using local `refreshed-tool-defs` variables. These build a local opts map with `:tool-defs` as a parameter to `build-system-prompt` — they never read `:tool-defs` from session state. Same category as `system_prompt.clj:391-392`. The callers that persist tool state are already covered by the `:session/set-active-tools` dispatch path.

## Acceptance criteria

1. No session state map contains `:tool-defs` or `:active-tools` after initialization.
2. `tool-authority-fields` is removed; callers `assoc :tool-ids` directly.
3. `resolve-tool-defs` is the single derivation API for `tool-ids → tool-def maps`.
4. All prompt assembly, child session creation, and workflow step configuration produce identical tool payloads as before (derived on demand).
5. Schema validates without `:tool-defs` or `:active-tools`.
6. All existing tests pass (updated as needed).
7. No regression in tool availability during sessions, workflows, or scheduled sessions.
8. Dispatch event contract for `:session/create-child` uses `:tool-ids` (not `:tool-defs`).
9. `child_session_contract.clj` schema uses `:tool-ids` (not `:tool-defs`).
