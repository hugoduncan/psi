# Plan

## Approach

Update the existing design-review and plan-review workflow topology in place so each per-reviewer follow-up is guarded by deterministic `PASS_STATUS` routing, while preserving the current cycle-level review structure.

Key decisions:

- Reuse the existing deterministic `workflow/pass-status-routing` operation for individual `*-review` steps instead of adding a new routing mechanism.
- Route per-reviewer `REVIEW_COMPLETE` to the next required reviewer or cycle-status step, not to workflow termination.
- Keep `clarity-status` as the only repeat/done decision for the whole design/plan review cycle.
- Keep design-review follow-up artifacts isolated to `design-steps.md`.
- Change plan-review prompt wording so review and follow-up items consistently target `steps.md`, never `design-steps.md`.
- Add focused workflow definition/runtime tests that prove skipped follow-ups do not prevent later reviewers or cycle-status from running.

## Risks

- Workflow EDN routing shape must match the runtime's existing invoke-judge/reference syntax exactly; small shape drift can cause runtime-only failures.
- Tests that assert whole workflow execution may need deterministic fake review outputs to avoid accidental reliance on model text.
- Prompt wording changes must not alter reviewer scope or cycle semantics beyond the artifact target correction.
- Shared status-routing validation should be reused rather than duplicated, to avoid inconsistent malformed/duplicate `PASS_STATUS` behaviour.

## Slice order

1. Inspect current workflow and prompt topology for design and plan review.
2. Add conditional per-reviewer routing to `review-task-design` while preserving all-reviewers-before-cycle ordering.
3. Add conditional per-reviewer routing to `review-task-plan` with the same topology.
4. Correct plan-review prompt artifact references from `design-steps.md` to `steps.md`, and verify design-review prompts still target `design-steps.md`.
5. Add or update focused tests for design-review conditional follow-up routing.
6. Add or update focused tests for plan-review conditional follow-up routing and prompt artifact targets.
7. Run focused workflow tests and lint for affected workflow/prompt/test files.
8. Record implementation notes and any discovered decisions in `implementation.md`.
