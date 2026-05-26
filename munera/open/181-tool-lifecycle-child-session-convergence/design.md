# 181 tool lifecycle and child-session convergence

## Status: satisfied by tasks 179 + 180

Tasks 179 and 180 have already migrated all six lifecycle surfaces listed in scope. This task should be closed as satisfied. See "Scope analysis" below for the detailed accounting.

## Intent

Migrate bootstrap, new/resume/fork, and workflow child-session shaping so tool inheritance and filtering run from the authoritative tool membership/selection field introduced in task 180, rather than from embedded `:tool-defs` payloads.

After this task, every session lifecycle surface that narrows or inherits tools does so by operating on membership/selection authority first, then re-materializing `:tool-defs` as a derived execution payload.

## Context

Follow-on B from `178-registry-session-membership-unification`.

Task 180 introduced the authoritative session field for tool membership/selection and aligned direct mutations so they update membership authority first and `:tool-defs` second. This task extends that authority model into the lifecycle and child-session shaping surfaces that were left operating on `:tool-defs` directly.

## Scope analysis

All six lifecycle surfaces are already migrated:

1. **bootstrap/default session construction** — ✅ `init.clj` copies `:tool-ids` via `select-keys`; `:tool-defs` removed from session schema and `initial-session`. App-runtime `adopt-startup-plan-into-session!` dispatches `:session/set-active-tools` which sets `:tool-ids` as authority.
2. **new session** — ✅ `initialize-new-session-state` includes `:tool-ids` in its `select-keys` baseline.
3. **resume session** — ✅ `initialize-resumed-session-state` includes `:tool-ids` in its `select-keys` baseline; `session_lifecycle.clj` derives `resolved-tool-defs` via `resolve-tool-defs` from `:tool-ids`.
4. **fork session** — ✅ `initialize-forked-session-state` includes `:tool-ids` in its `select-keys` baseline; `session_lifecycle.clj` derives `resolved-tool-defs` from `:tool-ids`.
5. **child session creation** — ✅ `child_session_state.clj` accepts `:tool-ids`, derives tool-defs from registry lookup via `resolve-tool-defs`; dispatch event `:session/create-child` uses `:tool-ids`; `child_session_contract.clj` schema uses `[:tool-ids ...]`.
6. **workflow child-session shaping** — ✅ `workflow_step_session_config/core.clj` reads parent `:tool-ids` and derives tool-defs via `resolve-tool-defs`. Step-config outputs `:tool-defs` (derived full maps) as a local data structure, not session authority. `attempts.clj` extracts `:tool-ids` from these maps at the child-session contract boundary. This was explicitly documented in task 180's design as intentional: "Step-config continues to output `:tool-defs` (derived maps) — this is a local data structure, not session state."

### Remaining workflow step-config internal pipeline

The only seam where `:tool-defs` maps flow as an intermediate is the internal workflow step-config pipeline:
- `workflow_step_session_config/core.clj` outputs `:tool-defs` (resolved maps) in step-config
- `statechart_runtime.clj:90` passes `:tool-defs (:tool-defs step-config)`
- `attempts.clj:59,70` destructures `:tool-defs` and converts to `:tool-ids (when tool-defs (mapv :name tool-defs))` at the contract boundary

This is a **local data-passing concern** (step-config → statechart → attempts), not a session authority violation. The `:tool-defs` here are derived on-demand from `:tool-ids` + registry and never persisted to session state. Task 180 explicitly documented this as intentional.

## Resume backward compatibility

The backward-compatibility mechanism for resuming sessions persisted before `:tool-ids` existed is implicit bootstrap-after-resume re-population:

- `initialize-resumed-session-state` copies `:tool-ids` from `current-sd` (the in-memory session defaults from the already-bootstrapped source session), not from the persisted journal.
- The source session's `:tool-ids` are set during app startup by `adopt-startup-plan-into-session!` → `:session/set-active-tools`.
- Therefore, resumed sessions inherit the current source session's tool membership regardless of whether the persisted journal contained `:tool-ids`.
- No explicit migration logic is needed — the `select-keys` from `current-sd` mechanism is the intended backward-compatibility path.

## Test coverage

Tasks 179 and 180 already provide focused test coverage for parent/child tool selection semantics through the membership authority path:

- `tool_defs_test.clj` — `resolve-tool-defs` unit tests (filtering, ordering, empty/nil, unknown ids)
- `tool_authority_handlers_test.clj` — `:session/set-active-tools` and `:session/add-tool` dispatch handlers verify `:tool-ids` authority, absence of `:tool-defs`/`:active-tools` in session state, effect emission
- `child_session_state_test.clj` — `child-session-tool-ids-coherence-test` covers default inheritance, explicit override, and prompt-component-selection filtering of `:tool-ids`; `child-session-base-state-normalizes-and-inherits-test` verifies `:tool-defs` and `:active-tools` are absent from child session state
- `child_session_mutation_test.clj` — `create-child-session` dispatch mutation tests with `:tool-ids []`
- `child_session_contract_test.clj` — contract schema uses `:tool-ids`

No additional focused test coverage is required beyond what tasks 179 and 180 already added. AC 6 ("focused tests cover parent→child tool selection and re-derivation semantics") is satisfied.

## Desired outcome

- every lifecycle surface that shapes tool availability operates on the authoritative membership/selection field first
- `:tool-defs` is always re-derived from canonical registry definitions plus the authoritative membership/selection, never treated as input authority for narrowing decisions
- parent→child tool inheritance is expressed as membership inheritance/filtering, with `:tool-defs` rebuilt afterward
- focused tests prove parent/child tool selection semantics through the membership authority path

## Constraints

- preserve the existing `:tool-defs` execution payload — downstream consumers (provider request shaping, agent runtime tool installation, prompt assembly) still read it
- do not change the external behaviour of tool availability; only change the internal authority path
- keep resume backward-compatible with persisted sessions that predate tool membership fields

## Acceptance criteria

All acceptance criteria are satisfied by tasks 179 + 180:

- ✅ bootstrap seeds tool membership/selection from canonical registry definitions
- ✅ new/resume/fork session lifecycle derives `:tool-defs` from membership/selection plus registry lookup
- ✅ child-session creation narrows tools by membership/selection, then re-materializes `:tool-defs`
- ✅ workflow child-session shaping uses membership/selection vocabulary for tool narrowing
- ✅ no lifecycle surface treats `:tool-defs` as the input authority for tool narrowing or inheritance
- ✅ focused tests cover parent→child tool selection and re-derivation semantics
- ✅ resume handles sessions persisted before tool membership fields exist (backward compatibility)
