# Implementation

Created task only.

Initial shaping decisions:
- keep omitted-scope behavior unchanged for existing `/model <provider> <model-id>` and RPC callers
- expose explicit `session|project|user` scope rather than inventing new persistence semantics
- make workflow-owned model changes explicitly `:session` scoped so workflow execution remains transient and local to the created child session

Expected likely owners:
- command parsing/help in `psi.agent-session.commands`
- helper threading in `psi.agent-session.session-settings` and `psi.agent-session.core`
- RPC transport in `psi.rpc.session.ops`
- workflow execution adapter/runtime seam in `psi.agent-session.context` and workflow runtime fallback paths

No code changes yet.

## Design ambiguity review — pass 1 (2026-05-12)

Three actionable ambiguities found:

1. **Canonical helper/API surface boundary is underspecified for direct picker setters.** The design says the “canonical public helper/API surface used by commands and RPC can carry optional scope”, but current direct interactive setters (`components/emacs-ui/psi-session-commands.el:457`, `components/app-runtime/src/psi/app_runtime/tui_frontend_actions.clj:19`) call `set_model` / `session/set-model-in!` directly outside the slash-command parser. It is ambiguous whether these surfaces must also be updated to the new canonical scope-carrying helper shape for consistency, or whether they are intentionally out of scope so long as omitted-scope compatibility holds.

2. **Workflow-owned judge sessions are not explicitly classified under the transient-model rule.** The design’s authoritative workflow rule talks about “workflow runtime creates or updates the model for a workflow-owned child session” and cites execution child sessions and ranked fallback switching, but the likely owners and examples focus on execution sessions only. `components/agent-session/src/psi/agent_session/workflow_judge.clj` also creates workflow-owned child sessions. The task should explicitly say whether judge sessions are included in the session-scoped/no-persistence rule whenever they ever gain model-setting behavior, or whether this slice is intentionally restricted to actor/execution sessions.

3. **Acceptance does not explicitly require proof for both initial and fallback workflow model paths.** The design text says the transient rule includes the initially resolved concrete model and ranked fallback switching, but the acceptance/test bullets collapse this to one workflow regression assertion. Existing workflow tests already cover fallback switching (`components/agent-session/test/psi/agent_session/workflow_statechart_runtime_test.clj`) and would not by themselves prove that the initial child-session model setup path is also non-persistent. The task should make that proof obligation explicit.