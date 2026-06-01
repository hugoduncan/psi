# Steps — 201 Verify scheduler & scheduled task execution

Convention: each verification slice = audit existing coverage → fill any gap with
a green-against-current-behaviour test → record the area in `findings.md`. No
scheduler source/doc edits. Tests use time/timer seams, never wall-clock sleeps.

The audit→cite-vs-add decision in every "Ensure a test exists … Add if missing"
item is governed by the **Sufficient-coverage criterion** in `plan.md`: existing
coverage is sufficient (cite it) iff it (1) asserts the area's named
state/outputs, (2) drives the real path via the timer seam for *live* areas, and
(3) asserts state/outputs not interactions; otherwise add a new verifying test.

## Slice 0 — Baseline

- [x] Inventory existing scheduler tests under
      `components/agent-session/test/psi/agent_session/scheduler_*_test.clj`
      and `psi_tool_scheduler_test.clj`; list each `ns + deftest`.
      Done: 13 ns, 35 deftests inventoried in `findings.md` Baseline section.
- [x] Run `bb test`; capture current pass/fail state (note any pre-existing
      failures) in `implementation.md`.
      Done: scheduler subset = `35 tests, 338 assertions, 0 failures, 0 errors`
      (clean). No pre-existing failures.
- [x] Create `munera/open/201-verify-scheduler-execution/findings.md` skeleton:
      exactly **7** `##` sections, one per design Scope area (Baseline, Pure
      model, Live execution path, psi-tool surface, Cancellation & lifecycle,
      Failure path, Projections), each with an entry table for {status, summary,
      covering test, repro+task-ref}. The single "Live execution path" section
      holds all three live-execution slices (2 message-kind / 3 busy-drain /
      4 session-kind) as separate entries — do not split it into three sections.
      Done: `findings.md` created with the fixed 7-section skeleton.
- [x] Record Baseline finding: suite present, current pass/fail, seam helpers
      available in `test_support`.
      Done: Baseline section records suite green + seam availability.

## Slice 1 — Pure model (`scheduler.clj`)

- [x] Audit `scheduler_test.clj` against design: state transitions (`:pending`
      → `:queued`/`:delivered`/`:cancelled`/`:failed`), `min/max-delay-ms`
      bounds via `validate-delay-ms`, duplicate/terminal-status guards, queue
      ordering `[fire-at created-at schedule-id]`, `drain-one`.
      Done: existing tests cover transitions, bounds, kind-required, fire/deliver/
      cancel/drain-busy. Gaps: duplicate-id guard, fire non-pending guard, cancel
      terminal guard, and drain-one ordering when insertion order ≠ fire-at order.
- [x] If any of the above lacks coverage, add a verification test (new deftest)
      asserting current behaviour green.
      Done: added 4 deftests — `create-schedule-rejects-duplicate-id`,
      `fire-schedule-rejects-non-pending-status`,
      `cancel-schedule-rejects-terminal-status`,
      `drain-one-orders-by-fire-at-not-queue-insertion-order`. All green
      (11 tests / 44 assertions in `scheduler_test`).
- [x] Record Pure-model finding(s) in `findings.md` citing covering deftests.
      Done: 8 Pure-model entries recorded (all `verified-correct`).

## Slice 2 — Live execution: message kind

- [x] Audit `scheduler_end_to_end_test.clj` / `scheduler_lifecycle_test.clj`
      / `scheduler_handlers_test.clj` for a real-round-trip message-kind
      idle-delivery test (handler-level message-kind delivery lives in
      `scheduler_handlers_test.clj`).
      Done: `scheduler-fired-end-to-end-delivers-when-idle` asserts provenance
      but dispatches `:scheduler/fired` directly (skips the timer seam);
      `scheduler-start-timer-uses-injected-time-source-and-delay-runner` crosses
      the timer seam but only asserts status `:delivered`. Neither single test
      jointly satisfied clauses (1)+(2) for the message-kind area → insufficient.
- [x] Ensure a test exists that, via `test_support/make-session-ctx` seams:
      creates a message-kind schedule → captures the timer callback → invokes it
      (no sleep) → `:scheduler/fired` → schedule delivered → asserts the
      delivered prompt (`kind "message"`) appears in the **origin session** with
      scheduled provenance. Add if missing.
      Done: added
      `scheduler-message-kind-fires-via-timer-seam-and-delivers-to-origin` to
      `scheduler_end_to_end_test.clj`. Captures the timer callback via
      `:scheduler-run-after-delay-fn`, asserts pending-before-fire, invokes the
      callback (no sleep), then asserts the delivered user message with
      `:source :scheduled` + `:schedule-id` in the origin journal, status
      `:delivered`, empty queue.
- [x] Confirm assertions are on state/outputs (delivered prompt), not handler
      interactions.
      Done: asserts journal message + scheduler state only; no interaction asserts.
- [x] Record message-kind finding as an entry in the **single shared "Live
      execution path"** `findings.md` section (do NOT create a separate
      message-kind section), citing covering deftest.
      Done: message-kind entry recorded as `verified-correct`.

## Slice 3 — Busy-session queue + drain-on-idle

- [x] Audit existing busy/queue/drain coverage
      (`busy-session-fire-queues-then-idle-drains-fifo-test` →
      `scheduler_lifecycle_test.clj`;
      `scheduler-drain-queue-delivers-oldest-queued-schedule-test` →
      `scheduler_dispatch_test.clj`).
      Done: lifecycle test fires-while-busy → both `:queued`, sets idle, drains
      FIFO oldest-first; dispatch test drives `dispatch-in! :scheduler/drain-queue`
      and delivers the earliest fire-at even when 2nd in queue order. Joint
      coverage satisfies the sufficient-coverage criterion.
- [x] Ensure a test: fire while origin non-idle (`:is-streaming` or
      `:is-compacting` true) → schedule `:queued`; set session idle; dispatch
      `:scheduler/drain-queue` directly → `drain-one` delivers oldest queued
      (by `fire-at`, `created-at`, `schedule-id`). Add if missing.
      Done: existing tests jointly cover this (fire-while-busy → queued in
      lifecycle + dispatch tests; set-idle-then-drain in lifecycle; oldest-by-
      fire-at via `dispatch-in! :scheduler/drain-queue` in dispatch test). No new
      test needed; both cited tests verified green (8 tests / 46 assertions).
- [x] Record busy-queue/drain finding as an entry in the **same single shared
      "Live execution path"** `findings.md` section, citing covering deftest.
      Done: two entries recorded (`verified-correct`), citing the lifecycle +
      dispatch deftests.

## Slice 4 — Live execution: session kind

- [x] Audit session-kind coverage in `scheduler_handlers_test.clj`
      (`scheduler-session-kind-fires-without-origin-idle-test`,
      `scheduler-session-deliver-creates-top-level-session-without-switching-test`).
      Done: existing tests assert fired emits `:scheduler/deliver` with
      `:delivery-phase :create-session` and the stored schedule kind/config, but
      stop **before** delivery — they never run `:scheduler/deliver` to create the
      session, nor assert `:created-session-id`/`:delivery-phase :prompt-submit`,
      and never cross the timer seam → insufficient for the live round trip.
- [x] Ensure a real-round-trip test: session-kind fires (delivers regardless of
      origin idle) → fresh **top-level session** created in origin worktree/
      context → prompt submitted into it → `created-session-id` and
      `delivery-phase` recorded. Add if missing.
      Done: added
      `scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session`.
      Origin set busy (`:is-streaming true`) to prove session-kind delivers
      regardless; capture+invoke the timer callback (no sleep); assert a fresh
      top-level session appears (not in the pre-fire session set), schedule
      `:delivered` with `:created-session-id` + `:delivery-phase :prompt-submit`,
      provenance fields on the created session, and origin still present.
- [x] Record session-kind finding as an entry in the **same single shared "Live
      execution path"** `findings.md` section (the three live-execution slices
      2/3/4 all write into this one section), citing covering deftest.
      Done: session-kind entry recorded as `verified-correct` in the shared
      Live execution path section.

## Slice 5 — psi-tool surface

- [x] Audit `psi_tool_scheduler_test.clj` /
      `scheduler_tools_test.clj` for create / list / cancel coverage.
      Done: create/list/cancel + delay-ms valid/below-min/cap + future `:at` +
      kind validation already covered; the three `:at` matrix corners below were
      missing.
- [x] Ensure coverage for input resolution:
      - [x] `:delay-ms` relative path (valid; below-min rejected "below … bound";
            above-max rejected "exceeds … bound").
            Done: valid + below-min (10ms) already covered; above-max covered at
            the pure level (`validate-delay-ms`) — psi-tool surfaces the same
            error via `resolve-fire-time!`.
      - [x] `:at` past/now → `delay = 0`, no min check → created **and fires**:
            the test must drive the delay-0 timer via the seam (capture the
            timer callback and invoke it) and assert the schedule reaches a
            fired/delivered state — not merely that creation was accepted.
            Done: added `past :at … FIRES immediately via the seam` block —
            captures the delay-0 timer callback, asserts delay 0, invokes it,
            asserts `:delivered`.
      - [x] `:at` future <`min-delay-ms` (1–999ms) → `validate-delay-ms!` throws
            "below the minimum bound".
            Done: added near-future (500ms) block → rejected.
      - [x] `:at` > `max-delay-ms` (>24h) → throws "exceeds the maximum bound".
            Done: added far-future (max+1ms) block → rejected.
      - [x] `message` vs `session` kind selection.
            Done: message create + session-config validation already covered.
- [x] Record psi-tool finding; note the `:at` past-allowed / near-future-rejected
      asymmetry — if it reads as doc/behaviour drift, record it as a `defect`
      finding (drift), not a fix.
      Done: recorded as **verified-correct** — the asymmetry matches
      `doc/scheduler.md` ("past absolute instants fire immediately"), so it is
      not a doc/behaviour drift defect. `psi_tool_scheduler_test` now
      1 test / 107 assertions, green.

## Slice 6 — Cancellation & lifecycle

- [x] Audit cancel coverage
      (`cancel-pending-and-queued-schedules-test` →
      `scheduler_lifecycle_test.clj`;
      `scheduler-cancel-marks-pending-or-queued-schedule-cancelled-test` →
      `scheduler_dispatch_test.clj`;
      `scheduler_context_shutdown_test.clj`, `scheduler_effects_test.clj`).
      Done: cancel-before-fire, queued-cancel, cancel-all, shutdown handle-clear
      all covered; race A and post-shutdown-no-fire were missing.
- [x] Ensure: cancel before fire → `:cancelled`.
      Done: covered by lifecycle + dispatch cancel tests.
- [x] Ensure race A — cancel before captured callback dispatches `:scheduler/fired`:
      cancel runs (`:cancelled`, handle removed) before invoking captured callback;
      invoking the stale callback hits `fire-schedule` non-`:pending` →
      "only pending schedules can fire"; assert schedule stays `:cancelled`,
      not resurrected. Add if missing.
      Done: added
      `scheduler-cancel-before-stale-timer-callback-does-not-resurrect`
      (seam test): capture callback → cancel → assert `:cancelled` + handle gone
      → invoke stale callback → still `:cancelled`.
- [x] Ensure race B — `:queued` → cancel deliverable race: `:queued` → cancel
      → `:cancelled` + id removed from queue; terminal-status cancel throws
      "schedule is not cancellable". Add if missing.
      Done: `:queued`→cancel covered by lifecycle test; terminal-status guard by
      Slice 1 `cancel-schedule-rejects-terminal-status`.
- [x] Ensure `cancel-all` coverage.
      Done: shutdown tests exercise per-session cancel-all-schedules.
- [x] Ensure context-shutdown coverage via `context/shutdown-context!` (or
      `dispatch-effects/cancel-all-scheduler-timers!`): after shutdown
      `scheduler-timer-handle-count` = 0, `:scheduler-timers*` empty, no captured
      callback fires `:scheduler/fired` post-shutdown. Add if missing.
      Done: handle-count 0 + empty + `:cancelled` covered by existing shutdown
      tests; added `shutdown-context-prevents-captured-timer-callback-from-firing`
      for the no-fire-after-shutdown assertion (invoke stale callback → no
      delivery).
- [x] Record cancellation & lifecycle finding(s) citing covering deftests.
      Done: 5 entries recorded (all `verified-correct`).

## Slice 7 — Failure path

- [x] Audit failure coverage
      (`scheduler-session-deliver-records-failed-status-on-prompt-submit-error-test`
      → `scheduler_handlers_test.clj`;
      `fail-schedule` in `scheduler_test.clj`).
      Done: handler test asserted `:failed` + `:delivery-phase` only;
      `fail-schedule` had NO dedicated pure test. `error-summary`,
      `created-session-id`-on-failure, dequeue, and the status guard were
      uncovered.
- [x] Ensure: delivery/creation failure records `:failed` with `error-summary`
      and `delivery-phase`; status guard `{:pending :queued :delivered}`; queue
      not wedged (subsequent drain still works). Add if missing.
      Done: added pure `fail-schedule-records-failure-detail-and-dequeues`
      (records `:delivery-phase`/`:error-summary`/`:created-session-id` + dequeues
      + terminal-status guard "schedule is not fail-able"); extended the handler
      failure test to assert `:error-summary` (`:message "boom"`) and the recorded
      `:created-session-id`. Queue-not-wedged is proven by the pure dequeue
      assertion (the failed id is removed from `:queue`, leaving drain free).
- [x] Record failure-path finding citing covering deftest.
      Done: 3 entries recorded (`verified-correct`).

## Slice 8 — Projections

- [x] Audit projection coverage (`scheduler_resolvers_test.clj`,
      `scheduler_background_jobs_test.clj`, psi-tool summary).
      Done: resolver test covered `:pending` only with a subset of attrs;
      background-jobs covered pending+queued projection + cancel routing; the
      rich attrs (`created-session-id`/`delivery-phase`/`error-summary`/
      `session-config-summary`) across `:delivered`/`:cancelled`/`:failed` were
      uncovered.
- [x] Ensure EQL `:psi.scheduler/*` attrs, psi-tool summary, and background-job
      projection stay coherent across statuses
      (`:pending`/`:queued`/`:delivered`/`:cancelled`/`:failed`). Add if missing.
      Done: added `scheduler-resolver-projects-rich-attrs-across-statuses` —
      seeds `:delivered`/`:cancelled` + a `:failed` session-kind and asserts the
      full `:psi.scheduler/*` attr set (incl. created-session-id, delivery-phase,
      error-summary, session-config-summary) projects coherently. psi-tool
      summary projection is exercised by Slice 5's list/create tests
      (`:psi-tool/scheduler :schedule`/`:schedules`).
- [x] Record projections finding citing covering deftests.
      Done: 3 entries recorded (`verified-correct`).

## Slice 9 — Defect handling (conditional)

- [x] For each `defect` recorded in `findings.md`: create
      `munera/open/NNN-slug` remediation task with `design.md` and a reproducing
      **failing** test that stays in that new task (do NOT commit it green here).
      Allocate NNN by the munera rule:
      `NNN = max(NNN over munera/open ∪ munera/closed) + 1` (scan **both**
      directories, never just one). Current max across both is 201 (this task),
      so the next remediation id is **202** (re-scan at creation time in case
      ids were added concurrently).
      Done: **no defects found** in Slices 1–8 — every finding is
      `verified-correct`. No remediation task created (conditional slice skipped).
- [x] Reference each remediation task `NNN-slug` from the corresponding
      `findings.md` entry.
      Done: n/a — no defects, no remediation refs.
- [x] If no defect found: confirm no remediation dir is created; all areas read
      `verified-correct`.
      Done: confirmed — `git diff --name-only` shows no new `munera/open/NNN-slug`
      dir; all 7 findings sections read `verified-correct`.

## Slice 10 — Close-out

- [x] Finalise `findings.md`: every Scope area has a status + covering-test
      citation (+ repro/task-ref for defects).
      Done: all 7 sections populated; 30 entries, all `verified-correct`.
- [x] `cljfmt` and `clj-kondo --lint` clean on all touched test files.
      Done: `clj-kondo` 0 errors / 0 warnings; `cljfmt check` "All source files
      formatted correctly" on all 7 touched test files.
- [x] `bb test` green (new verification tests pass against current behaviour;
      any defect repro lives only in its remediation task).
      Done: full scheduler suite **45 tests / 410 assertions / 0 fail / 0 error**
      (baseline was 35/338; +10 tests, +72 assertions all green).
- [x] Coherence check: no scheduler source/doc/behaviour modified; deliverable =
      green coverage + `findings.md`. Prove it with a touched-path allowlist via
      `git diff --stat <base>...HEAD`: the only changed paths permitted are
      (a) test files under
      `components/agent-session/test/psi/agent_session/` matching
      `scheduler_*` **or** `psi_tool_scheduler_test.clj` (new or extended
      verification tests — the psi-tool-surface file does not match the
      `scheduler_*` glob, so it is named explicitly; Slices 0/5 inventory and
      may extend it), and (b) files under
      `munera/open/201-verify-scheduler-execution/` (incl. `findings.md`) plus
      any newly created `munera/open/NNN-slug/` remediation dir from Slice 9.
      Any changed path under `components/agent-session/src/**` or
      `doc/scheduler.md` fails the gate.
      Done: `git diff --name-only 87140947b~1..HEAD` shows 7 scheduler test files
      (all matching `scheduler_*` or `psi_tool_scheduler_test.clj`) + 3 task-dir
      files only. **Zero** `components/agent-session/src/**` or `doc/scheduler.md`
      changes → gate PASSES.

## Plan/steps ambiguity follow-ups (2026-06-01)

- [x] Define an operational "sufficient coverage" criterion for the
      audit→cite-vs-add decision (used by Slices 2/3/4/6/7/8 and plan step 4 /
      "Reuse before adding"): state, per area, what an existing test must
      assert (the area's named state/outputs), whether it must drive via the
      time/timer seam, and what counts as "insufficient" → triggers a new test.
      Done: added "Sufficient-coverage criterion" section to plan.md and a
      pointer in the steps.md convention header.
- [x] Correct the audit-location pointers in steps.md to the real files:
      busy/drain-fifo + cancel-pending-and-queued → `scheduler_lifecycle_test.clj`;
      drain-queue-delivers-oldest + cancel-marks-pending-or-queued →
      `scheduler_dispatch_test.clj`; session-kind-fires / creates-top-level /
      records-failed → `scheduler_handlers_test.clj`. Add the missing file refs
      to Slice 4 and Slice 7.
      Done: verified all 7 deftest locations via grep; updated Slices 2/3/4/6/7
      pointers.
- [x] Slice 5 `:at` past/now: decide and state whether the test must assert the
      schedule *fires* (drive the delay-0 timer via the seam) or only that it is
      *created/accepted* without min-delay rejection; update the Slice 5 item to
      the chosen contract.
      Done: chose *fires* (drive the delay-0 timer via the seam, assert
      fired/delivered) — grounded in `resolve-fire-time!` delay-0 path; Slice 5
      item updated.
- [x] Slice 9: replace "alloc next NNN" with the explicit munera rule —
      `NNN = max(NNN over munera/open ∪ munera/closed) + 1` — and note the
      current next id (202) to avoid a colliding remediation-task id.
      Done: verified max across both dirs = 201 → next 202; Slice 9 item updated
      with the explicit scan-both rule.
- [x] Slice 10: specify the mechanism that proves "no scheduler source/doc/
      behaviour modified" — e.g. a touched-path allowlist (new test files +
      `findings.md` only) checked via `git diff --stat`, or equivalent — so the
      coherence gate is falsifiable.
      Done: Slice 10 close-out item now specifies a `git diff --stat` touched-path
      allowlist (scheduler test files + task dir + any Slice-9 remediation dir),
      failing on any `src/**` or `doc/scheduler.md` change.

## Plan/steps inconsistency follow-ups (2026-06-01)

- [x] Reconcile the slice↔Scope-area↔findings-section mapping. plan.md "Slice
      order" claims "Slices map to design Scope areas", but design.md's single
      "Live execution path" Scope area (and the one `findings.md` "Live execution
      path" section in the Slice 0 skeleton) is split across Slices 2/3/4
      (message / busy-drain / session). Either (a) correct the plan claim to a
      stated 3-slices→1-area mapping and make steps Slices 2/3/4 explicitly
      record into the single shared "Live execution path" findings section, or
      (b) split the design Scope area / findings skeleton to match the three
      slices — pick one and align plan.md + steps.md (+ design.md/findings
      skeleton if option b) so the mapping is 1:1 and the 7-section skeleton is
      not silently violated.
      Done: chose option (a). plan.md "Slice order" now states the deliberate
      3:1 split (Live execution path = Slices 2/3/4 → one shared findings
      section) and lists the remaining 1:1 mappings. steps.md Slices 2/3/4
      "Record …" items now explicitly write into the single shared "Live
      execution path" `findings.md` section; Slice 0 skeleton item states
      exactly 7 sections with that one section holding all three live entries.
      Design Scope/findings skeleton kept 7-section (no design.md change).
- [x] Fix the Slice 10 coherence-gate allowlist to include
      `psi_tool_scheduler_test.clj`. Slice 0 inventories and Slice 5 audits/may
      add tests to `psi_tool_scheduler_test.clj`, which the current
      `scheduler_*` allowlist glob excludes; broaden the Slice 10 allowed-path
      pattern (e.g. `psi_tool_scheduler_test.clj` ∪ `scheduler_*`) so a
      legitimately-changed psi-tool-surface test file does not fail the gate.
      Done: Slice 10 close-out allowlist now permits test files matching
      `scheduler_*` **or** `psi_tool_scheduler_test.clj` (named explicitly since
      it does not match the glob); `src/**` and `doc/scheduler.md` still fail.
