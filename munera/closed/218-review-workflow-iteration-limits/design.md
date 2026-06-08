# Review workflow iteration limits

## Goal

Update the Munera review workflows so their retry/follow-up limits match the desired depth for current task lifecycle use:

- `review-task-implementation`: allow **10 total review executions** per delegated `review-step` profile, including the initial review execution.
- `review-task-design`: allow **6 total full design-review passes**.
- `review-task-plan`: allow **5 total full plan-review passes**.

## Context

Current workflow behavior is inconsistent across implementation, design, and plan review:

- `review-task-implementation` delegates each review profile to `review-step`, whose follow-up transition currently has `:max-iterations 6`.
- `review-task-design` has architecture, ambiguity, and inconsistency phases, but after each follow-up it routes forward to the next phase rather than looping back through the review cycle.
- `review-task-plan` has ambiguity and inconsistency phases, but likewise routes forward after each follow-up rather than looping.

The descriptions for design and plan review say they repeatedly review until no actionable feedback remains, but the current EDN definitions only allow one follow-up per phase.

## Desired behavior

### Iteration-counting convention

Workflow `:max-iterations` is a target-step entry limit. A step's initial normal entry counts as one entry, and each loop-back into that same target step counts another entry. Therefore `:max-iterations N` means the target step may be entered at most `N` total times, not `N` retries after the initial entry.

This task should preserve that runtime convention and choose authored limits that match the intended total executions:

- `review-step` implementation review uses the `follow-up` → `review` loop-back with `:max-iterations 10`, so each review profile can execute its `review` step at most 10 total times: the initial review plus at most 9 follow-up-driven re-reviews.
- `review-task-design` uses a pass loop-back to the first phase with `:max-iterations 6`, so each phase can be entered at most 6 total times when every pass reaches that phase.
- `review-task-plan` uses a pass loop-back to the first phase with `:max-iterations 5`, so each phase can be entered at most 5 total times when every pass reaches that phase.

Tests should assert the authored `:max-iterations` values and topology, and should describe the expected runtime meaning as total target-step entries.

### Implementation review

Each `review-step` loop used by `review-task-implementation` should permit up to 10 total executions of the `review` step before hitting the workflow iteration guard.

The limit should remain attached to the shared `review-step` loop unless implementation discovers that changing the shared limit would incorrectly affect other workflows. If the shared workflow is too broad a surface, the design should be refined before implementation.

### Design review

`review-task-design` should run repeated full passes over its review phases until no phase finds actionable feedback, subject to a maximum of 6 passes.

A full pass means the workflow gives the task another opportunity to be reviewed for:

1. architectural fit,
2. ambiguities,
3. inconsistencies.

Each pass should run the phases in that order. If an earlier phase produces actionable feedback and its follow-up is executed, the workflow should still finish the remaining phases in the current pass before deciding whether another pass is needed. The next pass should restart at architectural fit, not at the phase that found feedback.

Completion needs a pass-level memory of whether any phase in the just-completed pass produced actionable feedback. Because the follow-up step may clear the unchecked items before later phases run, completion must not infer pass cleanliness solely from the current contents of `design-steps.md`. The workflow topology should preserve an explicit route/state distinction, such as separate clarity-status entrypoints or deterministic route constants, so that any architecture, ambiguity, or inconsistency `ACTIONABLE_FEEDBACK` result in the pass causes the final pass-status decision to loop back after the inconsistency phase completes.

The iteration guard should be on the loop-back from the pass-status/clarity decision to the first design-review phase (`architecture-review`) with `:max-iterations 6`. This limits full-pass restarts by limiting total entries to the first phase. The initial architecture review counts as pass 1; the loop-back can start passes 2 through 6. If pass 6 still has any actionable feedback, the attempted loop-back to pass 7 must fail through the existing workflow iteration-limit failure path, surfacing a clear workflow limit failure rather than declaring review complete.

### Plan review

`review-task-plan` should run repeated full passes over its review phases until no phase finds actionable feedback, subject to a maximum of 5 passes.

A full pass means the workflow gives the task another opportunity to be reviewed for:

1. plan/steps ambiguities,
2. plan/steps inconsistencies.

Each pass should run the phases in that order. If the ambiguity phase produces actionable feedback and its follow-up is executed, the workflow should still run the inconsistency phase in the current pass before deciding whether another pass is needed. The next pass should restart at plan/steps ambiguity review.

Completion needs a pass-level memory of whether either phase in the just-completed pass produced actionable feedback. Because the follow-up step may clear unchecked items before the pass-status decision, completion must not infer pass cleanliness solely from the current contents of `steps.md`. The workflow topology should preserve an explicit route/state distinction, such as separate clarity-status entrypoints or deterministic route constants, so that any ambiguity or inconsistency `ACTIONABLE_FEEDBACK` result in the pass causes the final pass-status decision to loop back after the inconsistency phase completes.

The iteration guard should be on the loop-back from the pass-status/clarity decision to the first plan-review phase (`ambiguity-review`) with `:max-iterations 5`. This limits full-pass restarts by limiting total entries to the first phase. The initial ambiguity review counts as pass 1; the loop-back can start passes 2 through 5. If pass 5 still has any actionable feedback, the attempted loop-back to pass 6 must fail through the existing workflow iteration-limit failure path, surfacing a clear workflow limit failure rather than declaring review complete.

## Acceptance criteria

- `review-step` or the implementation-review-specific loop permits 10 total `review` step entries per review profile used by `review-task-implementation`.
- `review-task-design` can perform up to 6 repeated full review passes, counting the initial pass.
- `review-task-plan` can perform up to 5 repeated full review passes, counting the initial pass.
- Design and plan review pass loops complete every phase in the current pass before restarting the next full pass.
- Design and plan review completion is based on whether any phase in the completed pass found actionable feedback, not on whether follow-up files are currently clear after follow-up execution.
- If the final allowed implementation/design/plan review iteration still yields actionable feedback, the next loop-back attempt fails through the workflow iteration-limit path instead of silently completing.
- The workflow definitions remain deterministic EDN workflows compatible with the existing workflow loader/runtime.
- Tests or workflow-definition checks cover the configured limits, total-entry counting expectations, design/plan loop-back behavior, and final-allowed-pass failure behavior.
- User-facing workflow documentation is updated if it describes review-loop limits or repeated review behavior.
- `CHANGELOG.md` is updated if the changed limits are user-visible.

## Constraints

- Keep the change localized to workflow definitions, workflow tests, and relevant docs unless runtime support is actually missing.
- Do not introduce ad-hoc compatibility shims.
- Preserve existing review prompt behavior and follow-up prompt behavior; only change the allowed repetition topology and limits.
- Avoid unbounded loops; every repeated path must have an explicit guard.
