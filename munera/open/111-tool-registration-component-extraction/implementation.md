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
