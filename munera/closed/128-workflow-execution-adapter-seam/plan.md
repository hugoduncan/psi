Approach:
- treat this as explicit boundary modelling for the remaining workflow-runtime ↔ session-owned interaction surface
- preserve behavior while replacing scattered workflow-specific callback keys with one named seam
- keep the seam small, cohesive, and specific to workflow runtime execution needs
- allow `agent-session.context` to remain the assembly site for the canonical seam implementation while moving workflow-runtime to consume the named seam instead of raw workflow-specific callback keys
- leave broader context/protocol redesign out of scope

Planned outcomes:
1. inventory workflow-runtime callback dependencies currently supplied through ctx, preferably against the post-`127` shape when available
2. identify the smallest cohesive execution/session adapter surface they imply
3. choose one explicit representation for the seam and record why it was better than the main rejected alternatives, including the most plausible alternatives named in the design
4. define and adopt the named adapter seam in workflow-runtime call sites
5. provide the canonical implementation from `agent-session`, including `psi_tool_workflow` backfill/compatibility wiring where it participates in workflow-specific callback provisioning
6. simplify test wiring/stubbing around the named seam
7. record the final seam name, representation, responsibilities, excluded concerns, and any residual raw-key plumbing in `implementation.md`

Scope boundaries:
- no workflow behavior redesign
- no child-session or judge semantic redesign
- no dispatcher redesign
- no broad context abstraction overhaul
- no unrelated callback cleanup outside workflow runtime needs
