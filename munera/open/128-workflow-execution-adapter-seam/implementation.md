2026-05-07

Task created from post-123/124/125 workflow boundary review.

Creation rationale:
- the main remaining workflow awkwardness is the implicit workflow-runtime ↔ session-owned callback boundary
- lower workflow runtime code now avoids upward namespace coupling, but the required higher services are still represented as a loose set of callback keys
- a named execution adapter seam should make the boundary clearer, testing simpler, and ownership more explicit without changing behavior

Initial boundary notes:
- expected seam responsibilities: workflow child-session creation, bounded prompt execution, judge execution, and the minimum parent-session reads needed for workflow step config shaping
- expected implementation owner: `agent-session`
- keep the seam workflow-specific and cohesive; avoid inventing an over-generic adapter

2026-05-09

Implemented named seam:
- chose `psi.workflow-runtime.execution-adapter`
- introduced named adapter value key `:workflow-execution-adapter`
- added lower owner `components/workflow-runtime/src/psi/workflow_runtime/execution_adapter.clj`

Chosen representation:
- explicit named adapter value/map with one lower namespace of wrapper functions
- workflow-runtime now calls `psi.workflow-runtime.execution-adapter/*` helpers instead of reading raw workflow-specific callback keys from ctx

Why this representation won:
- simpler than protocol/record machinery for a small fixed surface
- more inspectable and coherent than a thin, undocumented bag of raw ctx callback keys
- preserves existing ctx-based assembly without forcing a broader context framework redesign
- keeps the runtime-facing contract named and explicit while leaving implementation ownership in `agent-session`

Rejected alternatives:
- namespace API surface only: rejected because workflow-runtime still needed one explicit injected boundary value rather than hidden global/higher calls
- protocol/record seam: rejected as heavier than needed for the current small operation set and would add framework surface without behavioral gain
- thin wrapper over raw ctx keys without a named adapter value: rejected because it would leave the seam implicit and testing less coherent
- broader `session-adapter` naming: rejected because responsibilities remain specific to workflow execution/orchestration rather than a wider session substrate

Final responsibility inventory inside the seam:
- create workflow child session
- execute one bounded workflow prompt turn
- execute workflow judge orchestration
- read session data
- list context sessions
- find skill

Intentionally excluded from the seam:
- `:resolve-workflow-step-session-config-fn`
- `:materialize-workflow-step-session-conversation-fn`
- `:split-workflow-step-session-conversation-fn`

Why excluded:
- these are lower-owned workflow-runtime collaborators, not higher/session-bound services
- keeping them outside the adapter keeps the seam cohesive around runtime → session crossings only

Canonical assembly:
- `psi.agent-session.context/workflow-execution-adapter` now builds the canonical adapter from callback fns
- `create-context*` installs the adapter under `:workflow-execution-adapter`
- `psi_tool_workflow` now backfills the named adapter for older live ctx maps after backfilling callback compatibility as needed

Call-site rewiring completed:
- `psi.workflow-runtime.attempts`
- `psi.workflow-runtime.turn-execution-contract`
- `psi.workflow-runtime.step-session-config`
- `psi.workflow-runtime.statechart-runtime`
- higher `psi.agent-session.workflow-judge` also routes child-session creation through the same adapter seam

Testing/wiring updates:
- shared test support now injects the named adapter by default
- focused workflow tests that intentionally prove workflow-runtime consumption now stub the named seam instead of raw callback keys
- retained raw callback fns in ctx behind the seam for canonical assembly and compatibility backfill

Residual raw-key plumbing left behind the seam:
- `agent-session.context` still owns raw callback fn provisioning because it is the canonical assembly site
- `psi_tool_workflow` still backfills raw callback keys first for compatibility with older live contexts, then derives the named adapter from that compatibility surface

Residual direct workflow-runtime raw-key dependence:
- none found in the targeted runtime surfaces after rewiring `attempts`, `turn-execution-contract`, `step-session-config`, and `statechart-runtime`

Verification:
- focused tests: `12 tests, 66 assertions, 0 failures, 0 errors`
- focused lint: clean

Follow-on notes:
- broader cleanup of raw callback keys inside `agent-session` plumbing remains out of scope for this task
- if later workflow runtime needs another higher/session-bound operation, extend the named adapter intentionally rather than adding new direct raw ctx key reads
