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
