# 182 remove :prompt-contributions projection from session state

## Intent

Eliminate the persisted `:prompt-contributions` vector from session state. After this task, `:prompt-contribution-ids` is the sole session-owned prompt membership field and all consumers derive prompt contribution data on demand from the registry via `prompt-storage/list-contributions`.

## Context

Follow-on C from `178-registry-session-membership-unification`.

Task 178 identified `:prompt-contributions` as a derived compatibility projection that should eventually be removed once refresh/introspection/mutation contracts no longer depend on it. The registry derivation path already exists and is authoritative — `prompt-storage/list-contributions` resolves `:prompt-contribution-ids` against the root-backed prompt registry and returns sorted contributions. The persisted vector is redundant.

This task mirrors the pattern established by task 180 (which removed `:tool-defs` and `:active-tools` from session state after task 179 introduced `:tool-ids` as the sole tool authority).

## Problem

Session state currently persists both:

- `:prompt-contribution-ids` — authoritative membership (vector of id strings)
- `:prompt-contributions` — derived projection (vector of full contribution maps)

The projection is written into session state at 4 handler sites and persisted/seeded at 4 additional lifecycle/init sites, but no site reads it back from session state — the resolver already derives on demand. Every handler write site already has the derived value from `prompt-storage/list-contributions` and persists the vector redundantly.

## Current write sites (`:prompt-contributions` into session state)

All in `prompt_handlers.clj`:

| Handler | Line | Pattern |
|---------|------|---------|
| `:session/register-prompt-contribution` | 94 | `assoc-in ... :prompt-contributions next*` |
| `:session/update-prompt-contribution` | 118 | `assoc-in ... :prompt-contributions next*` |
| `:session/unregister-prompt-contribution` | 140 | `assoc-in ... :prompt-contributions next*` |
| `:session/reset-prompt-contributions` | 157 | `assoc-in ... :prompt-contributions next*` |

Note: `:session/refresh-system-prompt` line 55 passes `:prompt-contributions nil` as a `build-system-prompt` build-opts parameter, not as a session-state write. Its `:root-state-update` writes only `:base-system-prompt` and `:system-prompt`. No changes needed in that handler for this task.

Plus persistence/init sites:

| Location | Pattern |
|----------|---------|
| `child_session_state.clj:114` | `assoc :prompt-contributions prompt-contributions` in child session data |
| `session_state/init.clj:94,150,196` | `select-keys` includes `:prompt-contributions` in new/resume/fork lifecycle |
| `session_state/model.clj:190,281` | schema definition and default `[]` |
| `nullable_api.clj:37` | seeds `:prompt-contributions []` in nullable extension test helper initial state |

## Current read sites (`:prompt-contributions` from session state)

None. The resolver at `resolvers/session.clj:196` already derives contributions via `ss/list-prompt-contributions-in` (which calls `prompt-storage/list-contributions`). The `:prompt-contributions` key at that line is an output map key in the `:prompt-layers` response, not a session-state read. No resolver migration is needed — removing the persisted field is sufficient.

## Derivation path already exists

`prompt-storage/list-contributions` takes `(root-state, session-data)` and:
1. reads `:prompt-contribution-ids` from session data
2. resolves each id against the root-backed prompt registry
3. returns sorted contributions

Every handler that currently writes `:prompt-contributions` already calls this function to produce the value — it then redundantly persists the result. The resolver already derives on demand via this path — no migration needed.

## Scope

### In scope

- Remove `:prompt-contributions` from session schema (`model.clj`)
- Remove `:prompt-contributions` from `initial-session` defaults (`model.clj`)
- Remove `:prompt-contributions` from lifecycle `select-keys` in `init.clj` (new/resume/fork)
- Remove `:prompt-contributions` persistence from `child_session_state.clj`
- Remove all `assoc-in ... :prompt-contributions` writes from `prompt_handlers.clj` (4 handlers) — handlers already derive the value for prompt assembly; they just stop persisting it
- Remove `:prompt-contributions []` seed from `nullable_api.clj` initial state
- Update tests that assert on `:prompt-contributions` presence in session state

Note: `resolvers/session.clj` already derives contributions on demand — no migration needed there.

### Out of scope

- Changing prompt registry ownership or `prompt-contribution-ids` semantics
- Changing how prompt contributions are resolved or sorted
- Removing `:prompt-contribution-ids` (that field is authoritative and stays)
- Shared lifecycle vocabulary consolidation (follow-on D from 178)

## Constraints

- `:prompt-contribution-ids` remains the sole authoritative session prompt membership field
- `prompt-storage/list-contributions` remains the canonical derivation API
- No behavioural change — same contributions appear in prompts, same introspection data is queryable
- Resume backward-compatible with sessions persisted before this change (`:prompt-contributions` in persisted state is simply ignored; `:prompt-contribution-ids` is authoritative)

## Acceptance criteria

- No session state map contains `:prompt-contributions` after initialization
- All prompt handler write sites no longer persist `:prompt-contributions` into session state
- Child-session creation no longer persists `:prompt-contributions`
- Lifecycle paths (new/resume/fork) no longer copy `:prompt-contributions`
- Session introspection resolver continues to derive contributions on demand (already does; no change needed)
- Schema validates without `:prompt-contributions`
- All existing tests pass (updated as needed)
- No regression in prompt contribution availability during sessions, workflows, or child sessions
