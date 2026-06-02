# Plan — 201 Verify scheduler & scheduled task execution (verification-only)

## Approach

This is a **verification-only** task: no scheduler source, `doc/scheduler.md`,
or behaviour changes. The deliverable is (a) green verification coverage of the
end-to-end firing/delivery path and (b) a structured `findings.md` recording, per
Scope area, `verified-correct` or `defect` (with reproduction notes + remediation
task ref).

Work is an **audit-then-fill** loop, executed Scope area by Scope area:

1. For each Scope area, inventory the existing tests that already cover it (the
   suite is already extensive — see existing `scheduler_*_test.clj`).
2. Map existing coverage to the area's intended behaviour from `doc/scheduler.md`
   and design.md "Key concepts" / "Verification mechanics".
3. Where coverage exists and passes → record the area `verified-correct` in
   `findings.md`, citing the covering `ns + deftest`.
4. Where coverage is **missing or insufficient** for the area's acceptance
   criterion → add a new verification test (new namespace, or new deftest in a
   clearly-verification namespace) that drives the behaviour via the time/timer
   seam, asserting against current behaviour so it passes green.
5. Where a **defect** is discovered → record it in `findings.md` with
   reproduction notes; create a `munera/open/NNN-slug` remediation task carrying
   the reproducing **failing** test (which stays in the new task, NOT committed
   green here); reference it from `findings.md`.

### Sufficient-coverage criterion (audit → cite-vs-add)

Operational rule for the step-4 / "Reuse before adding" gate and every
Slice "Ensure a test exists … Add if missing" item. Existing coverage is
**sufficient** for an acceptance area (→ cite it, do not add) iff all hold:

1. **Asserts the area's named state/outputs.** The test asserts on the specific
   state/output that area enumerates (e.g. delivered prompt + scheduled
   provenance for message-kind; `created-session-id`/`delivery-phase` for
   session-kind; `:cancelled` + handle/queue removal for cancel; `:failed` +
   `error-summary`/`delivery-phase` for failure; handle-count 0 + empty
   `:scheduler-timers*` for shutdown), not just a status enum in isolation.
2. **Drives the real path via the seam where the area is a *live* path.** For
   live-execution / timer / drain / shutdown areas (Slices 2/3/4/6 shutdown/7),
   the existing test must drive the real dispatch+effect path and cross the
   time/timer boundary through the seams (capture+invoke the callback, or
   dispatch `:scheduler/drain-queue`), not stub delivery and not wall-clock
   sleep. Pure-model areas (Slice 1) need no seam.
3. **Asserts state/outputs, not handler interactions.**

Coverage is **insufficient** (→ add a new verifying test, green against current
behaviour) if any clause fails — e.g. the behaviour is only exercised
indirectly, asserts interactions, or never crosses the timer seam for a live
area. A single existing test satisfying (1)–(3) suffices; multiple tests may
jointly satisfy them. `findings.md` cites the authoritative test(s) per area
regardless of pre-existing vs new.

### Key decisions (from resolved design)

- **No new source/doc edits.** Only new test/characterisation namespaces and the
  task-local `findings.md` are added. Any doc↔behaviour drift is a *finding*.
- **Time/timer seams only.** Live tests drive the real dispatch pipeline + real
  effect executor synchronously; only the time/delay boundary is replaced via
  the seams already wired in `test_support/make-session-ctx`
  (`:scheduler-run-after-delay-fn`, `:scheduler-cancel-delay-fn`,
  `:scheduler-timers*`, `:daemon-thread-fn`). No `Thread/sleep` waiting in tests
  — fire by invoking the captured callback directly.
- **Drain is dispatch-driven.** Message-kind busy/queue/drain tests fire while
  the origin session is non-idle (`:is-streaming`/`:is-compacting`), set idle,
  then dispatch `:scheduler/drain-queue` directly (there is no idle detector).
- **Acceptance focus.** The new-coverage gaps that the acceptance criteria
  explicitly require: message-kind live round trip, session-kind live round trip,
  busy-queue + drain-on-idle, cancel-racing-the-timer, failure recording, and
  context-shutdown timer cleanup — each must have passing verifying coverage
  (new test if not already present).
- **Reuse before adding.** Prefer asserting against / extending existing tests
  over duplicating coverage; add a new test only where a required behaviour is
  not already demonstrably covered. `findings.md` cites the authoritative test
  per area regardless of whether it is pre-existing or new.
- **findings.md is the structured deliverable**; `implementation.md` stays the
  append-only working log.

## Risks

- **Coverage already complete for some areas.** The existing suite may already
  cover an acceptance area; then the deliverable for that area is the citation in
  `findings.md`, not a redundant new test. Risk: adding duplicate tests. Mitigation:
  inventory first (Slice 0), add only where a gap exists.
- **Live round-trip seam fidelity.** Driving the real effect executor
  synchronously while only swapping the time boundary must not accidentally stub
  delivery. Mitigation: build live tests on `test_support/make-session-ctx`'s
  already-wired seams; assert real delivered-prompt provenance / created session.
- **Runtime-owned deliver frontier.** `:scheduler/deliver`, synthetic-prompt
  submission, and top-level session creation run through runtime-owned effects;
  asserting their observable result (not interactions) is required. Mitigation:
  assert state/outputs (delivered prompt in session, `created-session-id`,
  `delivery-phase`), never handler interactions.
- **Cancel/timer race nondeterminism.** Races must be made deterministic via the
  captured callback + `:scheduler-cancel-delay-fn`, not timing. Mitigation: use
  the seams to order cancel vs callback explicitly.
- **Defect-found branch is conditional work.** If a defect is found, a remediation
  task dir + failing repro must be created; if none, no remediation dir is
  created. Mitigation: Slice 8 is conditional and gated on actual findings.
- **`clj-kondo` / `cljfmt` cleanliness** on new test files is an acceptance
  gate. Mitigation: lint + format each new test file before commit.

## Slice order

Each slice is a vertical area-verification (audit → fill gap → record finding),
keeping `bb test` green throughout. Slices map to design Scope areas
**mostly 1:1, with one deliberate 3:1 split**: design Scope area #3 "Live
execution path" is verified across **three** slices — Slice 2 (message kind),
Slice 3 (busy-session queue + drain-on-idle), and Slice 4 (session kind). All
three record into the **single shared "Live execution path"** section of the
fixed 7-section `findings.md` skeleton (Baseline, Pure model, Live execution
path, psi-tool surface, Cancellation & lifecycle, Failure path, Projections);
they do **not** create three separate findings sections. The remaining slices
map 1:1 to their Scope areas (Slice 0→Baseline, 1→Pure model, 5→psi-tool
surface, 6→Cancellation & lifecycle, 7→Failure path, 8→Projections).

0. **Baseline** — inventory existing scheduler tests; run `bb test`; capture
   current pass/fail; start `findings.md` skeleton (one section per Scope area).
1. **Pure model** — verify state transitions, bounds, duplicate/terminal guards,
   queue ordering, `drain-one`; record finding.
2. **Live execution — message kind** — real round trip, idle delivery with
   scheduled provenance; gap-fill new test if not covered; record finding.
3. **Busy-session queue + drain-on-idle** — fire while busy → `:queued`; set
   idle; dispatch `:scheduler/drain-queue` → oldest delivered; record finding.
4. **Live execution — session kind** — real round trip → fresh top-level session
   in origin context + prompt submitted; `created-session-id` /
   `delivery-phase` recorded; record finding.
5. **psi-tool surface** — create / list / cancel; `:at` (past/now → immediate,
   future <min → reject, >max → reject) and `:delay-ms`; message vs session
   kinds; record finding (including the `:at` asymmetry as a potential drift).
6. **Cancellation & lifecycle** — cancel before fire; cancel-racing-the-timer
   (both races); `cancel-all`; context shutdown clears timers (handle count 0,
   `:scheduler-timers*` empty, no post-shutdown fire); record finding.
7. **Failure path** — delivery/creation failure records `:failed` with
   `error-summary` / `delivery-phase`; queue not wedged; record finding.
8. **Projections** — EQL `:psi.scheduler/*`, psi-tool summary, background-job
   projection coherent across statuses; record finding.
9. **Defect handling (conditional)** — for any defect found in 1–8: create
   `munera/open/NNN-slug` remediation task carrying the reproducing failing test;
   reference from `findings.md`. Skip if no defect found.
10. **Close-out** — finalise `findings.md`; `cljfmt`/`clj-kondo` clean on all
    touched test files; `bb test` green; final coherence check.
