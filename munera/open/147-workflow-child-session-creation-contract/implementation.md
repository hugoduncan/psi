# Implementation

Created task only.

Initial shaping decisions:
- the canonical workflow child-session creation seam is `psi.workflow-runtime.execution-adapter/create-child-session!`
- both workflow attempt execution sessions and workflow-created judge sessions are in scope because they share that seam
- delegate steps are out of scope as direct creators; they create nested workflow runs whose internal session steps and judge phases use this seam indirectly
- prefer a lower executable contract owner plus boundary validation, rather than prose-only documentation or broad adapter redesign

Expected likely owners:
- `components/workflow-runtime/src/psi/workflow_runtime/execution_adapter.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/attempts.clj`
- new lower workflow-runtime child-session contract namespace
- `components/agent-session/src/psi/agent_session/context.clj`
- `components/agent-session/src/psi/agent_session/workflow_judge.clj`
- focused tests under `components/workflow-runtime/test/psi/workflow_runtime/` and `components/agent-session/test/psi/agent_session/`

2026-05-13 ambiguity review:
- Actionable ambiguity: `design.md` declares the seam field list authoritative after implementation but omits `:model-fallback`, while the current authored step path still carries `:model-fallback` in step-config and `statechart_runtime.clj` forwards it into `create-step-attempt-session!`, which then preserves it only on the returned execution-session map rather than crossing the child-session creation seam. The task should explicitly decide whether `:model-fallback` is part of the child-session contract, or explicitly mark it caller-local/non-seam state with preserved behaviour rationale so the “authoritative field list” does not silently narrow real workflow-authored behaviour.

2026-05-13 ambiguity follow-up:
- Resolved `:model-fallback` as caller-local, out of the workflow child-session creation seam.
- Audit result:
  - `psi.workflow-step-session-config.core/resolve-step-session-config` may produce `:model-fallback` alongside `:model`.
  - `psi.workflow-runtime.statechart-runtime` forwards that key into `psi.workflow-runtime.attempts/create-step-attempt-session!`.
  - `psi.workflow-runtime.attempts/create-step-attempt-session!` does not include `:model-fallback` in the opts passed to `execution-adapter/create-child-session!`.
  - `psi.agent-session.context/create-workflow-child-session!` therefore never realizes or persists `:model-fallback` on the child session.
  - `create-step-attempt-session!` reattaches `:model-fallback` only to the returned `:execution-session` map, which is the value later consumed by workflow step execution fallback logic.
- Design updated to make this explicit so the authoritative create-child field list does not silently narrow or accidentally absorb non-persisted attempt/runtime metadata.
- No blocker: the ambiguity was resolvable from current code and tests.

2026-05-13 inconsistency review:
- Actionable inconsistency: `design.md`/`plan.md` require the contract to cover both workflow attempt sessions and judge sessions under the same explicit seam, but `steps.md` omits any audit/proof work for the higher realization owner `create-workflow-child-session!` to verify the judge path’s narrower field surface against the same authoritative contract and omits any explicit follow-up to reconcile the existing mixed proof ownership split (`components/workflow-runtime/test/.../attempts_test.clj` vs `components/agent-session/test/.../workflow_attempts_test.clj`). Add design steps so implementation deliberately proves one authoritative contract across both callers and chooses the canonical proof owners rather than leaving duplicate/fragmented coverage implicit.

2026-05-13 inconsistency follow-up:
- Completed the newly added design-step follow-ups by updating task artifacts only.
- `design.md` now explicitly requires proof that `psi.agent-session.context/create-workflow-child-session!` applies the same authoritative create-child request/result contract to both the wider attempt caller surface and the narrower judge caller surface.
- `plan.md` now treats higher realization-edge proof as its own deliberate slice before caller-specific proof strengthening.
- `steps.md` now makes the realization-edge proof explicit and assigns canonical proof ownership by role:
  - workflow-runtime attempt tests own attempt-side request forwarding and attempt invariants
  - workflow-judge tests own judge-specific request semantics
  - higher realization-edge proof should live with `create-workflow-child-session!`
- No blocker: both unchecked design-steps were documentation/design alignment work and are now complete.

2026-05-13 implementation:
- Added lower contract owner `psi.workflow-runtime.child-session-contract` with closed malli request/result schemas plus executable assertion helpers.
- Authoritative request contract now covers the supported workflow child-session create surface crossing `psi.workflow-runtime.execution-adapter/create-child-session!`; minimal result contract remains `{:psi.agent-session/session-id <id>}`.
- `psi.workflow-runtime.attempts/create-step-attempt-session!` now validates the request before crossing the adapter seam and validates the returned result immediately after seam crossing.
- `psi.agent-session.workflow-judge/execute-judge!` now validates its narrower judge create request before crossing the seam and validates the returned result immediately after seam crossing.
- `psi.agent-session.context/create-workflow-child-session!` now treats the incoming request as the shared realization-edge contract input, validates it before realization, and validates the minimal result shape before returning.
- Preserved caller-local `:model-fallback` behaviour: it remains outside the create-child seam and is still reattached only to the returned `:execution-session` map from `create-step-attempt-session!`.

2026-05-13 proof additions:
- Added pure contract tests in `components/workflow-runtime/test/psi/workflow_runtime/child_session_contract_test.clj` for valid request/result handling and local malformed request/result failures.
- Extended `execution_adapter_test.clj` to prove `create-child-session!` forwards ctx, parent-session-id, and opts unchanged.
- Extended `attempts_test.clj` as the canonical attempt-path proof owner for:
  - supported request surface forwarding
  - local malformed request failures
  - local malformed result failures
  - preserved caller-local `:model-fallback` reattachment on the returned execution-session map
- Extended `workflow_judge_test.clj` as the canonical judge-path proof owner for:
  - explicit judge defaults
  - projected preload message forwarding
  - local malformed request failures
  - local malformed result failures
- Added higher realization-edge proof in `components/agent-session/test/psi/agent_session/workflow_child_session_context_test.clj` covering both:
  - wider attempt-shaped requests
  - narrower judge-shaped requests
  - persisted child-session state and runtime initialization
  - local malformed request failure at realization time

2026-05-13 verification:
- `clj-kondo --lint components/agent-session/src/psi/agent_session/context.clj components/agent-session/src/psi/agent_session/workflow_judge.clj components/workflow-runtime/src/psi/workflow_runtime/attempts.clj components/workflow-runtime/src/psi/workflow_runtime/child_session_contract.clj components/workflow-runtime/test/psi/workflow_runtime/child_session_contract_test.clj components/workflow-runtime/test/psi/workflow_runtime/execution_adapter_test.clj components/workflow-runtime/test/psi/workflow_runtime/attempts_test.clj components/agent-session/test/psi/agent_session/workflow_judge_test.clj components/agent-session/test/psi/agent_session/workflow_child_session_context_test.clj`
  - result: clean
- `clojure -M:test --focus psi.workflow-runtime.child-session-contract-test --focus psi.workflow-runtime.execution-adapter-test --focus psi.workflow-runtime.attempts-test --focus psi.agent-session.workflow-judge-test --focus psi.agent-session.workflow-child-session-context-test`
  - result: `27 tests, 125 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.child-session-mutation-test --focus psi.agent-session.workflow_execution_test --focus psi.agent-session.workflow_statechart_runtime_test`
  - result: `9 tests, 34 assertions, 0 failures`

2026-05-13 follow-up execution:
- Preloaded review-added actionable items were already implemented in the current worktree on task commit `016933a6` (`⚒ 147 workflow child-session creation contract`).
- Re-read `steps.md`, `implementation.md`, `design.md`, and `plan.md`; no newly added unchecked actionable follow-up items remain for this task.
- Re-ran the focused workflow child-session contract suites, nearby integration/regression suites, and lint to confirm the landed implementation remains green after the review follow-up pass.
- No blocker: there were no remaining unchecked follow-up items to execute beyond verification of the already-landed work.
