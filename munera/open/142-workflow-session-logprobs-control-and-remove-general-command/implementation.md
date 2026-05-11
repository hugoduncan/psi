# Implementation Notes

Created from user request on 2026-05-11.

## Requested change

Create a task to:
- add `:logprobs` as a workflow session control, similar to `:response-mode` from task 141
- remove the general `/logprobs` command

## Initial intent

This looks like a scope-tightening follow-on to tasks 140 and 141:
- task 140 introduced lower logprob capability plus a general command toggle
- task 141 introduced workflow-owned child-session execution controls for the motivating provider combination
- this task should align logprob control with that workflow-only execution-control pattern

## Open design decision to resolve before implementation

Choose and record one canonical enabled-state key shape:
- keep persisted session-data `:logprobs-enabled` and map workflow `:logprobs` onto it, or
- rename persisted session-data coherently to `:logprobs`

The task should not leave both names as parallel canonical state.

## 2026-05-11 design review

- Ambiguity: default semantics for `:top-logprobs` are underspecified when workflow config omits `:logprobs` but provides `:top-logprobs`; design says the field may remain absent unless enabled or "explicitly set by workflow resolution rules" but does not define whether resolution should preserve, drop, or reject that authored value.
- Ambiguity: propagation scope is incomplete in task artifacts; current code shows logprob controls would need decisions across `target_ir_compiler`, `resolve-step-session-config`, `statechart_runtime`, workflow attempt child-session creation, dispatch handlers, mutation params, and child-session state, not just the narrower surfaces named in design/plan.
- Ambiguity: removal scope for the general control path is not fully closed; design removes `/logprobs`, but does not state whether public non-workflow child-session/session-creation surfaces that already accept execution controls must stay unable to set logprob controls, which matters if the goal is truly workflow-only authoring.

## 2026-05-11 follow-up execution

- Resolved defaulting rule: workflow-authored `:top-logprobs` is dropped during workflow session-config resolution whenever `:logprobs` is false or absent. The config is treated as effectively disabled rather than rejected. When `:logprobs true` is present and `:top-logprobs` is omitted, persisted child-session state may leave `:top-logprobs` absent and lower `session->request-options` continues to supply task 140's default top-N of 3.
- Recorded propagation inventory from current code:
  - authored workflow session config enters through `components/workflow-runtime/src/psi/workflow_runtime/target_ir_compiler.clj`
  - resolved child-session config is shaped in `components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj`
  - workflow attempt child-session opts are assembled in `components/workflow-runtime/src/psi/workflow_runtime/attempts.clj`
  - workflow-only child-session creation crosses the higher seam in `components/agent-session/src/psi/agent_session/context.clj`
  - dispatch params flow through `components/agent-session/src/psi/agent_session/dispatch_handlers/session_lifecycle.clj`
  - persisted child-session state is created in `components/agent-session/src/psi/agent_session/child_session_state.clj`
  - lower request shaping consumes the persisted flags in `components/agent-session/src/psi/agent_session/prompt_request.clj`
- Related surface note: session-step execution under `statechart-runtime/*` depends on the propagated child-session config indirectly via workflow attempts; no separate statechart-only logprob toggle owner was found beyond keeping that path coherent.
- Boundary decision: removing `/logprobs` does not require removing the public `psi.extension/create-child-session` mutation, but it does require keeping that non-workflow mutation surface unable to author logprob controls in this task. Workflow-only propagation should widen only the internal workflow child-session path, not the public interactive mutation params.
- Current code evidence for the boundary:
  - public mutation `components/agent-session/src/psi/agent_session/mutations/session.clj` currently accepts `:response-mode` but no logprob params
  - workflow child-session seam `components/agent-session/src/psi/agent_session/context.clj` currently carries workflow-owned creation opts separately
  - command-only general toggle owners still live in `commands.clj`, `session_settings.clj`, `core.clj`, and `dispatch_handlers/session_mutations.clj`
- Result: the newly added design follow-up items are now resolved in task artifacts; no blocking ambiguity remains for implementation planning.

## 2026-05-11 consistency review

- Review result: no new actionable inconsistency found across `design.md`, `plan.md`, `steps.md`, and `design-steps.md`; previously identified ambiguities are already resolved and tracked without missing follow-up work.
