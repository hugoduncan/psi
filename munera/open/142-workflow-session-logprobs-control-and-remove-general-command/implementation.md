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
