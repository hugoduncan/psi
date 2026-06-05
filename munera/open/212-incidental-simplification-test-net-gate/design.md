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
- Add a workflow-level baseline/diff gate around the characterization phase so simplification routing is allowed only after the workflow proves that the source/target code is still unchanged, except for explicitly recorded minimal testability seams.
- Update workflow tests so the ordering, routing, and baseline/diff enforcement are locked.
- Update user-facing workflow documentation and changelog if behavior changes are user-visible.

Out of scope:

- Changing the incidental-complexity selection algorithm.
- Changing Gordian metrics or acceptance formulas.
- Implementing a runtime-level proof beyond the workflow-owned git baseline/diff gate. The workflow gate must inspect the worktree diff before simplification; deeper runtime attestation is out of scope.
- Redesigning the whole Munera task lifecycle.
- Reintroducing `work-on` or workflow-managed worktree switching.

## Desired workflow behavior

For a target-present `reduce-incidental-complexity` run, the workflow should execute conceptually as:

1. Select target and create the Munera task in the current inherited worktree.
2. Review/refine the generated task design.
3. Create the task plan and steps.
4. Review/refine the plan.
5. Establish a characterization-test net:
   - before recording the characterization baseline, verify the source/target worktree state is clean for the target file(s) or source paths identified by the task; pre-existing dirty task artifacts/docs may remain only when explicitly classified, but pre-existing dirty source/target changes stop the workflow with an explicit finding instead of being absorbed into the baseline;
   - record a workflow baseline for the target/source state before characterization work begins (at minimum the relevant git `HEAD`/status plus the target file(s) or source paths identified by the task);
   - inspect the target behavior and existing tests;
   - decide whether nominal, edge, and boundary behavior is sufficiently covered;
   - add characterization tests or minimal testability seams when coverage is insufficient;
   - run relevant tests green against the current behavior;
   - before routing to simplification, compute the diff from the recorded baseline and classify every coverage-phase change as characterization tests, task artifacts, docs, or an explicitly justified minimal testability seam;
   - stop before simplification when any source/target change is unclassified, broader than a minimal seam, or already performs the simplification; the workflow must require revert/split/close with an explicit finding rather than proceeding;
   - repeat until coverage is sufficient, the baseline/diff check passes, or the task is stopped/closed with an explicit finding.
6. Only after the test-net gate and baseline/diff check pass, implement the simplification/refactor.
7. Review the implementation as usual.

For a no-target run, the workflow should continue to skip downstream lifecycle work and report that no qualifying target was found.

## Characterization-test-net gate requirements

The pre-simplification gate is complete only when all of the following are true:

- The target unit and observable behavior under review are clear from the task artifacts.
- Existing and/or newly added tests would catch behavior changes in the target's externally observable state or outputs.
- Coverage considers nominal, edge, and boundary behavior relevant to the target.
- Tests avoid interaction assertions except where the interaction is itself the observable behavior.
- Relevant tests pass before simplification begins.
- Before the pre-characterization baseline is recorded, the workflow verifies that the target/source paths are not already dirty; only pre-existing task-artifact/doc changes may be carried forward, and they must be explicitly classified.
- If pre-existing dirty target/source changes are present at baseline time, the workflow stops with an explicit finding instead of treating those changes as the unmodified behavior baseline.
- A workflow-level baseline/diff check runs after characterization work and before simplification routing.
- The diff check records the pre-characterization baseline and classifies all coverage-phase changes; only tests, task artifacts, docs, and explicitly justified minimal testability seams may be present.
- If the diff includes unclassified source/target changes, broad production edits, or simplification/refactor work, the workflow stops before simplification and records whether the change must be reverted, split, or the task closed.
- The task artifacts record what coverage was reviewed or added and the result of the baseline/diff classification.
- If sufficient characterization is not feasible, the task records the reason and does not proceed to simplification.

## Acceptance criteria

- `reduce-incidental-complexity` no longer delegates to an opaque whole-task lifecycle for target-present runs when doing so would hide the Phase 0/Phase 1 boundary.
- The workflow has an explicit pre-simplification characterization-test-net phase before simplification implementation.
- The characterization-test-net phase is iterated with deterministic routing:
  - actionable coverage feedback causes a coverage-fix pass and another review;
  - review completion allows simplification implementation to begin.
- The coverage-fix pass is constrained to characterization tests and explicitly justified minimal testability seams; it must not perform the simplification/refactor itself.
- A workflow-level baseline/diff gate runs before simplification implementation and prevents routing forward when baseline-time source/target paths are dirty, or when coverage-phase changes include unclassified or non-minimal source/target edits.
- Tests lock the workflow step order, delegate targets, deterministic routing, clean-baseline precondition, baseline/diff enforcement, and no-refactor-before-test-net intent.
- Documentation describes the new gate and the fact that `reduce-incidental-complexity` still runs in the current inherited worktree.
- Existing workflow-loader tests remain green.

## Architectural fit

This should be implemented as workflow topology and prompt behavior rather than runtime-special-case logic. The change belongs with the workflow definitions and workflow prompt assets because the invariant is process-level: the same existing child-session inheritance, deterministic routing, git-visible task artifacts, and task-review machinery should orchestrate the new gate.

The baseline/diff check should be a first-class workflow boundary, not merely advice inside the implementation prompt. The characterization phase records the source baseline before adding tests, and the routing step into simplification consumes an explicit classification of the resulting diff. That keeps the proof at the workflow layer: coverage can be strengthened in the current inherited worktree, but simplification cannot begin if the worktree already contains unreviewed target/source changes.

A reusable mini-workflow for establishing a characterization-test net is preferred if it can be named clearly and reused by future behaviour-preserving refactor workflows. If reuse makes the change less direct, an incidental-complexity-specific workflow/prompt pair is acceptable as the first slice.

The design should preserve the existing current-worktree model: child sessions and delegated workflows inherit the invoking session's worktree path; no step should call `work-on` to switch or recreate worktrees.

## Notes from motivating evidence

Task 211 (`211-simplify-start-tui-runtime`) required enough post-implementation test work that the missing gate became visible. The task's outcome is evidence that the workflow should make the characterization-test net a first-class, iterated phase rather than relying on generated design prose alone.
