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
      Done: full scheduler suite **45 tests / 412 assertions / 0 fail / 0 error**
      (baseline was 35/338; +10 tests, +74 assertions all green). (Count updated
      from 410 → 412 in review pass 4 after the pass-2 `:at` named-bound
      follow-up added two assertions.)
- [x] Coherence check: no scheduler source/doc/behaviour modified; deliverable =
      green coverage + `findings.md`. The gate's **true invariant** is: **zero**
      changed paths under `components/agent-session/src/**` or `doc/scheduler.md`.
      Prove it with a touched-path allowlist via
      `git diff --stat <base>...HEAD`: the only changed paths permitted are
      (a) **test files** under
      `components/agent-session/test/**` — this covers the verification tests
      matching `scheduler_*` **or** `psi_tool_scheduler_test.clj`, **and** shared
      test-support files such as `test_support.clj` (added by the pass-1
      `capturing-delay-fn` extraction), all of which live under `test/`, not
      `src/**`/`doc/`; and (b) files under
      `munera/open/201-verify-scheduler-execution/` (incl. `findings.md`) plus
      any newly created `munera/open/NNN-slug/` remediation dir from Slice 9.
      Any changed path under `components/agent-session/src/**` or
      `doc/scheduler.md` fails the gate.
      Done: `git diff --name-only 87140947b~1..HEAD` shows 8 test files (7
      scheduler test files matching `scheduler_*`/`psi_tool_scheduler_test.clj`
      + `test_support.clj`, all under `components/agent-session/test/**`) + 3
      task-dir files only. **Zero** `components/agent-session/src/**` or
      `doc/scheduler.md` changes → gate PASSES.

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

## Implementation review follow-ups (2026-06-01)

- [x] Extract the duplicated capture-timer override idiom
      `(assoc ctx :scheduler-run-after-delay-fn (fn [_ _ f] (reset! cb* f) {:handle :captured}))`
      + external `cb*` atom (currently inlined in 4 files / 5 sites:
      `scheduler_end_to_end_test` ×2, `scheduler_timer_seam_test`,
      `scheduler_context_shutdown_test`, `psi_tool_scheduler_test`) into a shared
      `test-support` helper (e.g. `capturing-delay-fn` → `[override-fn cb*]`, or a
      `with-captured-timer` macro), and have the verification tests use it.
      Test-quality DRY only — no behaviour change; keep suite green + kondo clean.
      (Verification-only-scope note: this edits test files only, within the
      Slice-10 allowlist; does not touch `src/**` or `doc/scheduler.md`. If the
      task is treated as closed, raise it as a small standalone test-hygiene task
      instead.)
      Done: added `test-support/capturing-delay-fn` → `[override-fn cb*]` where
      `cb*` holds `{:delay-ms delay-ms :f f}` and `override-fn` returns
      `{:handle :captured}`. Migrated all 5 named sites to
      `[capture* callback*] (test-support/capturing-delay-fn)` +
      `:scheduler-run-after-delay-fn capture*`; callback invocation is now the
      uniform `((:f @callback*))` (psi-tool site already read `(:delay-ms @cb*)`
      / `((:f @cb*))`). The two extra-state override forms in
      `scheduler_timer_seam_test` (capture observed-delay; capture cancelled
      handle) are intentionally left — not the named idiom. clj-kondo 0/0,
      cljfmt clean; full scheduler suite still 45 tests / 410 assertions / 0
      failures (unchanged baseline at pass-1 time; later raised to 412 by the
      pass-2 `:at` named-bound follow-up). No `src/**` or `doc/scheduler.md`
      touched.

## Implementation review follow-ups — pass 2 (2026-06-01)

- [x] Tighten the `:at` bound-rejection assertions in
      `psi_tool_scheduler_test.clj` so each block asserts the *named* bound.
      The near-future (~L228) and far-future (~L243) `:at` rejection blocks
      assert only `(true? (:is-error result))` + `(= :error
      (:psi-tool/overall-status parsed))` — they do not verify *which* bound was
      hit, so they are assertion-indistinguishable and pass for any error
      (including a swapped/unrelated rejection). This under-asserts the
      sufficient-coverage criterion clause 1 and the `:at` *asymmetry* finding
      (the deliberate below-min vs exceeds-max distinction the finding records as
      verified-correct). Assert the specific surfaced error message per block —
      near-future → "below the minimum bound", far-future → "exceeds the maximum
      bound" — via `(get-in parsed [:psi-tool/error :message])` (matching
      `scheduler.clj:85`/`:89`). Test file only, within the Slice-10 allowlist;
      no `src/**`/`doc/scheduler.md` change. Keep the suite green + clj-kondo
      clean. (If the task is treated as closed, raise it as a small standalone
      test-hygiene task instead.)
      Done: both `:at` rejection blocks now assert the named bound via
      `(get-in parsed [:psi-tool/error :message])` — near-future →
      `"delay-ms is below the minimum bound"`, far-future →
      `"delay-ms exceeds the maximum bound"` (exact `scheduler.clj:85`/`:89`
      messages surfaced through `psi-tool-error-summary`'s `ex-message`). The
      blocks are now assertion-distinguishable (a swapped/unrelated rejection
      would fail). `psi_tool_scheduler_test` now 1 test / 109 assertions
      (was 107; +2), green; clj-kondo 0/0, cljfmt clean. Test file only — no
      `src/**` or `doc/scheduler.md` touched (within the Slice-10 allowlist).

## Implementation review follow-ups — pass 3 (2026-06-01)

- [x] Broaden the Slice-10 coherence-gate allowlist to admit shared
      test-support files. The gate currently permits only test files matching
      `scheduler_*` **or** `psi_tool_scheduler_test.clj` (+ task dir + Slice-9
      remediation dir), but the actual changeset includes
      `components/agent-session/test/psi/agent_session/test_support.clj` (added
      by the pass-1 `capturing-delay-fn` extraction), which matches neither
      pattern — so a literal application of the documented gate would FAIL on a
      legitimately-touched file even though the verification-only invariant holds
      (`test_support.clj` is under `test/`, not `src/**`/`doc/`). Update the
      Slice-10 close-out item: add `test_support.clj` to the named exceptions
      (or generalise the allowed-path rule to "test files under
      `components/agent-session/test/**`"), and re-state the gate's real
      invariant as "zero `components/agent-session/src/**` or `doc/scheduler.md`
      changes". steps.md doc edit only — no test/src/doc behaviour change. (If
      the task is treated as closed, raise it as a small standalone
      task-hygiene task instead.)
      Done: generalised the Slice-10 allowlist to **test files under
      `components/agent-session/test/**`** (covering `scheduler_*` /
      `psi_tool_scheduler_test.clj` **and** shared `test_support.clj`), and
      hoisted the gate's **true invariant** to the front of the close-out item:
      "zero `components/agent-session/src/**` or `doc/scheduler.md` changes".
      Updated the "Done:" note to the actual 8-test-file changeset
      (`git diff --name-only 87140947b~1..HEAD` = 7 scheduler test files +
      `test_support.clj`, all under `test/`, + 3 task-dir files; zero `src/**` /
      `doc/scheduler.md`). steps.md doc edit only — no test/src/doc change; gate
      now passes on a literal application against the real changeset.

## Implementation review follow-ups — pass 4 (2026-06-01)

- [x] Correct the stale assertion count in the deliverable summaries from
      **410** to **412** to match the green runtime. The pass-2 `:at`
      named-bound follow-up added two assertions (psi-tool test 107 → 109,
      committed), but the aggregate "45 tests / 410 assertions" claim was never
      updated — runtime now reports **45 tests / 412 assertions** (re-run
      confirms; `410 − 107 + 109 = 412`). Update `findings.md` Outcome
      ("45 tests / 410 assertions" → 412) and the `steps.md` / `implementation.md`
      close-out + review-note counts that cite 410, so the structured deliverable
      matches `runtime ≡ truth`. Doc-accuracy only — no test/src/doc behaviour
      change; within the verification-only scope (zero `components/agent-session/
      src/**` or `doc/scheduler.md`). (If the task is treated as closed, raise it
      as a small standalone task-hygiene fix instead.)
      Done: confirmed runtime truth — scheduler suite reports **45 tests / 412
      assertions** (verified via focused `clojure -M:test` on the 13 scheduler
      namespaces; full `bb test` green for scheduler). Corrected the deliverable
      counts 410 → 412: `findings.md` Outcome; `steps.md` Slice-10 close-out
      (+74 delta); `implementation.md` Slice-10 close-out + pass-2 review re-run
      note (genuinely stale — pass-2 *added* the +2). The pass-1 review notes
      (410 was accurate at pass-1 time, pre-pass-2) are left intact with a
      forward-pointer parenthetical to 412 to avoid misleading the reader. The
      pass-4 flag note and this step's own 410→412 description are intentionally
      preserved (they document the fix). Doc-accuracy only — no test/src/doc
      change; verification-only scope held (zero `src/**` / `doc/scheduler.md`).
      Note: scheduler namespaces run *in isolation together* surface 4 ordering-
      dependent failures that do NOT occur under the canonical full `bb test`
      run (each ns green standalone) — a pre-existing cross-ns test-isolation
      artifact, out of scope for this doc-accuracy step.

## Test review follow-ups — pass 6 (task-test-review, 2026-06-01)

- [x] Replace the `with-redefs` AI-boundary stub in
      `scheduler_end_to_end_test/scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session-test`
      (line ~109) with the **injectable ctx seam**. The
      `execute-prepared-request!` boundary it stubs is already an injectable ctx
      dependency (`dispatch_effects.clj:154` → `(:execute-prepared-request-fn
      ctx)`), and `test_support/make-session-ctx` (~L246) already provides a
      default stub via that seam. Per the test-review skill
      (`infra_deps → injectable ∧ ¬stub`) and the plan's "build live tests on
      `make-session-ctx`'s already-wired seams" mitigation, pass
      `:execute-prepared-request-fn` through `safe-context-opts`/the ctx (or
      build the test on `make-session-ctx`) and drop the `with-redefs`. Keep the
      same shaped execution-result so the round trip still creates the top-level
      session + records `:created-session-id`/`:delivery-phase`. Test file only,
      within the Slice-10 allowlist (zero `src/**`/`doc/scheduler.md`); keep the
      suite green + clj-kondo/cljfmt clean. (If the task is treated as closed,
      raise it as a small standalone test-hygiene task instead.)
      Done: dropped the `with-redefs` of
      `psi.turn-runtime.core/execute-prepared-request!` and now bind the same
      shaped stub to a local `execute-prepared-request-fn`, threaded onto the
      live ctx via the injectable seam alongside the timer seam
      (`(assoc ctx :scheduler-run-after-delay-fn capture*
      :execute-prepared-request-fn execute-prepared-request-fn)`). The effect
      reads the seam from ctx (`dispatch_effects.clj:154`), so the round trip is
      unchanged: session-kind fires → fresh top-level session created →
      `:created-session-id`/`:delivery-phase :prompt-submit` recorded. Removed
      the now-unused `[psi.turn-runtime.core]` require (it existed only as the
      `with-redefs` target). `scheduler_end_to_end_test` green
      (3 tests / 20 assertions); related handler/lifecycle/dispatch/shutdown
      suites green (19 tests / 104 assertions). clj-kondo 0/0, cljfmt clean.
      Test file only — zero `components/agent-session/src/**` or
      `doc/scheduler.md` (within the Slice-10 allowlist).

## Test review follow-ups — pass 7 (task-test-review, 2026-06-01)

- [x] Remove the `with-redefs` boundary-stub from the Slice-7 failure-path
      deliverable test
      `scheduler_handlers_test/scheduler-session-deliver-records-failed-status-on-prompt-submit-error-test`
      (~L337). It forces the prompt-submit failure by redefining
      `dispatch/dispatch!` to throw on `:session/submit-synthetic-user-prompt`
      — a stub of the dispatch infra dep, the same class the test-review skill
      flags (`infra_deps → injectable ∧ ¬stub`) and that pass-6 removed from the
      e2e session-kind test. (The `with-redefs` predates 201 — `59e338cb9` — but
      201 `d9f2ca032` adopted it as its cited failure-path deliverable by adding
      the `:error-summary`/`:created-session-id` assertions.) Unlike the e2e
      path, the `:submitted? false` guard fires on the prompt-submit *dispatch
      result* before `:execute-prepared-request-fn` runs, so there is no
      equally-clean existing ctx seam: either (a) introduce a small injectable
      seam for the synthetic-prompt-submit result (ctx-level fn returning
      `{:submitted? false}`), or (b) drop the handler test as redundant and rely
      on the **pure** `scheduler_test/fail-schedule-records-failure-detail-and-dequeues`
      (which already covers `:failed` + `:delivery-phase` + `:error-summary` +
      `:created-session-id` + dequeue without any stub). Option (a) preserves
      the live failure-path round trip; option (b) is verification-only-scope-safe
      (no `src/**` change). Keep the suite green + clj-kondo/cljfmt clean. (If the
      task is treated as closed, raise it as a small standalone test-hygiene task;
      note option (a) would touch `src/**` and so falls outside this
      verification-only task's Slice-10 allowlist — option (b) does not.)
      Done: chose a **third, scope-safe path that preserves the live failure
      round trip** without an infra var-stub and without touching `src/**`:
      re-register the `:session/submit-synthetic-user-prompt` handler in the
      kernel handler registry (`kernel/register-handler!`) to return
      `{:submitted? false}`, driving the *real* `:scheduler/deliver` catch
      branch through the *real* dispatch pipeline. The kernel registry is the
      project's own dispatch seam (the same one `with-registered-handlers`
      already uses to install the real handlers), so this is injection-over-redef
      (`infra_deps → injectable ∧ ¬stub`) rather than the rejected option (a)
      ctx-seam-in-`src` or option (b) test-deletion. Dropped the
      `with-redefs [dispatch/dispatch! …]` stub and the now-orphan
      `[psi.agent-session.dispatch :as dispatch]` require. The catch branch now
      throws its own `"scheduled session prompt submission failed"` ex-info with
      the **real** `:created-session-id` (a genuine top-level session is created
      before the prompt-submit fails) and `:delivery-phase :prompt-submit`, so
      the assertions still verify `:failed` + `:delivery-phase` + non-nil
      `:error-summary`/`:created-session-id`; the error-message assertion changed
      from the stub's `"boom"` to the real surfaced message. Assertion count
      unchanged (6 `is`), so the aggregate stays 45 tests / 412 assertions.
      `scheduler_handlers_test` green (9 tests / 51 assertions); full `bb test`
      green. clj-kondo 0/0, cljfmt clean. Test file only — zero
      `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
      held).

## Test review follow-ups — pass 8 (task-test-review, 2026-06-01)

- [x] Resolve the infra-boundary `with-redefs` stub in the **cited** busy-drain
      covering test
      `scheduler_lifecycle_test/busy-session-fire-queues-then-idle-drains-fifo-test`
      (L51 & L101: `(with-redefs [psi.turn-runtime.core/execute-prepared-request! …])`).
      `findings.md` (Live execution path, L87) cites this test as an authoritative
      covering test for the busy-queue + drain-on-idle acceptance area, but it
      stubs the AI-execution infra boundary — the same `infra_deps → ¬stub` class
      pass-6 removed from the e2e session-kind test via the already-wired
      `:execute-prepared-request-fn` ctx seam. The area is already covered
      stub-free by `scheduler_dispatch_test` (`scheduler-fired-queues-while-session-busy`
      + `scheduler-drain-queue-delivers-oldest-queued-schedule`, 0 `with-redefs`).
      Pick one (test-file/findings-only, within the Slice-10 allowlist — zero
      `src/**`/`doc/scheduler.md`): (a) migrate
      `busy-session-fire-queues-then-idle-drains-fifo-test` off `with-redefs`
      onto `:execute-prepared-request-fn` (mirroring pass-6) and keep the
      citation; or (b) drop the lifecycle citation from the L87 busy-drain
      finding and rest the area on the co-cited stub-free `scheduler_dispatch_test`
      deftests. Keep the suite green + clj-kondo/cljfmt clean. (If the task is
      treated as closed, raise it as a small standalone test-hygiene task instead.)
      Done: chose **option (a)** — migrated
      `busy-session-fire-queues-then-idle-drains-fifo-test` off the
      `with-redefs [psi.turn-runtime.core/execute-prepared-request! …]` stub onto
      the injectable `:execute-prepared-request-fn` ctx seam (mirroring pass-6's
      e2e session-kind migration): bind the same shaped execution-result stub to
      a local fn and thread it onto the ctx via
      `(assoc ctx :execute-prepared-request-fn …)`. The effect reads this seam
      from ctx (`dispatch_effects.clj:154`), so the busy-fire-queues →
      idle-drain-FIFO round trip is unchanged (both `:scheduler/fired` dispatches
      queue, drain delivers oldest-by-fire-at, scheduled-message timestamp from
      the runtime scheduler time source). The `findings.md` Live-execution-path
      busy-drain citation is **kept** (still an authoritative covering test, now
      stub-free). The first test in the file
      (`scheduled-deliver-runs-canonical-prompt-lifecycle-test`) still uses its
      own `with-redefs` and is **out of scope** for pass 8 (which names only the
      busy-drain test), so the `[psi.turn-runtime.core]` require stays.
      `scheduler-lifecycle-test` green (3 tests / 26 assertions); related
      dispatch/e2e/handlers suites green (17 tests / 91 assertions). clj-kondo
      0/0, cljfmt clean. Test file only — zero
      `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
      held; assertion count unchanged, aggregate stays 45 tests / 412 assertions).

## Test review follow-ups — pass 9 (task-test-review, 2026-06-01)

- [x] Re-audit all 201 verification-test deliverables + cited covering tests
      against the task-test-review skill (well-formed, behaviour coverage,
      infra-deps injectable not stubbed). No new actionable issues — the
      passes 6/7/8 `with-redefs`-removal follow-ups are all closed; the only
      surviving infra `with-redefs`/`Thread/sleep` sites are pre-existing
      baseline tests (`scheduler_effects_test/scheduler-start-and-cancel-timer-effects-test`,
      `scheduler_lifecycle_test/scheduled-deliver-runs-canonical-prompt-lifecycle-test`)
      that are NOT cited as covering tests for any acceptance area, so they are
      out of 201's verification-test scope. Suite green (45 tests / 412
      assertions); clj-kondo 0/0; cljfmt clean. Review chain converged →
      REVIEW_COMPLETE.

## Test review follow-ups — test-shaper pass (2026-06-01)

- [x] Fix misleading shared setup in
      `scheduler-test/fail-schedule-records-failure-detail-and-dequeues-test`.
      The top-level `let` builds `s0` (`:session`-kind) → `s1 = fire-schedule
      (s0, idle)` with comment "session-kind fire delivers (action :deliver)",
      but the first `testing` block uses fresh `q0`/`q1` and never touches
      `s0`/`s1`; `s1` is used only by the second (terminal fail-guard) block,
      relying on the non-obvious fact that pure `fire-schedule` leaves status
      `:pending` (it returns the `:deliver` *action* without mutating status).
      Scope each concern to its own minimal setup (or correct the comment to
      state session-kind `fire-schedule` leaves status `:pending` and move
      `s0`/`s1` into the guard block), removing the dead binding from the first
      block's scope. Test-file only (Slice-10 allowlist); keep suite green +
      clj-kondo/cljfmt clean. If 201 is treated as closed, raise as a small
      standalone test-hygiene task.
      Done: removed the top-level `let`; each `testing` block now owns its
      minimal setup. The `:queued`-dequeue block keeps its self-contained
      `q0`/`q1`; the terminal fail-guard block now builds its own `s0`/`s1`
      locally with a corrected comment stating that pure session-kind
      `fire-schedule` returns the `:deliver` action and **leaves status
      `:pending`** (hence still cancellable). Dead cross-block binding removed.
      Assertion shape unchanged. `scheduler-test` green; clj-kondo 0/0, cljfmt
      clean. Test file only — zero `components/agent-session/src/**` or
      `doc/scheduler.md` (Slice-10 allowlist held).

- [x] Add explicit `:kind :message` to the three live `:scheduler/create`
      dispatches that currently omit it and rely on the handler default
      (`dispatch_handlers/scheduler.clj:123` `(or kind :message)`):
      `scheduler-timer-seam-test/scheduler-start-timer-uses-injected-time-source-and-delay-runner-test`
      (both the main and the cancel `testing` blocks) and
      `scheduler-timer-seam-test/scheduler-cancelled-default-delay-thread-exits-without-uncaught-interrupted-exception-test`.
      Brings their data shape in line with every other 201 live create and makes
      the kind-under-test local. No behaviour change (default already resolves to
      `:message`). Test-file only; keep suite green + clj-kondo/cljfmt clean.
      Done: added `:kind :message` (after `:schedule-id`) to all three
      `:scheduler/create` dispatch payloads in `scheduler_timer_seam_test.clj`
      — the main + cancel blocks of
      `scheduler-start-timer-uses-injected-time-source-and-delay-runner-test`
      and `scheduler-cancelled-default-delay-thread-exits-without-uncaught-interrupted-exception-test`.
      No behaviour change (default already resolved to `:message`); the
      kind-under-test is now explicit, matching every other 201 live create.
      `scheduler-timer-seam-test` green; clj-kondo 0/0, cljfmt clean. Test file
      only — zero `components/agent-session/src/**` or `doc/scheduler.md`
      (Slice-10 allowlist held).

## Test review follow-ups — test-shaper pass 2 (2026-06-01)

- [x] Split the megatest
      `psi_tool_scheduler_test/psi-tool-scheduler-create-list-cancel-test`
      (one deftest, 17 `testing` blocks, 109 assertions) into focused deftests
      by concern, so each distinct behaviour has its own name + minimal ctx
      setup and failure localisation is restored. Suggested split:
      `…-create-list-cancel` (happy path: create pending → list → cancel only),
      `…-time-source-required` (missing/invalid scheduler-time-source → error),
      `…-bounds-and-cap` (below-min `delay-ms` rejected + 51st-pending cap),
      `…-session-id-resolution` (requires invoking/explicit session-id +
      explicit-session-id report path), `…-kind-validation` (`session` requires
      session-config / `message` rejects session-config / unsupported
      session-config keys rejected), and `…-at-resolution-matrix` (past `:at`
      fires immediately via the seam / near-future `<min` → below-minimum bound
      / above-max → exceeds-maximum bound + the absolute-instant delay
      calculation). Keep assertions and their messages intact (aggregate count
      unchanged); update the `findings.md` psi-tool-surface citations to point at
      the precise new deftest(s). Test-file-only (Slice-10 allowlist — zero
      `components/agent-session/src/**` or `doc/scheduler.md`); keep the suite
      green + clj-kondo/cljfmt clean. If 201 is treated as closed, raise it as a
      small standalone test-hygiene task instead.
      Done: split into the 6 suggested focused deftests —
      `psi-tool-scheduler-create-list-cancel` (happy path),
      `…-time-source-required`, `…-bounds-and-cap`, `…-session-id-resolution`,
      `…-kind-validation`, `…-at-resolution-matrix` — each with its own minimal
      ctx setup. The previously top-level "absolute instant" `testing` block
      (written outside the megatest deftest) was folded into the `:at` matrix
      deftest by concern. Assertions + messages intact → aggregate **unchanged
      at 412 assertions**; scheduler-suite deftest count 45 → **50** (psi-tool
      1 → 6). `findings.md` psi-tool-surface citations updated to the precise new
      deftests (Outcome 45 → 50; inventory + "Extended in place" note). Verified:
      `--focus psi.agent-session.psi-tool-scheduler-test` = 6 tests / 109
      assertions / 0 failures; full `bb test` green; clj-kondo 0/0, cljfmt clean.
      Test file + task-dir docs only — zero `components/agent-session/src/**` or
      `doc/scheduler.md` (Slice-10 allowlist held).

## Test review follow-ups — test-shaper pass 3 (2026-06-01)

- [x] Consolidate the duplicated `create-session-context` test fixture. The
      identical (modulo the `:persist? false` lifecycle/effects variant) helper
      is copy-pasted across 9 scheduler test ns
      (`scheduler_end_to_end_test`, `scheduler_timer_seam_test`,
      `scheduler_context_shutdown_test`, `scheduler_resolvers_test`,
      `scheduler_lifecycle_test`, `scheduler_effects_test`,
      `scheduler_background_jobs_test`, `scheduler_cancel_job_test`,
      `scheduler_tools_test`). Extract one shared helper into
      `psi.agent-session.test-support` (e.g. `make-session-context` taking opts,
      with the two callers that want `:persist? false` passing it explicitly or
      via a thin convenience), then delete the 9 local copies and update call
      sites. test-shaper `consistent(fixtures) ∧ helpers_that_compress(ceremony)`;
      precedent already set by the `capturing-delay-fn` extraction (steps.md
      pass-2 line ~377). Test-file/`test_support`-only (Slice-10 allowlist — zero
      `components/agent-session/src/**` or `doc/scheduler.md`); keep suite green +
      clj-kondo/cljfmt clean. If 201 is closed, raise as a standalone
      test-hygiene task.
      Done: replaced the raw 6-segment `(swap! (:state* ctx) assoc-in
      [:agent-session :sessions session-id :data :scheduler] {:schedules …
      :queue []})` write in
      `scheduler-resolver-projects-rich-attrs-across-statuses-test` with
      `(swap! (:state* ctx) (ss/session-update session-id (fn [sd] (assoc sd
      :scheduler {:schedules schedules :queue []}))))`, matching the
      `ss/session-update` busy-flag-seed idiom in `scheduler_end_to_end_test`.
      Behaviour-preserving: `ss/session-update sid f` ≡ `update-in state
      (session-data-path sid) f` with `session-data-path sid = [:agent-session
      :sessions sid :data]`, so the targeted path is identical.
      **Correction to the step's premise:** `ss` was **not** already required in
      `scheduler_resolvers_test` (the ns required only `session` +
      `test-support`) — added `[psi.session-state.state :as ss]` (the same
      require e2e uses; the dep is already on the agent-session test classpath).
      No deftest renamed → `findings.md` citations unchanged; no assertions
      added/removed → aggregate stays 412. `scheduler-resolvers-test` green
      (2 tests / 21 assertions); full `bb test` green; clj-kondo 0/0; cljfmt
      "All source files formatted correctly". Test file only —
      `git diff --name-only` = the single `scheduler_resolvers_test.clj` path;
      zero `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10
      allowlist held).
      Done: **reused the existing `test-support/create-test-session`** instead of
      adding a new redundant helper (`λbuild: ∃lib → use(lib)`). Discovery: all 9
      local `create-session-context` copies are behaviourally identical to each
      other **and** to `test-support/create-test-session` — `safe-context-opts`
      already defaults `:persist? false`, so the 7 "persist" copies, the 2
      `(assoc opts :persist? false)` lifecycle/effects variants, and
      `create-test-session` all resolve to the same persist-false context (only
      the no-arg default differs cosmetically: `create-test-session` → `{:persist?
      false}`, the locals → `{}`, both persist-false via `safe-context-opts`).
      Deleted all 9 local defns; rewrote every call site to
      `test-support/create-test-session` (opts pass through unchanged). Removed
      the now-unused `[psi.agent-session.core :as session]` require from
      `scheduler_tools_test` (its only `session/` use was the deleted defn; the
      other 8 files still use `session/` elsewhere so their requires stay).
      `findings.md` psi-tool/Live citations unchanged (no deftest renamed).
      clj-kondo 0/0 across all touched files; cljfmt reformatted the realigned
      `create-test-session` opts indentation in lifecycle/timer-seam then clean;
      full `bb test` green; aggregate count unchanged (no assertions added).
      Test/`test_support`-only — zero `components/agent-session/src/**` or
      `doc/scheduler.md` (Slice-10 allowlist held).

- [x] Add explicit `:kind :message` to the `:scheduler/create` dispatch in
      `scheduler-context-shutdown-test/shutdown-context-clears-scheduler-timers-test`
      (currently omits it and relies on the handler default
      `(or kind :message)`). Brings its data shape in line with every other 201
      live create — the same alignment pass-1 applied to
      `scheduler_timer_seam_test` but which missed this pre-existing deftest in a
      touched namespace. No behaviour change (default already resolves to
      `:message`). test-shaper `consistent(data_shapes)`. Test-file only
      (Slice-10 allowlist); keep suite green + clj-kondo/cljfmt clean.
      Done: added `:kind :message` (after `:schedule-id`) to the first
      `:scheduler/create` dispatch in
      `shutdown-context-clears-scheduler-timers-test` (the second create in the
      file already declared it). Data shape now matches every other 201 live
      create; no behaviour change (default already resolved to `:message`).
      clj-kondo 0/0, cljfmt clean; `bb test` green. Test file only — zero
      `components/agent-session/src/**` or `doc/scheduler.md`.

- [x] Relabel the misleading `testing` block in
      `scheduler-test/fire-schedule-test`: "idle session delivers immediately"
      asserts the `:deliver` *action* with status still `:pending` (pure
      `fire-schedule` returns the action without mutating status). Reword to
      state it returns the `:deliver` action and leaves the schedule `:pending`
      (same `:pending`-after-fire confusion pass-1 corrected for `fail-schedule`).
      Assertions unchanged. test-shaper `meaningful_failures` / label accuracy
      (lowest priority — cosmetic). Test-file only (Slice-10 allowlist); keep
      suite green + clj-kondo/cljfmt clean.
      Done: relabelled the block to "idle session: returns the :deliver action
      and leaves the schedule :pending" + a one-line comment stating pure
      `fire-schedule` returns the action without mutating status. Assertions
      unchanged. clj-kondo 0/0, cljfmt clean; `bb test` green. Test file only —
      zero `components/agent-session/src/**` or `doc/scheduler.md`.

## Test review follow-ups — test-shaper pass 4 (2026-06-01)

- [x] Complete pass-3's fixture consolidation by migrating the **holdout**
      `psi_tool_scheduler_test.clj` (L11) off its local `create-session-context`
      defn onto the shared `test-support/create-test-session`. pass-3
      consolidated 9 scheduler test ns onto `create-test-session` but did not
      name `psi_tool_scheduler_test` (itself a 201-touched file — pass-2 split
      its megatest), leaving the scheduler suite's fixture split across two
      behaviourally-identical helpers. The local copy is equivalent
      (`safe-context-opts` already defaults `:persist? false`, so its redundant
      `(assoc opts :persist? false)` resolves to the same persist-false context).
      Delete the local `create-session-context` defn; rewrite its call sites
      (all 6 deftests) to `test-support/create-test-session` (opts pass through
      unchanged — `{:scheduler-time-source …}` / no-arg). Remove the now-unused
      `[psi.agent-session.core :as session]` require **only if** `session/` is
      no longer referenced elsewhere in the file (it is still used for
      `session/dispatch-in!`/`session/query-in` in this ns, so the require
      likely stays — verify before removing). No deftest renamed → `findings.md`
      psi-tool-surface citations unchanged. test-shaper `consistent(fixtures) ∧
      helpers_that_compress(ceremony)`. Test-file-only (Slice-10 allowlist —
      zero `components/agent-session/src/**` or `doc/scheduler.md`); keep the
      suite green + clj-kondo/cljfmt clean. The project-wide
      `create-session-context` idiom in ~40 non-scheduler ns is out of 201
      scope. If 201 is treated as closed, raise it as a small standalone
      test-hygiene task instead.
      Done: deleted the local `create-session-context` defn and rewrote all 13
      call sites (across the 6 deftests) to `test-support/create-test-session`,
      opts passing through unchanged (`{:scheduler-time-source …}` / no-arg).
      Correction to the step's hedge: `[psi.agent-session.core :as session]`
      was used **only** inside the deleted defn (`session/create-context` +
      `session/new-session-in!`); this ns has **no** `session/dispatch-in!`/
      `session/query-in` references, so the require became unused and was
      removed (clj-kondo confirms 0/0 — an unused require would warn). The
      no-arg `create-session-context` → `{}` and `create-test-session` no-arg →
      `{:persist? false}` resolve to the same persist-false context via
      `safe-context-opts`, so behaviour is unchanged. No deftest renamed →
      `findings.md` psi-tool-surface citations unchanged. Verified:
      `--focus psi.agent-session.psi-tool-scheduler-test` = **6 tests / 109
      assertions / 0 failures** (aggregate unchanged at 50 tests / 412
      assertions); full `bb test` green; clj-kondo 0/0; cljfmt clean. Test file
      only — `git diff --name-only` = the single
      `psi_tool_scheduler_test.clj` path; zero `components/agent-session/src/**`
      or `doc/scheduler.md` (Slice-10 allowlist held). The scheduler suite's
      fixture is now fully consolidated on `test-support/create-test-session`
      (no remaining `create-session-context` copies in scheduler test ns).

## Test review follow-ups — test-shaper pass 5 (2026-06-01)

- [x] Add explicit `:kind :message` to the `:scheduler/create` payloads that
      still omit it in `scheduler_lifecycle_test.clj` and
      `scheduler_dispatch_test.clj`, completing the data-shape alignment that
      test-shaper passes 1 & 3 applied only to `scheduler_timer_seam_test` /
      `scheduler_context_shutdown_test`. Sites:
      `scheduler_lifecycle_test/scheduled-deliver-runs-canonical-prompt-lifecycle-test`
      (~L55); `…/busy-session-fire-queues-then-idle-drains-fifo-test` (~L116, the
      `findings.md`-cited busy-drain covering test);
      `…/cancel-pending-and-queued-schedules-test` (~L152 & ~L167, the cited
      cancel covering test); `scheduler_dispatch_test/scheduler-create-stores-schedule-and-starts-timer-test`
      (~L23); and the `schedule` helper (~L9) used by the dispatch deftests.
      Makes the kind-under-test local + matches every other 201 live create
      (`consistent(data_shapes)`); no behaviour change (handler default
      `(or kind :message)` already resolves `:message`). Test-file only
      (Slice-10 allowlist — zero `components/agent-session/src/**` /
      `doc/scheduler.md`); keep suite green + clj-kondo/cljfmt clean. If 201 is
      treated as closed, raise as a small standalone test-hygiene task.
      Done: added `:kind :message` to all 6 named sites — `scheduler_lifecycle_test`
      ×4 (`scheduled-deliver-runs-canonical-prompt-lifecycle-test` ~L55;
      `busy-session-fire-queues-then-idle-drains-fifo-test` ~L116;
      `cancel-pending-and-queued-schedules-test` ~L152 & ~L167) and
      `scheduler_dispatch_test` ×2 (the `schedule` helper map ~L9 — the stored
      schedule shape used by the dispatch deftests; and the
      `scheduler-create-stores-schedule-and-starts-timer-test`
      `:scheduler/create` payload ~L23). The kind-under-test is now explicit,
      matching every other 201 live create. No behaviour change (handler default
      `(or kind :message)` already resolved `:message`); no assertions
      added/removed by this item. clj-kondo 0/0, cljfmt/clj-paren-repair clean
      on both files; full `bb test` green. Test files only — zero
      `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
      held).

- [x] Remove the duplicated assertion in
      `scheduler_dispatch_test/scheduler-fired-queues-while-session-busy-test`
      (~L65–66): `(is (= :queued (:status stored)))` appears **twice** verbatim.
      Drop one copy (redundant — both assert the same thing, no extra signal;
      `economical / minimal(redundant)`). Note the aggregate assertion count
      drops by 1 after the edit — recompute and update any deliverable count
      citations (`findings.md` / `steps.md` / `implementation.md`) that quote the
      total. Test-file only (Slice-10 allowlist); keep suite green +
      clj-kondo/cljfmt clean.
      Done: dropped the second verbatim `(is (= :queued (:status stored)))` in
      `scheduler-fired-queues-while-session-busy-test`; the remaining single copy
      plus the queue-contents assertion preserve all signal. Recomputed the
      aggregate: focused kaocha run of `scheduler-dispatch-test` now reports
      **5 tests / 19 assertions** (was 20; −1), so the scheduler-suite
      deliverable total is **50 tests / 411 assertions** (was 412; the `:kind`
      alignment item adds no assertions). Updated the current deliverable count
      citation in `findings.md` Outcome (412 → 411, noting the dropped
      duplicate). Historical per-pass `412` Done-notes left intact as the record
      of state at their pass time (matching the pass-4 precedent of preserving
      historical counts). clj-kondo 0/0, cljfmt clean; full `bb test` green.
      Test/task-doc files only — zero `components/agent-session/src/**` or
      `doc/scheduler.md` (Slice-10 allowlist held).

## Test review follow-ups — test-shaper pass 6 (2026-06-01)

- [x] Dedupe the journal-scan idiom (`consistent(test_abstractions)` /
      `economical`). The "find the scheduled user message in the journal" block
      `(some->> journal (keep #(get-in % [:data :message])) (some (fn [m] (when (and (= "user" (:role m)) (= :scheduled (:source m)) (= "<id>" (:schedule-id m))) m))))`
      is repeated verbatim at `scheduler_end_to_end_test` L26 + L70 and
      `scheduler_dispatch_test` L85 — the exact ceremony
      `scheduler_lifecycle_test` already compresses via its `journal-messages` /
      `scheduled-user-messages` helpers. Lift a shared
      `scheduled-message-by-id` helper into `test-support` (taking ctx,
      session-id, schedule-id → the matching scheduled user message), reuse it
      at all three sites (and from the lifecycle helpers where it fits), so the
      suite has **one** journal-scan abstraction, not a per-ns mix. At minimum
      dedupe the two copies inside `scheduler_end_to_end_test`.
      `helpers_that_compress(ceremony) ∧ ¬helpers_that_hide(intent)`. Distinct
      from pass-3's `create-session-context` consolidation (context builder vs
      journal-scan assertion helper). Test-file/`test_support`-only (Slice-10
      allowlist — zero `components/agent-session/src/**` / `doc/scheduler.md`);
      keep suite green + clj-kondo/cljfmt clean; no deftest renamed →
      `findings.md` citations unchanged. If 201 is closed, raise as a standalone
      test-hygiene task.
      Done: lifted `test-support/scheduled-message-by-id` (ctx, session-id,
      schedule-id → the matching `"user"`-role message with `:source :scheduled`
      + `:schedule-id`), reusing the existing `ss/get-state-value-in` +
      `ss/state-path :journal` journal source. Replaced all **three** verbatim
      inline copies — `scheduler_end_to_end_test` ×2 (the
      `scheduler-fired-end-to-end-delivers-when-idle-test` "sch-1" scan and the
      message-kind-seam "sch-msg" scan) and `scheduler_dispatch_test` ×1
      (`scheduler-deliver-submits-canonical-prompt-lifecycle-test` "sch-1") —
      with `(test-support/scheduled-message-by-id …)`; the surrounding
      `(is (some? scheduled-msg))` assertions are preserved unchanged. The
      `scheduler_lifecycle_test` `journal-messages`/`scheduled-user-messages`
      helpers are deliberately **left as-is**: they read a *different* journal
      source (`persist/all-entries-in`, persistence-backed) and filter only on
      `:schedule-id` presence (not `:source :scheduled`), so they are not the
      same abstraction and do not cleanly fold into the new state-journal
      helper. No deftest renamed → `findings.md` citations unchanged; no
      assertions added/removed → aggregate stays **50 tests / 411 assertions**.
      Focused run of the three touched live nss = 11 tests / 65 assertions / 0
      failures; full `bb test` green. clj-kondo 0/0, cljfmt clean. Test/
      `test_support`-only — zero `components/agent-session/src/**` or
      `doc/scheduler.md` (Slice-10 allowlist held).

- [x] Replace wall-clock `Instant/now` in execution-result stubs with a fixed
      instant (`deterministic(tests)` — control(time)). The stubbed
      assistant-message `:timestamp` is `(java.time.Instant/now)` at
      `scheduler_end_to_end_test` L111 (session-kind seam) and
      `scheduler_lifecycle_test` L51 + L106 — real wall-clock inside otherwise
      fully time-seamed tests (every other instant uses the injected
      `fixed-scheduler-time-source`). Low-priority (no assertion reads the
      assistant timestamp today, so not flaky), but it breaks
      `control(time(tests))` and is a latent footgun. Replace each with a fixed
      `(java.time.Instant/parse …)` literal consistent with the test's `now`,
      matching the surrounding time-control discipline. Test-file-only (Slice-10
      allowlist — zero `components/agent-session/src/**` / `doc/scheduler.md`);
      keep suite green + clj-kondo/cljfmt clean; no behaviour/assertion change.
      If 201 is closed, raise as a standalone test-hygiene task.
      Done: replaced all three wall-clock `(java.time.Instant/now)`
      assistant-message timestamps with fixed instants already in scope, derived
      from each test's own time-controlled bindings (no new literals needed):
      `scheduler_end_to_end_test` session-kind seam → `(.plusMillis now 5000)`
      (the test's fire instant, consistent with its `:fire-at`);
      `scheduler_lifecycle_test/scheduled-deliver-runs-canonical-prompt-lifecycle-test`
      → `delivered-at` (the test's `fixed-scheduler-time-source` instant);
      `scheduler_lifecycle_test/busy-session-fire-queues-then-idle-drains-fifo-test`
      → `delivered-at-1` (the first drain's scheduler-clock instant). No
      assertion reads the assistant timestamp, so behaviour + the aggregate
      (50 tests / 411 assertions) are unchanged; the tests are now fully
      wall-clock-free in the time-seamed paths. Focused run of the three touched
      live nss = 11 tests / 65 assertions / 0 failures; full `bb test` green.
      clj-kondo 0/0, cljfmt clean. Test-file-only — zero
      `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
      held).

## Test review follow-ups — pass 10 (task-test-review, 2026-06-01)

- [x] Migrate the drain phase of
      `scheduler_lifecycle_test/busy-session-fire-queues-then-idle-drains-fifo-test`
      (the `findings.md`-cited busy queue + drain-on-idle covering test) off the
      local `invoke-scheduler-handler` + `apply-root-state-update!` helpers (which
      invoke the `:scheduler/drain-queue` handler `:fn` directly, bypassing the
      dispatch pipeline/interceptors — L140-141, L149-150) onto the **real**
      `dispatch-in! :scheduler/drain-queue` path, so the busy→fire→queue→idle→drain
      sequence is driven end-to-end through real dispatch (design "Drain-on-idle
      trigger" mechanic: "dispatches `:scheduler/drain-queue` **directly**"; plan
      sufficient-coverage clause 2 — drive the real path for a live area). Then
      replace the FIFO-timestamp assertions
      `(-> drain-1 :effects first :event-data :user-msg :timestamp)` /
      `(-> drain-2 …)` (L146, L154) — which assert on the **shape of
      handler-returned effect data** (a produced-effect interaction) — with
      assertions on **observable delivered-message state** (e.g.
      `test-support/scheduled-message-by-id` → `:timestamp`), per sufficient-coverage
      clause 3 (assert state/outputs, not handler interactions). If the
      time-source-stamp-on-effect assertion has independent unit value, keep it as a
      separate, clearly-named handler-unit assertion rather than as part of the
      *cited live covering test* for the area. Keep suite green + clj-kondo/cljfmt
      clean; test-file-only (Slice-10 allowlist — zero
      `components/agent-session/src/**` / `doc/scheduler.md`); no scheduler-source
      change. If 201 is treated as closed instead, raise as a small standalone
      test-hygiene task.
      Done: migrated `busy-session-fire-queues-then-idle-drains-fifo-test` off
      the local `invoke-scheduler-handler` + `apply-root-state-update!` helpers
      onto the **real** `dispatch-in! :scheduler/drain-queue` path — both drain
      phases now drive the full fire-while-busy → queue → idle → drain sequence
      through the real dispatch pipeline (matching the design "dispatch the event
      directly" mechanic and the stub-free `scheduler_dispatch_test` drain
      pattern). The cited live covering test now asserts only **observable
      delivered state**: per-schedule `:delivered`/`:queued` status, FIFO drain
      order (oldest by `[fire-at created-at schedule-id]` delivered first via the
      `:return` schedule-id), and post-drain queue contents — no handler-returned
      effect-shape assertions. **Discovery (grounds the keep-as-separate-unit
      branch):** the `:scheduler/drain-queue` deliver effect
      (`:runtime/dispatch-event-with-effect-result` → `:session/submit-synthetic-user-prompt`)
      does **not** land the scheduled user message observably in the test ctx
      journal (runtime-owned-deliver frontier — verified: drain sets `:delivered`
      via `drain-one`'s root-state-update, but the synthetic-prompt re-dispatch
      does not append to the state/persistence journal under either
      `create-test-session` or `make-session-ctx`; this is why the original test
      — and the cited `scheduler_dispatch_test` drain test — asserted the
      timestamp on the *handler-returned effect* rather than the delivered
      message). So `scheduled-message-by-id` is **not** observable here for drain.
      The time-source-stamp-on-effect check therefore retains independent unit
      value and was split into a new, clearly-named handler-unit deftest
      `drain-one-stamps-scheduled-user-message-from-scheduler-time-source-test`
      (invokes the handler `:fn` directly via the retained, now-documented
      `invoke-scheduler-handler` helper and asserts the emitted
      `:user-msg :timestamp` is stamped from the scheduler time source) —
      explicitly NOT part of the cited live covering test. `apply-root-state-update!`
      helper deleted (no longer used). Verified: `scheduler-lifecycle-test`
      4 tests / 26 assertions green; full `bb test` green; clj-kondo 0/0; cljfmt
      clean. Aggregate: deftests 50 → **51** (split added one), assertions stay
      **411** (`findings.md` Outcome + lifecycle inventory updated). Test file
      only — `git diff --name-only` = the single
      `scheduler_lifecycle_test.clj` path; zero `components/agent-session/src/**`
      or `doc/scheduler.md` (Slice-10 allowlist held).

## Test review follow-ups — pass 11 (task-test-review, 2026-06-01)

- [x] Re-audit all 201 verification-test deliverables + cited covering tests
      against the task-test-review skill (well_formed ∧ behaviour-coverage ∧
      infra_deps→injectable∧nullable∧¬mock∧¬stub), after pass-10's busy-drain
      migration to real `dispatch-in! :scheduler/drain-queue`. No new actionable
      issue: well_formed (clj-kondo 0/0, full `bb test` green); every Scope area
      + acceptance criterion maps to a cited covering test; all cited/201-added
      tests drive infra via ctx-injected seams and assert observable state (no
      mocks/stubs/interaction-asserts). The two surviving `with-redefs` sites
      (`scheduler-effects-test/scheduler-start-and-cancel-timer-effects-test`,
      `scheduler-lifecycle-test/scheduled-deliver-runs-canonical-prompt-lifecycle-test`)
      are pre-existing baseline, non-cited — already evaluated & scoped out in
      passes 7 & 9; not re-filed (no-duplicate). Review chain converged →
      REVIEW_COMPLETE.

## Test-shaper follow-ups — pass 12 (test-shaper, 2026-06-01)

- [x] Standardise the live-test schedule/queue *state reads* on the existing
      `ss/get-session-data-in` helper, replacing the repeated raw 6-segment path
      literal `(get-in @(:state* ctx) [:agent-session :sessions session-id :data
      :scheduler :schedules id :status])`. Affected: `scheduler_end_to_end_test`
      (×5 raw + ×2 helper — mixed within one ns), `scheduler_context_shutdown_test`
      (×2 raw), `scheduler_timer_seam_test` (×5 raw). Rationale (test-shaper
      consistent(test_abstractions) ∧ locally_comprehensible): the raw literal
      couples each assertion to internal state nesting (brittle to shape drift)
      and is noisier than the already-available helper; one ns mixes both idioms.
      Use `(get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules
      id :status])` / `[:scheduler :queue]` consistently. Keep suite green +
      clj-kondo/cljfmt clean. Test-file-only — zero
      `components/agent-session/src/**` / `doc/scheduler.md` (verification-only
      invariant; Slice-10 allowlist). Update `findings.md` only if a covering-test
      citation’s read form changes (no status changes expected). If 201 is treated
      as closed instead, raise as a small standalone test-hygiene task.
      Done: migrated all 12 raw schedule/queue *state reads* to
      `(get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules id
      :status])` / `[:scheduler :queue]` — `scheduler_end_to_end_test` (×5; the
      ×2 already-helper sites unchanged → ns now single-idiom),
      `scheduler_context_shutdown_test` (×2), `scheduler_timer_seam_test` (×5).
      Added the `[psi.session-state.state :as ss]` require to the shutdown +
      timer-seam ns (e2e already had it). The one remaining `:state* ctx*` site
      (e2e ~L105) is a `swap!` busy-state *write*, not a schedule/queue read →
      intentionally untouched (out of scope). `findings.md` unchanged: no
      covering-test deftest was renamed and no status changed (the step's update
      condition is not met). Affected ns green (8 tests / 41 assertions); full
      `bb test` green; clj-kondo 0/0; `bb fmt:check` "All source files formatted
      correctly". Test-file-only — `git status` shows only the 3 scheduler test
      files; zero `components/agent-session/src/**` or `doc/scheduler.md`
      (Slice-10 allowlist held; aggregate assertion count unchanged at 412).

## Test-shaper follow-ups — pass 13 (test-shaper, 2026-06-01)

- [x] Standardise the 201 session-data *write* idiom (write-side analogue of the
      pass-12 read standardisation, not covered by it). Replace the raw 6-segment
      `(swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data
      :scheduler] …)` write in
      `scheduler_resolvers_test/scheduler-resolver-projects-rich-attrs-across-statuses-test`
      (L78-79) with the existing helper form `(swap! (:state* ctx)
      (ss/session-update session-id (fn [sd] (assoc sd :scheduler {:schedules …
      :queue []}))))`, matching the `ss/session-update` idiom already used for the
      busy-flag seed in `scheduler_end_to_end_test` (L105-106). Rationale
      (test-shaper consistent(test_abstractions) ∧ locally_comprehensible): two
      idioms seed session-data across the 201 set; the raw `assoc-in` couples the
      test to internal `[:agent-session :sessions … :data]` nesting (brittle to
      state-shape drift) and is noisier than the already-available helper —
      exactly the coupling pass-12 removed for reads. `ss/session-update sid f` ≡
      `update-in state (session-data-path sid) f`, `session-data-path sid =
      [:agent-session :sessions sid :data]`, so the rewrite is behaviour-preserving
      with no new abstraction. `ss` is already required in
      `scheduler_resolvers_test`. Keep suite green + clj-kondo/cljfmt clean.
      Test-file-only — zero `components/agent-session/src/**` /
      `doc/scheduler.md` (verification-only invariant; Slice-10 allowlist). Update
      `findings.md` only if a covering-test citation's form changes (none expected).
      If 201 is treated as closed instead, raise as a small standalone
      test-hygiene task.

## Docs-review follow-ups — pass 1 (review-task-docs, 2026-06-01)

- [x] Correct the `findings.md` "psi-tool surface" `:at`-asymmetry row to stop
      claiming the near-future/`>24h` `:at` rejection "matches `doc/scheduler.md`".
      `doc/scheduler.md` "Create validation rules" documents only *relative*-delay
      bounds + "past absolute instants fire immediately"; it is **silent** on a
      future `:at` below `min-delay-ms` (1–999ms ahead) being rejected and on `:at`
      above `max-delay-ms` (>24h) being rejected. Reword the row to record this as
      a **doc-gap finding** (doc↔behaviour drift: near-future/`>24h` `:at`
      rejection is undocumented) rather than `verified-correct`/"matches doc".
      Grounded in `psi_tool_scheduler/resolve-fire-time!` (`validate-delay-ms!`
      runs only when `delay` is strictly positive). Verification-only: edit the
      task-local `findings.md` only — no `doc/scheduler.md`/`src` change here.
      Done: rewrote the `:at`-asymmetry row from `verified-correct`/"matches doc"
      to `defect (doc-gap)` — behaviour correct (grounded in `resolve-fire-time!`:
      `validate-delay-ms!` runs only when resolved `delay` is strictly positive),
      but `doc/scheduler.md` "Create validation rules" is silent on the
      near-future-below-min / `>24h` `:at` rejection (doc↔behaviour drift). Added
      the remediation task-ref `202-document-at-bounds-in-scheduler-doc` to the
      row's task-ref column. Also corrected the `findings.md` Outcome from "no
      defects found / no remediation task" to record the one doc-gap defect +
      raised remediation task 202 (behaviour proven correct; doc-only fix). Verification-only:
      task-local `findings.md` only — zero `doc/scheduler.md` / `src` change
      (Slice-10 allowlist held).
- [x] Raise a doc-clarification remediation task
      (`munera/open/NNN-document-at-bounds-in-scheduler-doc` or similar) to extend
      `doc/scheduler.md` "Create validation rules" with the absolute-`:at` bound
      behaviour: only past/now `:at` fire immediately (delay 0, no min check);
      future `:at` below `min-delay-ms` is rejected (below-minimum bound); `:at`
      above `max-delay-ms` is rejected (exceeds-maximum bound). Reference the new
      task id from the corrected `findings.md` row. Per design policy this doc fix
      is out of scope for 201 (verification-only); it is a separate remediation
      task. If 201 is being closed, this is the deliverable handoff for the doc gap.
      Done: created remediation task
      `munera/open/202-document-at-bounds-in-scheduler-doc/` (next free NNN after
      201) with a `design.md` capturing the doc-gap goal/context/scope/acceptance:
      extend `doc/scheduler.md` "Create validation rules" with the absolute-`:at`
      bound behaviour (past/now fire immediately; future-below-min rejected;
      `>max` rejected), grounded in `resolve-fire-time!` and proven by 201's
      `psi-tool-scheduler-at-resolution-matrix` tests; doc-only, behaviour
      out of scope. The corrected `findings.md` `:at`-asymmetry row (item above)
      references `202-document-at-bounds-in-scheduler-doc`. Verification-only
      scope held: only the new task dir + task-local `findings.md` touched — zero
      `doc/scheduler.md` / `src` change.

## Code-shaper follow-ups — pass 1 (code-shaper, 2026-06-01)

- [x] Extract a shared `stub-execution-result` builder in
      `components/agent-session/test/psi/agent_session/test_support.clj` and route
      the duplicated execution-result stub shape through it
      (`consistent(test_abstractions)` / DRY). Today the
      `{:execution-result/turn-id … :assistant-message {… :timestamp …} …}` shape
      is open-coded in two places: `make-session-ctx`'s `:execute-prepared-request-fn`
      (test_support.clj L246-255) and the inline session-kind seam stub in
      `scheduler_end_to_end_test/scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session-test`.
      Add a helper, e.g. `(stub-execution-result {:keys [sid prepared timestamp]})`,
      returning the canonical shape; have both callsites consume it. Keep the
      `now`-anchored deterministic timestamp from the scheduler test as the helper's
      default discipline (no wall-clock). Test-file/`test_support`-only (Slice-10
      allowlist — zero `components/agent-session/src/**` / `doc/scheduler.md`); keep
      suite green + clj-kondo/cljfmt clean; no behaviour/assertion change.
      `findings.md` citations unchanged. If 201 is closed instead, raise as a small
      standalone test-hygiene task.
      Done: added `test-support/stub-execution-result`
      (`{:keys [sid prepared timestamp text]}` → the canonical
      `:execution-result/*` shape), with a fixed `default-stub-execution-instant`
      (`2026-01-01T00:00:00Z`) as the `:timestamp` default and `text` default
      `"ok"`. Routed both callsites through it: `make-session-ctx`'s
      `:execute-prepared-request-fn` (now
      `(stub-execution-result {:sid sid :prepared prepared})`) and the e2e
      session-kind seam stub (now
      `(stub-execution-result {:sid sid :prepared prepared :text "scheduled ack"
      :timestamp (.plusMillis now 5000)})`, preserving its `now`-anchored
      deterministic fire-time timestamp). No behaviour/assertion change — the
      shape is identical and no assertion reads the assistant timestamp.
      `findings.md` citations unchanged. clj-kondo 0/0, clj-paren-repair clean;
      full `bb test` green. Test/`test_support`-only — `git diff --name-only` =
      `test_support.clj` + `scheduler_end_to_end_test.clj`; zero
      `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
      held).
- [x] Replace the wall-clock `(java.time.Instant/now)` execution-result-stub
      timestamp in `test_support/make-session-ctx` (test_support.clj L252) with a
      fixed instant (`deterministic(tests)` — control(time)). This is the same
      footgun test-shaper pass-6 removed from the in-scope scheduler *test files*,
      but it was left in the shared `make-session-ctx` helper that
      `scheduler_dispatch_test` / `scheduler_handlers_test` depend on transitively —
      every other instant on those paths is time-controlled, so this lone
      wall-clock read breaks `control(time(tests))`. Preferably fold this into the
      `stub-execution-result` extraction above (one structural fix resolves both):
      have the helper take/default a fixed instant rather than calling
      `Instant/now`. Leave the `notify-extension-fn` `Instant/now` timestamps
      (L266/L279) out of scope — notification messages, broader concern, not on
      the 201 surface. Test-file/`test_support`-only (Slice-10 allowlist); keep
      suite green + clj-kondo/cljfmt clean; no assertion change (no assertion reads
      the stub timestamp today, so it is a latent-footgun fix, not a flake fix). If
      201 is closed instead, raise as a small standalone test-hygiene task.
      Done: folded into the `stub-execution-result` extraction above (one
      structural fix resolves both, as suggested). The shared
      `default-stub-execution-instant` fixed literal is now the helper's
      `:timestamp` default, so `make-session-ctx`'s `:execute-prepared-request-fn`
      no longer calls `(java.time.Instant/now)` — it is time-controlled like
      every other instant on the dispatch/handlers paths. The
      `notify-extension-fn` `Instant/now` timestamps were left untouched (out of
      scope as specified). No assertion reads the stub timestamp → latent-footgun
      fix, no behaviour/assertion change. clj-kondo 0/0, formatted clean; full
      `bb test` green. Test/`test_support`-only — zero
      `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
      held).

## Code-shaper follow-ups — pass 2 (code-shaper, 2026-06-01)

- [x] Converge the literal-instant idiom across the 201 test files on a single
      shared helper. `scheduler_test.clj` defines a private `(defn- instant [s]
      (java.time.Instant/parse s))` and uses it throughout, but the four new
      integration test files open-code `(java.time.Instant/parse …)`:
      `scheduler_end_to_end_test` (×4), `scheduler_timer_seam_test` (×4),
      `scheduler_resolvers_test` (×4), `scheduler_context_shutdown_test` (×3).
      Rationale (code-shaper consistent(idioms) ∧ locally_comprehensible): two
      idioms for the same literal→`Instant` operation span the task's own
      surface; the verbose fully-qualified static call is noisier than the
      one-word helper. Promote `instant` to `test-support` (e.g.
      `(defn instant [s] (java.time.Instant/parse s))`, sitting beside
      `fixed-scheduler-time-source`), have `scheduler_test.clj` consume the
      shared helper instead of its private copy, and replace the open-coded
      `(java.time.Instant/parse …)` literal sites in the four integration test
      files with `(test-support/instant …)`. Keep the runtime-derived instants
      (`(.plusMillis now …)` / `(.plusSeconds now …)`) as-is — those are not
      literal parses. Behaviour-preserving, no assertion change. Keep suite green
      + clj-kondo/cljfmt clean. Test-file-only — zero
      `components/agent-session/src/**` / `doc/scheduler.md` (verification-only
      invariant; Slice-10 allowlist). `findings.md` citations unchanged (no
      deftest renamed). If 201 is treated as closed instead, raise as a small
      standalone test-hygiene task.
      Done: promoted `instant` to `test-support` (public
      `(defn instant [s] (java.time.Instant/parse s))`, beside
      `fixed-scheduler-time-source`). `scheduler_test.clj` now consumes the
      shared helper via `[psi.agent-session.test-support :refer [instant]]` and
      its private `(defn- instant …)` copy is deleted (30 `(instant …)` call
      sites unchanged). Replaced all 15 open-coded `(java.time.Instant/parse
      "…")` literal sites with `(test-support/instant "…")`:
      `scheduler_end_to_end_test` ×4, `scheduler_timer_seam_test` ×4,
      `scheduler_resolvers_test` ×4, `scheduler_context_shutdown_test` ×3
      (verified: zero `java.time.Instant/parse` literals remain in the four
      integration files). Runtime-derived instants (`(.plusMillis now …)` /
      `(.plusSeconds now …)`) left as-is — not literal parses. Behaviour- and
      assertion-preserving (no deftest renamed → `findings.md` citations
      unchanged; aggregate stays 51 tests / 411 assertions). clj-kondo 0/0 on
      all 6 touched files; `bb fmt:check` "All source files formatted
      correctly"; full `bb test` green. Test/`test_support`-only —
      `git diff --name-only` = 6 test files (5 scheduler test ns +
      `test_support.clj`); zero `components/agent-session/src/**` or
      `doc/scheduler.md` (Slice-10 allowlist held).

## Code-shaper follow-ups — pass 3 (code-shaper, 2026-06-01)

- [x] Converge the duplicated `[:scheduler :schedules <id> …]` read-path idiom
      across the 201 test files onto shared `test-support` helpers
      (`consistent(idioms)` ∧ DRY ∧ `locally_comprehensible`). The open-coded
      `(get-in (ss/get-session-data-in ctx sid) [:scheduler :schedules <id> …])`
      shape repeats **41×** across the 8 scheduler test files all touched by 201
      (`scheduler_context_shutdown_test`, `scheduler_dispatch_test`,
      `scheduler_effects_test`, `scheduler_end_to_end_test`,
      `scheduler_handlers_test`, `scheduler_lifecycle_test`,
      `scheduler_timer_seam_test`, `psi_tool_scheduler_test`) — **29** of them the
      `:status` read — plus **14×** for `[:scheduler :queue]`. NOTE: this
      *corrects* pass-2's deliberate decline of this same convergence; pass-2's
      stated rationale ("reaches pre-existing baseline files outside 201's
      new-test scope") is factually wrong — `git diff 2335116a4..HEAD` shows all
      8 files are `touched` by 201, none `UNTOUCHED`. Add to `test-support`,
      beside the existing `scheduled-message-by-id` precedent (the established
      shared scheduler-read home): `(defn schedule-by-id [ctx session-id
      schedule-id] (get-in (ss/get-session-data-in ctx session-id) [:scheduler
      :schedules schedule-id]))`, `(defn schedule-status [ctx session-id
      schedule-id] (:status (schedule-by-id ctx session-id schedule-id)))`, and
      `(defn schedule-queue [ctx session-id] (get-in (ss/get-session-data-in ctx
      session-id) [:scheduler :queue]))`. Replace the 41 `[:scheduler :schedules
      …]` sites: pure `… :status` reads → `(test-support/schedule-status ctx sid
      id)`; reads of other schedule keys (`:delivery-phase`,
      `:created-session-id`, `:kind`, `:session-config-summary`, full-map
      `[:scheduler :schedules id]`) → `(test-support/schedule-by-id ctx sid id)`
      then key-access; and the 14 `[:scheduler :queue]` sites →
      `(test-support/schedule-queue ctx sid)`. Behaviour- and
      assertion-preserving — no deftest renamed (`findings.md` citations
      unchanged); aggregate stays 51 tests / 411 assertions. Keep suite green +
      clj-kondo/cljfmt clean. Test/`test_support`-only — verify
      `git diff --name-only` touches only `components/agent-session/test/**` +
      `test_support.clj`; zero `components/agent-session/src/**` or
      `doc/scheduler.md` (verification-only invariant; Slice-10 allowlist). If
      201 is treated as closed instead, raise as a small standalone
      test-hygiene task.
      Done (commit d29cf0407): extracted `schedule-by-id` / `schedule-status` /
      `schedule-queue` into `test-support` (L378-393); replaced all 41 open-coded
      `[:scheduler :schedules id …]` + 14 `[:scheduler :queue]` reads across the
      8 scheduler test files; dropped now-unused `ss` requires. Verified zero
      `[:scheduler :schedules` / `[:scheduler :queue]` path literals remain in
      any `*_test.clj`. clj-kondo 0/0; full `bb test` green. `git diff
      --name-only` = test files + `test_support.clj` only; zero
      `components/agent-session/src/**` / `doc/scheduler.md` (invariant held).
      (Marked complete during task-implementation-review pass — item executed but
      left unchecked in the commit.)

## Test review follow-ups — pass 10 (task-test-review, 2026-06-01)

- [x] Correct the inaccurate `findings.md` L62 (Baseline) citation. The entry
      "Deterministic time/timer seams … enable firing **without wall-clock
      sleeps**" co-cites `scheduler_timer_seam_test.clj` **and**
      `scheduler_effects_test.clj`, but the only *firing* deftest in
      `scheduler_effects_test.clj`
      (`scheduler-start-and-cancel-timer-effects-test`) fires via the **real
      wall-clock `Thread/sleep` daemon path** (`(.plusMillis now 20)` real delay,
      `(deref fired 1000 …)`, `Thread/sleep 10/30` polling) and `with-redefs`-
      stubs `dispatch/dispatch!` — directly contradicting the "without wall-clock
      sleeps" claim it is cited to support. (Pass-9 scoped this deftest out at the
      *covering-cell* level but missed this *file-level* Baseline citation.) Fix:
      drop `scheduler_effects_test.clj` from the L62 citation (or replace it with
      the specific seam-using deftest), leaving
      `scheduler_timer_seam_test.clj/scheduler-start-timer-uses-injected-time-
      source-and-delay-runner-test` — which already fully supports the claim
      (captures `delay-ms`+callback, invokes `(@callback*)`, asserts
      `:delivered`, zero wall-clock) — as the authoritative no-wall-clock-firing
      citation. `findings.md`-only edit (no deftest renamed → other citations
      unchanged; no scheduler source/`doc/scheduler.md` change; verification-only
      invariant + Slice-10 allowlist held). If 201 is treated as closed instead,
      raise as a small standalone findings-accuracy fix.
      Done: verified the flag against source —
      `scheduler_effects_test/scheduler-start-and-cancel-timer-effects-test`
      does fire via the real wall-clock daemon path (`(.plusMillis now 20)`,
      `(deref fired 1000 ::timeout)`, `Thread/sleep 10`/`30`) and `with-redefs`-
      stubs `dispatch/dispatch!`, so co-citing it under "firing without
      wall-clock sleeps" is contradictory. Replaced the L62 covering-test cell
      (`scheduler_timer_seam_test.clj`, `scheduler_effects_test.clj`) with the
      single authoritative no-wall-clock-firing deftest
      `scheduler_timer_seam_test.clj/scheduler-start-timer-uses-injected-time-source-and-delay-runner-test`
      (captures `delay-ms`+callback, invokes `(@callback*)`, asserts
      `:delivered`, zero wall-clock). `findings.md`-only edit (no deftest
      renamed → all other citations unchanged); zero
      `components/agent-session/src/**` or `doc/scheduler.md` change —
      verification-only invariant + Slice-10 allowlist held.

## Test-shaper follow-ups — pass 15 (test-shaper, 2026-06-01)

- [x] Converge the 5 remaining open-coded literal-instant setup sites in
      `psi_tool_scheduler_test.clj` onto `test-support/instant`
      (`consistent(idioms)` — finish the convergence commit `5e5fe10af` started
      across the 201 scheduler test set). Replace
      `(java.time.Instant/parse "2026-04-21T18:00:00Z")` at L10, L183, L201,
      L225, L243 with `(test-support/instant "2026-04-21T18:00:00Z")` — the ns
      already requires `[psi.agent-session.test-support :as test-support]`, so
      no require change is needed. This is the *literal-setup* idiom that all 5
      sibling 201 files (`scheduler_test`, `scheduler_end_to_end_test`,
      `scheduler_timer_seam_test`, `scheduler_resolvers_test`,
      `scheduler_context_shutdown_test`) already use (0 `Instant/parse` literals
      each); `psi_tool_scheduler_test.clj` is the lone outlier omitted from
      `5e5fe10af`. **Do NOT** touch the 5 runtime-output-parse sites
      (L30, L31, L196, L197, L218 — `(java.time.Instant/parse (:fire-at
      schedule))` etc.): those deserialize a string from the tool result (a
      distinct concern), and converging them would mis-use `instant`'s
      "literal test instant" intent. Behaviour- and assertion-preserving (no
      deftest renamed → `findings.md` citations unchanged; aggregate stays
      51 tests / 411 assertions). Keep suite green + clj-kondo/cljfmt clean.
      Test-only — verify `git diff --name-only` touches only
      `psi_tool_scheduler_test.clj`; zero `components/agent-session/src/**` or
      `doc/scheduler.md` (verification-only invariant; Slice-10 allowlist).
      Note: this corrects test-shaper pass-14's rejection — pass-14's "inline
      `Instant/parse` is the consistent baseline" premise is factually wrong for
      the 201 set, where `5e5fe10af` already chose `test-support/instant` as the
      convergence target. If 201 is treated as closed instead, raise as a small
      standalone test-hygiene task.
      Done: replaced all 5 open-coded literal-instant *setup* bindings
      (`fixed-now (java.time.Instant/parse "2026-04-21T18:00:00Z")` at L10, L183,
      L201, L225, L243) with `(test-support/instant "2026-04-21T18:00:00Z")` —
      `psi_tool_scheduler_test.clj` now matches the 5 sibling 201 files
      (0 `Instant/parse` literal-setup sites). The 5 runtime-output-parse sites
      (L30/L31 `:created-at`/`:fire-at`, L196/L197, L218 `:fire-at`) were left
      untouched as specified — they deserialize tool-result strings (a distinct
      concern, not the literal-test-instant idiom). The ns already required
      `[psi.agent-session.test-support :as test-support]`, so no require change.
      Behaviour- and assertion-preserving: `--focus
      psi.agent-session.psi-tool-scheduler-test` = 6 tests / 109 assertions / 0
      failures (no deftest renamed → `findings.md` citations unchanged; aggregate
      stays 51 tests / 411 assertions); full `bb test` green; clj-kondo 0/0;
      `bb fmt:check` "All source files formatted correctly". Test-file-only —
      `git status --short` = the single `psi_tool_scheduler_test.clj` path; zero
      `components/agent-session/src/**` or `doc/scheduler.md` (verification-only
      invariant; Slice-10 allowlist held).

## Test-shaper follow-ups — pass 16 (test-shaper, 2026-06-01)

- [x] Finish the literal-instant idiom convergence across the **remaining five**
      201-touched scheduler test files (pass-15 only converged
      `psi_tool_scheduler_test.clj`; its "all siblings already at 0
      `Instant/parse`" premise was scoped to the 6 new/core nss and is wrong for
      the full 14-file 201-touched set). Replace open-coded literal-instant
      *setup* `(java.time.Instant/parse "…")` with `(test-support/instant "…")`
      in: `scheduler_dispatch_test.clj` (`schedule` helper L14-15; L20, L85-86),
      `scheduler_effects_test.clj` (L12, L41, L51, L54, L78-79, L87-88),
      `scheduler_lifecycle_test.clj` (L37, L61-62, L98, L111-112, L147, L158-159,
      L178-179, L194-195 — incl. the loop-bound *literal* `created`/`fire` strings
      at L111-112), `scheduler_background_jobs_test.clj` (L17-18, L27-28, L46-47),
      and `scheduler_cancel_job_test.clj` (L15-16). All five already require
      `[psi.agent-session.test-support :as test-support]`, so no require change
      is needed. **Do NOT** convert runtime-output/deserialization parses:
      `scheduler_handlers_test.clj:27` (`(Instant/parse s)` over a runtime
      string) and `psi_tool_scheduler_test.clj` L30/31/196/197/218 (tool-result
      strings) — those are a distinct concern, not the literal-test-instant
      idiom. Behaviour- and assertion-preserving (no deftest renamed →
      `findings.md` citations unchanged; aggregate stays 51 tests / 411
      assertions). Keep suite green + clj-kondo/cljfmt clean. Test-only — verify
      `git diff --name-only` touches only the five
      `components/agent-session/test/**` files; zero
      `components/agent-session/src/**` or `doc/scheduler.md` (verification-only
      invariant; Slice-10 allowlist). If 201 is treated as closed instead, raise
      as a small standalone test-hygiene task.
      Done: replaced every open-coded literal-instant *setup*
      `(java.time.Instant/parse "…")` with `(test-support/instant "…")` across
      the five remaining 201-touched files — `scheduler_dispatch_test` (5 sites:
      `schedule` helper L14-15, L20, L85-86), `scheduler_effects_test` (6 sites:
      L12, L41, L51, L54, L78-79, L87-88), `scheduler_lifecycle_test` (9 sites:
      L37, L61-62, L98, L111-112 incl. the loop-bound literal `created`/`fire`
      strings, L147, L158-159, L178-179, L194-195),
      `scheduler_background_jobs_test` (6 sites: L17-18, L27-28, L46-47), and
      `scheduler_cancel_job_test` (2 sites: L15-16). All five already required
      `[psi.agent-session.test-support :as test-support]` → no require change.
      Runtime/deserialization parses left untouched as specified:
      `scheduler_handlers_test.clj:27` (private `instant` over a runtime string)
      and the `psi_tool_scheduler_test` tool-result parses (already excluded from
      the edit). Verified: zero `Instant/parse` literals remain in the five
      files; `scheduler_handlers_test:27` unchanged. Behaviour- and
      assertion-preserving (no deftest renamed → `findings.md` citations
      unchanged; aggregate stays 51 tests / 411 assertions). clj-kondo 0/0;
      `bb fmt:check` "All source files formatted correctly"; full `bb test`
      green (two `turn-runtime`/`prompt-lifecycle` retry-backoff failures
      observed on one run were a pre-existing non-deterministic test-ordering
      flake — they touch no file edited here and cleared on re-run with the
      change in place; the same flake reproduces with the change stashed-then-
      popped). Test-file-only — `git diff --name-only` = the five
      `components/agent-session/test/**` scheduler files; zero
      `components/agent-session/src/**` or `doc/scheduler.md` (verification-only
      invariant; Slice-10 allowlist held).

## Test-shaper follow-ups — pass 17 (test-shaper, 2026-06-01)

- [x] Tighten the two generic-only bound/cap rejection assertions in
      `psi_tool_scheduler_test.clj/psi-tool-scheduler-bounds-and-cap-test` to
      assert the *named* error message (meaningful_failures ∧
      consistent(assertion_style)), matching the precedent already set for the
      `:at` matrix blocks and the `scheduler_test.clj` `thrown-with-msg?` guards:
      - below-min `:delay-ms` (10ms) block → add
        `(= "delay-ms is below the minimum bound" (get-in parsed [:psi-tool/error :message]))`
        (`scheduler.clj:85`).
      - cap-overflow (51st pending) block → add
        `(= "scheduler pending cap exceeded" (get-in parsed [:psi-tool/error :message]))`
        (`psi_tool_scheduler.clj:149`).
      Leave the existing `:is-error`/`:overall-status` assertions in place.
      Verify: test-file-only edit (Slice-10 allowlist; no
      `components/agent-session/src/**` or `doc/scheduler.md`); deftest name
      unchanged (findings citations stable); clj-kondo 0/0; `bb fmt:check`
      clean; scheduler `bb test` subset green.
      Done: added both named-message assertions —
      below-min `delay-ms` block → `"delay-ms is below the minimum bound"`
      (`scheduler.clj:85`), cap-overflow block → `"scheduler pending cap
      exceeded"` (`psi_tool_scheduler.clj:149`) — via
      `(get-in parsed [:psi-tool/error :message])`, matching the `:at` matrix
      precedent. Existing `:is-error`/`:overall-status` assertions kept; deftest
      name unchanged → `findings.md` citations stable. The blocks are now
      assertion-distinguishable. psi-tool deftest 109 → 111 assertions (+2);
      scheduler-suite total **51 tests / 413 assertions / 0 failures** (was 411).
      Updated `findings.md` Outcome current-count 411 → 413. clj-kondo 0/0;
      `bb fmt:check` "All source files formatted correctly"; full `bb test`
      green. Test/task-doc files only — zero `components/agent-session/src/**`
      or `doc/scheduler.md` (Slice-10 allowlist held).

## Test-shaper follow-ups — pass 18 (test-shaper, 2026-06-01)

- [x] Remove the dead `(or (:return result) result)` / `(:return result result)`
      dispatch-return hedge and assert on the bare returned map. `dispatch-in!`
      → `dispatch!` returns `(:result result-ictx)` where the kernel already
      unwraps the handler's pure `:return` (`state_kernel/dispatch.clj:272`
      `(assoc :result (:return pure-result))`, returned at `:442`); so the test
      `result` is already the handler's map and `(:return result)` is always
      `nil`. The hedge is dead and masks the contract (meaningful_failures —
      a `:return`-wrapped wrong shape would pass; consistent(assertion_style) —
      L33 uses the keyword-default spelling, others use `(or …)`). Sites:
      - `scheduler_dispatch_test.clj`: L33 `(:schedule-id (:return result result))`,
        L49 `(:status (or (:return result) result))`,
        L79 `(:schedule-id (or (:return result) result))`,
        L98 `(:drained? (or (:return result) result))`,
        L99 `(:schedule-id (or (:return result) result))` → assert on bare
        `result` (e.g. `(:schedule-id result)`, `(:status result)`,
        `(:drained? result)`).
      - `scheduler_lifecycle_test.clj`: L129 `(:schedule-id (or (:return drain-1) drain-1))`,
        L138 `(:schedule-id (or (:return drain-2) drain-2))` → assert on bare
        `drain-1` / `drain-2`.
      Keep deftest names unchanged (findings citations stable). Verify: test-only
      edit (Slice-10 allowlist; no `components/agent-session/src/**` or
      `doc/scheduler.md`); clj-kondo 0/0; `bb fmt:check` clean; scheduler
      `bb test` subset green (assertions count unchanged — same number of
      `is` forms, just tightened targets).
      Done: verified the contract — kernel sets `:result` from `(:return
      pure-result)` (`state_kernel/dispatch.clj:272`) and `dispatch!` returns
      `(:result result-ictx)` (`:442`), so the test `result`/`drain-*` is already
      the unwrapped handler map and `(:return result)` is always `nil`. Removed
      all 5 dead hedges in `scheduler_dispatch_test.clj`
      (L33/49/79/98/99 → bare `(:schedule-id result)` / `(:status result)` /
      `(:drained? result)`) and both in `scheduler_lifecycle_test.clj`
      (L129/138 → bare `drain-1`/`drain-2`). Deftest names unchanged (findings
      citations stable); same `is` count → aggregate unchanged. clj-kondo 0/0;
      `bb fmt:check` "All source files formatted correctly"; focused subset green
      (`scheduler-dispatch-test` + `scheduler-lifecycle-test` = 9 tests / 45
      assertions / 0 failures); full `bb test` green. Test files only — zero
      `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
      held).

## Test-shaper follow-ups — pass 19 (test-shaper, 2026-06-01)

- [x] Collapse the redundant psi-tool-surface overlap between
      `scheduler_tools_test/make-psi-tool-scheduler-test` and the authoritative
      `psi_tool_scheduler_test` (economical / minimal(redundant_tests) ∧
      consistent(assertion_style) ∧ meaningful_failures). After 201 built
      `psi_tool_scheduler_test` into the stronger psi-tool authority (6 focused
      deftests, named-bound messages pass-17, full `:at` matrix), the pre-existing
      `make-psi-tool-scheduler-test` re-covers the same surface with weaker
      assertions, and duplicates the expensive `dotimes 50` cap-overflow drive
      (the single most expensive scheduler test, now run twice). Overlapping
      blocks:
      - "create stores a pending schedule" ≈ `psi-tool-scheduler-create-list-cancel-test` create.
      - "list returns pending and queued" ≈ create-list-cancel list.
      - "cancel cancels a pending schedule" ≈ create-list-cancel cancel.
      - "rejects too-short delay" (999ms) ≈ `psi-tool-scheduler-bounds-and-cap-test`
        below-min — weaker (generic `:is-error`/`:overall-status` only, no named bound).
      - "normalizes past absolute instants" ≈ `psi-tool-scheduler-at-resolution-matrix-test`
        past-`:at` — strictly weaker (`(string? fire-at)` only vs delay-0 + immediate
        fire + exact fire-at).
      - "rejects the 51st pending schedule" ≈ `psi-tool-scheduler-bounds-and-cap-test`
        cap — duplicates the `dotimes 50` cap drive.
      Action: drop the redundant create/list/cancel/below-min/cap/`:at`-past blocks
      from `make-psi-tool-scheduler-test` (especially the second `dotimes 50` cap
      drive), letting `psi_tool_scheduler_test` be the sole cited psi-tool-surface
      authority; OR if any `scheduler_tools_test`-only nuance survives audit, keep
      it but tighten its assertions to the named-message / exact-fire-at precedent
      so the two files stop diverging in rigour. Update `findings.md`
      psi-tool-surface citations to the single retained authority + the deftest/
      assertion counts. Prefer the drop-redundant path unless a unique behaviour
      surfaces during execution.
      Verify: test-only edit (Slice-10 allowlist; no `components/agent-session/src/**`
      or `doc/scheduler.md`); deftest names of the *retained* authority unchanged
      (findings citations stable for it); clj-kondo 0/0; `bb fmt:check` clean;
      psi-tool + scheduler `bb test` subset green; full `bb test` green.
      Done: chose the **drop-redundant path** — audited all 6 `testing` blocks of
      `scheduler-tools-test/make-psi-tool-scheduler` against the authoritative
      `psi-tool-scheduler-test` and confirmed every one is re-covered with
      *stronger* assertions: create/list/cancel → `…-create-list-cancel` (+ exact
      created-at/fire-at + status); below-min (999ms) → `…-bounds-and-cap`
      below-min (+ named "delay-ms is below the minimum bound"); 51st-pending
      `dotimes 50` cap → `…-bounds-and-cap` cap (+ named "scheduler pending cap
      exceeded"); past `:at` `(string? fire-at)` only → `…-at-resolution-matrix`
      past (delay-0 + immediate seam-driven fire + exact fire-at). The only
      nuance unique to the dropped file (list surfacing a manually-poked
      `:queued` status) is covered by the Projections section
      (`scheduler-background-job-projection` pending+queued). No unique behaviour
      surfaced → `git rm` the whole `scheduler_tools_test.clj` (its sole deftest),
      leaving `psi-tool-scheduler-test` the single cited psi-tool-surface
      authority and removing the second expensive `dotimes 50` cap drive.
      `findings.md` updated: psi-tool-surface create/list/cancel citation now
      points only at `…-create-list-cancel`; baseline inventory line annotated as
      pass-19-removed; Outcome counts 51→50 tests / 413→339 assertions (−1
      deftest, −74 assertions). Verified: full `bb test` green; the 12 remaining
      scheduler ns run together = **50 tests / 339 assertions / 0 failures**;
      `psi-tool-scheduler-test` standalone = 6 tests / 111 assertions; clj-kondo
      0/0; `bb fmt:check` "All source files formatted correctly". Test/task-doc
      files only — `git rm` of one `components/agent-session/test/**` file + the
      task-dir `findings.md`/`steps.md`; zero `components/agent-session/src/**` or
      `doc/scheduler.md` (verification-only invariant; Slice-10 allowlist held).

## Test-shaper follow-ups — pass 20 (test-shaper, 2026-06-01)

- [x] Relabel the misleading `testing` block in
      `scheduler-test/drain-one-test` (L119): "drain-one is FIFO by queue order
      when session is idle". `drain-one` sorts by `[fire-at created-at
      schedule-id]` (`scheduler.clj:262-264`), not FIFO-by-insertion; the block's
      setup queues sch-a (fire-at 18:05:00) then sch-b (18:05:01) so insertion
      order coincidentally equals fire-at order, making the docstring assert a
      non-existent contract and the test unable to catch a FIFO-insertion
      regression (`meaningful_failures` gap). It also contradicts the sibling
      `drain-one-orders-by-fire-at-not-queue-insertion-order-test` in the same
      file. Reword to "drain-one delivers the earliest fire-at when session is
      idle" (sch-a is both first-inserted and earliest fire-at here, so the
      assertions stand). Optionally add a one-line comment that the dedicated
      ordering test proves the sort when insertion ≠ fire-at order. test-shaper
      `behavior_focused ∧ meaningful_failures ∧ consistent(naming)`.
      Assertions unchanged; deftest name unchanged → `findings.md` Pure-model
      citations stable. Test-file-only (Slice-10 allowlist — zero
      `components/agent-session/src/**` or `doc/scheduler.md`); keep the suite
      green + clj-kondo/cljfmt clean.
      Done: reworded the `testing` docstring to "drain-one delivers the earliest
      fire-at when session is idle" (no longer asserts a non-existent FIFO-by-
      insertion contract) and added a 3-line comment above the block stating
      sch-a is both first-inserted and earliest fire-at here, with a pointer to
      the dedicated `drain-one-orders-by-fire-at-not-queue-insertion-order-test`
      that proves the `[fire-at created-at schedule-id]` sort when insertion ≠
      fire-at order. Assertions + deftest name unchanged → `findings.md`
      Pure-model citations stable; aggregate assertion count unchanged. Full
      `bb test` green; clj-kondo 0/0; `bb fmt:check` "All source files formatted
      correctly". Test file only — zero `components/agent-session/src/**` or
      `doc/scheduler.md` (Slice-10 allowlist held).

- [x] Fix the flaky cited baseline timer-race in `scheduler_dispatch_test.clj`
      (implementation-review finding 2026-06-01). Two deftests intermittently
      fail under the canonical kaocha runner (`46 tests, 2 failures` one run,
      `0` on re-run) because they use `make-session-ctx`'s **default** real
      `Thread/sleep`-daemon `:scheduler-run-after-delay-fn` instead of the
      deterministic `capturing-delay-fn` seam:
      - `scheduler-create-stores-schedule-and-starts-timer-test` — the 1000ms
        daemon can fire `:scheduler/fired` → deliver → remove the handle from
        `:scheduler-timers*` before `(contains? @(:scheduler-timers* ctx) "sch-1")`
        reads it (observed `actual: (not (contains? {} "sch-1"))`).
      - `scheduler-cancel-marks-pending-or-queued-schedule-cancelled-test` —
        `swap!`s `(Thread/currentThread)` into `:scheduler-timers*` then cancels,
        which `.interrupt`s the **test-runner thread**, setting an interrupt flag
        that can cross-contaminate other sleeping daemon timers.
      Fix (test-file-only, Slice-10 allowlist — zero
      `components/agent-session/src/**` or `doc/scheduler.md`): drive both via
      `capturing-delay-fn` (`assoc :scheduler-run-after-delay-fn capture*`),
      assert `:scheduler-timers*` membership **before** firing, fire by invoking
      the captured callback, and replace the `(Thread/currentThread)` handle with
      a non-runner handle (or the captured handle) so cancel no longer interrupts
      the runner. This restores the design's "no wall-clock sleeps / controlled
      time" discipline (these are the only scheduler sites still on the default
      delay-fn while asserting timer state — grep-confirmed). Then update
      `findings.md` Baseline + Cancellation rows to cite them as seam-driven and
      correct the Outcome's "all green / pre-existing isolation artifact"
      characterisation (the race is real under the canonical runner). Keep the
      full `bb test` green (re-run ≥3× to confirm non-flaky) + clj-kondo/cljfmt
      clean.
      Done: migrated both deftests off the default `Thread/sleep`-daemon
      delay-fn onto the deterministic `capturing-delay-fn` seam.
      `scheduler-create-stores-schedule-and-starts-timer-test` now threads
      `(assoc ctx :scheduler-run-after-delay-fn capture*)`, so the 1000ms timer
      is captured (never run on a real daemon) → the schedule cannot fire →
      deliver → remove the handle before the `:scheduler-timers*` membership
      assertion reads it.
      `scheduler-cancel-marks-pending-or-queued-schedule-cancelled-test`
      replaced the `(Thread/currentThread)` handle seed with a non-Thread
      `{:handle :captured}` sentinel — `:scheduler/cancel-timer` only
      `.interrupt`s a `(instance? Thread handle)` (`dispatch_effects.clj:244`),
      so the sentinel hits `:else nil` and cancel no longer interrupts the
      test-runner thread (no cross-contamination of sibling daemon timers).
      Assertions unchanged (4 `is` each) → aggregate stays **50 tests / 339
      assertions**. Verified: `scheduler-dispatch-test` green 3× (5 tests / 19
      assertions); all 13 scheduler ns run **together in isolation** now report
      **50 tests / 339 assertions / 0 failures**, stable across 2 runs — the
      prior in-isolation / intermittent-under-canonical-runner race is **gone**.
      Full `bb test` green; clj-kondo 0/0; cljfmt "All source files formatted
      correctly". Updated `findings.md` (Baseline seam row now cites both
      dispatch tests as seam-driven) and corrected the pass-4/5
      "pre-existing isolation artifact" characterisation in `implementation.md`
      (the race was real, now fixed at its root). Test files + task-dir docs
      only — zero `components/agent-session/src/**` or `doc/scheduler.md`
      (Slice-10 allowlist held).

- [ ] Fix the still-flaky cited lifecycle test
      `scheduler_lifecycle_test/scheduled-deliver-runs-canonical-prompt-lifecycle-test`
      (implementation-review finding 2026-06-02). The full scheduler suite still
      fails intermittently under the canonical kaocha runner (**4 failures one
      run, 0 on others; ~1-in-8 full-suite runs**), falsifying the prior
      flaky-baseline fix's "race is gone / all green deterministically (no
      lucky-run dependence)" claim. The two failing assertions are
      `(is (some #(= :session/prompt-record-response (:event-type %)) entries))`
      and `(is (some #(= :session/prompt-finish (:event-type %)) entries))`
      (`scheduler_lifecycle_test.clj:84-85`): the test dispatches
      `:scheduler/fired` synchronously then immediately reads
      `kernel/event-log-entries`, but `:session/prompt-record-response` /
      `:session/prompt-finish` are emitted by the **canonical prompt lifecycle /
      turn execution that completes asynchronously** after
      `:session/prompt-submit` returns — so the event-log read races the late
      lifecycle tail. (Passes 8/8 in isolation; fails ~1-in-8 only when the full
      13-ns scheduler suite runs together — cross-ns thread-scheduling pressure
      surfaces the async-completion-vs-synchronous-read race on the shared
      `kernel` event log.)
      Fix (test-file-only — Slice-10 allowlist, zero
      `components/agent-session/src/**` or `doc/scheduler.md`): preferred —
      replace the `with-redefs` stub of `psi.turn-runtime.core/execute-prepared-request!`
      with the synchronous `:execute-prepared-request-fn` `ctx` seam already used
      by `scheduler_end_to_end_test.clj` so delivery completes synchronously
      before the event-log read; alternative — await a bounded settle condition
      (poll the event log for `:session/prompt-finish` to a deadline, no
      `Thread/sleep` fixed wait) before asserting. Then re-verify the full
      scheduler suite ≥10× green under the canonical runner, and correct
      `findings.md` Outcome/Baseline (and any "all green" wording) to reflect the
      now-genuinely-deterministic state. Keep clj-kondo 0/0 and `bb fmt:check`
      clean.
