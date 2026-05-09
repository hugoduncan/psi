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
