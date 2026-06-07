# Review workflow iteration limits

## Goal

Update the Munera review workflows so their retry/follow-up limits match the desired depth for current task lifecycle use:

- `review-task-implementation`: allow **10** follow-up/review iterations per delegated `review-step` profile.
- `review-task-design`: allow **6** full design-review passes.
- `review-task-plan`: allow **5** full plan-review passes.

## Context

Current workflow behavior is inconsistent across implementation, design, and plan review:

- `review-task-implementation` delegates each review profile to `review-step`, whose follow-up transition currently has `:max-iterations 6`.
- `review-task-design` has architecture, ambiguity, and inconsistency phases, but after each follow-up it routes forward to the next phase rather than looping back through the review cycle.
- `review-task-plan` has ambiguity and inconsistency phases, but likewise routes forward after each follow-up rather than looping.

The descriptions for design and plan review say they repeatedly review until no actionable feedback remains, but the current EDN definitions only allow one follow-up per phase.

## Desired behavior

### Implementation review

Each `review-step` loop used by `review-task-implementation` should permit up to 10 follow-up/review cycles before hitting the workflow iteration guard.

The limit should remain attached to the shared `review-step` loop unless implementation discovers that changing the shared limit would incorrectly affect other workflows. If the shared workflow is too broad a surface, the design should be refined before implementation.

### Design review

`review-task-design` should run repeated full passes over its review phases until no phase finds actionable feedback, subject to a maximum of 6 passes.

A full pass means the workflow gives the task another opportunity to be reviewed for:

1. architectural fit,
2. ambiguities,
3. inconsistencies.

When any phase in a pass produces actionable feedback and its follow-up is executed, the workflow should continue into a later pass rather than declaring completion after the current phase sequence.

The iteration guard should prevent infinite review/follow-up loops and should surface a clear workflow limit failure if the design still produces actionable feedback after the allowed passes.

### Plan review

`review-task-plan` should run repeated full passes over its review phases until no phase finds actionable feedback, subject to a maximum of 5 passes.

A full pass means the workflow gives the task another opportunity to be reviewed for:

1. plan/steps ambiguities,
2. plan/steps inconsistencies.

When any phase in a pass produces actionable feedback and its follow-up is executed, the workflow should continue into a later pass rather than declaring completion after the current phase sequence.

The iteration guard should prevent infinite review/follow-up loops and should surface a clear workflow limit failure if the plan still produces actionable feedback after the allowed passes.

## Acceptance criteria

- `review-step` or the implementation-review-specific loop permits 10 iterations per review profile used by `review-task-implementation`.
- `review-task-design` can perform up to 6 repeated full review passes.
- `review-task-plan` can perform up to 5 repeated full review passes.
- The workflow definitions remain deterministic EDN workflows compatible with the existing workflow loader/runtime.
- Tests or workflow-definition checks cover the configured limits and the design/plan loop-back behavior.
- User-facing workflow documentation is updated if it describes review-loop limits or repeated review behavior.
- `CHANGELOG.md` is updated if the changed limits are user-visible.

## Constraints

- Keep the change localized to workflow definitions, workflow tests, and relevant docs unless runtime support is actually missing.
- Do not introduce ad-hoc compatibility shims.
- Preserve existing review prompt behavior and follow-up prompt behavior; only change the allowed repetition topology and limits.
- Avoid unbounded loops; every repeated path must have an explicit guard.
