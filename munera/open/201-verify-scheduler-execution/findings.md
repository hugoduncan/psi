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
| verified-correct | State transitions `:pending`→`:queued`/`:delivered`/`:cancelled` and create/list/count. | `scheduler-test/create-and-list-schedule`, `fire-schedule`, `deliver-and-cancel` | — |
| verified-correct | Explicit `:kind` required on create ("kind is invalid"). | `scheduler-test/create-schedule-requires-explicit-kind` | — |
| verified-correct | `validate-delay-ms!` bounds: inclusive min/max accepted, <1000 "minimum", >24h "maximum". | `scheduler-test/validate-delay-ms` | — |
| verified-correct | Duplicate-id guard: second create with same id throws "schedule-id already exists", state unchanged. | `scheduler-test/create-schedule-rejects-duplicate-id` (new) | — |
| verified-correct | `fire-schedule` non-pending guard: firing a delivered or already-queued schedule throws "only pending schedules can fire". | `scheduler-test/fire-schedule-rejects-non-pending-status` (new) | — |
| verified-correct | `cancel-schedule` terminal guard: cancelling a delivered or already-cancelled schedule throws "schedule is not cancellable". | `scheduler-test/cancel-schedule-rejects-terminal-status` (new) | — |
| verified-correct | `drain-one` ordering: with queue-insertion order ≠ fire-at order, drains the earliest `fire-at` (sorts by `[fire-at created-at schedule-id]`, not FIFO-by-insertion). | `scheduler-test/drain-one-orders-by-fire-at-not-queue-insertion-order` (new) | — |
| verified-correct | `drain-one` is a no-op (`:session-busy`) while session non-idle. | `scheduler-test/drain-one` | — |

---

## Live execution path

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |
| verified-correct | **message kind** — real timer-seam round trip: create message-kind → captured timer callback invoked (no sleep) → delivered prompt with scheduled provenance (`:source :scheduled`, `:schedule-id`, role `"user"`) appears in the **origin session**; status `:delivered`, queue empty. | `scheduler-end-to-end-test/scheduler-message-kind-fires-via-timer-seam-and-delivers-to-origin` (new) | — |

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
