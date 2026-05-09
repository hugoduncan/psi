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
- Task 125 workflow-runtime core component extraction is now landed locally with follow-up decomposition, Kaocha wiring, and test-shaping polish complete:
  - added lower component `components/workflow-runtime/` with authoritative namespaces under `psi.workflow-runtime.*`
  - moved canonical workflow runtime owners out of `components/agent-session/src/psi/agent_session/`: model, IR, target IR compiler, statechart, source resolution, runtime core, progression recording, attempts, terminal contract, statechart runtime, and bounded turn execution contract
  - split the former mixed lower `psi.workflow-runtime.step-prep` owner into:
    - `psi.workflow-runtime.step-materialization`
    - `psi.workflow-runtime.step-session-config`
  - removed `psi.workflow-runtime.step-prep` instead of keeping a façade after rewiring production/test consumers directly to the split owners
  - rewired higher `agent-session` owners (`context`, `workflow-execution`, workflow mutations/resolvers, `psi_tool_workflow`, and `workflow-judge`) to depend downward on `psi.workflow-runtime.*`
  - rewired workflow callback/backfill surfaces directly to the split owners:
    - `:resolve-workflow-step-session-config-fn` → `psi.workflow-runtime.step-session-config/resolve-step-session-config`
    - `:materialize-workflow-step-session-conversation-fn` → `psi.workflow-runtime.step-materialization/materialize-step-session-conversation`
    - `:split-workflow-step-session-conversation-fn` → `psi.workflow-runtime.step-materialization/split-step-session-conversation`
  - moved the bounded prompt seam to `psi.workflow-runtime.turn-execution-contract` and deleted the old `agent-session` owner instead of leaving a compatibility shim
  - changed the lower turn execution contract to call a ctx-supplied `:workflow-prompt-execution-result-fn`, removing the back-edge from `workflow-runtime` to `psi.agent-session.turn`
  - decomposed the former large `psi.workflow-runtime.statechart-runtime` into smaller role-focused lower namespaces:
    - `psi.workflow-runtime.statechart-runtime.state`
    - `psi.workflow-runtime.statechart-runtime.queue`
    - `psi.workflow-runtime.statechart-runtime.step-execution`
    - `psi.workflow-runtime.statechart-runtime.delegate`
    - `psi.workflow-runtime.statechart-runtime.lifecycle`
    - with `psi.workflow-runtime.statechart-runtime` retained as the public orchestration façade
  - rewired lower proof ownership to match the split:
    - `psi.workflow-runtime.step-materialization-test`
    - `psi.workflow-runtime.step-session-config-test`
    - shared `psi.workflow-runtime.step-test-support`
  - wired `components/workflow-runtime/{src,test}` into the top-level Kaocha `tests.edn` unit and integration suites
  - verification green for the role split: focused workflow/runtime proofs `13 tests, 39 assertions, 0 failures`; lint green `0 errors, 0 warnings`

## Suggested next step
- Review `105-agent-session-component-extraction-map` against the now-complete workflow-runtime extraction and identify the next smallest boundary cleanup.
