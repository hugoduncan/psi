# 201 — Verify scheduler and scheduled task execution

## Intent

Establish confidence that the scheduler subsystem is correct **end-to-end** —
both the pure state model and the live runtime firing/delivery path — and
remediate any defects discovered along the way.

The verification is the primary deliverable; fixes are whatever the
verification exposes. The success of this task is measured by *demonstrated
correct behaviour*, not by a predetermined code change.

## Problem

The scheduler has many moving parts across several layers:

- pure state model — `scheduler.clj` (create / cancel / fire / deliver / fail /
  drain; statuses `:pending :queued :delivered :cancelled :failed`; one-shot,
  volatile)
- mandatory time-source boundary — `scheduler_time.clj`
- dispatch handlers — `dispatch_handlers/scheduler.clj` (`:scheduler/fired`,
  create, cancel → emit `:scheduler/start-timer` / `:scheduler/cancel-timer` /
  `:scheduler/drain-queue` effects)
- live timer effects — `dispatch_effects.clj` (`:scheduler/start-timer` runs a
  daemon thread that `Thread/sleep`s then dispatches `:scheduler/fired`;
  `:scheduler/cancel-timer` interrupts the handle)
- psi-tool surface — `psi_tool_scheduler.clj` (`action "scheduler"`, `op`
  create / list / cancel)
- projections — `scheduler_runtime.clj` (EQL attrs, psi-tool summaries,
  background-job projection)

Existing unit/integration tests are extensive, but it is not established that
the **live execution path actually fires and delivers** — i.e. that a created
schedule reaches its origin session as an injected prompt (`kind "message"`),
or produces a fresh top-level session with the prompt submitted (`kind
"session"`), through the real timer → `:scheduler/fired` → deliver/queue round
trip. Latent defects most plausibly live at the layer seams: timer firing,
busy-session queueing and drain-on-idle, cancellation racing the timer,
session-kind creation, failure recording, and context-shutdown timer cleanup.

## Scope

Verification, layer by layer, of existing behaviour against documented intent
(`doc/scheduler.md`), plus fixes for any defect found:

1. **Baseline** — inventory and run the existing scheduler test suite; confirm
   current pass/fail state and capture it.
2. **Pure model** — confirm state transitions, bounds (`min/max-delay-ms`),
   duplicate/terminal-status guards, queue ordering, and `drain-one` semantics
   behave as specified.
3. **Live execution path** — demonstrate, through the real effect/dispatch
   round trip (with an injectable delay/time seam, not wall-clock waits):
   - `kind "message"`: timer fires → `:scheduler/fired` → delivered prompt
     appears in the origin session with scheduled provenance; busy session
     queues, then drains on idle.
   - `kind "session"`: timer fires → fresh top-level session created in the
     origin worktree/context → prompt submitted into it; `created-session-id`
     and `delivery-phase` recorded.
4. **psi-tool surface** — create / list / cancel behave and project correctly,
   including the `:at` (absolute instant) and `:delay-ms` (relative) inputs and
   the `message` vs `session` kinds.
5. **Cancellation & lifecycle** — cancel before fire; cancel racing the timer;
   `cancel-all`; context shutdown cancels outstanding timers (no leaked daemon
   threads, no fire after shutdown).
6. **Failure path** — delivery/creation failure records `:failed` with
   `error-summary` / `delivery-phase` and does not wedge the queue.
7. **Projections** — EQL (`:psi.scheduler/*`), psi-tool summary, and
   background-job projections stay coherent with underlying state across
   statuses.

For every defect found: write a failing test that reproduces it, fix at root
cause (prefer structural over patch), and follow the change_chain
(meta/spec/tests/code/doc). If no defects are found in an area, the deliverable
for that area is the verifying test/evidence itself.

## Out of scope

- Adding schedule **persistence** across process restart — the scheduler is
  intentionally volatile by design.
- **Recurring** schedules — intentionally one-shot by design.
- Architectural redesign of the scheduler layering.
- New scheduler features or new psi-tool ops beyond create / list / cancel.
- Emacs session-tree / background-job UI surfacing (task `021`).

## Adjacent task-like work

- If verification surfaces a desirable feature (persistence, recurrence, richer
  UI), capture it as a **new** task rather than expanding this one.

## Acceptance criteria

- The existing scheduler test suite passes (`bb test`), and the
  message-kind and session-kind **live execution paths** are each covered by a
  test that drives the real effect/dispatch round trip via an injectable
  time/delay seam (no wall-clock sleeps in tests).
- Busy-session queueing + drain-on-idle, cancellation (including cancel-racing
  the timer), failure recording, and context-shutdown timer cleanup each have
  passing verifying coverage.
- Every defect discovered is captured by a failing-then-passing test and fixed
  at root cause; coherence across meta/spec/tests/code/doc is maintained.
- `doc/scheduler.md` accurately reflects verified behaviour; corrected if drift
  is found.
- `clj-kondo` is clean on all touched files; `bb test` is green.

## Key concepts

- **one-shot / volatile** — schedules do not survive restart; no recurrence.
- **fire → deliver vs queue** — `:session` kind always delivers; `:message`
  kind delivers when the origin session is idle, otherwise queues until drained
  on idle.
- **time-source boundary** — all current-time reads go through an explicit
  injectable time source; tests must use a controlled source, never wall-clock.
- **timer seam** — the live `:scheduler/start-timer` effect supports an
  injectable `:scheduler-run-after-delay-fn`, enabling deterministic
  firing in tests without real delays.
