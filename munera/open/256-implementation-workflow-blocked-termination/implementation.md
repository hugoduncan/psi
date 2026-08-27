# Implementation notes

Task created from the request to add an explicit blocked termination path to `implement-task`, analogous in intent to the task-lifecycle unresolved scope-question handback.

No implementation has started. The existing implementation loop currently recognizes only `MORE_WORK_REMAINS` and `IMPLEMENTATION_COMPLETE`; the task must preserve their behavior while adding authored `IMPLEMENTATION_BLOCKED` routing and a lifecycle boundary before implementation review.

- architectural review added 1 new design step
- ambiguity review added 2 new design steps
- inconsistency review added 1 new design step
- For the design-step resolution, inspect `.psi/workflows/implement-task.edn`, `.psi/workflows/task-lifecycle.edn`, `components/agent-session/src/psi/agent_session/workflow/routing.clj`, and `components/agent-session/src/psi/agent_session/workflow/execution.clj`; preserve authored-policy ownership, deterministic routing, and the no-generic-runtime-blocker constraint.

- 2026-08-24 design follow-up: resolved all four items from review batch `d11b08d4a..495c8f2c2`. Existing `:terminal-outcome :step-id` selects the executed terminal branch for standalone text and delegate handoff; `workflow/exact-marker-routing` accepts authored raw routes, unlike the fixed DONE/REPEAT pass-status router. Implementation must prove that projection and use `IMPLEMENTATION_STATUS` in the terminal summaries as the lifecycle delegate contract. Blocked summaries must read only the final complete `IMPLEMENTATION_BLOCKER` block in `implementation.md`; absent or malformed records are invalid output, not a reason to infer a blocker.

- no architectural review feedback
- no ambiguity review feedback
- no inconsistency review feedback
- Blocker-record validation needs an explicit deterministic seam before either blocked summary: status-only `workflow/exact-marker-routing` cannot validate `implementation.md`. Keep record syntax/policy authored, fail missing or malformed records rather than synthesizing a handback, and distinguish that failure from authored `IMPLEMENTATION_BLOCKED`; inspect `components/agent-session/src/psi/agent_session/workflow/routing.clj` and `.psi/workflows/implement-task.edn`.

- Follow-up implementation seam: retain generic routing/terminal projection; confine the new status vocabulary and branch topology to `.psi/workflows/implement-task.edn` and `task-lifecycle.edn`. Runtime touchpoints are `components/agent-session/src/psi/agent_session/workflow/routing.clj` and `workflow_execution.clj`; definition validation coverage is in `components/agent-session/test/psi/agent_session/workflow_migration_validation_test.clj`.
- ambiguity review added 1 new design step
- no inconsistency review feedback

- 2026-08-24 plan-review follow-up: the confidently identified immediately preceding review batch is `3da18f361..0d03bc83e`, with baseline `6fecb083`. Its task-scoped `steps.md` diff adds no checklist lines, so there are no attributable current unchecked follow-ups to execute; pre-existing Slice 1–4 items remain untouched.
- no new ambiguity review feedback
- no new inconsistency review feedback
