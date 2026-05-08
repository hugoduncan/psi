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
- landed task `120-rename-psi-turn-to-agent-session-turn` resolved surrounding turn naming churn; this extraction does not need to solve turn naming or turn-namespace migration questions
- `operation-result->invoke-step-result` should move into `psi.agent-session.workflow-statechart-runtime` because invoke-step accepted-result/execution-error shaping is workflow-facing adapter logic, not generic deterministic-operation runtime behavior
- `psi.agent-session.workflow-statechart-runtime` is the pragmatic first-cut workflow-local home for that wrapper in this extraction slice; a later shaping pass could still choose a smaller workflow invoke helper namespace if warranted
- do not leave compatibility shims: remove the old authoritative `psi.agent-session.deterministic-operations` namespace after consumers are rewired
- callers that need defs-level validation/result helpers should use `psi.deterministic-operation-registry.defs` directly rather than through the extracted runtime component

Implementation notes:
- created new lower component `components/deterministic-operation-runtime/`
- added authoritative runtime namespace `psi.deterministic-operation-runtime.core`
- moved canonical invoke execution and malformed-result detection/`ex-info` shaping into the new component
- kept the extracted runtime component narrow: it does not re-export defs-level schemas or validation helpers
- updated `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj` to depend downward on:
  - `psi.deterministic-operation-runtime.core` for invoke execution
  - `psi.deterministic-operation-registry.defs` for workflow-local invoke-step wrapping validation/explanation
- moved `operation-result->invoke-step-result` into `workflow_statechart_runtime.clj` as explicit workflow-owned adapter logic
- rewired extension invoke-path tests to call `psi.deterministic-operation-runtime.core/invoke-operation`
- rewired workflow wrapping tests and `with-redefs` callsites to target `psi.agent-session.workflow-statechart-runtime/operation-result->invoke-step-result`
- removed the old authoritative namespace `components/agent-session/src/psi/agent_session/deterministic_operations.clj` rather than leaving a shim
- added focused runtime unit coverage in `components/deterministic-operation-runtime/test/psi/deterministic_operation_runtime/core_test.clj`

Residual boundary decision:
- the workflow-owned wrapper currently lives in `workflow_statechart_runtime.clj` because this task intentionally extracts only the lower deterministic-operation runtime seam
- if later workflow shaping wants a smaller helper ns for invoke-step adapter logic, that should be a workflow-local cleanup task rather than a reason to broaden this extraction

Review 2026-05-08:
- Reviewed implementation against task `121` design/plan and changed code paths.
- No new actionable code-quality or architecture issues found.
- The extraction matches the intended boundary: generic invoke execution now lives in `psi.deterministic-operation-runtime.core`, while workflow-specific invoke-step result wrapping stays explicit in `psi.agent-session.workflow-statechart-runtime`.
- Focused verification already recorded for the implementation remains the relevant proof surface for this slice.
- Minor task-artifact drift found during review: `steps.md` still showed the final implementation-note step unchecked even though `implementation.md` had already recorded the final boundary decisions; corrected that checklist item.

Code-shaper review 2026-05-08:
- One actionable follow-up: workflow invoke-path tests still stub non-canonical operation success results with `:status :succeeded` and stub the wrapper, which weakens proof of the extracted runtime/result contract.
- Follow-up should tighten those tests to use canonical `{:status :ok ...}` results and keep at least one focused workflow proof on the real wrapper path.

Follow-up execution 2026-05-08:
- Updated workflow invoke-path test stubs in `workflow_execution_test.clj` from `:status :succeeded` to canonical `:status :ok`.
- Removed wrapper stubbing from the focused session-contribution and delegate invoke-path workflow tests so those proofs now exercise the real `psi.agent-session.workflow-statechart-runtime/operation-result->invoke-step-result` path.
