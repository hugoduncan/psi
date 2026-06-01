# Steps — 201 Verify scheduler & scheduled task execution

Convention: each verification slice = audit existing coverage → fill any gap with
a green-against-current-behaviour test → record the area in `findings.md`. No
scheduler source/doc edits. Tests use time/timer seams, never wall-clock sleeps.

## Slice 0 — Baseline

- [ ] Inventory existing scheduler tests under
      `components/agent-session/test/psi/agent_session/scheduler_*_test.clj`
      and `psi_tool_scheduler_test.clj`; list each `ns + deftest`.
- [ ] Run `bb test`; capture current pass/fail state (note any pre-existing
      failures) in `implementation.md`.
- [ ] Create `munera/open/201-verify-scheduler-execution/findings.md` skeleton:
      one `##` section per Scope area (Baseline, Pure model, Live execution path,
      psi-tool surface, Cancellation & lifecycle, Failure path, Projections),
      each with an entry table for {status, summary, covering test, repro+task-ref}.
- [ ] Record Baseline finding: suite present, current pass/fail, seam helpers
      available in `test_support`.

## Slice 1 — Pure model (`scheduler.clj`)

- [ ] Audit `scheduler_test.clj` against design: state transitions (`:pending`
      → `:queued`/`:delivered`/`:cancelled`/`:failed`), `min/max-delay-ms`
      bounds via `validate-delay-ms`, duplicate/terminal-status guards, queue
      ordering `[fire-at created-at schedule-id]`, `drain-one`.
- [ ] If any of the above lacks coverage, add a verification test (new deftest)
      asserting current behaviour green.
- [ ] Record Pure-model finding(s) in `findings.md` citing covering deftests.

## Slice 2 — Live execution: message kind

- [ ] Audit `scheduler_end_to_end_test.clj` / `scheduler_lifecycle_test.clj`
      for a real-round-trip message-kind idle-delivery test.
- [ ] Ensure a test exists that, via `test_support/make-session-ctx` seams:
      creates a message-kind schedule → captures the timer callback → invokes it
      (no sleep) → `:scheduler/fired` → schedule delivered → asserts the
      delivered prompt (`kind "message"`) appears in the **origin session** with
      scheduled provenance. Add if missing.
- [ ] Confirm assertions are on state/outputs (delivered prompt), not handler
      interactions.
- [ ] Record message-kind live-path finding citing covering deftest.

## Slice 3 — Busy-session queue + drain-on-idle

- [ ] Audit existing busy/queue/drain coverage
      (`busy-session-fire-queues-then-idle-drains-fifo-test`,
      `scheduler-drain-queue-delivers-oldest-queued-schedule-test`).
- [ ] Ensure a test: fire while origin non-idle (`:is-streaming` or
      `:is-compacting` true) → schedule `:queued`; set session idle; dispatch
      `:scheduler/drain-queue` directly → `drain-one` delivers oldest queued
      (by `fire-at`, `created-at`, `schedule-id`). Add if missing.
- [ ] Record busy-queue/drain finding citing covering deftest.

## Slice 4 — Live execution: session kind

- [ ] Audit session-kind coverage
      (`scheduler-session-kind-fires-without-origin-idle-test`,
      `scheduler-session-deliver-creates-top-level-session-without-switching-test`).
- [ ] Ensure a real-round-trip test: session-kind fires (delivers regardless of
      origin idle) → fresh **top-level session** created in origin worktree/
      context → prompt submitted into it → `created-session-id` and
      `delivery-phase` recorded. Add if missing.
- [ ] Record session-kind live-path finding citing covering deftest.

## Slice 5 — psi-tool surface

- [ ] Audit `psi_tool_scheduler_test.clj` /
      `scheduler_tools_test.clj` for create / list / cancel coverage.
- [ ] Ensure coverage for input resolution:
      - [ ] `:delay-ms` relative path (valid; below-min rejected "below … bound";
            above-max rejected "exceeds … bound").
      - [ ] `:at` past/now → `delay = 0`, no min check → created + fires immediately.
      - [ ] `:at` future <`min-delay-ms` (1–999ms) → `validate-delay-ms!` throws
            "below the minimum bound".
      - [ ] `:at` > `max-delay-ms` (>24h) → throws "exceeds the maximum bound".
      - [ ] `message` vs `session` kind selection.
      Add tests for any uncovered case.
- [ ] Record psi-tool finding; note the `:at` past-allowed / near-future-rejected
      asymmetry — if it reads as doc/behaviour drift, record it as a `defect`
      finding (drift), not a fix.

## Slice 6 — Cancellation & lifecycle

- [ ] Audit cancel coverage (`cancel-pending-and-queued-schedules-test`,
      `scheduler-cancel-marks-pending-or-queued-schedule-cancelled-test`,
      `scheduler_context_shutdown_test.clj`, `scheduler_effects_test.clj`).
- [ ] Ensure: cancel before fire → `:cancelled`.
- [ ] Ensure race A — cancel before captured callback dispatches `:scheduler/fired`:
      cancel runs (`:cancelled`, handle removed) before invoking captured callback;
      invoking the stale callback hits `fire-schedule` non-`:pending` →
      "only pending schedules can fire"; assert schedule stays `:cancelled`,
      not resurrected. Add if missing.
- [ ] Ensure race B — `:queued` → cancel deliverable race: `:queued` → cancel
      → `:cancelled` + id removed from queue; terminal-status cancel throws
      "schedule is not cancellable". Add if missing.
- [ ] Ensure `cancel-all` coverage.
- [ ] Ensure context-shutdown coverage via `context/shutdown-context!` (or
      `dispatch-effects/cancel-all-scheduler-timers!`): after shutdown
      `scheduler-timer-handle-count` = 0, `:scheduler-timers*` empty, no captured
      callback fires `:scheduler/fired` post-shutdown. Add if missing.
- [ ] Record cancellation & lifecycle finding(s) citing covering deftests.

## Slice 7 — Failure path

- [ ] Audit failure coverage
      (`scheduler-session-deliver-records-failed-status-on-prompt-submit-error-test`,
      `fail-schedule` in `scheduler_test.clj`).
- [ ] Ensure: delivery/creation failure records `:failed` with `error-summary`
      and `delivery-phase`; status guard `{:pending :queued :delivered}`; queue
      not wedged (subsequent drain still works). Add if missing.
- [ ] Record failure-path finding citing covering deftest.

## Slice 8 — Projections

- [ ] Audit projection coverage (`scheduler_resolvers_test.clj`,
      `scheduler_background_jobs_test.clj`, psi-tool summary).
- [ ] Ensure EQL `:psi.scheduler/*` attrs, psi-tool summary, and background-job
      projection stay coherent across statuses
      (`:pending`/`:queued`/`:delivered`/`:cancelled`/`:failed`). Add if missing.
- [ ] Record projections finding citing covering deftests.

## Slice 9 — Defect handling (conditional)

- [ ] For each `defect` recorded in `findings.md`: create
      `munera/open/NNN-slug` remediation task (alloc next NNN) with `design.md`
      and a reproducing **failing** test that stays in that new task (do NOT
      commit it green here).
- [ ] Reference each remediation task `NNN-slug` from the corresponding
      `findings.md` entry.
- [ ] If no defect found: confirm no remediation dir is created; all areas read
      `verified-correct`.

## Slice 10 — Close-out

- [ ] Finalise `findings.md`: every Scope area has a status + covering-test
      citation (+ repro/task-ref for defects).
- [ ] `cljfmt` and `clj-kondo --lint` clean on all touched test files.
- [ ] `bb test` green (new verification tests pass against current behaviour;
      any defect repro lives only in its remediation task).
- [ ] Coherence check: no scheduler source/doc/behaviour modified; deliverable =
      green coverage + `findings.md`.

## Plan/steps ambiguity follow-ups (2026-06-01)

- [ ] Define an operational "sufficient coverage" criterion for the
      audit→cite-vs-add decision (used by Slices 2/3/4/6/7/8 and plan step 4 /
      "Reuse before adding"): state, per area, what an existing test must
      assert (the area's named state/outputs), whether it must drive via the
      time/timer seam, and what counts as "insufficient" → triggers a new test.
- [ ] Correct the audit-location pointers in steps.md to the real files:
      busy/drain-fifo + cancel-pending-and-queued → `scheduler_lifecycle_test.clj`;
      drain-queue-delivers-oldest + cancel-marks-pending-or-queued →
      `scheduler_dispatch_test.clj`; session-kind-fires / creates-top-level /
      records-failed → `scheduler_handlers_test.clj`. Add the missing file refs
      to Slice 4 and Slice 7.
- [ ] Slice 5 `:at` past/now: decide and state whether the test must assert the
      schedule *fires* (drive the delay-0 timer via the seam) or only that it is
      *created/accepted* without min-delay rejection; update the Slice 5 item to
      the chosen contract.
- [ ] Slice 9: replace "alloc next NNN" with the explicit munera rule —
      `NNN = max(NNN over munera/open ∪ munera/closed) + 1` — and note the
      current next id (202) to avoid a colliding remediation-task id.
- [ ] Slice 10: specify the mechanism that proves "no scheduler source/doc/
      behaviour modified" — e.g. a touched-path allowlist (new test files +
      `findings.md` only) checked via `git diff --stat`, or equivalent — so the
      coherence gate is falsifiable.
