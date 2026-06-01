# Plan

## Approach

Audit open tasks from `077` onward in numeric order and make one explicit disposition at a time.

For each reviewed task:

1. read task artifacts (`design.md`, `plan.md`, `steps.md`, `implementation.md` when present)
2. inspect current repository evidence relevant to its acceptance
3. inspect later task notes, `munera/plan.md`, and `mementum/state.md` for evidence of completion or supersession
4. decide whether to:
   - leave open unchanged
   - leave open with clarified rationale
   - close as completed
   - close as superseded/absorbed
5. commit immediately after each reviewed-task disposition

## Initial execution order

1. `077-custom-provider-string-provider-auth-normalization`
2. continue through later-numbered open tasks in ascending order
3. stop only when a user decision is required

## Evidence standard

A task may be closed only when current artifacts and repository state show that its intent and acceptance are materially satisfied, or that it has been explicitly superseded/absorbed by later completed work.

Similarity to adjacent work is not sufficient.

## Expected edits

- reviewed task artifacts when rationale needs to be recorded
- `munera/plan.md` when open/closed status changes
- `munera/open/173-investigate-and-close-completed-open-tasks-from-077/implementation.md` as the audit log
- task moves between `munera/open/` and `munera/closed/` when closure is justified

## Risks

- closing a task based on nearby work rather than its own acceptance
- missing open tasks that are not currently listed in `munera/plan.md`
- leaving the audit task without a discoverable per-task rationale

## Verification

For each reviewed task, verify that the resulting open/closed state is reflected consistently in:

- task artifacts
- directory location (`open/` vs `closed/`)
- `munera/plan.md`
- audit notes in task `173`
