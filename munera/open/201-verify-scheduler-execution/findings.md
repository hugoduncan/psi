# Findings — 201 Verify scheduler & scheduled task execution

Verification-only deliverable. One section per design Scope area (7 total). The
single **Live execution path** section holds the three live-execution slices
(message-kind, busy-session queue+drain, session-kind) as separate entries.

Status legend: `verified-correct` | `defect`. For defects: reproduction notes +
raised remediation task ref (`NNN-slug` or `not-yet-raised`).

---

## Baseline

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |
| verified-correct | Full scheduler suite present (13 test ns, 35 deftests, 338 assertions) and green against current behaviour. | all `scheduler_*_test.clj` + `psi_tool_scheduler_test.clj` (see inventory below) | — |
| verified-correct | Deterministic time/timer seams available in `test_support/make-session-ctx` (`:scheduler-run-after-delay-fn`, `:scheduler-cancel-delay-fn`, `:scheduler-timers*`, `:daemon-thread-fn`); enable firing without wall-clock sleeps. | `scheduler_timer_seam_test.clj`, `scheduler_effects_test.clj` | — |

### Inventory (ns → deftests)

- `scheduler-test`: empty-state, create-and-list-schedule, create-schedule-requires-explicit-kind, validate-delay-ms, fire-schedule, deliver-and-cancel, drain-one
- `scheduler-dispatch-test`: scheduler-create-stores-schedule-and-starts-timer, scheduler-cancel-marks-pending-or-queued-schedule-cancelled, scheduler-fired-queues-while-session-busy, scheduler-deliver-submits-canonical-prompt-lifecycle, scheduler-drain-queue-delivers-oldest-queued-schedule
- `scheduler-handlers-test`: scheduler-create-cancel-fire-deliver-handlers, scheduler-deliver-and-drain-use-time-source-when-delivered-at-omitted, scheduler-deliver-and-drain-require-time-source-when-delivered-at-omitted, scheduler-session-deliver-requires-time-source-without-marking-failed, scheduler-deliver-checks-schedule-before-time-source, scheduler-session-kind-fires-without-origin-idle, scheduler-session-deliver-creates-top-level-session-without-switching, scheduler-session-deliver-records-failed-status-on-prompt-submit-error, scheduler-drain-and-statechart-idle-hooks
- `scheduler-lifecycle-test`: scheduled-deliver-runs-canonical-prompt-lifecycle, busy-session-fire-queues-then-idle-drains-fifo, cancel-pending-and-queued-schedules
- `scheduler-end-to-end-test`: scheduler-fired-end-to-end-delivers-when-idle
- `scheduler-effects-test`: scheduler-start-and-cancel-timer-effects, shutdown-context-cancels-scheduler-timers
- `scheduler-timer-seam-test`: scheduler-start-timer-uses-injected-time-source-and-delay-runner, scheduler-cancelled-default-delay-thread-exits-without-uncaught-interrupted-exception
- `scheduler-context-shutdown-test`: shutdown-context-clears-scheduler-timers
- `scheduler-background-jobs-test`: scheduler-background-job-projection
- `scheduler-cancel-job-test`: session-cancel-job-routes-scheduler-projection-to-scheduler-cancel
- `scheduler-resolvers-test`: scheduler-resolver
- `scheduler-tools-test`: make-psi-tool-scheduler
- `psi-tool-scheduler-test`: psi-tool-scheduler-create-list-cancel

Baseline `bb test` (scheduler subset, 2026-06-01): `35 tests, 338 assertions, 0 failures, 0 errors`.

---

## Pure model

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |

---

## Live execution path

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |

---

## psi-tool surface

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |

---

## Cancellation & lifecycle

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |

---

## Failure path

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |

---

## Projections

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |
