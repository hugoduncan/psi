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
