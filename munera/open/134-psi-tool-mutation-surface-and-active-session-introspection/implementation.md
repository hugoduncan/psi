Implementation notes:
- design refined first to lock the concrete v1 API contract, examples, and test contract before code changes
- implementation plan is now split into five execution slices: mutation seam discovery, mutate action, active-session attr, compact session-summary attr, and composed workflow proof/docs

Decisions recorded so far:
- `psi-tool(action: "mutate")` is single-mutation-only in v1
- v1 uses `params` as the sole canonical business payload; canonical external shape is map/object
- v1 does not add tool-level `session-id`/`target-session-id` routing convenience; mutation params remain the single canonical business payload
- v1 does not support `entity` for `action: "mutate"`; supplying it should fail validation explicitly
- success results should return `:psi-tool/result` with the mutation payload preserved
- on success, `:psi-tool/error` is absent; on error, `:psi-tool/result` is absent
- error results should be structured with explicit phase labels such as `:validate` and `:mutation`, preserving original `ex-data` when available
- `:psi.agent-session/active-session-id` is the canonical authoritative active-session root attr and should return nil rather than guessing when no active conversation target exists
- `:psi.agent-session/context-session-summaries` is the canonical compact operational session inventory attr
- context-session-summaries should preserve canonical session ordering and prefer reusing the existing canonical session-info source/projection trimmed to the allowed fields
- mutate must reuse existing capability/permission/validation enforcement rather than bypassing it
- implementation should land in small vertical slices with focused proof after each slice where practical

Still to decide during implementation:
- canonical helper/path used to invoke registered mutations
- chosen source of truth/mechanics for active-session-id in the live runtime
- chosen owner/resolver path for context-session-summaries
- any session-targeting/routing clarifications needed to keep mutation execution explicit and safe in the final code
- exact split between unit-level proof and composed integration proof for the final workflow
