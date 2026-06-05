# Incidental simplification pre-refactor test-net gate

## Intent

Make `reduce-incidental-complexity` structurally enforce its Phase 0 promise before any simplification/refactor work begins.

The workflow already generates task designs that require a green characterization-test safety net before refactoring, but task 211 showed that prose is not enough: substantial test coverage work was discovered after implementation/review rather than being completed as a dedicated pre-simplification gate.

## Problem

`reduce-incidental-complexity` currently hands the generated task to the generic task lifecycle. That lifecycle can mix characterization-test work and simplification work inside the same implementation phase. As a result:

- missing target-behavior coverage may be found late, after simplification has started;
- implementation review may need to add or request substantial tests after code changes exist;
- the intended behaviour-preserving proof is weaker than the generated task design claims;
- regressions in the characterization-net discipline are easy because the workflow topology does not encode the ordering.

The desired invariant is:

> No incidental-complexity simplification/refactor starts until target behavior is sufficiently characterized and the relevant tests are green against the unmodified target behavior.

## Scope

In scope:

- Add an explicit, iterated pre-simplification characterization-test-net phase to the incidental-complexity simplification workflow path.
- Ensure the phase runs after the generated task has a reviewed design/plan and before simplification implementation begins.
- Ensure the phase can loop: review coverage, perform coverage fixes, review again, then proceed only when coverage is sufficient.
- Ensure routing is deterministic, using existing workflow routing operations where possible.
- Ensure the pre-simplification phase distinguishes characterization/test-net work from simplification/refactor work.
- Update workflow tests so the ordering and routing are locked.
- Update user-facing workflow documentation and changelog if behavior changes are user-visible.

Out of scope:

- Changing the incidental-complexity selection algorithm.
- Changing Gordian metrics or acceptance formulas.
- Implementing a runtime-level proof that no production code changed before the coverage gate, unless a minimal testability seam is explicitly required and recorded.
- Redesigning the whole Munera task lifecycle.
- Reintroducing `work-on` or workflow-managed worktree switching.

## Desired workflow behavior

For a target-present `reduce-incidental-complexity` run, the workflow should execute conceptually as:

1. Select target and create the Munera task in the current inherited worktree.
2. Review/refine the generated task design.
3. Create the task plan and steps.
4. Review/refine the plan.
5. Establish a characterization-test net:
   - inspect the target behavior and existing tests;
   - decide whether nominal, edge, and boundary behavior is sufficiently covered;
   - add characterization tests or minimal testability seams when coverage is insufficient;
   - run relevant tests green against the current behavior;
   - repeat until coverage is sufficient or the task is stopped/closed with an explicit finding.
6. Only after the test-net gate passes, implement the simplification/refactor.
7. Review the implementation as usual.

For a no-target run, the workflow should continue to skip downstream lifecycle work and report that no qualifying target was found.

## Characterization-test-net gate requirements

The pre-simplification gate is complete only when all of the following are true:

- The target unit and observable behavior under review are clear from the task artifacts.
- Existing and/or newly added tests would catch behavior changes in the target's externally observable state or outputs.
- Coverage considers nominal, edge, and boundary behavior relevant to the target.
- Tests avoid interaction assertions except where the interaction is itself the observable behavior.
- Relevant tests pass before simplification begins.
- The task artifacts record what coverage was reviewed or added.
- If sufficient characterization is not feasible, the task records the reason and does not proceed to simplification.

## Acceptance criteria

- `reduce-incidental-complexity` no longer delegates to an opaque whole-task lifecycle for target-present runs when doing so would hide the Phase 0/Phase 1 boundary.
- The workflow has an explicit pre-simplification characterization-test-net phase before simplification implementation.
- The characterization-test-net phase is iterated with deterministic routing:
  - actionable coverage feedback causes a coverage-fix pass and another review;
  - review completion allows simplification implementation to begin.
- The coverage-fix pass is constrained to characterization tests and explicitly justified minimal testability seams; it must not perform the simplification/refactor itself.
- Tests lock the workflow step order, delegate targets, deterministic routing, and no-refactor-before-test-net intent.
- Documentation describes the new gate and the fact that `reduce-incidental-complexity` still runs in the current inherited worktree.
- Existing workflow-loader tests remain green.

## Architectural fit

This should be implemented as workflow topology and prompt behavior rather than runtime-special-case logic. The change belongs with the workflow definitions and workflow prompt assets because the invariant is process-level: the same existing child-session inheritance, deterministic routing, and task-review machinery should orchestrate the new gate.

A reusable mini-workflow for establishing a characterization-test net is preferred if it can be named clearly and reused by future behaviour-preserving refactor workflows. If reuse makes the change less direct, an incidental-complexity-specific workflow/prompt pair is acceptable as the first slice.

The design should preserve the existing current-worktree model: child sessions and delegated workflows inherit the invoking session's worktree path; no step should call `work-on` to switch or recreate worktrees.

## Notes from motivating evidence

Task 211 (`211-simplify-start-tui-runtime`) required enough post-implementation test work that the missing gate became visible. The task's outcome is evidence that the workflow should make the characterization-test net a first-class, iterated phase rather than relying on generated design prose alone.
