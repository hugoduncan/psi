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
