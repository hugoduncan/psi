2026-05-07

Task created from post-123/124/125 workflow boundary review.

Creation rationale:
- `psi.workflow-runtime.step-prep` is now the most boundary-sensitive lower workflow-runtime namespace
- it currently mixes workflow step materialization with parent-session/config shaping
- separating those roles should improve local comprehensibility and make future ownership decisions easier without forcing a behavior redesign

Initial boundary notes:
- expected materialization owner: a lower workflow step materialization namespace
- expected config-shaping owner: a distinct lower workflow step session-config namespace
- do not move either role back to `agent-session` by default; decide from the resulting ownership shape
