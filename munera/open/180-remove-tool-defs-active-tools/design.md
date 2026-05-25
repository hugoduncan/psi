# 180 — Remove :tool-defs and :active-tools

## Goal

Eliminate the two compatibility projection fields (`:tool-defs` and `:active-tools`) from session state. After task 179, `:tool-ids` is the authoritative tool membership field. The projections are redundant derivable state that adds maintenance cost, drift risk, and conceptual noise.

## Context

Task 179 introduced `:tool-ids` as the single authority and kept `:tool-defs`/`:active-tools` as derived projections for backward compatibility. This task completes the migration by:

1. Migrating all consumers of `:tool-defs` and `:active-tools` to derive what they need from `:tool-ids` + the tool registry (or normalized tool maps at the boundary).
2. Removing the fields from schema, defaults, lifecycle propagation, and `tool-authority-fields`.

## Constraints

- `:tool-ids` remains the sole authoritative membership field.
- Tool definition payloads (the full maps with `:description`, `:parameters`, etc.) must still be available where needed (prompt assembly, provider requests, child session bootstrapping) — but derived on demand from `:tool-ids` + registry lookup rather than stored redundantly in session state.
- The workflow child-session contract currently accepts `:tool-defs` as an optional input; this must migrate to `:tool-ids` (or derive internally).
- No behavioural change to end users or AI providers — same tools appear in prompts, same tools are callable.

## Scope

### In scope

- Remove `:tool-defs` from session schema, `initial-session`, lifecycle `select-keys`.
- Remove `:active-tools` from `tool-authority-fields` and all read sites.
- Migrate all consumers that read `:tool-defs` from session state to instead derive tool definitions from `:tool-ids` + a registry/resolver lookup.
- Migrate workflow child-session contract from `[:tool-defs {:optional true} ...]` to `[:tool-ids {:optional true} ...]`.
- Update `tool-authority-fields` to return only `{:tool-ids ids}`.
- Remove or update tests that assert on `:tool-defs`/`:active-tools` presence in session state.

### Out of scope

- Registering base tools in tool-registry (deferred per 179 design — base tools still arrive as normalized maps at boundaries).
- Changing the provider-facing tool format (still JSON Schema maps).
- Reworking how tools are discovered/registered by extensions.

## Consumers to migrate

### :active-tools (2 read sites)

| Location | Current use | Migration path |
|----------|-------------|----------------|
| `prompt_request.clj:279` | `:turn/active-tools (:active-tools session-data)` | Derive `(set (:tool-ids session-data))` |
| `turn_runtime/request.clj:92` | `:active-tools (:turn/active-tools normalized-turn)` | Unchanged — consumes turn data, not session state directly |

### :tool-defs (many read sites)

| Location | Current use | Migration path |
|----------|-------------|----------------|
| `child_session_state.clj` | Resolve child tool-defs from parent | Derive from parent `:tool-ids` via registry lookup |
| `context.clj:134` | Pass tool-defs to context builder | Derive from `:tool-ids` |
| `prompt_handlers.clj:45-55` | Live tool-defs for prompt | Derive from `:tool-ids` |
| `session_lifecycle.clj:126` | Session initialization | Remove — `:tool-ids` suffices |
| `session_mutations.clj:512` | `:session/add-tool` reads current defs | Derive from `:tool-ids` + registry, append new |
| `mutations/session.clj:89` | Workflow session creation | Use `:tool-ids` |
| `prompt_request.clj:284` | Filter tool-defs for system prompt | Derive from `:tool-ids` |
| `psi_tool.clj:515` | Check builtins | Derive from `:tool-ids` |
| `psi_tool_scheduler.clj:78` | Session config field | Use `:tool-ids` |
| `scheduler_runtime.clj:44` | Tool count | `(count (:tool-ids ...))` |
| `session_runtime.clj:38` | Runtime tool list | Derive from `:tool-ids` |
| `workflow_judge.clj:61` | Empty tool-defs | Empty `:tool-ids` |
| `app_runtime.clj:364,468` | Session start / refresh | Derive at boundary |
| `system_prompt.clj:391-392` | Prompt assembly input | Accept derived defs, not session field |
| `session_state/init.clj` | Lifecycle select-keys | Remove |
| `session_state/model.clj` | Schema + defaults | Remove |
| `workflow_runtime/attempts.clj:70` | Step tool-defs | Derive from `:tool-ids` |
| `workflow_runtime/child_session_contract.clj:15` | Contract schema | Migrate to `:tool-ids` |
| `workflow_runtime/statechart_runtime.clj:90` | Step config | Derive from `:tool-ids` |
| `workflow_step_session_config/core.clj:166,196` | Resolve step tools | Derive from `:tool-ids` + registry |

## Acceptance criteria

1. No session state map contains `:tool-defs` or `:active-tools` after initialization.
2. `tool-authority-fields` returns only `{:tool-ids [...]}`.
3. All prompt assembly, child session creation, and workflow step configuration produce identical tool payloads as before (derived on demand).
4. Schema validates without `:tool-defs` or `:active-tools`.
5. All existing tests pass (updated as needed).
6. No regression in tool availability during sessions, workflows, or scheduled sessions.
