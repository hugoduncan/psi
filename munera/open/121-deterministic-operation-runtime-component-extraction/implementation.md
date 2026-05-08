2026-05-07

Task created from workflow extraction review.

Creation rationale:
- `psi.agent-session.deterministic-operations` is entirely below the dispatch/adapter layer
- it is small and low-ambiguity compared to the larger workflow runtime extraction
- it is one of the few remaining non-workflow `agent-session` dependencies reached directly by lower workflow execution code
- extracting it should reduce workflow runtime dependence on `agent-session` without requiring a larger workflow-runtime redesign

Initial boundary notes:
- operation registration/query ownership is already handled separately by the extracted deterministic-operation registry component
- this task is specifically about invoke execution and result validation/wrapping
- authoritative extracted namespace is `psi.deterministic-operation-runtime.core` under `components/deterministic-operation-runtime/`
- `operation-result->invoke-step-result` should move into `psi.agent-session.workflow-statechart-runtime` because invoke-step accepted-result/execution-error shaping is workflow-facing adapter logic, not generic deterministic-operation runtime behavior
- do not leave compatibility shims: remove the old authoritative `psi.agent-session.deterministic-operations` namespace after consumers are rewired
- callers that need defs-level validation/result helpers should use `psi.deterministic-operation-registry.defs` directly rather than through the extracted runtime component
