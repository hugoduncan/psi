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
