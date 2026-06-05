## 2026-06-05 — Architecture-fit review

Reviewed `design.md` against `AGENTS.md`, `META.md`, and `doc/architecture.md`; did not review `plan.md` or `steps.md`. Found one architectural misfit (**ARCH1**): the proposed gate separates coverage and simplification phases topologically, but does not require a pre-simplification baseline/diff check proving the coverage-fix loop left target/source code unchanged except explicitly recorded minimal testability seams. That leaves the "green against unmodified target behavior" invariant partly enforced by prompt prose in the mutable current worktree.

PASS_STATUS: ACTIONABLE_FEEDBACK
