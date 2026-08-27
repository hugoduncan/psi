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
- Add a deterministic task-lifecycle gate immediately after its `implement-task` delegate step. It must route the implementation workflow's terminal status so `IMPLEMENTATION_COMPLETE` alone proceeds to `review-task-implementation`; `IMPLEMENTATION_BLOCKED` routes to a dedicated lifecycle blocked handback.
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

- `IMPLEMENTATION_BLOCKED` is authored workflow policy. `implement-pass` is judged by the existing generic `workflow/exact-marker-routing` with `:marker-label "PASS_STATUS"` and exactly the three workflow-owned allowed routes `MORE_WORK_REMAINS`, `IMPLEMENTATION_COMPLETE`, and `IMPLEMENTATION_BLOCKED`. Its authored routing table maps those raw routes respectively to the bounded repeat, normal summary, and blocked summary. This replaces use of the two-route `workflow/pass-status-routing` at this step; that operation remains unchanged for its existing DONE/REPEAT status families.
- The implementation pass owns the durable blocker record and writes it before emitting `PASS_STATUS: IMPLEMENTATION_BLOCKED`. It appends one complete, exact block to `implementation.md`:

  ```text
  <!-- IMPLEMENTATION_BLOCKER: START -->
  - blocker: <concise concrete blocker>
  - required-human-action: <safe action or decision>
  <!-- IMPLEMENTATION_BLOCKER: END -->
  ```

  Both field values are required and non-empty. When earlier blocked attempts exist, the current blocker is the last complete such block in file order; incomplete or malformed blocks are not a blocker record and must not be summarized as one. Both blocked summaries read that final complete block and reproduce its two fields rather than inferring a blocker from surrounding prose.
- Both terminal summaries are explicit terminal steps. The runtime records the actual terminal outcome (`:terminal-outcome :step-id`) and projects that step's yielded text, so the standalone result and terminal contract select the summary on the route taken rather than the last declared step. The normal and blocked summaries therefore may be separate authored branches without depending on declaration order.
- Each `implement-task` terminal summary ends with exactly one column-zero `IMPLEMENTATION_STATUS: IMPLEMENTATION_COMPLETE` or `IMPLEMENTATION_STATUS: IMPLEMENTATION_BLOCKED` line matching its branch. That terminal summary is the outer workflow's yielded text: a lifecycle delegate receives it at `{:from {:step "implement-task" :yield :text}}`. The lifecycle gate uses the existing generic `workflow/exact-marker-routing` with `:marker-label "IMPLEMENTATION_STATUS"` and exactly those two allowed routes; its authored routing table alone determines completion-to-review versus blocked handback. `MORE_WORK_REMAINS` is internal to `implement-task` and is never exported to the lifecycle.
- The lifecycle gate is deterministic and is placed before `review-task-implementation`, preserving the one-way stage boundary.
- A blocked implementation is a clean authored handback, not a failed or runtime-`:blocked` workflow run. It remains distinguishable from successful implementation through its explicit terminal status and summary.

## Acceptance criteria

1. The implementation-pass prompt documents exactly three permitted final `PASS_STATUS` values: `MORE_WORK_REMAINS`, `IMPLEMENTATION_COMPLETE`, and `IMPLEMENTATION_BLOCKED`; it defines the required blocker block for the blocked value.
2. `implement-pass` uses generic exact-marker routing with those three authored routes. A valid `IMPLEMENTATION_BLOCKED` reply routes `implement-task` to its blocked handback without re-entering `implement-pass`; malformed, duplicated, or unsupported status markers fail rather than becoming a blocker.
3. Existing valid completion and repeat replies retain their current routing behavior and bounded repeat limit.
4. A standalone blocked implementation run projects the blocked handback text from the recorded terminal outcome; a normal run projects the normal summary, independent of terminal-step declaration order.
5. Every blocked attempt appends a complete two-field `IMPLEMENTATION_BLOCKER` record. The blocked summaries select the last complete record only and present its `blocker` and `required-human-action` fields.
6. In `task-lifecycle`, the post-delegate exact-marker gate reads `IMPLEMENTATION_STATUS` from `implement-task`'s yielded terminal summary. Only `IMPLEMENTATION_COMPLETE` advances from implementation to implementation review; `IMPLEMENTATION_BLOCKED` reaches the lifecycle blocked handback instead.
7. The lifecycle blocked route does not invoke implementation review or knowledge extraction and explicitly explains the required human resolution and later re-invocation.
8. Workflow loader/definition tests and execution-routing tests prove all three pass statuses, malformed-status rejection, branch-specific terminal projection, exported terminal statuses, latest blocker-record selection, and the no-review/no-extraction blocked boundary using observable workflow state/outputs rather than interaction-only assertions.
9. Relevant user-facing workflow documentation agrees with the authored status, blocker-record, terminal-export, and lifecycle behavior.

## Risks and open questions

- The implementation must prove the existing `:terminal-outcome :step-id` projection on both branches, including through delegation, rather than relying on step declaration order.
- The summaries must reject an absent or malformed final blocker record as invalid workflow output; they must not invent a handback from unrelated `implementation.md` prose.
