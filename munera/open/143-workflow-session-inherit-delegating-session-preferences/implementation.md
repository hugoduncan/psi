# Implementation Notes

Created from user request on 2026-05-11.

## Requested change

Create a task to update workflow session creation so workflow-owned child sessions prefer the delegating session's model and related preferences, rather than inheriting from user/project-configured defaults that may arrive through a different context session.

## Initial design intent

This is a workflow-inheritance correction.
The likely issue is not lower child-session persistence itself, but loss or weakening of authoritative parent-session identity before `resolve-step-session-config` shapes the child session config.

## Key hypothesis to verify during implementation

Current code in `psi.workflow-step-session-config.core/resolve-step-session-config` already prefers an explicit `parent-session-id`, but falls back to the first listed context session when it is nil. The task should verify which workflow execution entrypoint(s) are failing to preserve the delegating session id, then fix that path rather than broadening session-default semantics.

## Boundaries to preserve

- explicit workflow-authored model / preference overrides should still win
- this task should not redesign top-level new-session defaulting
- compatibility fallback for nil parent-session-id may remain for tests or legacy paths, but should no longer be the common authoritative workflow path

## 2026-05-11 design review

- Actionable ambiguity: the task did not include the `design-steps.md` follow-up surface that this review protocol writes to, so the canonical place for new design-review actions was implicit rather than task-local. Added `design-steps.md` with a follow-up to make that review/follow-up surface explicit in the task artifacts.
- Completed the ambiguity follow-up by updating `design.md` and `plan.md` to define task-local roles for `design-steps.md`, `steps.md`, and `implementation.md`, making future design-review actions explicit without widening scope into implementation work.

## 2026-05-11 design/plan/steps consistency review

- Actionable inconsistency: `design.md` and `plan.md` both frame the fix as covering workflow create/execute/resume paths, but `steps.md` only asks for a generic create/execute/resume inventory and never adds an explicit follow-through step to verify or test resume-path preservation separately. Added a `design-steps.md` follow-up to make that missing task-file obligation explicit without broadening scope beyond the already-stated intent.
- Completed the resume-path follow-up by updating `steps.md` to require a distinct resume-path verification/proof step and to include resume-path preservation in the focused test obligation, aligning task execution with the already-stated create/execute/resume intent.
