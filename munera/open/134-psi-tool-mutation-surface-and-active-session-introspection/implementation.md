Implementation notes:
- design refined first to lock the concrete v1 API contract, examples, and test contract before code changes
- implementation plan is now split into five execution slices: mutation seam discovery, mutate action, active-session integration, compact session-summary attr, and composed workflow proof/docs

Discovery notes from current codebase state:
- task 139 is already landed: `components/agent-session/src/psi/agent_session/resolvers/session.clj` now contains `active-session-id-resolver`, and focused proof exists in `components/agent-session/test/psi/agent_session/resolvers_test.clj`
- the authoritative source of truth for `:psi.agent-session/active-session-id` is the invoking query context's `:psi.agent-session/session-id`, matching task 139's design and implementation
- the canonical registered mutation execution helper already exists at `psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!`
- `run-extension-mutation-in!` registers the live resolver/mutation graph, builds canonical runtime seed params, and executes the named mutation through `query/query-in`; this satisfies the design requirement that `psi-tool` reuse the production mutation path rather than routing through raw `eval` or slash-command parsing
- important routing constraint discovered in the canonical mutation helper: for session-scoped extension mutations it currently injects `:session-id` from the helper's `session-id` argument, so `psi-tool(action: "mutate")` must ensure explicit business params remain authoritative for operations like `psi.extension/close-session` that intentionally target a session other than the caller
- the existing broad session inventory surface is `:psi.agent-session/context-sessions` on `agent-session-identity` in `resolvers/session.clj`; a compact summary attr should likely reuse the same `ss/list-context-sessions-in` source and preserve its ordering while trimming fields

Decisions recorded so far:
- `psi-tool(action: "mutate")` is single-mutation-only in v1
- v1 uses `params` as the sole canonical business payload; canonical external shape is map/object
- v1 does not add tool-level `session-id`/`target-session-id` routing convenience; mutation params remain the single canonical business payload
- v1 does not support `entity` for `action: "mutate"`; supplying it should fail validation explicitly
- success results should return `:psi-tool/result` with the mutation payload preserved
- on success, `:psi-tool/error` is absent; on error, `:psi-tool/result` is absent
- error results should be structured with explicit phase labels such as `:validate` and `:mutation`, preserving original `ex-data` when available
- `:psi.agent-session/active-session-id` is already landed as the canonical authoritative active-session root attr and should be treated as a prerequisite dependency of this task, not reopened here
- `:psi.agent-session/context-session-summaries` is the canonical compact operational session inventory attr
- context-session-summaries should preserve canonical session ordering and prefer reusing the existing canonical session-info source/projection trimmed to the allowed fields
- mutate must reuse existing capability/permission/validation enforcement rather than bypassing it
- implementation should land in small vertical slices with focused proof after each slice where practical

Still to decide during implementation:
- chosen owner/resolver path for context-session-summaries
- exact split between unit-level proof and composed integration proof for the final workflow

Clarifications locked from review:
- `mutation` should be validated as a string that parses as a qualified symbol before registry lookup
- when present, `params` must be a map/object payload; v1 may normalize only top-level string keys to keywords and should otherwise preserve values and unknown keys
- `:validate` errors are pre-invocation request or mutation-name failures; `:mutation` errors are failures after canonical mutation invocation is attempted
- `:psi-tool/result nil` is a valid success when the canonical mutation returns nil
- `:psi.agent-session/context-session-summaries` should expose exactly the allowed summary fields in v1, not a minimum-plus-extra shape
- task 139's `active-session-id` semantics are now fixed by landed code: invoking-session identity comes from the query context's bound `:psi.agent-session/session-id`, while adapter-local focus remains intentionally outside this graph surface
- `psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!` is already the concrete canonical mutation execution seam; remaining implementation work is to preserve explicit caller targeting when a mutation intentionally acts on a session other than the invoking session

2026-05-13 ambiguity review:
- Found one actionable design/plan inconsistency: `implementation.md` records the canonical mutation seam as already discovered (`psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!`), but `plan.md` still treated that seam choice as an open blocking question. Added follow-up in `design-steps.md` to align the task artifacts and make the remaining ambiguity explicit: how the chosen seam will preserve caller-supplied target `:session-id` for cross-session lifecycle mutations like `psi.extension/close-session`.
- Completed the ambiguity follow-up by updating `plan.md` to treat `psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!` as the chosen canonical helper. The remaining implementation ambiguity is now narrowed correctly: preserve explicit caller targeting when business `params` intentionally carry a different `:session-id` than the invoking session.

2026-05-13 inconsistency review:
- Found one actionable task-file inconsistency: `design.md` and `implementation.md` both lock `:psi.agent-session/context-session-summaries` to exact v1 fields including `:psi.session-info/updated`, but the referenced canonical source/projection in `resolvers/session.clj` currently exposes `context-sessions` entries without any `updated` field. `steps.md` and `plan.md` do not yet call out the needed source/projection alignment, so implementation could otherwise trim the existing projection and silently violate the design's exact-field contract. Added a follow-up in `design-steps.md` to align the session-info source/projection decision with the required `updated` field before coding the compact summary attr.
