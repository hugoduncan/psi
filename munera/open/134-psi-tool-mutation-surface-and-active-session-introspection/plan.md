Approach:
- implement this as a narrow `psi-tool` surface extension plus two small introspection additions, not as a general runtime-write redesign
- preserve the v1 contract already fixed in `design.md`: one registered mutation per tool call, params-only business payload, explicit structured success/error results
- reuse existing canonical mutation execution and session identity/state surfaces wherever possible
- land the work in small vertical slices, each ending in focused proof, so the new tool surface stays explicit and trustworthy
- prefer clear validation failures and stable result shapes over convenience shortcuts that could hide wrong-session behavior

Execution slices:

Slice 1 — Mutation dispatch seam discovery and contract shaping
1. wire `psi-tool(action: "mutate")` through the already-chosen canonical helper `psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!`
2. confirm that helper path continues to invoke registered mutations through the live runtime graph rather than raw `eval` or slash-command parsing
3. align implementation with the already-defined request/result/error contract in `design.md`
4. record the helper's remaining routing constraint in `implementation.md`: preserve caller-supplied `params :session-id` as authoritative when a mutation intentionally targets a session other than the invoking session

Slice 2 — `psi-tool(action: "mutate")` implementation
1. add `"mutate"` to the allowed `psi-tool` action contract
2. implement request parsing and validation for `mutation` and `params`
3. explicitly reject unsupported `entity` in v1 with a structured validation error
4. normalize string-keyed map/object input into the canonical mutation param shape if needed
5. execute the named registered mutation through the canonical helper path
6. ensure the mutate action reuses canonical capability, permission, and validation enforcement rather than bypassing it
7. shape success results as `#:psi-tool{:action :mutate ... :result ...}` with `:psi-tool/error` absent
8. shape invalid-request and mutation-execution failures as structured `:error` results with explicit phase labels, preserved `ex-data` where available, and `:psi-tool/result` absent

Slice 3 — Integrate with existing active session introspection attr
1. consume the now-landed `:psi.agent-session/active-session-id` root attr from task 139 as a prerequisite surface
2. record its authoritative source of truth in this task's implementation notes: the invoking query context's bound `:psi.agent-session/session-id`, not adapter-local UI focus or session ordering
3. keep semantics explicit: active conversation target for the live invoking runtime, not inferred oldest/newest session ordering
4. use the existing focused proof from task 139 as prerequisite coverage and add only workflow-composition proof here where this task depends on it

Slice 4 — Compact session summary attr
1. identify the smallest existing canonical session-info source/projection that can back a compact operational summary surface
2. add and wire root attr `:psi.agent-session/context-session-summaries`
3. keep only the intended identification/selection fields
4. explicitly exclude transcript-heavy or message-body payloads, including `:psi.session-info/first-message`, `:psi.session-info/all-messages-text`, and message-history joins
5. preserve the canonical ordering of the existing context session inventory surface
6. add focused proof for shape, content, boundedness, exclusions, and ordering

Slice 5 — Composed workflow proof and docs
1. add focused tests for successful mutation invocation and explicit validation failure behavior
2. include proof that unsupported `entity` fails validation, result invariants hold, and canonical enforcement is not bypassed
3. add a composed workflow/integration test proving query active session → query compact summaries → mutate chosen non-active session → verify result
4. verify the chosen session is gone, the active session remains, and `:psi.agent-session/active-session-id` is unchanged when the closed session was not active
5. update docs/examples to show the canonical query → select → mutate workflow
6. verify that the motivating session-cleanup workflow no longer requires raw runtime `eval` or a bespoke admin command

Planned outcomes:
- `psi-tool` has a first-class generic mutation action that reuses registered runtime mutations
- active/current session identity is explicitly introspectable
- compact session inventory is available without heavy payload expansion
- callers can safely compose runtime discovery with explicit mutation requests
- focused proof covers success, validation failure, introspection, and composed admin workflow behavior

Scope boundaries:
- no bespoke bulk-delete or delete-old-sessions command
- no generic dispatch/event-submission surface
- no batching DSL in v1
- no redesign of session lifecycle semantics
- no replacement of existing specialized `psi-tool` actions such as `project-repl`, `scheduler`, or `workflow`

Implementation questions to answer while coding:
- what exact helper/path should own registered mutation execution for `psi-tool` so the new action stays canonical and narrow?
- should the compact summary attr reuse the existing `context-sessions` projection and trim it, or have its own resolver/projection path?
- what is the cleanest way to normalize validation vs mutation-execution errors into the documented tool result shape?
