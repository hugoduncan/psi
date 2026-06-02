# Findings — 201 Verify scheduler & scheduled task execution

Verification-only deliverable. One section per design Scope area (7 total). The
single **Live execution path** section holds the three live-execution slices
(message-kind, busy-session queue+drain, session-kind) as separate entries.

Status legend: `verified-correct` | `defect`. For defects: reproduction notes +
raised remediation task ref (`NNN-slug` or `not-yet-raised`).

## Outcome (2026-06-01)

> **Update (2026-06-02 — lifecycle-event read race fixed).** The cited
> `scheduler-lifecycle-test/scheduled-deliver-runs-canonical-prompt-lifecycle`
> was intermittently failing (~1-in-8 full-suite runs, 4 failures) on its
> `:session/prompt-record-response` / `:session/prompt-finish` event-log
> assertions. **Root cause:** it asserted against the **process-global bounded
> ring** `kernel/event-log-entries` (shared across every namespace), so
> concurrent cross-ns dispatch under full-suite load could pollute/evict this
> session's lifecycle tail — *not* an async prompt-lifecycle tail (verified:
> the scheduled-delivery effect runs the canonical lifecycle **synchronously**
> on-thread — effect → `:session/prompt-record-response` → `:session/prompt-finish`
> — all entries present in the just-cleared log before `:scheduler/fired`
> returns; every entry carries this session-id). **Fix (test-only):** scope the
> event-log read to this test's own session-id
> (`filterv #(= session-id (get-in % [:event-data :session-id]))`), making the
> assertion immune to cross-ns pollution; and swap the `with-redefs` of
> `turn/execute-prepared-request!` for the local `:execute-prepared-request-fn`
> ctx seam (matching `scheduler-end-to-end-test`). Re-verified: scheduler suite
> 10× green; full `bb test` green. The "all green / deterministic" wording below
> now genuinely holds (no lucky-run dependence). Slice-10 allowlist held — only
> `scheduler_lifecycle_test.clj` + this task dir changed; zero scheduler source
> / `doc/scheduler.md`. (Aggregate unchanged: 50 tests / 339 assertions — no
> deftest renamed, same `is` count.)

**All 7 Scope-area behaviours verified-correct.** One **doc-gap defect** found
(behaviour correct, doc silent): `doc/scheduler.md` "Create validation rules"
does not document that future absolute `:at` below `min-delay-ms` / above
`max-delay-ms` is rejected (only past-instant immediate-fire is documented).
Remediation raised as `202-document-at-bounds-in-scheduler-doc` (doc-only fix;
behaviour proven correct by `psi-tool-scheduler-at-resolution-matrix`). No
scheduler-behaviour defect found. Scheduler suite changed from baseline **35 tests / 338 assertions** to
**50 tests / 339 assertions**, all green (the test-shaper-pass-2 split of the
psi-tool megatest into 6 focused deftests raised the deftest count 45 → 50;
assertions unchanged. test-shaper pass 5 then dropped one duplicated
`:queued`-status assertion in `scheduler-fired-queues-while-session-busy-test`,
412 → 411. task-test-review pass 10 then split the busy-drain covering test's
time-source-stamp handler-unit assertion out of the live covering test into a
dedicated `drain-one-stamps-scheduled-user-message-from-scheduler-time-source`
deftest, 50 → 51; assertions unchanged. test-shaper pass 17 then added the two
named-message assertions to `psi-tool-scheduler-bounds-and-cap-test`
(below-min `delay-ms` + pending-cap), 411 → 413. test-shaper pass 19 then
**deleted the redundant baseline `scheduler-tools-test/make-psi-tool-scheduler`**
deftest — every block was re-covered with stronger assertions by the
authoritative `psi-tool-scheduler-test` (incl. a second expensive `dotimes 50`
cap drive), so the file was dropped, 51 → 50 tests and 413 → 339 assertions).
No scheduler source or `doc/scheduler.md` modified
(coherence gate passes: only test files under
`components/agent-session/test/**` + this task dir changed).

New verification tests added (10):
- `scheduler-test`: `create-schedule-rejects-duplicate-id`,
  `fire-schedule-rejects-non-pending-status`,
  `cancel-schedule-rejects-terminal-status`,
  `fail-schedule-records-failure-detail-and-dequeues`,
  `drain-one-orders-by-fire-at-not-queue-insertion-order`
- `scheduler-end-to-end-test`:
  `scheduler-message-kind-fires-via-timer-seam-and-delivers-to-origin`,
  `scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session`
- `scheduler-timer-seam-test`:
  `scheduler-cancel-before-stale-timer-callback-does-not-resurrect`
- `scheduler-context-shutdown-test`:
  `shutdown-context-prevents-captured-timer-callback-from-firing`
- `scheduler-resolvers-test`:
  `scheduler-resolver-projects-rich-attrs-across-statuses`

Extended (in place): the psi-tool-surface `:at` matrix + bounds/cap/kind/
session-id/time-source coverage (since split test-shaper pass 2 into focused
deftests: `psi-tool-scheduler-create-list-cancel`,
`psi-tool-scheduler-time-source-required`, `psi-tool-scheduler-bounds-and-cap`,
`psi-tool-scheduler-session-id-resolution`,
`psi-tool-scheduler-kind-validation`, `psi-tool-scheduler-at-resolution-matrix`);
`scheduler-session-deliver-records-failed-status-on-prompt-submit-error`
(error-summary + created-session-id).

---

## Baseline

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |
| verified-correct | Full scheduler suite present (13 test ns, 35 deftests, 338 assertions) and green against current behaviour. | all `scheduler_*_test.clj` + `psi_tool_scheduler_test.clj` (see inventory below) | — |
| verified-correct | Deterministic time/timer seams available in `test_support/make-session-ctx` (`:scheduler-run-after-delay-fn`, `:scheduler-cancel-delay-fn`, `:scheduler-timers*`, `:daemon-thread-fn`); enable firing without wall-clock sleeps. | `scheduler_timer_seam_test.clj/scheduler-start-timer-uses-injected-time-source-and-delay-runner-test` (captures `delay-ms`+callback, invokes `(@callback*)`, asserts `:delivered`, zero wall-clock) | — |
| verified-correct | `scheduler-dispatch-test` timer-state assertions are seam-driven (no real `Thread/sleep` daemon): `scheduler-create-stores-schedule-and-starts-timer` drives the timer via `capturing-delay-fn` and asserts `:scheduler-timers*` membership before any fire; `scheduler-cancel-marks-pending-or-queued-schedule-cancelled` uses a non-Thread `{:handle :captured}` sentinel so cancel cannot `.interrupt` the test-runner thread. Restores the design's controlled-time discipline (fixes the prior canonical-runner timer race). | `scheduler-dispatch-test/scheduler-create-stores-schedule-and-starts-timer`, `scheduler-dispatch-test/scheduler-cancel-marks-pending-or-queued-schedule-cancelled` | — |

### Inventory (ns → deftests)

- `scheduler-test`: empty-state, create-and-list-schedule, create-schedule-requires-explicit-kind, validate-delay-ms, fire-schedule, deliver-and-cancel, drain-one
- `scheduler-dispatch-test`: scheduler-create-stores-schedule-and-starts-timer, scheduler-cancel-marks-pending-or-queued-schedule-cancelled, scheduler-fired-queues-while-session-busy, scheduler-deliver-submits-canonical-prompt-lifecycle, scheduler-drain-queue-delivers-oldest-queued-schedule
- `scheduler-handlers-test`: scheduler-create-cancel-fire-deliver-handlers, scheduler-deliver-and-drain-use-time-source-when-delivered-at-omitted, scheduler-deliver-and-drain-require-time-source-when-delivered-at-omitted, scheduler-session-deliver-requires-time-source-without-marking-failed, scheduler-deliver-checks-schedule-before-time-source, scheduler-session-kind-fires-without-origin-idle, scheduler-session-deliver-creates-top-level-session-without-switching, scheduler-session-deliver-records-failed-status-on-prompt-submit-error, scheduler-drain-and-statechart-idle-hooks
- `scheduler-lifecycle-test`: scheduled-deliver-runs-canonical-prompt-lifecycle, busy-session-fire-queues-then-idle-drains-oldest-by-fire-at, drain-one-stamps-scheduled-user-message-from-scheduler-time-source, cancel-pending-and-queued-schedules
- `scheduler-end-to-end-test`: scheduler-fired-end-to-end-delivers-when-idle
- `scheduler-effects-test`: scheduler-start-and-cancel-timer-effects, shutdown-context-cancels-scheduler-timers
- `scheduler-timer-seam-test`: scheduler-start-timer-uses-injected-time-source-and-delay-runner, scheduler-cancelled-default-delay-thread-exits-without-uncaught-interrupted-exception
- `scheduler-context-shutdown-test`: shutdown-context-clears-scheduler-timers
- `scheduler-background-jobs-test`: scheduler-background-job-projection
- `scheduler-cancel-job-test`: session-cancel-job-routes-scheduler-projection-to-scheduler-cancel
- `scheduler-resolvers-test`: scheduler-resolver
- `scheduler-tools-test`: make-psi-tool-scheduler *(baseline; removed by
  test-shaper pass 19 as fully redundant with the stronger
  `psi-tool-scheduler-test` authority — see psi-tool-surface section)*
- `psi-tool-scheduler-test`: psi-tool-scheduler-create-list-cancel,
  psi-tool-scheduler-time-source-required, psi-tool-scheduler-bounds-and-cap,
  psi-tool-scheduler-session-id-resolution, psi-tool-scheduler-kind-validation,
  psi-tool-scheduler-at-resolution-matrix

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
| verified-correct | **busy queue + drain-on-idle** — fire while `:is-streaming` true → schedule `:queued`; set idle → dispatch `:scheduler/drain-queue` → oldest queued (by [fire-at created-at schedule-id]) delivered first; queue mutates; scheduled-message timestamp from runtime scheduler time source. | `scheduler-lifecycle-test/busy-session-fire-queues-then-idle-drains-oldest-by-fire-at`, `scheduler-dispatch-test/scheduler-fired-queues-while-session-busy` (cited) | — |
| verified-correct | **drain oldest-by-fire-at via dispatch** — `dispatch-in! :scheduler/drain-queue` delivers the earliest `fire-at` even when it is second in queue order (`["sch-1" "sch-2"]`, `sch-2` earlier → `sch-2` delivered); skips a missing queue id; no effects on no-op. | `scheduler-dispatch-test/scheduler-drain-queue-delivers-oldest-queued-schedule` (cited) | — |
| verified-correct | **session kind** — real timer-seam round trip with origin **busy**: captured timer callback fires → `:scheduler/deliver` creates a **fresh top-level session** in origin worktree (provenance `:scheduled-origin-session-id`/`:scheduled-from-schedule-id`/`:scheduled-from-label`), submits the scheduled prompt; schedule `:delivered` with `:created-session-id` + `:delivery-phase :prompt-submit`; origin session not switched away. | `scheduler-end-to-end-test/scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session` (new) | — |

---

## psi-tool surface

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |
| verified-correct | create / list / cancel happy paths; message-kind stored pending; list returns pending; cancel marks cancelled. (`:queued`-status list projection covered by the Projections section.) | `psi-tool-scheduler-test/psi-tool-scheduler-create-list-cancel` (sole psi-tool authority since test-shaper pass 19 dropped the redundant `scheduler-tools-test/make-psi-tool-scheduler`) | — |
| verified-correct | `:delay-ms` relative path: valid 1000ms accepted; below-min (10ms) rejected as scheduler error; cap (51st pending) rejected. | `psi-tool-scheduler-test/psi-tool-scheduler-bounds-and-cap` (below-min + cap); valid 1000ms exercised in `…/psi-tool-scheduler-create-list-cancel` | — |
| verified-correct | `:at` future absolute resolves delay from scheduler time source (5000ms → fire-at). | `psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix` (absolute-instant block) | — |
| verified-correct | `:at` **past/now** → delay 0, no min-delay check → created **and fires immediately** (delay-0 timer driven via the captured seam, asserts `:delivered`). | `psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix` (`past :at … FIRES immediately via the seam` block) | — |
| verified-correct | `:at` future **<min-delay-ms** (500ms) → rejected (below-minimum bound). | `psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix` (near-future block) | — |
| verified-correct | `:at` **>max-delay-ms** (>24h) → rejected (exceeds-maximum bound). | `psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix` (far-future block) | — |
| defect (doc-gap) | `:at` asymmetry: past/now `:at` fires immediately (delay 0, no min check) while future `:at` **below `min-delay-ms`** (1–999ms ahead) and `:at` **above `max-delay-ms`** (>24h) are rejected. The behaviour itself is correct (grounded in `psi_tool_scheduler/resolve-fire-time!`: `validate-delay-ms!` runs only when the resolved `delay` is strictly positive), but `doc/scheduler.md` "Create validation rules" documents only *relative*-delay bounds + "past absolute instants fire immediately" — it is **silent** on the near-future/`>24h` `:at` rejection. This is doc↔behaviour drift (an undocumented doc-gap), **not** verified-correct/"matches doc". | `psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix` (near-future + far-future + past `:at` blocks) | doc-gap; remediation: `202-document-at-bounds-in-scheduler-doc` |
| verified-correct | kind selection + validation: `message` vs `session`; session-kind requires `:session-config`; message-kind forbids `:session-config`; unsupported session-config keys rejected. | `psi-tool-scheduler-test/psi-tool-scheduler-kind-validation` | — |
| verified-correct | session-id resolution: explicit-vs-invoking session-id; report path with explicit session-id. | `psi-tool-scheduler-test/psi-tool-scheduler-session-id-resolution` | — |
| verified-correct | missing/invalid scheduler-time-source fails create (no wall-clock fallback). | `psi-tool-scheduler-test/psi-tool-scheduler-time-source-required` | — |

---

## Cancellation & lifecycle

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |
| verified-correct | Cancel **before fire** → `:pending`→`:cancelled`; timer handle removed. | `scheduler-lifecycle-test/cancel-pending-and-queued-schedules`, `scheduler-dispatch-test/scheduler-cancel-marks-pending-or-queued-schedule-cancelled` (cited) | — |
| verified-correct | **Race B** — `:queued`→cancel deliverable race → `:cancelled` + id removed from queue; terminal-status cancel throws "schedule is not cancellable" (pure). | `scheduler-lifecycle-test/cancel-pending-and-queued-schedules` (queued branch), `scheduler-test/cancel-schedule-rejects-terminal-status` (cited/new) | — |
| verified-correct | **Race A** — cancel before the captured timer callback dispatches `:scheduler/fired`: cancel wins (`:cancelled`, handle removed); invoking the **stale** callback does **not** resurrect the schedule (stays `:cancelled`). | `scheduler-timer-seam-test/scheduler-cancel-before-stale-timer-callback-does-not-resurrect` (new) | — |
| verified-correct | `cancel-all` / context shutdown clears all scheduler timer handles (`scheduler-timer-handle-count` 0, `:scheduler-timers*` empty) and cancels outstanding schedules. | `scheduler-context-shutdown-test/shutdown-context-clears-scheduler-timers`, `scheduler-effects-test/shutdown-context-cancels-scheduler-timers` (cited) | — |
| verified-correct | **No fire-after-shutdown** — after `shutdown-context!`, the handle is gone, the schedule is `:cancelled`, and invoking a captured stale callback does not deliver (stays `:cancelled`). | `scheduler-context-shutdown-test/shutdown-context-prevents-captured-timer-callback-from-firing` (new) | — |

---

## Failure path

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |
| verified-correct | `fail-schedule` records `:failed` + `:delivery-phase` + `:error-summary` + `:created-session-id` and removes the id from the queue (queue not wedged). | `scheduler-test/fail-schedule-records-failure-detail-and-dequeues` (new) | — |
| verified-correct | `fail-schedule` status guard: cannot fail a terminal (`:cancelled`) schedule → throws "schedule is not fail-able". | `scheduler-test/fail-schedule-records-failure-detail-and-dequeues` (new) | — |
| verified-correct | session-kind delivery failure (prompt-submit throws after session creation): schedule `:failed` with `:delivery-phase :prompt-submit`, an `:error-summary` (`:message "boom"`), and the **created-session-id is still recorded**. | `scheduler-handlers-test/scheduler-session-deliver-records-failed-status-on-prompt-submit-error` (extended) | — |

---

## Projections

| status | summary | covering test | repro / task-ref |
| ------ | ------- | ------------- | ---------------- |
| verified-correct | EQL `:psi.scheduler/*` root + detail resolvers project `:pending` schedule attrs (id/kind/label/status/origin + message/fire-at) coherently. | `scheduler-resolvers-test/scheduler-resolver` | — |
| verified-correct | EQL `:psi.scheduler/*` project the **rich attrs across statuses** — `:delivered`/`:cancelled` (status+kind+origin) and a `:failed` session-kind exposing `:created-session-id`/`:delivery-phase`/`:error-summary`/`:session-config-summary` coherent with underlying state. | `scheduler-resolvers-test/scheduler-resolver-projects-rich-attrs-across-statuses` (new) | — |
| verified-correct | Background-job projection: pending+queued message/session schedules → `:scheduled-prompt`/`:scheduled-session` jobs; scheduler-projected job cancel routes to `:scheduler/cancel`. | `scheduler-background-jobs-test/scheduler-background-job-projection`, `scheduler-cancel-job-test/session-cancel-job-routes-scheduler-projection-to-scheduler-cancel` | — |
