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
