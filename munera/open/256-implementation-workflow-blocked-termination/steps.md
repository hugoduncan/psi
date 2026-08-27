# Steps

## Slice 1 — Implementation-pass blocked contract

- [x] Update `.psi/workflows/implement-task-implement-pass.md` to permit exactly `MORE_WORK_REMAINS`, `IMPLEMENTATION_COMPLETE`, and `IMPLEMENTATION_BLOCKED`, and require the exact complete two-field `IMPLEMENTATION_BLOCKER` append before emitting the blocked status.
- [x] Extend workflow definition tests to verify the implementation-pass prompt owns the three-status contract, exact blocker delimiters and fields, non-empty field requirement, and append-before-status requirement.
- [x] Run the focused workflow-loader definition tests for the implementation-pass contract.
  - `bb clojure:test:scry --namespace psi.agent-session.workflow-migration-validation-test` — 6 tests, 40 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_migration_validation_test.clj` — no findings.
- [x] Commit the Slice 1 prompt and proof changes without staging unrelated worktree changes.

## Slice 2 — Standalone implement-task routing and terminal handbacks

- [x] Update `.psi/workflows/implement-task.edn` so `implement-pass` uses `workflow/exact-marker-routing` for the three authored raw routes, preserving the 20-pass `MORE_WORK_REMAINS` loop and normal completion destination.
- [x] Split the terminal summaries into explicit normal and blocked steps; make each inspect the task artifacts and end with exactly one matching column-zero `IMPLEMENTATION_STATUS` line.
- [x] Define the blocked summary contract to select and reproduce the last complete `IMPLEMENTATION_BLOCKER` block's `blocker` and `required-human-action`, report completed work and verification, distinguish blocked from completed, and require resolution plus fresh re-invocation.
- [x] Extend loader/definition tests to prove the three-route table, separate terminal branches, branch-specific exported statuses, and summary blocker-selection instructions.
  - `bb clojure:test:scry --namespace psi.agent-session.workflow-migration-validation-test` — 7 tests, 49 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_migration_validation_test.clj` — no findings.
- [x] Extend execution-routing tests to prove valid repeat, completion, and blocked outcomes; blocked does not re-enter `implement-pass`; malformed, duplicated, and unsupported markers fail; and the repeat limit remains bounded.
  - `workflow-review-step-routing-test` now proves malformed, duplicate, and unsupported `PASS_STATUS` replies fail before either summary, and 20 `MORE_WORK_REMAINS` passes exhaust the existing iteration guard before a twenty-first pass.
- [ ] Add observable execution tests with multiple complete and malformed/incomplete blocker blocks to prove the final complete record alone is summarized and absent valid records do not yield an invented blocked handback.
- [x] Add terminal-result tests that declare terminal steps in a non-authoritative order and prove `:terminal-outcome :step-id` projects the executed normal or blocked summary for standalone output and delegated yielded text.
  - Standalone projection is covered with the blocked terminal declared before the complete terminal; delegated yield remains.
- [x] Run focused workflow-loader and agent-session routing/execution tests for standalone `implement-task`.
  - `bb clojure:test --focus psi.agent-session.workflow-review-step-routing-test` — 13 tests, 122 assertions passed.
  - `bb clojure:test --focus psi.agent-session.workflow-migration-validation-test` — 7 tests, 49 assertions passed.
  - `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_review_step_routing_test.clj` — no findings.
- [x] Commit the Slice 2 workflow and proof changes without staging unrelated worktree changes.
  - `67dc1df04 ⚒ Prove blocked implementation marker failures` and the focused standalone routing proof added in this pass.

## Slice 3 — Task-lifecycle implementation gate

- [x] Insert a gate immediately after the `implement-task` delegate in `.psi/workflows/task-lifecycle.edn` that reads its yielded text and uses `workflow/exact-marker-routing` for exactly `IMPLEMENTATION_COMPLETE` and `IMPLEMENTATION_BLOCKED`.
- [x] Route only `IMPLEMENTATION_COMPLETE` to `review-task-implementation`; route `IMPLEMENTATION_BLOCKED` to a dedicated lifecycle blocked handback terminal step.
- [x] Author the lifecycle blocked handback to inspect the final complete blocker record, summarize completed work and verification, state that review and extraction did not run, and instruct the human to resolve the blocker and freshly re-invoke `task-lifecycle`.
- [x] Extend task-lifecycle loader/definition tests to prove gate placement, marker source, allowed routes, route destinations, and blocked handback contract.
  - `bb clojure:test --focus psi.workflow-loader.task-lifecycle-definitions-test` — 1 test, 56 assertions passed.
  - `clj-kondo --lint components/workflow-loader/test/psi/workflow_loader/task_lifecycle_definitions_test.clj` — no findings.
- [ ] Extend observable lifecycle execution tests to prove completion reaches implementation review and blocked execution reaches the blocked handback without starting implementation review or `extract-task-knowledge`.
- [ ] Add lifecycle tests proving malformed, duplicate, missing, and unsupported exported `IMPLEMENTATION_STATUS` markers fail rather than route as blocked or complete.
- [ ] Run focused task-lifecycle loader and agent-session execution tests.
- [ ] Commit the Slice 3 lifecycle workflow and proof changes without staging unrelated worktree changes.
  - Partial Slice 3 gate topology and loader proof are ready; observable execution and exported-marker rejection proof remain before this slice is complete.

## Slice 4 — Documentation and integrated verification

- [ ] Update `doc/workflows.md` wherever it describes `implement-task` or the task-lifecycle implementation boundary so it documents the third pass outcome, exact blocker record, exported terminal statuses, blocked handback, fresh re-invocation, and no-review/no-extraction lifecycle gate.
- [ ] Run all affected workflow-loader, routing, execution, migration-validation, and lifecycle test namespaces through the repository's focused Scry/test commands.
- [ ] Run `clj-kondo` on every changed Clojure test or source file and repair formatting/delimiters where required.
- [ ] Inspect the final diff against every acceptance criterion, confirming generic runtime loop semantics and `workflow/pass-status-routing` remain unchanged unless a separately recorded defect required reconciliation.
- [ ] Update `implementation.md` with concise implementation decisions, verification commands/results, and any remaining risks; mark completed checklist items accurately.
- [ ] Commit the documentation and final task-artifact synchronization without staging unrelated worktree changes.
