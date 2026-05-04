Approach:
- treat source/reference/projection resolution as core workflow runtime infrastructure rather than as helper logic owned by any one step form
- keep the first cut small and exact: resolve shared source kinds, honor either `:path` or `:projection`, and reject ambiguous specs
- centralize the logic so schema/compiler/runtime layers can share semantics even if they do not all share the exact same function entrypoints
- use mixed-form tests to prove that invoke/session/delegate consumers all see the same resolved behavior
- make the review/output surface explicit in-task: `design-steps.md` tracks actionable design follow-ups and `implementation.md` records review notes, decisions, and blockers
- converge runtime resolution onto one IR-owned shared substrate namespace/API; keep authoring/file/current-target compiler helpers as authored-syntax translation seams only

Resolved boundary notes:
- canonical owner in scope for task 088: a shared workflow runtime source-resolution substrate for normalized IR refs/specs, path traversal, and projection application
- temporary current host: `components/agent-session/src/psi/agent_session/workflow_step_prep.clj`
- authoring-only / compatibility seams that should remain separate from runtime resolution ownership:
  - `workflow_file_authoring_session.clj`
  - `workflow_current_ir_compiler.clj`
  - `workflow_target_ir_compiler.clj`

Likely steps:
1. identify existing source-selection/projection helpers that can be reused or must be converged
2. define the canonical resolution contract for source refs and source specs
3. implement resolution for `:workflow-input` and `:workflow-original`
4. implement resolution for prior step `:output` and `:yield` refs
5. implement first-cut `:path` selection and richer `:projection` handling
6. enforce the rule that one source spec may contain either `:path` or `:projection`, but not both
7. thread the shared substrate into invoke arg resolution, session contribution resolution, delegate context resolution, and related call sites
8. add focused mixed-form resolution tests plus invalid-spec tests
9. tighten docs or implementation if drift appears between executable behavior and `doc/workflow-ir.md`

Proof target:
- the same source spec semantics are reused consistently across invoke/session/delegate workflow execution paths

Risks:
- existing local resolution helpers may encode subtle assumptions that are hard to unify cleanly
- `:projection` behavior may be more context-shaped in current code than the shared model wants
- partial convergence could leave apparently shared syntax with different runtime meanings
