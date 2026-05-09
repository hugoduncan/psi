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
