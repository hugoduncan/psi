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
  firing in tests without real delays. The full set of injectable
  timer/cancel seams available on `ctx` is:
  - `:scheduler-run-after-delay-fn` — `(fn [ctx delay-ms f] handle)`; tests
    capture `f` and the returned `handle` to fire on demand instead of
    sleeping. Returned handle is stored in both the internal
    `scheduler-timer-handles*` atom and any provided `:scheduler-timers*`.
  - `:scheduler-cancel-delay-fn` — `(fn [ctx handle] …)`; invoked by
    `:scheduler/cancel-timer` for non-`Thread` handles, giving tests a
    deterministic cancel observation point (the default path interrupts a
    `Thread`).
  - `:scheduler-timers*` — an atom map `schedule-id → handle` mirrored by
    start/cancel/fire, letting tests assert outstanding-timer membership
    without reaching into the private `scheduler-timer-handles*` atom.
  - `:daemon-thread-fn` — `(fn [thunk] …)`; the default
    `:scheduler-run-after-delay-fn` uses it to spawn the sleeping daemon;
    tests can substitute a synchronous runner.

## Verification mechanics (resolved 2026-06-01)

These resolve the ambiguities recorded in `implementation.md`. They constrain
*how* the verification tests drive behaviour; they do not change scheduler
source.

### Drain-on-idle trigger

There is no idle detector. Queue drain is driven explicitly by the
`:scheduler/drain-queue` dispatch event (emitted in production by statechart
actions on session-turn termination / on-abort, and surfaced as the
`:scheduler/drain-queue` effect which re-dispatches the same event). The
message-kind busy/queue test therefore: (1) fires while the origin session is
non-idle (`:is-streaming` or `:is-compacting` true) → schedule moves to
`:queued`; (2) sets the session idle; (3) dispatches `:scheduler/drain-queue`
directly → asserts `drain-one` delivers the next queued schedule (oldest by
`fire-at`, `created-at`, `schedule-id`). The test drives drain via the dispatch
event, not via wall-clock idle transitions.

### Cancel-racing-the-timer races

Two distinct races, each verified:

1. **Cancel before the timer callback dispatches `:scheduler/fired`.** Using
   the captured seam, cancel runs (`:scheduler/cancel` → status `:cancelled`,
   `:scheduler/cancel-timer` removes the handle) *before* the captured callback
   is invoked. Expected: schedule is `:cancelled`; if the stale callback is
   then invoked, `fire-schedule` throws `"only pending schedules can fire"`
   (non-`:pending`), and the live callback swallows `InterruptedException` only
   — a thrown `ex-info` from a stale fire surfaces. The verification asserts the
   schedule stays `:cancelled` and that invoking the stale callback does not
   resurrect it.
2. **`:scheduler/fired` already dispatched (schedule past `:pending`).** Here
   the timer has fired and the schedule is `:queued`/`:delivered`/`:failed`
   before cancel. Expected: `cancel-schedule` succeeds only from
   `:pending`/`:queued` (it throws `"schedule is not cancellable"` for terminal
   statuses); the `:queued`→cancel path is the deliverable race and is asserted
   to move `:queued` → `:cancelled` and remove the id from the queue.

### Context-shutdown surface

The shutdown entry point is `psi.agent-session.context/shutdown-context!`,
which (a) dispatches `:scheduler/cancel-all` per session and (b) calls
`psi.agent-session.dispatch-effects/cancel-all-scheduler-timers!` and resets
`:scheduler-timers*`. The observable assertions are:
`dispatch-effects/scheduler-timer-handle-count` returns `0` after shutdown, the
`:scheduler-timers*` atom is empty, and no captured callback fires a
`:scheduler/fired` after shutdown (handles removed). Tests may assert against
either `cancel-all-scheduler-timers!` directly or the full
`shutdown-context!` surface.

### `:at` past / sub-min-delay behaviour

`:at` is *not* min-delay-bounds-validated when it resolves to the present or
past. `resolve-fire-time!` computes `delay = max(0, between(now, at))` and only
calls `validate-delay-ms!` when `delay` is strictly positive. Consequences,
which the psi-tool-surface test asserts:
- past or now `:at` → `delay = 0`, no min-delay check → schedule is created and
  fires immediately (delay 0); matches `doc/scheduler.md` "past absolute
  instants fire immediately".
- future `:at` resolving below `min-delay-ms` (1–999ms ahead) → `delay` is
  positive but `< 1000` → `validate-delay-ms!` throws "below the minimum
  bound". So sub-min-delay future `:at` is rejected exactly like a sub-min
  `:delay-ms`.
- `:at` above `max-delay-ms` (>24h ahead) → rejected "exceeds the maximum
  bound".
This asymmetry (past allowed, near-future rejected) is current behaviour; if it
reads as a doc/behaviour drift it is recorded as a finding, not changed.

### "Real effect/dispatch round trip"

Live tests drive the *real dispatch pipeline and real effect executor*
synchronously — they call `dispatch-in!`/`dispatch!` and let the registered
effect handlers run, but replace only the *time/delay boundary* via the timer
seams above so firing is deterministic rather than wall-clock. They do **not**
substitute the handlers themselves. The handler-purity / runtime-owned-deliver
frontier means delivery (`:scheduler/deliver`, synthetic-prompt submission,
top-level session creation) executes through the same runtime-owned effects as
production; the seam only controls *when* `:scheduler/fired` is dispatched. "No
wall-clock sleeps" therefore means: no `Thread/sleep`-based waiting in tests —
the captured callback is invoked directly to advance time.

### Findings-inventory artifact

The findings inventory is a single Markdown file in the task directory:
`munera/open/201-verify-scheduler-execution/findings.md`. Required structure —
one section per Scope area (Baseline, Pure model, Live execution path, psi-tool
surface, Cancellation & lifecycle, Failure path, Projections). Each section
lists entries with: status (`verified-correct` | `defect`), a one-line summary,
the covering verification test (ns + deftest), and — for defects —
reproduction notes plus the raised remediation task reference (`NNN-slug` or
"not-yet-raised"). `implementation.md` remains the append-only working log;
`findings.md` is the structured deliverable.

### Remediation-task creation policy

Given the verification-only framing, this task **describes** defects in
`findings.md` and creates remediation tasks **only when a defect is actually
found** during execution. When a defect is found, a new `munera/open/NNN-slug`
dir is created (carrying the reproducing failing test, which stays *in that new
task*, not committed green here) and referenced from `findings.md`. If no
defect is found, no remediation dirs are created — the deliverable is the
green verification coverage plus a `findings.md` recording all areas as
`verified-correct`.
