# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — project meta model
- `munera/plan.md` — active task orchestration
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state
- Task 125 workflow-runtime core component extraction is now landed locally with review follow-ups addressed:
  - added new lower component `components/workflow-runtime/` with authoritative namespaces under `psi.workflow-runtime.*`
  - moved canonical workflow runtime owners out of `components/agent-session/src/psi/agent_session/`: model, IR, target IR compiler, statechart, source resolution, runtime core, progression recording, attempts, step prep, terminal contract, statechart runtime, and now the bounded turn execution contract
  - rewired higher `agent-session` owners (`context`, `workflow-execution`, workflow mutations/resolvers, `psi_tool_workflow`, and `workflow-judge`) to depend downward on `psi.workflow-runtime.*`
  - recorded final boundary decision that `workflow_runtime`, `workflow_step_prep`, and `workflow_terminal_contract` all belong in the extracted lower component, with `step_prep` and `terminal_contract` treated as sibling lower helpers rather than public/session orchestration owners
  - kept `workflow_statechart_runtime` mostly intact during the move instead of forcing extra decomposition in the same slice
  - moved the bounded prompt seam to `psi.workflow-runtime.turn-execution-contract` and deleted the old `agent-session` owner instead of leaving a compatibility shim
  - changed the lower turn execution contract to call a ctx-supplied `:workflow-prompt-execution-result-fn`, removing the back-edge from `workflow-runtime` to `psi.agent-session.turn`
  - rewired step-prep callback injection directly to `psi.workflow-runtime.step-prep/*` instead of bouncing through `psi.agent-session.workflow-execution` wrappers
  - copied focused lower runtime tests into `components/workflow-runtime/test/psi/workflow_runtime/` and wired root test paths/deps for the new component
  - focused verification green: `57 tests, 275 assertions, 0 failures`; focused workflow execution re-check green: `17 tests, 83 assertions, 0 failures`; lint green: `0 errors, 0 warnings`

- Task 123 workflow judge/routing component extraction remains the supporting lower pure routing/projection seam:
  - `psi.workflow-judge` owns pure projection and routing
  - `psi.agent-session.workflow-judge` remains the higher impure judge-session execution owner, now consuming the lower workflow-runtime turn execution seam

## Suggested next step
- Review `105-agent-session-component-extraction-map` against the fully landed `workflow-runtime` extraction and identify the next smallest boundary cleanup.
