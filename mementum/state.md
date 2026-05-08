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
- Task 124 turn-execution contract extraction is now complete and closed:
  - added new workflow-facing bounded execution namespace `components/agent-session/src/psi/agent_session/turn_execution_contract.clj`
  - moved canonical assistant-text extraction and execution-failure normalization into that boundary
  - rewired `psi.agent-session.workflow-statechart-runtime` to execute session-backed actor steps through `psi.agent-session.turn-execution-contract` instead of directly through `psi.agent-session.turn`
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

- Task 110 prompt-assets component extraction is now landed locally:
  - created new lower component `components/prompt-assets/` with authoritative namespaces `psi.prompt-assets.prompt-templates`, `psi.prompt-assets.skills`, and `psi.prompt-assets.system-prompt`
  - moved the three authoritative source namespaces and their focused tests out of `components/agent-session/` into the new component without introducing compatibility forwarding shims
  - updated `agent-session` and `app-runtime` consumers to depend downward on `psi.prompt-assets.*`
  - wired `psi/prompt-assets` into the root deps plus `components/agent-session/deps.edn` and `components/app-runtime/deps.edn`
  - kept `conversation`, `tool-defs`, and `message-text` outside the extracted component boundary as intended
  - focused verification green: `12 tests, 57 assertions, 0 failures`; lint green: `0 errors, 0 warnings`

- Task 109 shared-config extraction is now landed locally:
  - new lower component `components/shared-config/` owns user config, project config, and shared `:agent-session` resolution
  - `app-runtime` now depends on `psi.shared-config.resolution`
  - `agent-session.dispatch-effects` now writes through shared-config user/project helpers
  - `project-nrepl.config` now consumes shared-config reads/extraction instead of its copied file/merge substrate
  - removed old authoritative `psi.agent-session.config-resolution`, `psi.agent-session.project-preferences`, and `psi.agent-session.user-config` namespaces rather than leaving shims
  - focused verification green: `9 tests, 50 assertions, 0 failures`

- Task 086 delegated-boundary runtime invocation plumbing is now landed:
  - canonical IR `:type :delegate` steps now execute through `workflow_statechart_runtime.clj`
  - delegate execution resolves target workflow definitions, renders delegated `:prompt-string`, materializes ordered delegated `:context`, creates a callee workflow run via the canonical runtime seam, and executes it through the same Phase A statechart runtime path
  - workflow runs now support explicit top-level `:workflow-original` in addition to `:workflow-input`; source resolution now prefers that explicit field when present so delegated callees see the exact boundary payload promised by task 077/086
  - delegating step accepted results default to the callee workflow terminal accepted-result envelope, with delegate boundary diagnostics recorded under `[:diagnostics :delegate]`
  - focused delegate execution proofs landed in `workflow_execution_test.clj` (delegate-only and mixed session→delegate)
  - focused verification green: `35 tests, 178 assertions, 0 failures`

## Suggested next step
- Task `125-workflow-runtime-core-component-extraction` is now the direct follow-on.
- It can target `turn-execution-contract` instead of direct `turn` usage for session-backed actor/judge execution.
