# Implementation notes

## 2026-05-24 ambiguity review
- Reviewed `design.md` plus scheduler source/tests/docs references; `plan.md`, `steps.md`, and `design-steps.md` were absent before this pass.
- New actionable ambiguities found: production time-source ownership/injection path, dispatch create contract, delivery/drain timestamp source, scheduler-owned scan boundary, and test-helper location/scope.

## 2026-05-24 ambiguity follow-up execution
- Completed all ambiguity follow-up items in `design-steps.md`.
- Updated `design.md` with concrete boundary contract: runtime context owns `:scheduler-time-source`; `:scheduler/create` requires explicit `:created-at` and `:fire-at`; deliver/drain resolve or accept explicit `:delivered-at`.
- Defined scheduler-owned wall-clock scan boundary and excluded non-scheduler timestamps plus real Java timer sleeping infrastructure.
- Chose test helper location/shape in `components/agent-session/test/psi/agent_session/test_support.clj` as test support only; production API remains `psi.agent-session.scheduler-time`.
- Created `plan.md` from the clarified design because it was missing and the task protocol expects it before implementation.
- No ambiguity follow-up item remains blocked.

## 2026-05-24 inconsistency review
- Reviewed `design.md`, `plan.md`, missing `steps.md`, `design-steps.md`, `implementation.md`, and referenced scheduler source/tests.
- New actionable inconsistencies found: task execution checklist file is missing despite task/protocol references to steps, and scheduler wall-clock boundary text misplaces the `scheduler-time/system-time-source` exception under `context.clj` while omitting the new `scheduler_time.clj` file from the scan boundary.

## 2026-05-24 inconsistency follow-up execution
- Created `steps.md` as the task execution checklist; left all execution items unchecked because this pass was only for newly added design follow-ups and the user explicitly said not to execute `steps.md` items.
- Corrected `design.md` scheduler-owned wall-clock boundary to list `components/agent-session/src/psi/agent_session/scheduler_time.clj` and attach the `scheduler-time/system-time-source` wall-clock exception to that file.
- Completed all newly added design follow-up items; none remain blocked.

## 2026-05-24 ambiguity review pass 2
- Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, and scheduler source/tests/docs references.
- No new actionable ambiguities found; prior ambiguity/inconsistency follow-ups already cover the design boundary, scan scope, test helper shape, and execution checklist.

## 2026-05-24 ambiguity follow-up execution pass 2
- Used the preloaded ambiguity-review result: no new actionable ambiguities were added in the preceding review pass.
- Checked `design-steps.md`; all ambiguity follow-up items were already complete.
- No design-step was blocked or left newly unchecked by this pass.

## 2026-05-24 inconsistency review pass 2
- Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, and referenced scheduler source/tests/docs boundaries.
- No new actionable inconsistencies found; current plan and execution checklist align with the clarified mandatory scheduler time-source contract, delivery/drain timestamp handling, wall-clock scan boundary, and focused verification list.

## 2026-05-24 inconsistency follow-up execution pass 2
- Used the preloaded inconsistency-review result: no new actionable inconsistencies were added in the preceding review pass.
- Checked `design-steps.md`; all design follow-up items were already complete.
- Did not execute `steps.md` items, per instruction.
- No design-step was blocked or left newly unchecked by this pass.

## 2026-05-24 ambiguity review pass 3
- Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, and sampled referenced scheduler source/tests for current wall-clock, dispatch, timer, delivery, drain, and test-helper seams.
- No new actionable ambiguities found; the task already specifies the mandatory scheduler time-source boundary, explicit create instants, delivery/drain timestamp handling, scheduler-owned wall-clock scan boundary, test helper shape, and focused proof set.

## 2026-05-24 ambiguity follow-up execution pass 3
- Used the preloaded ambiguity-review result: no new actionable ambiguities were added in the preceding review pass.
- Checked `design-steps.md`; all ambiguity follow-up items were already complete.
- Did not execute `steps.md` items, per instruction.
- No design-step was blocked or left newly unchecked by this pass.

## 2026-05-24 inconsistency review pass 3
- Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, and sampled referenced scheduler source/tests for create, timer, delivery, drain, projection, lifecycle, and test-helper seams.
- No new actionable inconsistencies found; task artifacts remain aligned on mandatory scheduler time-source ownership, explicit create instants, delivery/drain timestamp handling, wall-clock scan boundary, execution checklist, and focused proof set.

## 2026-05-24 inconsistency follow-up execution pass 3
- Used the preloaded inconsistency-review result: no new actionable inconsistencies were added in the preceding review pass.
- Checked `design-steps.md`; all design follow-up items were already complete.
- Did not execute `steps.md` items, per instruction.
- No design-step was blocked or left newly unchecked by this pass.

## 2026-05-24 ambiguity review pass 4
- Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, and sampled referenced scheduler source/tests for psi-tool create-time resolution, create dispatch, timer delay, delivery/drain timestamps, projection-only namespaces, and test helper seams.
- No new actionable ambiguities found; existing design text and completed follow-ups already make the mandatory scheduler time-source ownership, explicit create instants, delivery/drain timestamp source, wall-clock scan boundary, test helper scope, and verification expectations concrete.

## 2026-05-24 ambiguity follow-up execution pass 4
- Used the preloaded ambiguity-review result: no new actionable ambiguities were added in the preceding review pass.
- Checked `design-steps.md`; all ambiguity follow-up items were already complete.
- Did not execute `steps.md` items, per instruction.
- No design-step was blocked or left newly unchecked by this pass.

## 2026-05-24 inconsistency review pass 4
- Reviewed `design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`, and sampled referenced scheduler source/tests for scheduler create, timer, delivery/drain, lifecycle, projection, psi-tool, and test-helper seams.
- No new actionable inconsistencies found; task artifacts remain aligned on mandatory scheduler time-source ownership, explicit create instants, delivery/drain timestamp handling, wall-clock scan boundary, execution checklist, and focused proof set.

## 2026-05-24 inconsistency follow-up execution pass 4
- Used the preloaded inconsistency-review result: no new actionable inconsistencies were added in the preceding review pass.
- Checked `design-steps.md`; all design follow-up items were already complete.
- Did not execute `steps.md` items, per instruction.
- No design-step was blocked or left newly unchecked by this pass.

## 2026-05-24 implementation pass
- Added `psi.agent-session.scheduler-time` with mandatory zero-arity scheduler time-source validation and production `system-time-source`.
- Runtime context now owns `:scheduler-time-source`; tests can override it through context opts and test support helpers.
- Psi-tool scheduler create resolution now derives `:created-at`, `:fire-at`, and absolute-`at` delay calculations from the scheduler time source.
- Scheduler create handler now requires explicit `:created-at` and `:fire-at`; no handler fallback to wall-clock time remains.
- Scheduler timer delay calculation now uses `:scheduler-time-source` instead of generic `:now-fn`/wall-clock fallback.
- Scheduler deliver/drain message construction now accepts explicit `:delivered-at` or resolves it from the scheduler time source.
- Updated focused scheduler tests to pass explicit create instants and deterministic scheduler time sources for create, timer, delay-ms, absolute-at, delivery, and drain timestamp assertions.
- Focused scheduler proof passed: 31 tests, 332 assertions, 0 failures.

## 2026-05-24 implementation review
- Reviewed task-implementation-review skill, task artifacts, scheduler source, focused scheduler tests, wall-clock scan, and focused scheduler proof subset (17 tests, 195 assertions, 0 failures).
- New actionable feedback: missing/invalid `:scheduler-time-source` fail-fast behavior is implemented by `scheduler-time/now` but not directly covered at scheduler runtime boundaries, so a regression could reintroduce fallback/default behavior unnoticed.

## 2026-05-24 implementation review follow-up execution
- Used the preloaded implementation-review result and completed the newly added follow-up item.
- Added focused fail-fast coverage for missing/invalid `:scheduler-time-source` at psi-tool create, scheduler timer effect, deliver, and drain runtime boundaries.
- Marked the follow-up checklist item complete in `steps.md`.
- Focused follow-up proof passed: `clojure -M:test --focus psi.agent-session.psi-tool-scheduler-test --focus psi.agent-session.scheduler-effects-test --focus psi.agent-session.scheduler-handlers-test` => 9 tests, 164 assertions, 0 failures.
- Full scheduler proof passed: `clojure -M:test --focus psi.agent-session.scheduler-test --focus psi.agent-session.scheduler-handlers-test --focus psi.agent-session.psi-tool-scheduler-test --focus psi.agent-session.scheduler-timer-seam-test --focus psi.agent-session.scheduler-effects-test --focus psi.agent-session.scheduler-end-to-end-test --focus psi.agent-session.scheduler-background-jobs-test --focus psi.agent-session.scheduler-resolvers-test --focus psi.agent-session.scheduler-tools-test --focus psi.agent-session.scheduler-lifecycle-test --focus psi.agent-session.scheduler-cancel-job-test --focus psi.agent-session.scheduler-context-shutdown-test --focus psi.agent-session.scheduler-dispatch-test` => 32 tests, 342 assertions, 0 failures.

## 2026-05-24 implementation review pass 2
- Reviewed task-implementation-review skill, task artifacts, scheduler source/tests, scheduler wall-clock scan boundary, deterministic time-source coverage, and focused boundary proof (`psi-tool-scheduler`, `scheduler-effects`, `scheduler-handlers`: 9 tests, 164 assertions, 0 failures).
- No new actionable implementation feedback found; prior fail-fast boundary feedback is covered and the implementation matches the mandatory scheduler time-source design.

## 2026-05-24 implementation review follow-up execution pass 2
- Used the preloaded implementation-review pass 2 result: no new actionable implementation feedback was added in the preceding review pass.
- Checked `steps.md`; all implementation follow-up items are already complete.
- No unchecked actionable follow-up item remains to execute or block.

## 2026-05-24 implementation review pass 3
- Reviewed task-implementation-review skill, task artifacts, scheduler source/tests, wall-clock scan boundary, and focused deterministic time-source/fail-fast coverage.
- New actionable feedback: `:scheduler/deliver` resolves `:scheduler-time-source` before confirming the target schedule exists/is deliverable, so missing/invalid time source can mask schedule-not-found or non-deliverable errors even when delivery will not construct a scheduled user message.
