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
