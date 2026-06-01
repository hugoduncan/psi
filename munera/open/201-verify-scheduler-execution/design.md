# 201 — Verify scheduler and scheduled task execution (verification-only)

## Intent

Establish confidence that the scheduler subsystem behaves correctly
**end-to-end** — both the pure state model and the live runtime firing/delivery
path — by exercising and documenting its behaviour.

This is a **verification-only** task. The deliverable is demonstrated evidence
of behaviour (passing characterisation/integration coverage) plus a clear
findings record. Defects discovered are **reported**, not fixed here: each is
captured as a new task (and a reproducing failing test where practical). The
success of this task is measured by *coverage and a defect inventory*, not by
any remediation.

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
(`doc/scheduler.md`):

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

The deliverable for each area is the verifying test/evidence itself. Where a
defect is found, record it in the findings inventory and (where practical)
write a failing test that reproduces it — but **do not fix it here**; raise a
separate task for remediation. New verification tests added by this task must
pass against current behaviour (a reproducing failing test for a defect stays
in the new remediation task, not committed green here).

## Out of scope

- **Fixing** any defect found — remediation is a separate task per defect. This
  task only verifies and reports.
- **Changing** scheduler source code, `doc/scheduler.md`, or behaviour. (Drift
  found between doc and behaviour is *recorded as a finding*, not corrected
  here.) Only new test/characterisation namespaces are added.
- Adding schedule **persistence** across process restart — intentionally
  volatile by design.
- **Recurring** schedules — intentionally one-shot by design.
- Architectural redesign of the scheduler layering.
- New scheduler features or new psi-tool ops beyond create / list / cancel.
- Emacs session-tree / background-job UI surfacing (task `021`).

## Adjacent task-like work

- Each defect found → a **new remediation task** (carrying its reproducing
  failing test), not handled here.
- If verification surfaces a desirable feature (persistence, recurrence, richer
  UI), capture it as a **new** task rather than expanding this one.

## Acceptance criteria

- The existing scheduler test suite passes (`bb test`), and the
  message-kind and session-kind **live execution paths** are each covered by a
  new verification test that drives the real effect/dispatch round trip via an
  injectable time/delay seam (no wall-clock sleeps in tests).
- Busy-session queueing + drain-on-idle, cancellation (including cancel-racing
  the timer), failure recording, and context-shutdown timer cleanup each have
  passing verifying coverage.
- A **findings inventory** records, per area: verified-correct, or defect
  (with reproduction notes and a raised remediation task reference).
- New verification tests pass against current behaviour; no scheduler
  source/doc/behaviour is modified by this task.
- `clj-kondo` is clean on all touched (test) files; `bb test` is green.

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
