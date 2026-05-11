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

## 2026-05-11 implementation

- Kept the existing persisted session-state keys as the canonical lower shape:
  - `:logprobs-enabled`
  - `:top-logprobs`
- Workflow-authored config now accepts `:logprobs` / `:top-logprobs`, and workflow session-config resolution maps that authored surface onto the persisted child-session keys. This keeps the workflow surface aligned with `:response-mode` while avoiding a broader rename across request building, provider integrations, telemetry, and journal projection.
- Implemented workflow-only propagation across the full authored step path:
  - `psi.workflow-runtime.ir/session-spec-schema` now accepts optional `:logprobs` and `:top-logprobs`
  - `psi.workflow-runtime.target-ir-compiler` preserves those fields in compiled session steps / judges
  - `psi.workflow-step-session-config.core/resolve-step-session-config` now returns `:logprobs` with explicit disabled default and only retains `:top-logprobs` when enabled
  - `psi.workflow-runtime.statechart-runtime` and `psi.workflow-runtime.attempts` now pass the resolved controls into workflow child-session creation
  - `psi.agent-session.context`, `dispatch_handlers/session_lifecycle`, and `child_session_state` persist them onto workflow-owned child sessions as `:logprobs-enabled` / `:top-logprobs`
- Removal scope landed as designed:
  - removed `/logprobs` from command help, dispatch, prefix resolution, and TUI builtin slash autocomplete
  - removed the command-only helper / wrapper path in `session_settings.clj` and `core.clj`
  - removed the command-only mutation handler `:session/set-logprobs`
- Boundary preserved:
  - public `psi.extension/create-child-session` remains unchanged as the non-workflow surface; it still does not accept logprob params
  - workflow propagation is widened only on the internal workflow-owned child-session path
- Added focused proofs for:
  - workflow session-config default/explicit/drop semantics
  - workflow attempt propagation into persisted child-session state
  - child-session base-state persistence of logprob controls
  - request-option projection when persisted enabled-state is false
  - `/logprobs` command removal from backend resolution/help and TUI autocomplete surfaces

## 2026-05-11 implementation review

- Actionable: acceptance proof is still incomplete for the motivating combined-control case. Existing tests cover `:response-mode` propagation and logprob propagation independently, but no focused proof exercises a workflow-authored session step or attempt carrying both `:response-mode :non-streaming` and enabled logprobs through the same path. This leaves the final unchecked verification step unproven in code.

## 2026-05-11 follow-up execution

- Added focused combined-control proof in `components/workflow-runtime/test/psi/workflow_runtime/attempts_test.clj` covering the same workflow-owned child-session creation path with `:response-mode :non-streaming`, `:logprobs true`, and explicit `:top-logprobs` on one attempt.
- Verified the motivating provider-shaping case is now covered end-to-end by the focused proof set already present across:
  - `psi.workflow-step-session-config.core-test` for resolved workflow config carrying both controls
  - `psi.workflow-runtime.attempts-test` and `psi.agent-session.workflow-attempts-test` for child-session creation/persistence of both controls on the same execution path
  - `psi.turn-runtime.response-mode-test` for non-streaming execution path selection
  - existing logprob request-shaping proofs for provider request options/building
- Verification green: `bb clojure:test:unit --focus psi.workflow-runtime.attempts-test --focus psi.agent-session.workflow-attempts-test --focus psi.workflow-step-session-config.core-test --focus psi.turn-runtime.response-mode-test` → `1716 tests, 12629 assertions, 0 failures`.

## 2026-05-11 code-shaper review

- Review result: no new actionable simplicity/consistency/robustness issues found beyond the already recorded and resolved combined-control proof gap. The workflow-only logprob path remains coherent, the public non-workflow child-session mutation boundary is still closed, and the persisted `:logprobs-enabled` / authored `:logprobs` split is applied consistently across resolution, propagation, persistence, request shaping, and command-surface removal proofs.

## 2026-05-11 follow-up execution

- Re-read the preloaded code-shaper review result and task artifacts; no newly added unchecked follow-up items remained in `steps.md`.
- Re-verified the combined motivating proof set with:
  - `bb clojure:test:unit --focus psi.workflow-runtime.attempts-test --focus psi.agent-session.workflow-attempts-test --focus psi.workflow-step-session-config.core-test --focus psi.turn-runtime.response-mode-test`
- Verification remains green: `1716 tests, 11956 assertions, 0 failures`.
- No additional code or task-artifact changes were required for task `142`; the only unrelated working-tree change present before commit was `munera/plan.md`.

## 2026-05-11 task-implementation-review

- Review result: no new actionable feedback. Re-checked workflow-only propagation (`context.clj`, `statechart_runtime.clj`, `session_lifecycle.clj`, `child_session_state.clj`, `prompt_request.clj`), confirmed `/logprobs` remains removed from command resolution/help/autocomplete (`commands.clj`, `shared.clj`, backend/TUI tests), and confirmed the public non-workflow `psi.extension/create-child-session` mutation still does not accept logprob controls.

## 2026-05-11 task-test-review

- Review result: no new actionable feedback. Re-read the task-test-review skill and verified the proof set covers workflow logprob defaults/explicit/drop semantics (`workflow_step_session_config/core_test.clj`), same-path combined `:response-mode` + logprob child-session propagation (`workflow_runtime/attempts_test.clj`, `agent_session/workflow_attempts_test.clj`), command/help/autocomplete removal of `/logprobs` (`agent_session/commands_test.clj`, `tui/app_input_selector_test.clj`), preservation of the closed public non-workflow mutation surface (`agent_session/mutations/session.clj`, `agent_session/child_session_mutation_test.clj`), and runtime non-streaming execution behaviour (`turn_runtime/response_mode_test.clj`). Focused verification remains green: `bb clojure:test:unit --focus psi.workflow-step-session-config.core-test --focus psi.workflow-runtime.attempts-test --focus psi.agent-session.workflow-attempts-test --focus psi.agent-session.commands-test --focus psi.tui.app-input-selector-test --focus psi.agent-session.child-session-mutation-test --focus psi.turn-runtime.response-mode-test` → `1719 tests, 12651 assertions, 0 failures`.

## 2026-05-11 follow-up execution

- Re-read the preloaded review result and task artifacts; there were no newly added unchecked follow-up items left in `steps.md`.
- Re-ran the focused acceptance proof set:
  - `bb clojure:test:unit --focus psi.workflow-step-session-config.core-test --focus psi.workflow-runtime.attempts-test --focus psi.agent-session.workflow-attempts-test --focus psi.agent-session.commands-test --focus psi.tui.app-input-selector-test --focus psi.agent-session.child-session-mutation-test --focus psi.turn-runtime.response-mode-test`
- Verification remains green: `1719 tests, 11978 assertions, 0 failures`.
- No task-local code or checklist updates were required; the only unrelated working-tree changes remain `munera/plan.md` and the new open task directory `munera/open/143-workflow-session-inherit-delegating-session-preferences/`.

## 2026-05-11 test-shaper review

- Review result: no new actionable feedback. The proof set stays behavior-focused and deterministic: workflow session-config tests cover disabled/explicit/drop partitions, same-path combined `:response-mode` + logprob propagation is exercised in focused attempt tests, `/logprobs` removal is asserted at command/help/autocomplete boundaries, and request-option projection keeps the enabled/disabled/default-top-N cases narrow and explicit without case explosion.
