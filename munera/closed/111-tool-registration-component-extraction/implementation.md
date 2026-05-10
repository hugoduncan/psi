2026-05-07

Task created from follow-on decomposition work under `105-agent-session-component-extraction-map` after reviewing the post-`104` tool boundary.

Creation rationale:
- `104-tool-runtime-component-extraction` already extracted lower-level tool execution mechanics into `components/tool-runtime/`
- a distinct remaining boundary still exists around canonical tool-definition ownership and extension tool registration
- current ownership is split between `psi.agent-session.tool-defs`, tool-specific logic in `psi.agent-session.extensions`, and thin higher-level registration entrypoints in extension mutations/API
- the desired first cut is narrower than a general “tool component” extraction: extract registration/catalog ownership without redesigning session tool-selection policy or re-merging execution concerns

Initial target recorded in `design.md`:
- component path: `components/tool-registry/`
- namespace family: `psi.tool-registry.*`
- expected first-cut namespaces:
  - `psi.tool-registry.defs`
  - `psi.tool-registry.registry`

Initial known current ownership/consumer inventory recorded at task creation:
- current canonical tool-definition owner:
  - `components/agent-session/src/psi/agent_session/tool_defs.clj`
- current tool-specific registration owner surfaces:
  - `valid-tool-name?` in `components/agent-session/src/psi/agent_session/extensions.clj`
  - `register-tool-in!` in `components/agent-session/src/psi/agent_session/extensions.clj`
  - tool-specific listing/query helpers in `components/agent-session/src/psi/agent_session/extensions.clj`
- known direct production consumers of canonical tool defs discovered during task creation review:
  - `session_runtime.clj`
  - `dispatch_effects.clj`
  - `conversation.clj`
  - `workflow_step_prep.clj`
  - `dispatch_handlers/session_mutations.clj`
  - `dispatch_handlers/scheduler.clj`
- known higher-level registration adapter seams at task creation:
  - `mutations/extensions.clj`
  - `extensions/api.clj`

Boundary decisions recorded at task creation:
- this task extracts registered tool-definition/catalog ownership, not session policy for active `:tool-defs`
- `tool-runtime` remains the owner of execution/runtime mechanics established by `104`
- command/flag/shortcut/generic handler registration remains outside this task
- extension mutation/API entrypoints may remain as thin higher-level adapters in the first cut

Review notes — ambiguities/open questions identified:
- The component name `tool-registry` may under-describe the fact that `psi.agent-session.tool-defs` is also consumed outside extension-registry flows (for example by session runtime, provider projection, and `components/ai/src/psi/ai/conversation.clj`).
- The biggest structural ambiguity is dependency direction around extension-registry helpers.
- The current consumer inventory in `design.md` was incomplete for tool-registration-specific queries.
- The task should preserve the current registered-tool query semantics explicitly.
- The task should state explicitly whether canonical tool-definition normalization continues to preserve runtime-only fields such as `:execute`, `:source`, and `:ext-path` in canonical maps while projection helpers strip or ignore them at the agent-core/provider boundaries.
- Tool-name validation scope was slightly ambiguous.
- Test ownership needed a sharper call.

Collaborative resolution recorded:
- accepted component name for this slice: `components/tool-registry/` with `psi.tool-registry.*`
- accepted first-cut layering decision: tool-specific registry operations may work directly over the current extension-registry state shape in this task; no prerequisite generic extension-registry extraction is required
- accepted consumer inventory expansion: include `bootstrap.clj`, `psi_tool.clj`, `tool_plan.clj`, `resolvers/extensions.clj`, and `components/ai/src/psi/ai/conversation.clj` as explicit in-scope consumers
- accepted behavior invariants: preserve `tool-names-in` as the cross-extension registered-name set and preserve `all-tools-in` as first-registration-wins by tool name
- accepted canonical-map rule: keep rich canonical normalized tool-def maps, including runtime-only internal fields such as `:execute`, `:source`, and `:ext-path`; projection helpers remain responsible for external boundary shaping
- accepted validation scope: canonical kebab-case tool-name validation remains scoped to extension registration in this slice rather than becoming a new global rule across every tool-def normalization path
- accepted test-ownership split: move lower-level tool-def normalization/projection and tool-registration/query behavior tests into the extracted component; keep mutation/API/resolver/integration proofs above the boundary

2026-05-08 implementation

Implemented extraction to new lower component:
- added `components/tool-registry/deps.edn`
- added authoritative namespaces:
  - `components/tool-registry/src/psi/tool_registry/defs.clj`
  - `components/tool-registry/src/psi/tool_registry/registry.clj`

Authoritative ownership now lives in `psi.tool-registry.*` for:
- canonical tool-definition normalization and projection helpers
- canonical tool-name validation for extension registration
- extension tool registration into the extension registry state shape
- registered-tool queries:
  - `tool-names-in`
  - `all-tools-in`
  - `get-tool-in`

Consumer migration completed:
- canonical tool-def consumers now require `psi.tool-registry.defs` directly:
  - `components/agent-session/src/psi/agent_session/session_runtime.clj`
  - `components/agent-session/src/psi/agent_session/conversation.clj`
  - `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
  - `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`
  - `components/agent-session/src/psi/agent_session/dispatch_handlers/scheduler.clj`
  - `components/agent-session/src/psi/agent_session/workflow_step_prep.clj`
  - `components/ai/src/psi/ai/conversation.clj`
- tool-registration/query consumers now depend on `psi.tool-registry.registry` where they specifically consume registered extension tools:
  - `components/agent-session/src/psi/agent_session/bootstrap.clj`
  - `components/agent-session/src/psi/agent_session/tool_plan.clj`
  - `components/agent-session/src/psi/agent_session/psi_tool.clj`
  - `components/agent-session/src/psi/agent_session/resolvers/extensions.clj`
  - `components/agent-session/src/psi/agent_session/mutations/extensions.clj`
  - several focused tests and helper test seams

Higher-level seam shaping:
- kept `psi.agent-session.mutations.extensions/register-tool` above the boundary as a thin adapter calling `psi.tool-registry.registry/register-tool-in!`
- kept `psi.agent-session.extensions` as the broader extension-registry owner, but removed authoritative tool-specific logic from it
- reintroduced thin compatibility seam functions in `psi.agent-session.extensions`:
  - `register-tool-in!`
  - `tool-names-in`
  - `all-tools-in`
  - `get-tool-in`
  each now delegates downward to `psi.tool-registry.registry`
- kept `create-extension-api` above the boundary while its tool registration callback now routes through the thin seam to the extracted owner

Compatibility status:
- `components/agent-session/src/psi/agent_session/tool_defs.clj` is no longer authoritative; it is now a thin compatibility wrapper re-exporting the extracted `psi.tool-registry.defs` vars
- no authoritative tool-definition implementation remains under `psi.agent-session.*`
- no authoritative tool-name validation or tool-registration implementation remains under `psi.agent-session.*`

Tests moved/added:
- added extracted-component tests under:
  - `components/tool-registry/test/psi/tool_registry/defs_test.clj`
  - `components/tool-registry/test/psi/tool_registry/registry_test.clj`
- reshaped `components/agent-session/test/psi/agent_session/tool_defs_test.clj` into a thin compatibility-wrapper proof so lower-level behavior ownership now lives under `tool-registry`
- removed lower-level duplicated tool-registration behavior proofs from `extensions_test.clj` while preserving higher-level extension registry / API / integration proofs

Test configuration updates:
- added `components/tool-registry/test` and `components/tool-registry/src` to root `tests.edn`
- added `components/tool-registry/test` and `components/tool-registry/src` to root `deps.edn` test aliases
- added `psi/tool-registry` as a root/component dependency where needed (`deps.edn`, `components/agent-session/deps.edn`, `components/ai/deps.edn`)

Behavior preserved explicitly:
- `tool-names-in` remains the cross-extension registered-name set
- `all-tools-in` remains first-registration-wins by tool name
- canonical normalized tool-def maps continue preserving runtime-oriented fields such as `:execute`, `:source`, and `:ext-path`
- tool-name validation remains scoped to extension registration

Focused verification run:
- `clj -M:test --focus psi.tool-registry.defs-test --focus psi.tool-registry.registry-test --focus psi.agent-session.tool-defs-test --focus psi.agent-session.extensions-io-test --focus psi.agent-session.query-graph-tools-test --focus psi.bootstrap-extension-invariant-test`
- result: `19 tests, 92 assertions, 0 failures`

Lint verification:
- `clj -M:lint`
- result: `0 errors, 0 warnings`

2026-05-08 review note

Review verdict:
- implementation matches the task design and intended architecture overall
- extraction boundary is correct in production code
- one non-blocking follow-up was identified before task closure

Follow-up note:
- `components/tool-registry/test/psi/tool_registry/registry_test.clj` still depends on upper-layer `psi.agent-session.extensions` for registry construction and setup
- this weakens the lower-component boundary slightly because component-owned tests still reach upward into the former owner layer
- preferred cleanup: introduce a minimal lower-level registry test fixture or tiny neutral helper so `tool-registry` tests can avoid requiring `psi.agent-session.extensions`

2026-05-08 follow-up implementation

Implemented the review follow-up:
- rewrote `components/tool-registry/test/psi/tool_registry/registry_test.clj` so it no longer requires `psi.agent-session.extensions`
- added a tiny local lower-level registry fixture in the test namespace:
  - `create-test-registry`
  - `register-extension-in!`
- the extracted component’s own tests now exercise `psi.tool-registry.registry` directly against the minimal registry-state shape it owns by contract, without reaching upward into `agent-session`

Follow-up verification:
- `clj -M:test --focus psi.tool-registry.defs-test --focus psi.tool-registry.registry-test`
- result: `6 tests, 26 assertions, 0 failures`
- `clj -M:lint`
- result: `0 errors, 0 warnings`

2026-05-08 code-shaper review note

Code-shaper verdict:
- the extraction shape is simple and coherent overall
- component boundaries improved materially
- one small robustness follow-up was identified

Follow-up note:
- `components/tool-registry/src/psi/tool_registry/registry.clj` `register-tool-in!` currently writes through `assoc-in` without enforcing that `ext-path` is already a registered extension path
- this can create partial `:extensions` entries that do not participate in canonical `:registration-order` tracking or carry the full expected extension-record shape
- preferred cleanup: make the registration precondition explicit by failing fast on unregistered `ext-path`, unless there is a deliberate supported use case for implicit pre-registration tool insertion

2026-05-08 robustness follow-up implementation

Implemented the robustness shaping follow-up:
- added `ensure-registered-extension-path!` to `components/tool-registry/src/psi/tool_registry/registry.clj`
- `register-tool-in!` now fails fast before writing tool data when `ext-path` is not already present in the registry `:extensions` map
- this makes the lower-level registry precondition explicit and prevents partial extension entries from being created outside canonical extension registration flow

Added focused regression coverage:
- `components/tool-registry/test/psi/tool_registry/registry_test.clj` now proves unregistered `ext-path` tool registration is rejected
- the regression also proves no partial registry drift occurs:
  - `:registration-order` remains empty
  - `:extensions` remains empty

Robustness follow-up verification:
- `clj -M:test --focus psi.tool-registry.defs-test --focus psi.tool-registry.registry-test`
- result: `6 tests, 29 assertions, 0 failures`
- `clj -M:lint`
- result: `0 errors, 0 warnings`
