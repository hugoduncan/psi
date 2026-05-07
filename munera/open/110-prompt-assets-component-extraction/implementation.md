2026-05-07

Task created from the `105-agent-session-component-extraction-map` umbrella after a focused namespace dependency review of the prompt composition / prompt assets slice.

Creation rationale:
- the umbrella in `105` identified prompt composition / prompt assets as a coherent extractable subsystem
- a live dependency-graph review sharpened that candidate into a narrower and cleaner component boundary
- the true prompt-assets chain is currently:
  - `psi.agent-session.prompt-templates`
  - `psi.agent-session.skills`
  - `psi.agent-session.system-prompt`
- adjacent namespaces originally mentioned near this area do not belong in the same first-cut extraction:
  - `psi.agent-session.conversation` is provider/request projection
  - `psi.agent-session.tool-defs` is a broader shared tool-definition substrate
  - `psi.agent-session.message-text` is a shared transcript/display helper

Observed internal dependency graph at task creation time:
- `prompt-templates` -> no internal `psi.*` deps
- `skills` -> `prompt-templates`
- `system-prompt` -> `skills`
- `conversation` -> `system-prompt`, `tool-defs`, `psi.ai.conversation`, `psi.tool-runtime.args`
- `tool-defs` -> no internal `psi.*` deps
- `message-text` -> no internal `psi.*` deps

Observed key live consumers at task creation time:
- `prompt-templates` consumers:
  - `app-runtime.clj`
  - `agent_session/prompt_request.clj`
  - `agent_session/commands.clj`
  - `agent_session/resolvers/discovery.clj`
  - `agent_session/workflow_file_parser.clj`
- `skills` consumers:
  - `app-runtime.clj`
  - `app_runtime/output.clj`
  - `agent_session/prompt_request.clj`
  - `agent_session/system_prompt.clj`
  - `agent_session/resolvers/discovery.clj`
  - `agent_session/workflow_step_prep.clj`
- `system-prompt` consumers:
  - `app-runtime.clj`
  - `agent_session/prompt_request.clj`
  - `agent_session/child_session_state.clj`
  - `agent_session/dispatch_handlers/prompt_handlers.clj`

Initial extraction decision recorded here:
- extract only the prompt-assets chain as the new component
- explicitly keep `conversation`, `tool-defs`, and `message-text` out of scope
- preserve current behavior before considering any deeper decomposition

Expected follow-on review point during implementation:
- `discover-context-files` currently lives in `system_prompt.clj`
- if extraction exposes pressure for a lower shared context-discovery owner, record it explicitly rather than silently broadening or relocating unrelated logic

Review notes — ambiguities and open questions identified after task creation:
- initially unresolved questions included namespace naming, compatibility-shim termination, verification specificity, consumer migration scope, context-file ownership, and extracted-test destination/naming
- those questions have since been resolved by the tightening passes recorded below; they remain here only as historical review context, not as current open design ambiguity

Tightening pass applied using the refactoring skill:
- fixed the authoritative namespace family to `psi.prompt-assets.*`
- fixed the component directory to `components/prompt-assets/`
- made compatibility shims explicitly temporary and required their removal before task completion
- clarified that all listed live consumers, not only `agent-session` and `app-runtime` core entrypoints, must be migrated
- set the default first-cut decision that `discover-context-files` moves unchanged with `system-prompt`
- set the default first-cut decision that `skills -> prompt-templates` reuse remains intact unless a concrete blocker forces a lower helper split
- made the focused test destination explicit as `components/prompt-assets/test/`
- tightened verification language around focused prompt-assets tests and prompt-building/child-session prompt-shaping consuming-path checks
- named the intended authoritative focused verification commands for the extracted component and the key child-session consuming-path proofs
- clarified that the consumer inventory in `design.md` is the minimum known set at task creation time and not a scope limit
- clarified that task completion requires both relocation of focused tests into `components/prompt-assets/test/` and final authoritative test namespaces under `psi.prompt-assets.*-test`
- clarified that indirect app-runtime coverage through shared prompt-building paths is acceptable unless extraction reveals a distinct app-runtime-only regression surface
