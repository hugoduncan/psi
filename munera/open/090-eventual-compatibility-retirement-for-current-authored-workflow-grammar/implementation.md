2026-05-06 review
- Reviewed `design.md`, `plan.md`, and `steps.md` for ambiguity and inconsistencies after the repo-specific retirement planning pass.
- Found actionable ambiguity: the task says all checked-in workflows must be target-authored before retirement, but it does not distinguish project-local checked-in workflows under `.psi/workflows/` from loader-discovered global workflow directories. For execution, the gate should be interpreted as applying to repository-owned checked-in workflows in this project.
- Found actionable ambiguity: `remove or archive` for `doc/workflow-grammar-current.md` and `doc/workflow-grammar-migration.md` leaves two materially different end states. The task should choose one explicit documentation outcome so implementers do not satisfy it with incompatible historical/documentation shapes.
- Found actionable ambiguity: `compat-oriented seams in step prep / statechart runtime that only exist to preserve current-authored behavior` is too broad as an execution target. The task needs an explicit inventory of which seams are expected to be removed versus retained as target-runtime implementation details after compiler retirement.
- Found actionable ambiguity: `Run focused verification` and `Run broader verification` do not name authoritative command sets. Without explicit commands or suites, completion remains subjective.
- Found actionable inconsistency: `steps.md` has a concrete repo-specific inventory for remaining current-authored workflows, but the code/test retirement section still uses partially abstract phrasing (`prune compatibility-only helper/seam tests`) rather than naming the expected proofs or files that replace them.
- Found actionable inconsistency: `design.md` says remove or disable current-authored grammar support once prerequisites are satisfied, while `plan.md`/`steps.md` read as full removal. The task should prefer one explicit end state for this repo: disable temporarily behind a gate or delete outright.
- Recommended resolution direction:
  1. define repository scope explicitly as checked-in `.psi/workflows/*.md` in this repo
  2. choose one doc end state (`delete` or `retain as historical note but not linked as live guidance`)
  3. add an explicit code-path inventory for retirement targets
  4. name the focused and broader verification commands
  5. choose one final compatibility outcome (`remove`, not merely `disable`, if that is the intended repo end state)
