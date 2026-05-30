# Conditional review follow-ups for design and plan workflows

## Intent

Make `review-task-design` and `review-task-plan` as robust as the recently improved `review-task-implementation` workflow with respect to unnecessary follow-up work: a follow-up step must run only when its immediately preceding reviewer reports actionable feedback.

Preserve the intentional orchestration difference between implementation review and design/plan review: design and plan workflows must still run all configured reviewers in a cycle before deciding whether the whole workflow should repeat or finish.

## Problem

`review-task-design` and `review-task-plan` currently ask review prompts to end with a `PASS_STATUS`, but their workflow topology does not route on that status. The follow-up steps run unconditionally:

- `ambiguity-review` always runs `ambiguity-follow-up`
- `inconsistency-review` always runs `inconsistency-follow-up`

This creates noisy no-op sessions, unnecessary commits or attempted commits, and less reliable review transcripts when a reviewer finds no actionable issues.

`review-task-plan` also currently refers to `design-steps.md` in the plan-review prompt files. Plan review follow-ups belong in `steps.md`; `design-steps.md` is for design-review follow-ups.

## Scope

Update the built-in workflow definitions and prompt files for:

- `.psi/workflows/review-task-design.edn`
- `.psi/workflows/review-task-plan.edn`
- `.psi/workflows/review-task-design-*-review.md`
- `.psi/workflows/review-task-design-*-follow-up.md`
- `.psi/workflows/review-task-plan-*-review.md`
- `.psi/workflows/review-task-plan-*-follow-up.md`

Add or update workflow-loader / workflow-runtime / live-delegate tests as needed to prove the routing behaviour.

## Required behaviour

### Per-reviewer follow-up routing

Each review step that emits a `PASS_STATUS` must route deterministically from that status:

- `PASS_STATUS: ACTIONABLE_FEEDBACK` means run that reviewer’s follow-up step.
- `PASS_STATUS: REVIEW_COMPLETE` means skip that reviewer’s follow-up step.
- malformed, missing, duplicate, or conflicting status output must fail through the same deterministic status-routing error surface used by `review-step`.

### Preserve all-reviewers-before-cycle topology

Unlike `review-task-implementation`, `review-task-design` and `review-task-plan` must not terminate a cycle as soon as one reviewer reports completion.

Within each cycle:

1. run the ambiguity reviewer
2. conditionally run ambiguity follow-up only if ambiguity review found actionable feedback
3. run the inconsistency reviewer regardless of the ambiguity review result
4. conditionally run inconsistency follow-up only if inconsistency review found actionable feedback
5. run the existing whole-cycle clarity/status decision
6. repeat the cycle only if the whole-cycle status says unresolved actionable review work remains

The whole-cycle `clarity-status` step remains the only place that decides whether the design/plan review workflow repeats or proceeds to final summary.

### Artifact targets

Design review must continue to use `design-steps.md` for review follow-up items and must not touch `plan.md` or `steps.md`.

Plan review must use `steps.md` for review follow-up items and must not use `design-steps.md` for new plan-review follow-ups.

### Final summaries

Existing final-summary behaviour should be preserved except where wording must change to reflect conditional follow-up routing.

## Out of scope

- Replacing the design/plan workflows wholesale with `review-step` if doing so would lose the all-reviewers-before-cycle behaviour.
- Changing the set of design or plan reviewers.
- Changing the semantics of `review-task-implementation` except for shared helper/test reuse that preserves its current behaviour.
- Introducing a new task lifecycle state or changing Munera task mechanics.

## Acceptance criteria

- When `review-task-design` ambiguity review returns `PASS_STATUS: REVIEW_COMPLETE`, the ambiguity follow-up step does not run, and the inconsistency review still runs in the same cycle.
- When `review-task-design` inconsistency review returns `PASS_STATUS: REVIEW_COMPLETE`, the inconsistency follow-up step does not run, and the workflow proceeds to whole-cycle clarity/status.
- When either design reviewer returns `PASS_STATUS: ACTIONABLE_FEEDBACK`, only that reviewer’s corresponding follow-up step runs before the workflow continues.
- Equivalent conditional follow-up behaviour is implemented and tested for `review-task-plan`.
- `review-task-plan` prompt files consistently instruct reviewers and follow-up actors to use `steps.md`, not `design-steps.md`.
- `review-task-design` prompt files consistently instruct reviewers and follow-up actors to use `design-steps.md`.
- The workflows still run all reviewers in a cycle before the cycle-level repeat/done decision.
- Existing deterministic status-routing validation covers the new routing edges, including malformed or duplicate `PASS_STATUS` output.
- Focused workflow tests pass for the affected review workflows.

## Notes for implementation planning

A likely shape is to reuse the deterministic `workflow/pass-status-routing` operation on the individual review steps, but route `DONE` to the next reviewer or cycle-status step rather than to workflow termination.

For example, the design workflow can use a topology equivalent to:

```text
ambiguity-review
  ACTIONABLE_FEEDBACK -> ambiguity-follow-up -> inconsistency-review
  REVIEW_COMPLETE     -> inconsistency-review

inconsistency-review
  ACTIONABLE_FEEDBACK -> inconsistency-follow-up -> clarity-status
  REVIEW_COMPLETE     -> clarity-status

clarity-status
  REPEAT -> ambiguity-review
  DONE   -> final-summary
```

The plan workflow should mirror this topology while targeting `steps.md` for follow-up items.
