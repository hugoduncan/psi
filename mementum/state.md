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
- Task 128 workflow execution adapter seam is now implemented locally:
  - added lower owner `components/workflow-runtime/src/psi/workflow_runtime/execution_adapter.clj`
  - chose named seam `psi.workflow-runtime.execution-adapter` with adapter value key `:workflow-execution-adapter`
  - moved lower workflow-runtime higher/session-bound crossings behind the named seam in:
    - `psi.workflow-runtime.attempts`
    - `psi.workflow-runtime.turn-execution-contract`
    - `psi.workflow-runtime.step-session-config`
    - `psi.workflow-runtime.statechart-runtime`
  - canonical adapter assembly now lives in `psi.agent-session.context/workflow-execution-adapter`
  - `psi_tool_workflow` now backfills the named seam for older live ctx maps after compatibility callback backfill
  - focused tests now stub the named seam where they are proving workflow-runtime consumption
  - intentionally left lower-owned workflow step session-config/materialization collaborators outside the seam because they are not runtime → session crossings
  - focused verification green: `12 tests, 66 assertions, 0 failures, 0 errors`; lint clean
- Task 130 workflow step materialization component extraction is now landed locally:
  - added lower component `components/workflow-step-materialization/`
  - moved authoritative owners out of `components/workflow-runtime/` into:
    - `psi.workflow-step-materialization.core`
    - `psi.workflow-step-materialization.source-resolution`
  - preserved the `127` role split: step materialization remains separate from `psi.workflow-step-session-config.core`
  - rewired lower runtime consumers downward to the new owner:
    - `psi.workflow-runtime.statechart-runtime.step-execution`
    - `psi.workflow-runtime.statechart-runtime.delegate`
  - rewired higher session assembly/backfill surfaces downward to the new owner:
    - `psi.agent-session.context`
    - `psi.agent-session.psi-tool-workflow`
    - `psi.agent-session.test-support`
  - moved lower proof ownership to the new component:
    - `psi.workflow-step-materialization.core-test`
    - `psi.workflow-step-materialization.source-resolution-test`
  - removed the old runtime owners entirely instead of leaving forwarding seams:
    - `psi.workflow-runtime.step-materialization`
    - `psi.workflow-runtime.source-resolution`
  - preserved existing public behavior/call/output contracts while changing only ownership and namespace placement
  - retained direct dependency on `psi.workflow-judge/project-messages` inside the new lower source-resolution owner as legitimate shared lower workflow projection semantics
  - focused verification green: `16 tests, 35 assertions, 0 failures`; broader workflow/session verification green: `24 tests, 79 assertions, 0 failures`; lint green `0 errors, 0 warnings`

- Task 138 github extension label ops and workflow adoption is now complete and closed:
  - extracted `psi.github.slug` shared ns; `find-issue` rewired
  - added `psi.github.find-pr` (deterministic PR selection, parallel to `find-issue`; URL regex `#"/pull/(\d+)"`)
  - added `psi.github.label-ops` with `add-label` and `remove-label` handlers (`:target` dispatch; shared `label-csv`)
  - `psi.github.extension/init` now registers all four ops; `extension-test` asserts all four ids
  - 36 unit tests, 117 assertions, lint clean
  - migrated 10 workflows: `gh-bug-discover-and-read`, `gh-bug-triage`, `gh-issue-ingest`, `gh-issue-implement`, `gh-pr-fix-checks`, `gh-bug-post-repro`, `gh-bug-triage-modular`, `gh-bug-request-more-info`, `gh-issue-refine`, `gh-bug-fix-and-pr`
  - `gh-pr-fix-checks` uses `:labels []` (no label filter) for find-pr
  - `gh-bug-triage-modular` post-repro prompt-string is now `:type :map` (structured, not rendered text) per §P

## Suggested next step
- Queue is empty. Next candidates from backlog: `108-project-nrepl-testing-without-mocks`, `136-built-in-registration-path-for-workflow`, `105-agent-session-component-extraction-map`.
