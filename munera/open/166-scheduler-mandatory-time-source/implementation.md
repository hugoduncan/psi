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
