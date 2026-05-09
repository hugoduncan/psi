2026-05-07

Task created from post-123/124/125 workflow boundary review.

Creation rationale:
- `psi.agent-session.workflow-execution` still appears to own lower helper behavior even though the authoritative lower workflow runtime component now exists
- the remaining higher façade is legitimate, but helper forwarding through it weakens the extracted dependency direction
- narrowing this namespace should make the boundary more legible without changing behavior

Initial boundary notes:
- expected higher façade owner: `execute-run!`, `resume-and-execute-run!`, and higher execution-result shaping
- expected lower owners: step-prep/materialization/config helper functions in `psi.workflow-runtime.*`
- preserve one obvious higher execution surface; do not delete it casually

Implementation notes:
- reviewed `psi.agent-session.workflow-execution` with `clj-surgeon :op :ls`; confirmed only two true façade entrypoints (`execute-run!`, `resume-and-execute-run!`) plus one private result-shaping helper, and identified six lower helper forwards that did not belong in the façade
- code search across production and tests found no remaining production callers using lower helpers via `psi.agent-session.workflow-execution`; callback wiring and dynamic lookup/backfill were already pointed directly at `psi.workflow-runtime.step-prep`
- removed the forwarded lower helper publics from `psi.agent-session.workflow-execution`: `binding-source-value`, `materialize-step-inputs`, `materialize-step-session-conversation`, `split-step-session-conversation`, `step-prompt`, and the `resolve-step-session-config` wrapper
- rewired affected tests to depend on the lower authoritative owner `psi.workflow-runtime.step-prep` directly, including config-resolution, prompt-materialization, conversation-splitting, and binding-source proof sites
- final remaining public vars in `psi.agent-session.workflow-execution` are exactly:
  - `execute-run!`
  - `resume-and-execute-run!`
- no temporary compatibility seam was kept; removal was fully local to boundary cleanup and did not require crossing into task `127` or `128`
- higher façade proofs remain in `workflow_execution_test`; lower helper proofs now point directly at `psi.workflow-runtime.step-prep`

Review note:
- lower-helper proof ownership was partially mixed: `workflow_execution_test` still housed step-prep proof cases, and the IR adoption binding-source proof was duplicated across `agent-session` and `workflow-runtime` suites

Follow-up implementation:
- moved lower step-prep proof cases out of `components/agent-session/test/psi/agent_session/workflow_execution_test.clj` into `components/workflow-runtime/test/psi/workflow_runtime/step_prep_test.clj`
- trimmed `workflow_execution_test` back to higher façade behavior only
- removed the duplicate `components/agent-session/test/psi/agent_session/workflow_ir_runtime_adoption_test.clj` proof surface, keeping the workflow-runtime-owned IR adoption proof as the authoritative lower coverage
- re-verified the affected namespaces through top-level Kaocha focused runs

Code-shaper review note:
- `components/workflow-runtime/test/psi/workflow_runtime/step_prep_test.clj` was the right lower-owner proof surface, but it had accumulated copied setup and mixed proof concerns

Code-shaper follow-up:
- extracted shared workflow step-prep test setup into `components/workflow-runtime/test/psi/workflow_runtime/step_prep_test_support.clj`
- split the lower-owner proof surface into role-focused namespaces:
  - `components/workflow-runtime/test/psi/workflow_runtime/step_prep_config_test.clj`
  - `components/workflow-runtime/test/psi/workflow_runtime/step_prep_prompt_test.clj`
- reduced `components/workflow-runtime/test/psi/workflow_runtime/step_prep_test.clj` to a minimal umbrella loader
- re-verified the reshaped lower-owner tests through top-level Kaocha focused runs

Test review note:
- test ownership and role-splitting are now good; only minor future shaping remained around façade fixture extraction and whether the minimal `step_prep_test.clj` umbrella still paid for itself

Test follow-up:
- extracted façade-specific workflow execution fixtures into `components/agent-session/test/psi/agent_session/workflow_execution_test_support.clj`
- removed the minimal `components/workflow-runtime/test/psi/workflow_runtime/step_prep_test.clj` umbrella loader and rely on direct focused lower-owner namespaces instead
- re-verified the affected test namespaces through top-level Kaocha focused runs
