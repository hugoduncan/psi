# 225 Fix: workflow/delegate cancellation does not halt in-flight async execution

## Intent

Cancelling a running delegated workflow must actually stop it. Today, cancelling
(or removing) a run updates registry/run state but leaves the in-flight worker
**future** driving the workflow forward: it keeps advancing steps, spawning child
agent sessions, and committing to the worktree. A cancelled run is a runaway.

This task makes cancellation authoritative: after a cancel, no further steps
execute, no further child sessions spawn, and **no new side effects are
initiated** (commits, journal writes) — transitively across nested sub-runs. A
single tool call already in syscall flight at the interrupt instant may complete;
nothing past that point starts (see D6).

## Evidence (observed this session)

A redundant `task-lifecycle` run (`224-lifecycle`) on an already-closed task was
cancelled and then removed, yet kept committing review/follow-up commits onto the
worktree for several minutes:

1. `workflow cancel-run 224-lifecycle` → run marked `:cancelled`, **but commits
   continued**.
2. `workflow cancel-run` of the nested sub-run → reported `:cancelled`, **but a
   later step still committed**.
3. `delegate remove 224-lifecycle` → removed the run record and dropped the
   future handle from `inflight-runs`, **orphaning the still-running thread** — it
   kept driving execution with no remaining handle to cancel it.
4. The only thing that stopped it was manually locating the worker thread
   (`clojure-agent-send-off-pool-39`, parked on a `send-and-drain` promise deref)
   and calling `Thread.interrupt` on it via the REPL.

Net effect: 7 spurious commits landed on a closed task and had to be removed by a
history rewrite.

## Root Cause (to be confirmed during design)

The async execution path launches a future
(`workflow.orchestration/execute-async!`) that calls
`(mutate! 'psi.workflow/execute-run …)`, which drives the workflow step machine
**synchronously to a terminal state**. Cancellation is not cooperative with this
loop:

- `cancel-run` sets the run's status to `:cancelled` in the registry, but the
  in-flight `execute-run` step loop does not check run-cancellation between steps,
  so it keeps advancing. The step driver blocks on a `send-and-drain` promise
  waiting for each child agent turn, then unconditionally advances on completion.
- `delegate remove` (`remove-run`) dissocs the run from `inflight-runs` **without
  `future-cancel`**, orphaning the worker thread and discarding the only handle
  that could stop it.
- Cancellation does not propagate to nested delegate sub-runs or to the child
  agent sessions executing turns (which perform the LLM calls and `git commit`).

## Desired Behaviour

- **Cooperative stop:** after a run is cancelled, the execution loop halts at the
  next safe checkpoint (at minimum, between steps) — it must not start another
  step, create another sub-run, or spawn another child session.
- **No orphaned futures:** cancelling a run cancels/interrupts its worker future;
  `remove` of a live run cancels it first then removes the record (cancel-then-
  remove, D5) — never drop the handle and leave the thread running.
- **Transitive propagation:** cancelling a parent run cancels its in-flight nested
  delegate sub-runs and signals their child agent sessions to stop, so the whole
  tree winds down.
- **In-flight child turn:** when cancel arrives with a child agent turn already
  mid-execution, the workflow does not advance past that step **and** the child
  turn is interrupted (guaranteed, via the `:session/abort` path) so it initiates
  no further tool calls / commits — only a single already-in-syscall-flight effect
  may complete (D6).
- **Idempotent / race-safe:** cancel during a step boundary, during a blocking
  wait, and after natural completion must all behave sanely (no double-terminal,
  no resurrection).
- Cancelled runs reach a clean terminal state with their background job marked
  terminal (no lingering `:running` job, as also seen this session).

## Scope

In scope:

- A cooperative cancellation check in the workflow execution/step-advance loop
  keyed on run status (`:cancelled`/removed), so the loop exits promptly.
- `execute-async!` / inflight-run lifecycle: `future-cancel` (with interrupt) on
  cancel; `remove` of a live run cancels-then-removes (D5) rather than orphaning.
- Propagation of cancellation to nested delegate sub-runs and their child agent
  sessions (reuse the existing session interrupt/abort path where possible).
- Ensuring the background job for a cancelled run is marked terminal.
- Tests: (a) a cancelled run performs no further step attempts after the cancel
  checkpoint; (b) `remove` of a live run does not leave a running future; (c)
  nested sub-run + child-session cancellation propagation; (d) no commits/journal
  writes after cancel in a controlled (nullable) harness.

Out of scope:

- The duplicate-tool_result fix (task 224 — already closed).
- Redesigning the workflow step machine or the delegate result-delivery paths
  beyond what cancellation propagation requires.
- Force-killing threads as the primary mechanism (manual `Thread.interrupt` was a
  one-off recovery, not the intended API).

## Design Questions (resolve during refinement)

All four resolved — see "Design Questions — Resolution status" below for pointers.

1. **Checkpoint granularity.** Between-steps cooperative check only, vs. also
   interrupting an in-flight child agent turn. Between-steps is simplest and stops
   runaway advancement; interrupting the current turn additionally prevents the
   one in-flight commit. Decide the guaranteed contract. → **RESOLVED (D6, D7).**
2. **Child-session stop mechanism.** Cooperative cancel signal threaded into the
   child session turn vs. thread interrupt of the executing worker. Prefer the
   existing session interrupt/abort pathway if it cleanly aborts a turn.
   → **RESOLVED (D3, D9).**
3. **`remove` semantics on a live run.** Cancel-then-remove vs. reject-with-error
   while running. Cancel-then-remove is friendlier; reject is safer against
   orphaning. Pick one and make it explicit. → **RESOLVED (D5): cancel-then-remove.**
4. **Synchronous `execute-run` boundary.** Whether cancellation needs the step
   loop to poll run status (pull) or to receive an interrupt/flag (push), given
   the loop blocks on `send-and-drain` promises — likely both: a status poll at
   each step boundary plus interrupt-aware waits. → **RESOLVED (D2, D7, D8): both.**

## Architecture & Boundary Decisions (ψ, 2026-06-10)

Resolves the four architecture-fit follow-ups raised by the design review. These
state how cancellation maps onto the project's State boundary, effects-as-data,
session-dispatch authority, and dispatch-serialization invariants — they do not
redesign the step machine (still out of scope).

### D1. Side effects as data at the runtime boundary (¬inline mutation side effects)

The pure workflow-runtime transition functions stay pure. `cancel-run` /
`remove-run` in `psi.workflow-runtime.core` remain `state → [state', run]` and
perform **no** side effects: no `future-cancel`, no thread interrupt, no
child-session abort inline.

The three cancellation side effects (`future-cancel`/interrupt of the worker
future, child-session abort) are modeled as **effects-as-data** returned from the
cancel/remove path and executed at the orchestration runtime boundary — the layer
that already owns `inflight-runs` (`psi.agent-session.workflow.orchestration` /
`runtime_state`). Concretely the agent-session cancel/remove mutation:

1. commits the pure canonical-state transition (signal → `:cancelled`), then
2. emits a cancellation effect (e.g. `{:cancel-inflight-run {:run-id …}}` plus,
   transitively, child-session abort effects) that the runtime boundary executes
   against the future handle and the session-dispatch authority.

This keeps side effects out of the pure transition and out of silent inline
mutation bodies, satisfying AGENTS.md S1/S3 + `λ(state)` (effects flow as data,
executed at the boundary). No legacy-mutation exception is taken; the only
canonical-state write in the mutation is the pure status transition (see D4 for
its serialization).

### D2. Signal in canonical `:state*`; future/`inflight-runs` is a runtime handle

The **cancellation signal** is the run's `:status :cancelled` already committed to
canonical `:state*` at `(run-path run-id)` by `cancel-run`. The cooperative
step-loop check reads this signal **via the read path** (`workflow-run-in` / a
status read) at each step boundary and at interrupt-aware wait wake-ups, and exits
promptly when it observes `:cancelled` (or a removed run).

`inflight-runs` and the worker `future` stay a **pure runtime handle** (per
doc/architecture.md "State boundary: canonical root vs runtime handles" — the
workflow registry / pump thread is already listed as a handle, projected into
`:state*` as background-job + workflow public data). The handle never becomes
queryable domain state; only its observable status is projected. Split:

- signal (status, terminal-outcome, history) ∈ canonical `:state*`
- handle (future, job-id, thread) ∈ `inflight-runs` runtime handle

The step loop is driven by the **signal** (pull via read path), not by reaching
into the handle.

### D3. Transitive cascade owned by the agent-session session-dispatch authority

The cancellation cascade (nested delegate sub-runs + child agent sessions) is
owned by a coordinated dispatch path routed through the agent-session
session-dispatch authority — not ad-hoc cross-handle reach-in and **not** a
propagation shim (AGENTS.md authority + `λ shims_adapters`).

- **Nested sub-runs:** the parent cancel enumerates in-flight nested sub-runs from
  the canonical run-tree state (`:state*`) and dispatches a cancel for each
  (recursively), reusing the same cancel mutation path. Discovery is from
  canonical state via the read path; no cross-handle reach-in.
- **Child agent sessions:** child-turn abort reuses the **existing** session
  interrupt/abort pathway (`turn/abort-active-turn-in!` → `:session/abort`
  dispatch → `agent-core/abort-in!` → context thread interrupt), which already
  aborts an in-flight turn. This is `λ extend` compose-over-new-mechanism; no new
  abort mechanism is introduced.

agent-session remains the authoritative owner of session-dispatch invocation;
workflow-runtime exposes pure domain APIs (run-tree reads, pure transitions) that
the agent-session cascade composes.

### D4. Terminal transitions route through serialized dispatch (single-writer)

Terminal-state transitions (cancel / remove / natural complete) route through the
serialized dispatch single-writer path (`state-kernel` dispatch pipeline) to earn
the design's idempotent / no-double-terminal / cancel-during-wait race-safety —
the same shape solved in task 224 by atomicity-from-dispatch-serialization.

The current `reset!`-on-`:state*` check-then-write in the cancel/remove mutations
is a TOCTOU race (status guard read, then unconditional `reset!`). The decision:
the read-guard-and-commit of the terminal transition is performed atomically under
the single serialized writer (dispatch), so that:

- two concurrent cancels, or cancel racing natural completion, cannot both apply a
  terminal transition (no double-terminal, no resurrection);
- a cancel arriving during a blocking wait commits the signal atomically, and the
  step loop observes it at the next read-path checkpoint (D2);
- terminal transitions are idempotent — a second terminal request on an
  already-terminal run is a no-op via the in-pipeline guard, not a racy outer check.

This supersedes ad-hoc guards on a directly-`reset!`'d atom. The pure transition
functions keep their argument-checks (terminal-status precondition) but the
authoritative atomicity comes from dispatch serialization, not the mutation's
outer `when` guard.

## Behaviour-Contract Decisions (ψ, 2026-06-10)

Resolves the ambiguity follow-ups raised by the design review. These pick a
single explicit contract for each under-specified behaviour and remove the
remaining "(or …)" / "/" alternatives. They do not redesign the step machine.

### D5. `remove` of a live run = cancel-then-remove (resolves Q3)

`remove` of a live (non-terminal) run is **cancel-then-remove**, not
reject-while-live. A `remove` request on a live run:

1. commits the `:cancelled` terminal transition (D4: serialized single-writer),
2. emits the cancellation effects (D1: `future-cancel`/interrupt + transitive
   cascade per D3),
3. removes the run record from the registry/`inflight-runs`.

The `future-cancel` interrupt (D8) guarantees the worker stops even though its run
record and canonical signal are gone after removal — so removal cannot re-orphan
the thread. This matches the observed user intent in the Evidence (the operator
ran `delegate remove` expecting the run to stop). `remove` of an
already-terminal run is unchanged (plain record removal). This is the single
chosen semantics; Desired Behaviour and Scope are updated to drop the "or refuse
while live" / "(or rejects)" alternative.

### D6. In-flight child turn: guaranteed interrupt action; "no new side effects initiated" contract (resolves Q1)

The directly-cancelled run's in-flight child turn is **always interrupted** (a
guaranteed action, via the D3/D9 `:session/abort` path), not best-effort. The
guarantee is stated over side effects that have **not yet started**:

- **Guaranteed:** after the cancel checkpoint no further step executes, no further
  sub-run is created, no further child session spawns, and the in-flight child
  turn is interrupted so it initiates **no new tool calls / commits**.
- **Not guaranteed (physics):** a single tool call already in syscall flight at the
  instant of interrupt (e.g. a `git commit` already issued) may complete; it cannot
  be recalled.

So the absolute Intent/Acceptance "no further side effects after cancel" is
restated precisely as "**no new side effects are initiated** after the cancel
checkpoint." The interrupt itself is the guaranteed requirement; the residual
in-flight effect is the only permitted exception. The nullable/controlled
acceptance harness asserts the guaranteed property (no *new* tool call initiated
after the checkpoint), which is deterministic.

### D7. Cancel during a blocking `send-and-drain` wait: actively interrupted (resolves part of Q4)

A cancel arriving while the step loop is parked on a `send-and-drain` deref
**actively interrupts the wait** — it does not wait for the child turn to complete
naturally. `future-cancel(mayInterruptIfRunning=true)` (D8) delivers a thread
interrupt that wakes the parked deref; the loop then returns to the cooperative
checkpoint, observes the `:cancelled` signal (or the `InterruptedException`), and
exits to a clean terminal. **Guaranteed stop bound:** interrupt delivery + child
abort, *not* natural turn completion. This is the concrete behaviour behind D2's
"interrupt-aware wait wake-ups"; the "next safe checkpoint (at minimum, between
steps)" wording in Desired Behaviour is the *cooperative* path for cancels that
arrive while running between steps — the parked-wait case is the interrupt path.

### D8. Division of labor: cooperative read = advance-guard (pull); future-cancel/interrupt = wait-wakeup + removed-run backstop (push)

Both mechanisms are required and cover **different runtime states**; neither is
merely a backstop:

- **Cooperative read-path check (D2) — primary advance-guard (pull).** At each
  step boundary and after each wait wake-up the loop reads the canonical
  `:cancelled` signal via the read path and refuses to start the next
  step/sub-run/child-session. This is the authoritative "do not advance"
  mechanism, and the only one needed when a cancel arrives while the loop is
  running between steps.
- **`future-cancel(true)` / interrupt — wait-wakeup + removed-run backstop
  (push).** Used to (a) wake a thread parked on a `send-and-drain` deref so the
  cooperative check can run (D7), and (b) stop a worker whose run record/signal was
  already removed by `remove` (D5), where no signal remains to read.

Consequently **interrupt-safety of the `send-and-drain` wait is in scope**: the
wait must propagate/handle `InterruptedException` so control returns to the
cooperative checkpoint for a clean terminal exit rather than leaking the interrupt
or dying uncleanly.

### D9. Single child-session-abort path: effect handler invokes the dispatch authority (reconciles D1 and D3)

D1 and D3 describe **one path, not two owners**. Child-session abort is a
cancellation effect-as-data emitted at the cancel/remove boundary (D1) whose
**effect handler invokes the agent-session session-dispatch authority's
`:session/abort`** (D3) — i.e. `emit effect → runtime-boundary effect handler →
:session/abort dispatch → agent-core/abort-in! → context thread interrupt`. D1
names *where the effect is emitted and executed* (the runtime boundary that owns
`inflight-runs`); D3 names *what that handler invokes* (the session-dispatch
authority). There is a single owner of the invocation (agent-session) reached
through a single effect path.

## Design Questions — Resolution status (ψ, 2026-06-10)

The "Design Questions (resolve during refinement)" Q1–Q4 above are resolved as:

- **Q1 (checkpoint granularity / in-flight child turn):** RESOLVED by D6 (+ D7) —
  guaranteed between-steps non-advancement *and* guaranteed interrupt of the
  in-flight child turn; contract is "no new side effects initiated after the cancel
  checkpoint."
- **Q2 (child-session stop mechanism):** RESOLVED by D3 + D9 — reuse the existing
  `:session/abort` session interrupt/abort pathway (compose > new mechanism).
- **Q3 (`remove` semantics on a live run):** RESOLVED by D5 — cancel-then-remove.
- **Q4 (synchronous `execute-run` boundary, pull vs push):** RESOLVED by D2 + D7 +
  D8 — both: read-path status poll at each step boundary (pull) *plus*
  interrupt-aware `send-and-drain` waits via `future-cancel(true)` (push).

No Design Questions remain live.

## Acceptance Criteria

- A test cancels a multi-step workflow run mid-flight and asserts that **no step
  attempt is started after the cancel checkpoint** and the run reaches a clean
  `:cancelled` terminal state with its background job terminal.
- A test asserts `remove` of a live run does not leave a running worker future /
  orphaned thread (future is cancelled, inflight cleared only after cancel).
- A test asserts cancellation propagates to a nested delegate sub-run: its child
  session is signalled to stop so its turn does not advance the parent.
- No **new** side effects (commits, journal writes, new child sessions) are
  initiated after the cancel checkpoint — the in-flight turn is interrupted and at
  most one already-in-flight tool call may complete (D6) — verified in a
  nullable/controlled harness.
- `bb test` green; clj-kondo clean; CHANGELOG updated (user-visible: cancelling a
  delegated workflow now actually stops it).
