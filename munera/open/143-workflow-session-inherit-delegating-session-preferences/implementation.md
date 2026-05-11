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

## 2026-05-11 implementation

- Inventoried the workflow authority path. `execute-run!` and `resume-and-execute-run!` already accept a parent/delegating session id and thread it into `create-workflow-context`, but workflow run creation surfaces did not persist that authoritative parent on the run itself.
- Root cause: `resolve-step-session-config` used explicit `parent-session-id` when supplied at execution time, otherwise it fell back immediately to the first context session. That made workflow-owned child sessions vulnerable whenever later execution/resume paths reconstructed context without an explicit parent or when a run needed to carry its delegation authority across boundaries.
- Narrow fix implemented: canonical workflow runs now persist `:parent-session-id` as the authoritative delegating session identity.
  - `psi.workflow-runtime.core/create-run` accepts and stores `:parent-session-id` and records it in run-created history.
  - `psi.workflow-runtime.statechart-runtime/create-workflow-context` falls back to the run-stored `:parent-session-id` when no explicit execution-time parent is supplied.
  - `psi.workflow-step-session-config.core/resolve-step-session-config` now prefers parent authority in this order: explicit arg → run-stored `:parent-session-id` → first context session compatibility fallback.
- Precedence correction implemented for model inheritance: step-authored `:model` still wins, but when absent the delegating session model now beats workflow file meta/default model fallback. This matches the task’s desired behavior for workflow child-session preference inheritance.
- Creation/resume surfaces updated to preserve delegating authority:
  - Pathom `psi.workflow/create-run` mutation now captures invoking `session-id` as `:parent-session-id`.
  - psi-tool workflow `create-run` now stores the invoking session as `:parent-session-id`.
  - delegated sub-workflow creation in `statechart_runtime/delegate.clj` now preserves the caller’s authoritative parent-session id on the delegated run as well.
- Focused proof added:
  - workflow runtime core test for stored `:parent-session-id`
  - step-session-config test proving the delegating session beats first-context-session defaults in a two-session scenario
  - mutation test proving `create-run` stores the invoking session id as parent authority
  - psi-tool workflow test proving `create-run` summaries expose the stored parent-session id
  - resume-path test proving `resume-and-execute-run!` passes the delegating session id through the distinct resume entrypoint
- Verification:
  - `bb clojure:test:unit --focus psi.workflow-runtime.core-test --focus psi.workflow-step-session-config.core-test --focus psi.agent-session.mutations.canonical-workflows-test --focus psi.agent-session.workflow-tools-test --focus psi.agent-session.workflow-execution-resume-test --focus psi.workflow-runtime.statechart-runtime.state-test`
  - `bb lint`
  - Result: focused tests green, lint clean.
