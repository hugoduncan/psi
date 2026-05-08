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
- Task 125 workflow-runtime core component extraction is now landed locally:
  - added new lower component `components/workflow-runtime/` with authoritative namespaces under `psi.workflow-runtime.*`
  - moved canonical workflow runtime owners out of `components/agent-session/src/psi/agent_session/`: model, IR, target IR compiler, statechart, source resolution, runtime core, progression recording, attempts, step prep, terminal contract, and statechart runtime
  - rewired higher `agent-session` owners (`context`, `workflow-execution`, workflow mutations/resolvers, `psi_tool_workflow`) to depend downward on `psi.workflow-runtime.*`
  - recorded final boundary decision that `workflow_runtime`, `workflow_step_prep`, and `workflow_terminal_contract` all belong in the extracted lower component, with `step_prep` and `terminal_contract` treated as sibling lower helpers rather than public/session orchestration owners
  - kept `workflow_statechart_runtime` mostly intact during the move instead of forcing extra decomposition in the same slice
  - preserved lower seam consumption: pure judge/routing remains in `psi.workflow-judge`, workflow runtime still uses the impure session-owned judge execution seam, and bounded actor execution still goes through `psi.agent-session.turn-execution-contract`
  - introduced explicit ctx callback keys (`:execute-workflow-judge-fn`, step-prep callbacks, session-state/skill lookups) so the extracted runtime component does not depend back upward on session-owned workflow namespaces
  - copied focused lower runtime tests into `components/workflow-runtime/test/psi/workflow_runtime/` and wired root test paths/deps for the new component
  - focused verification green: `57 tests, 275 assertions, 0 failures`; lint green: `0 errors, 0 warnings`

- Task 124 turn-execution contract extraction is now complete and closed:
  - added new workflow-facing bounded execution namespace `components/agent-session/src/psi/agent_session/turn_execution_contract.clj`
  - moved canonical assistant-text extraction and execution-failure normalization into that boundary
  - rewired `psi.workflow-runtime.statechart-runtime` to execute session-backed actor steps through `psi.agent-session.turn-execution-contract` instead of directly through `psi.agent-session.turn`
  - rewired `psi.agent-session.workflow-judge` to execute judge turns through the same contract instead of directly through `psi.agent-session.turn`
  - preserved workflow-specific session shaping, attempt child-session creation, judge child-session creation, routing interpretation, and judge retry orchestration above the new boundary
  - chose caller-supplied execution session ids as the boundary mode; the contract starts once workflow code has a session id and final prompt text
  - removed unused `:execution-result` retention from workflow pending actor state and added focused invariant proof for that boundary decision
  - shaped the contract so invocation selection and result normalization are separated, while keeping actor/judge entrypoints as intentional semantic aliases
  - aligned workflow judge tests to stub the extracted contract seam directly
  - verified through focused workflow surfaces and confirmed the component tests are runnable via top-level Kaocha `tests.edn` using `clojure -M:test`

- Task 123 workflow judge/routing component extraction is now landed locally:
  - created new lower component `components/workflow-judge/` with authoritative pure namespace `psi.workflow-judge`
  - moved canonical pure message projection and routing functions out of `psi.agent-session.workflow-judge`: `project-messages`, `match-signal`, `resolve-goto-target`, `check-iteration-limit`, and `evaluate-routing`, plus adjacent private helpers
  - kept `psi.agent-session.workflow-judge` as the authoritative impure owner of judge-session execution only (`execute-judge!`, persistence reads, prompt submission, retry feedback/orchestration)
  - rewired `workflow_source_resolution` to depend directly on `psi.workflow-judge`
  - split tests by boundary: pure projection/routing proofs now live in `components/workflow-judge/test/psi/workflow_judge_test.clj`; impure execution proofs remain in `components/agent-session/test/psi/agent_session/workflow_judge_test.clj`
  - preserved existing projection semantics, routing result shapes, and string step-id contract without redesign
  - focused verification green: `22 tests, 98 assertions, 0 failures`; lint green: `0 errors, 0 warnings`

- Task 122 prompt-control compatibility namespace removal is now landed locally:
  - removed `components/agent-session/src/psi/agent_session/prompt_control.clj` instead of leaving a forwarding shim
  - rewired `core`, `workflow-statechart-runtime`, `workflow-judge`, and `compaction-runtime` to depend directly on `psi.agent-session.turn`
  - rewired workflow-oriented tests and `with-redefs` callsites to target `psi.agent-session.turn/*`
  - removed the facade-only delegation test from `prompt_lifecycle_test.clj`; higher-level prompt lifecycle and workflow consuming-path tests remain the proof surfaces
  - updated active task text that still described `prompt-control` as a live compatibility seam
  - focused verification green: lint `0 errors, 0 warnings`; focused tests `1572 tests, 12038 assertions, 0 failures`

## Suggested next step
- Review `105-agent-session-component-extraction-map` against the new `workflow-runtime` landing and update the remaining extraction map/follow-ons.
- Likely next focused cleanup candidates from task 125 are session-owned child-session/judge callback surfaces if a later slice wants those lowered further.
