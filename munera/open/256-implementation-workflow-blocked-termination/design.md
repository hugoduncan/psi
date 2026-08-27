# Implementation workflow blocked termination

## Goal

Allow `implement-task` to terminate cleanly when an implementation pass cannot make safe autonomous progress because it needs a human decision, missing external information, unavailable access, or another explicit blocker. This is a third outcome alongside successful completion and more implementation work.

## Context

Today `.psi/workflows/implement-task.edn` routes its implementation pass only as `REPEAT` (`MORE_WORK_REMAINS`) or `DONE` (`IMPLEMENTATION_COMPLETE`). Consequently, a blocked pass either keeps looping until the limit is exhausted or inaccurately reports completion. The task lifecycle currently proceeds from `implement-task` directly into implementation review, so a blocked implementation result must also prevent review and knowledge extraction.

This should follow the established task-lifecycle scope-question pattern: preserve the work and task artifacts, stop at an explicit human handback, state the concrete blocker, and require an explicit human action before a later fresh invocation resumes.

## Scope

- Extend the implementation-pass prompt contract with one exact blocked terminal status: `PASS_STATUS: IMPLEMENTATION_BLOCKED`.
- Require a pass taking that outcome to record a concise, actionable blocker and the safe next human action in the task's `implementation.md`; completed work and checklist state remain accurately recorded.
- Extend `implement-task` routing so `IMPLEMENTATION_BLOCKED` reaches a dedicated blocked final-summary step rather than repeating or using the normal completion summary.
- The blocked summary must inspect the specific task artifacts, clearly say implementation stopped blocked rather than completed, present the recorded blocker and required human decision/action, identify completed work and verification, and explain that a later re-invocation is required after the blocker is resolved.
- Preserve the existing `MORE_WORK_REMAINS → REPEAT` loop limit and `IMPLEMENTATION_COMPLETE →` normal final-summary behavior.
- Make the terminal result exposed by a standalone blocked `implement-task` invocation be the blocked handback summary, not an empty result or a normal completion summary.
- Add a deterministic task-lifecycle gate immediately after its `implement-task` delegate step. It must route the implementation workflow's yielded status so `IMPLEMENTATION_COMPLETE` alone proceeds to `review-task-implementation`; `IMPLEMENTATION_BLOCKED` routes to a dedicated lifecycle blocked handback.
- The lifecycle blocked handback must stop before implementation review and `extract-task-knowledge`, preserve and summarize the recorded blocker, and tell the human how to resolve it and re-invoke the lifecycle.
- Update the workflow definition/load and runtime-routing proofs for all three implementation outcomes, including that the blocked route does not start a second implementation pass, review, or extraction.
- Update workflow documentation if it documents `implement-task` or the task-lifecycle implementation-stage contract.

## Non-goals

- Do not change generic workflow runtime loop semantics, iteration accounting, or introduce a runtime-specific implementation-blocked primitive.
- Do not treat a workflow runtime failure, cancellation, malformed pass status, or iteration-limit exhaustion as an implementation blocker.
- Do not automatically resolve, guess, or bypass the blocker.
- Do not change design scope-question detection or its ownership in `design-steps.md`.
- Do not close a Munera task merely because implementation is blocked.

## Design decisions

- `IMPLEMENTATION_BLOCKED` is authored workflow policy parsed through the existing generic `workflow/pass-status-routing` operation, rather than a new generic routing operation.
- The canonical durable blocker record is `implementation.md`, because it captures in-flight implementation discoveries and handoff information. The implementation pass owns writing it before emitting the blocked status.
- `implement-task` uses a separate blocked final-summary step so the terminal result corresponds to the route actually taken. Place it after the normal final summary in step order if the runtime's standalone result projection selects the last executed terminal step; verify the chosen ordering against the current result projection rather than relying on step order accidentally.
- The lifecycle gate is deterministic and reads only the yielded implementation status through the existing pass-status router. It must be placed before `review-task-implementation`, preserving the one-way stage boundary.
- A blocked implementation is a clean authored handback, not a failed or runtime-`:blocked` workflow run. It remains distinguishable from successful implementation through its explicit status and summary.

## Acceptance criteria

1. The implementation-pass prompt documents exactly three permitted final `PASS_STATUS` values: `MORE_WORK_REMAINS`, `IMPLEMENTATION_COMPLETE`, and `IMPLEMENTATION_BLOCKED`, and defines the artifact-recording obligation for the blocked value.
2. A valid `IMPLEMENTATION_BLOCKED` reply routes `implement-task` to its blocked handback without re-entering `implement-pass`; the workflow completes with a user-facing blocked summary.
3. Existing valid completion and repeat replies retain their current routing behavior and bounded repeat limit.
4. A standalone blocked implementation run returns the blocked handback text as its terminal result.
5. In `task-lifecycle`, only `IMPLEMENTATION_COMPLETE` advances from implementation to implementation review; an `IMPLEMENTATION_BLOCKED` yield reaches the lifecycle blocked handback instead.
6. The lifecycle blocked route does not invoke implementation review or knowledge extraction and explicitly explains the required human resolution and later re-invocation.
7. Workflow loader/definition tests and execution-routing tests prove all three statuses, malformed-status rejection, and the no-review/no-extraction blocked boundary using observable workflow state/outputs rather than interaction-only assertions.
8. Relevant user-facing workflow documentation agrees with the authored status and lifecycle behavior.

## Risks and open questions

- The current standalone-result projection may select the last declared step rather than the last executed step; implementation must inspect and prove the actual projection behavior so the blocked handback is visible.
- The exact wording/format for a blocker in `implementation.md` should be concise but must be sufficiently structured for the final summaries to identify it reliably without heuristic invention. Resolve this during planning by following existing task-artifact conventions.
