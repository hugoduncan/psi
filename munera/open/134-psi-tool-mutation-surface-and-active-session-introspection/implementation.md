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
- update user-facing docs/examples for the canonical query → select → mutate workflow
- whether any additional focused proof beyond the current green slices is still needed before closure

Clarifications locked from review:
- `mutation` should be validated as a string that parses as a qualified symbol before registry lookup
- when present, `params` must be a map/object payload; v1 may normalize only top-level string keys to keywords and should otherwise preserve values and unknown keys
- `:validate` errors are pre-invocation request or mutation-name failures; `:mutation` errors are failures after canonical mutation invocation is attempted
- success preserves the canonical mutation payload exactly; for `psi.extension/close-session` with no explicit target id this means a successful payload of `#:psi.agent-session{:close-session-closed? false :close-session-id nil}`, not a validation failure
- `:psi.agent-session/context-session-summaries` should expose exactly the allowed summary fields in v1, not a minimum-plus-extra shape
- task 139's `active-session-id` semantics are now fixed by landed code: invoking-session identity comes from the query context's bound `:psi.agent-session/session-id`, while adapter-local focus remains intentionally outside this graph surface
- `psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!` is already the concrete canonical mutation execution seam; implementation now preserves explicit caller targeting by not overwriting an already-supplied business `:session-id` for session-scoped extension mutations

2026-05-13 ambiguity review:
- Found one actionable design/plan inconsistency: `implementation.md` records the canonical mutation seam as already discovered (`psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!`), but `plan.md` still treated that seam choice as an open blocking question. Added follow-up in `design-steps.md` to align the task artifacts and make the remaining ambiguity explicit: how the chosen seam will preserve caller-supplied target `:session-id` for cross-session lifecycle mutations like `psi.extension/close-session`.
- Completed the ambiguity follow-up by updating `plan.md` to treat `psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!` as the chosen canonical helper. The remaining implementation ambiguity is now narrowed correctly: preserve explicit caller targeting when business `params` intentionally carry a different `:session-id` than the invoking session.

2026-05-13 inconsistency review:
- Found one actionable task-file inconsistency: `design.md` and `implementation.md` both lock `:psi.agent-session/context-session-summaries` to exact v1 fields including `:psi.session-info/updated`, but the referenced canonical source/projection in `resolvers/session.clj` currently exposes `context-sessions` entries without any `updated` field. `steps.md` and `plan.md` do not yet call out the needed source/projection alignment, so implementation could otherwise trim the existing projection and silently violate the design's exact-field contract. Added a follow-up in `design-steps.md` to align the session-info source/projection decision with the required `updated` field before coding the compact summary attr.
- Completed the inconsistency follow-up by inspecting the current live and persisted session-info owners. The chosen canonical source/projection path for `:psi.agent-session/context-session-summaries` is the live context inventory in `psi.agent-session.resolvers.session` backed by `psi.session-state.state/list-context-sessions-in`, because that is the existing source that defines the inherited context ordering required by the design. The discovery resolver's persisted listing already carries `:psi.session-info/modified`, but it is the wrong inventory/ordering surface for this task. The required follow-on implementation is therefore explicit: extend the shared live context-session projection to expose canonical `:updated-at` as `:psi.session-info/updated`, then trim that shared projection to the exact compact-summary v1 fields so the new attr stays aligned with the canonical live context inventory rather than forking a parallel model.

2026-05-13 task-implementation-review:
- Found one actionable proof gap, not a mechanism/design mismatch: the implementation and docs both promise that `psi-tool(action: "mutate")` may normalize top-level string-keyed `params` maps into canonical keyword-keyed mutation params, but the current mutate-focused proof set does not assert that contract on the live tool surface. Existing tests cover malformed params, unknown mutations, explicit session targeting preservation, compact summaries, and composed close-session flow, but not the documented string-key normalization behavior itself. Added a follow-up step to add a focused psi-tool mutate proof for that v1 contract and keep docs/spec/tests/code aligned.
- No additional architecture or code-shape issues found in the reviewed implementation slices. The mutate path stays canonical through `psi.agent-session.extensions.runtime-eql/run-extension-mutation-in!`, the compact summary resolver reuses the live context inventory projection instead of forking a parallel model, and the explicit-target preservation fix correctly narrows the session-routing hazard called out in the task design.

2026-05-13/14 implementation + verification:
- implemented `psi-tool(action: "mutate")` in `components/agent-session/src/psi/agent_session/psi_tool.clj`
  - request validation now covers supported action, required `mutation`, unsupported `entity`, params-map shape, qualified-symbol parsing, and registered-mutation lookup
  - success reports return `:psi-tool/result` with the canonical mutation payload preserved; failures return structured `:psi-tool/error`
- implemented compact root introspection attr `:psi.agent-session/context-session-summaries` in `components/agent-session/src/psi/agent_session/resolvers/session.clj`
  - added shared `context-session-info` projection so `context-sessions` and `context-session-summaries` share the same live source and ordering
  - extended the shared live projection to expose `:psi.session-info/updated` from session `:updated-at`
- implemented explicit-target preservation in `components/agent-session/src/psi/agent_session/extensions/runtime_eql.clj`
  - `run-extension-mutation-in!` now preserves an already-supplied business `:session-id` for session-scoped extension mutations instead of always overwriting it with the invoking session id
  - this is the critical behavior that makes canonical admin flows like `psi.extension/close-session` safe through psi-tool mutate
- testing nuance discovered while verifying mutate behavior:
  - `psi.extension/close-session` with missing `:session-id` does not throw; it returns the canonical successful payload `#:psi.agent-session{:close-session-closed? false :close-session-id nil}`
  - tests were aligned to assert actual canonical behavior rather than forcing a mutation-phase error expectation
- test-support nuance discovered in `tools_test.clj`:
  - the local `create-session-context` helper created contexts without `mutations/all-mutations`, which caused mutate requests to fail with `Unknown psi-tool mutation...`
  - fixed by constructing those test contexts with `:mutations mutations/all-mutations`
- graph-suite/OOM isolation:
  - `components/agent-session/test/psi/agent_session/graph_surface_test.clj` contained a pathological mega-query in `root-queryable-attrs-contract-test` that queried all advertised root attrs at once
  - changed the test to query each advertised root attr independently; focused graph suite then passed cleanly
  - full `bb clojure:test:unit` still OOMs elsewhere in the repository, so that remaining failure is separate from task 134
- focused verification now green:
  - `JAVA_TOOL_OPTIONS='-Xmx2g' clojure -M:test --focus psi.agent-session.graph-surface-test` → `22 tests, 2247 assertions, 0 failures`
  - `JAVA_TOOL_OPTIONS='-Xmx2g' clojure -M:test --focus psi.agent-session.tools-test/psi-tool-integration-test --reporter kaocha.report/dots --no-randomize` → `1 tests, 53 assertions, 0 failures`
  - `JAVA_TOOL_OPTIONS='-Xmx2g' clojure -M:test --focus psi.agent-session.resolvers-test --focus psi.agent-session.session-close-mutation-test --reporter kaocha.report/dots --no-randomize` → `23 tests, 141 assertions, 0 failures`

2026-05-14 follow-up execution:
- completed the review-added proof-gap follow-up for top-level string-key normalization on `psi-tool(action: "mutate")`
- added a focused live-surface test in `components/agent-session/test/psi/agent_session/psi_tool_mutate_test.clj` that invokes `psi.extension/close-session` through `psi-tool` with string-keyed `params`
- the proof asserts the request succeeds via the live tool surface, closes the explicit target session, and leaves the invoking session intact, covering the v1 contract promised by design/docs
- focused verification green:
  - `JAVA_TOOL_OPTIONS='-Xmx2g' clojure -M:test --focus psi.agent-session.psi-tool-mutate-test --reporter kaocha.report/dots --no-randomize` → `1 tests, 44 assertions, 0 failures`

2026-05-13 task-test-review:
- No new actionable test issues found after reviewing the task's mutate, resolver, graph-surface, and session-close proof surfaces against the locked design/test contract.
- Coverage already proves the required behaviors: successful mutate invocation, structured validation failures, explicit-target preservation through the canonical runtime mutation path, exact-field compact session summaries with ordering/exclusion checks, active-session composition, and the end-to-end query → select → mutate workflow.
- Focused verification rerun remains green across the reviewed suites: `clojure -M:test --focus psi.agent-session.psi-tool-mutate-test --focus psi.agent-session.resolvers-test --focus psi.agent-session.graph-surface-test --focus psi.agent-session.tools-test/psi-tool-integration-test --focus psi.agent-session.session-close-mutation-test --reporter kaocha.report/dots --no-randomize` → `47 tests, 2446 assertions, 0 failures`.

2026-05-14 follow-up execution:
- completed the newly added documentation/examples follow-up by extending `doc/graph-surface.md` with the canonical session-admin query → select → mutate workflow, alongside the existing compact session-summary discovery example
- documented the explicit caller-side selection rule: query active session id, query compact summaries, choose non-active target ids in caller logic, then invoke `psi-tool(action: "mutate")` with `psi.extension/close-session`
- no further code/test changes were needed for this follow-up because the live tool surface, docs in `doc/psi-project-config.md`, and focused proof already matched the locked design contract
