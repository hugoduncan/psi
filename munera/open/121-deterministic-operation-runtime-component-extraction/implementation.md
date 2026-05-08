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
- the only meaningful ownership choice to settle during implementation is whether `operation-result->invoke-step-result` remains with deterministic-operation runtime in the first cut or moves into workflow-owned adapter code
