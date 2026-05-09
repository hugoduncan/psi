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
- Task 125 workflow-runtime core component extraction is now landed locally with follow-up decomposition complete:
  - added lower component `components/workflow-runtime/` with authoritative namespaces under `psi.workflow-runtime.*`
  - moved canonical workflow runtime owners out of `components/agent-session/src/psi/agent_session/`: model, IR, target IR compiler, statechart, source resolution, runtime core, progression recording, attempts, step prep, terminal contract, statechart runtime, and bounded turn execution contract
  - rewired higher `agent-session` owners (`context`, `workflow-execution`, workflow mutations/resolvers, `psi_tool_workflow`, and `workflow-judge`) to depend downward on `psi.workflow-runtime.*`
  - moved the bounded prompt seam to `psi.workflow-runtime.turn-execution-contract` and deleted the old `agent-session` owner instead of leaving a compatibility shim
  - changed the lower turn execution contract to call a ctx-supplied `:workflow-prompt-execution-result-fn`, removing the back-edge from `workflow-runtime` to `psi.agent-session.turn`
  - rewired step-prep callback injection directly to `psi.workflow-runtime.step-prep/*` instead of bouncing through `psi.agent-session.workflow-execution` wrappers
  - decomposed the former large `psi.workflow-runtime.statechart-runtime` into smaller role-focused lower namespaces:
    - `psi.workflow-runtime.statechart-runtime.state`
    - `psi.workflow-runtime.statechart-runtime.queue`
    - `psi.workflow-runtime.statechart-runtime.step-execution`
    - `psi.workflow-runtime.statechart-runtime.delegate`
    - `psi.workflow-runtime.statechart-runtime.lifecycle`
    - with `psi.workflow-runtime.statechart-runtime` retained as the public orchestration façade
  - split focused statechart-runtime tests accordingly under `components/workflow-runtime/test/psi/workflow_runtime/statechart_runtime/`
  - verification after decomposition green: focused tests `18 tests, 85 assertions, 0 failures`; lint green `0 errors, 0 warnings`

## Suggested next step
- Review `105-agent-session-component-extraction-map` against the now-complete workflow-runtime extraction and identify the next smallest boundary cleanup.
