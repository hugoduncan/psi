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
  tree winds down. Because nested sub-runs run synchronously on the single
  top-level worker thread (no per-sub-run future), the wind-down is: a `:cancelled`
  signal per in-flight sub-run (pull stop), the one top-level `future-cancel(true)`
  interrupt (push wake-up), and a child-session abort per in-flight attempt
  (D14/D15).
- **In-flight child turn:** when cancel arrives with a child agent turn already
  mid-execution, the workflow does not advance past that step **and** the child
  turn is interrupted (guaranteed, via the `:session/abort` path) so it initiates
  no further tool calls / commits — only a single already-in-syscall-flight effect
  may complete (D6).
- **Idempotent / race-safe:** cancel during a step boundary, during a blocking
  wait, and after natural completion must all behave sanely (no double-terminal,
  no resurrection).
- Cancelled runs reach a clean terminal state with their background job marked
  terminal (no lingering `:running` job, as also seen this session) — job
  terminalization is emitted by the D2/D4 terminal transition reusing the existing
  `:runtime/mark-workflow-jobs-terminal` effect, the single writer for the
  background-job (projected) terminal status (D13) — the run's own `:status` is
  written by the D4 dispatch terminal transition, the single *logical* writer of
  run `:status` (atomicity from the apply-phase atom CAS with the guard inside the
  `:root-state-update` fn, D20 — not dispatch serialization) — not a separate
  registry write.

## Scope

In scope:

- A cooperative cancellation check in the workflow execution/step-advance loop
  keyed on run status (`:cancelled`/removed), so the loop exits promptly.
- `execute-async!` / inflight-run lifecycle: `future-cancel` (with interrupt) on
  cancel; `remove` of a live run cancels-then-removes (D5) rather than orphaning.
- Propagation of cancellation to nested delegate sub-runs and their child agent
  sessions (reuse the existing session interrupt/abort path where possible).
  Effect targets are pinned: the worker `future-cancel` hits only the single
  top-level run's future (sub-runs are synchronous, no own future — D14); the
  child-session abort targets each in-flight run's current attempt
  `:execution-session-id`, never a run's `:parent-session-id` (D15); the D3
  cascade enumerates non-terminal (`#{:pending :running :blocked}`) descendants by
  `:delegating-run-id` parentage.
- **Direct** cancellation of a nested sub-run (Evidence step 2): in scope. The
  cascade runs downward from the cancelled sub-run only; the worker
  `future-cancel(true)` is **not** emitted (it is reserved for top-level-run
  cancels — D14/D19); the downward child-abort unblocks the shared parent worker,
  the sub-run reaches `:cancelled`, and the parent observes it as a **failed
  delegate step** via the existing `delegate-step-runtime-result` `:cancelled`
  case and continues (the parent is not halted) — D19. Direct **`remove`** of a
  live nested sub-run is likewise in scope (cancel-then-remove per D5/D17); after
  the record is dropped the parent reads run-absence, which maps to the **same**
  failed-delegate-step result as `:cancelled` so the parent's continue-not-halt
  outcome is race-independent — D21.
- Modelling the cancellation side effects as canonical dispatch `:runtime/*`
  effects (parity: `effect-schema` + `execute-effect!`) executed by the dispatch
  `:effects` interceptor — child-session abort reusing the existing
  `:runtime/agent-abort` effect (D12); not an out-of-dispatch execution path. The
  remove flow's `inflight-runs` handle entry-drop is likewise its own canonical
  `:runtime/drop-inflight-run` cleanup effect in the remove dispatch's effect set
  (D24), not the pure `remove-run` `:state*` dissoc and not a command-layer
  `swap!`.
- Ensuring the background job for a cancelled run is marked terminal by reusing the
  existing `:runtime/mark-workflow-jobs-terminal` effect from the D2/D4 terminal
  transition (single writer for the background-job (projected) terminal status, D13;
  the run's `:status` single-writer is the D4 dispatch transition) — not a separate
  ad-hoc registry write — with the D16 ordering + cancelled-path constraints so the
  cancel-then-remove path leaves no lingering job.
- Tests: (a) a cancelled run performs no further step attempts after the cancel
  checkpoint; (b) `remove` of a live run does not leave a running future; (c)
  nested sub-run + child-session cancellation propagation; (d) no commits/journal
  writes after cancel in a controlled (nullable) harness.

Out of scope:

- The duplicate-tool_result fix (task 224 — already closed).
- Redesigning the workflow step machine or the delegate result-delivery paths
  beyond what cancellation propagation requires. (Direct sub-run cancellation
  reuses the *existing* `delegate-step-runtime-result` `:cancelled` → failed-step
  mapping — D19 — so it stays inside this boundary; no new result-delivery path.)
- Force-killing threads / abrupt unsafe termination as the primary stop mechanism
  — the one-off REPL `Thread.interrupt` on a non-interrupt-aware worker during the
  Evidence recovery (`Thread.stop`-style abandonment, not an API). This is
  **distinct from** the in-scope *cooperative* `future-cancel(true)` wait-wakeup
  interrupt (D7/D8/D11), which an interrupt-aware worker handles to return to its
  cooperative checkpoint and exit cleanly. See D11 for the in-scope/out-of-scope
  thread-interrupt boundary.

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

**Executor (refined by D12):** the "orchestration runtime boundary" wording above
is made precise by D12 — these cancellation effects are canonical dispatch
`:runtime/*` effect types executed by the dispatch **`:effects` interceptor**, not
by an out-of-dispatch orchestration-layer execution path. The runtime boundary
that owns `inflight-runs` supplies the handle the `execute-effect!` method acts on
(via ctx), but the *executor* of record is the dispatch `:effects` interceptor.

### D2. Signal in canonical `:state*`; future/`inflight-runs` is a runtime handle

The **cancellation signal** is the run's `:status :cancelled` already committed to
canonical `:state*` at `(run-path run-id)` by `cancel-run`. The cooperative
step-loop check reads this signal **via the read path** (`workflow-run-in` / a
status read) at each step boundary and at interrupt-aware wait wake-ups, and exits
promptly when it observes `:cancelled` (or a removed run — run-absence is itself a
pull stop signal, D10).

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
  the canonical run-tree state (`:state*`) — by `:delegating-run-id` parentage,
  keeping only non-terminal (`#{:pending :running :blocked}`) runs (D14) — and
  applies the `:cancelled` terminal transition to the cancelled run **and every
  enumerated in-flight descendant in a single multi-run apply-phase
  `:root-state-update` within the one parent-cancel dispatch** (D23), not N
  re-entrant dispatches and not a command-layer recursion loop. Discovery is from
  canonical state via the read path; no cross-handle reach-in. Per-sub-run *effect*
  targets are pinned by D14 (worker future-cancel hits only the single top-level
  run; sub-runs wind down via cooperative signals) and D15 (child-session abort
  targets each in-flight attempt's `:execution-session-id`), all emitted as the
  cancel dispatch's effect set through the `:effects` interceptor (D23).
- **Child agent sessions:** child-turn abort reuses the **existing** session
  interrupt/abort pathway (`turn/abort-active-turn-in!` → `:session/abort`
  dispatch → `agent-core/abort-in!` → context thread interrupt), which already
  aborts an in-flight turn. This is `λ extend` compose-over-new-mechanism; no new
  abort mechanism is introduced.

agent-session remains the authoritative owner of session-dispatch invocation;
workflow-runtime exposes pure domain APIs (run-tree reads, pure transitions) that
the agent-session cascade composes.

### D4. Terminal transitions route through dispatch; atomicity from the apply-phase atom CAS with the guard inside the update fn (refined by D20)

Terminal-state transitions (cancel / remove / natural complete) route through the
dispatch pipeline (`state-kernel`) to earn the design's idempotent /
no-double-terminal / cancel-during-wait race-safety — the same shape solved in
task 224.

**Atomicity basis (corrected by D20).** `dispatch!` does **not** serialize against
concurrent threads (no global lock — see D20); the authoritative atomicity is the
per-`swap!` CAS on `:state*` in the `:apply` phase, and it only covers the terminal
transition if the **terminal-status guard is evaluated inside the
`:root-state-update` fn** passed to that `swap!` (so the read-guard-and-commit are
one CAS-retried step), not as a separate pre-read in the handler. Earlier wording
in this section attributing the safety to "serialized single-writer dispatch" /
"atomicity-from-dispatch-serialization" is superseded by D20; read those phrases as
"the apply-phase atom CAS with the guard inside the update fn."

The current `reset!`-on-`:state*` check-then-write in the cancel/remove mutations
is a TOCTOU race (status guard read, then unconditional `reset!`). The decision:
the read-guard-and-commit of the terminal transition is performed atomically by the
apply-phase atom CAS with the guard inside the update fn (D20), so that:

- two concurrent cancels, or cancel racing natural completion, cannot both apply a
  terminal transition (no double-terminal, no resurrection) — the second CAS
  re-runs its update fn against the already-`:cancelled` state and the in-fn guard
  makes it a no-op;
- a cancel arriving during a blocking wait commits the signal in one CAS, and the
  step loop observes it at the next read-path checkpoint (D2);
- terminal transitions are idempotent — a second terminal request on an
  already-terminal run is a no-op via the in-update-fn guard, not a racy outer
  check.

This supersedes ad-hoc guards on a directly-`reset!`'d atom. The pure transition
functions keep their terminal-status precondition, and that precondition is the
guard evaluated **inside** the `swap!` update fn (D20) — the authoritative atomicity
is the atom CAS, not the (non-existent) dispatch serialization, and not the
mutation's outer `when` guard.

## Behaviour-Contract Decisions (ψ, 2026-06-10)

Resolves the ambiguity follow-ups raised by the design review. These pick a
single explicit contract for each under-specified behaviour and remove the
remaining "(or …)" / "/" alternatives. They do not redesign the step machine.

### D5. `remove` of a live run = cancel-then-remove (resolves Q3)

`remove` of a live (non-terminal) run is **cancel-then-remove**, not
reject-while-live. A `remove` request on a live run is handled by the **`remove-run`
handler itself** (the entry-event taxonomy is pinned in D26: `cancel-run` and
`remove-run` are the two entry events, sharing one cancel-transition helper, with the
live-vs-terminal branch in the handler-`:before` D22.1 gate — not command-layer
orchestration). On a live run the `remove-run` handler's first pass:

1. commits the `:cancelled` terminal transition (D4: apply-phase atom CAS with the
   guard inside the update fn — D20),
2. emits the cancellation effects (D1: `future-cancel`/interrupt + transitive
   cascade per D3) **and** the job-terminalization effect (D13), all in the same
   **cancel dispatch** while the run record is still present,
3. removes the run in a **distinct, subsequent remove dispatch** (D17), chained
   from the cancel dispatch via a re-entrant `:runtime/dispatch-event` effect
   ordered after the terminalization/cancellation effects (D18) — not within the
   cancel dispatch's own apply phase — so the job is terminalized (step 2) before
   the record is dropped. The remove dispatch drops two distinct stores (D2/D24):
   the pure `remove-run` dissoc removes the **canonical run record**, and a
   `:runtime/drop-inflight-run` cleanup effect removes the **`inflight-runs` handle
   entry** (the pure transition does not mutate the handle — D24).

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
guarantee is stated over side effects that have **not yet started**, and over the
**cancel checkpoint** defined precisely by D31: the apply-phase CAS that first
commits `:status :cancelled` for the directly-cancelled run (and, for a cascade,
for each enumerated descendant).

- **Guaranteed after that checkpoint:** no further workflow step attempt starts, no
  further delegate sub-run is created by the cancelled subtree, no further ordinary
  child agent session spawns, and the in-flight child turn is interrupted so it
  initiates **no new tool calls / commits**.
- **Allowed cancellation-control work:** the `:cancelled` state write itself,
  background-job terminalization, abort/interruption records or events,
  dispatch-trace/effect bookkeeping, the re-entrant remove dispatch, and
  `inflight-runs` cleanup are required control effects, not forbidden workflow
  advancement (D30).
- **Not guaranteed (physics):** a single tool call already in syscall flight at the
  instant of interrupt (e.g. a `git commit` already issued) may complete; it cannot
  be recalled. Work that starts before the D31 checkpoint is likewise outside the
  post-checkpoint prohibition, though later advancement is stopped once the signal
  is visible.

So the absolute Intent/Acceptance "no further side effects after cancel" is
restated precisely as "**no new ordinary workflow/child-turn side effects are
initiated after the cancel checkpoint**" (D30/D31). The interrupt itself is the
guaranteed requirement; the residual in-flight effect is the only permitted
physics exception. The nullable/controlled acceptance harness asserts the
guaranteed property (no *new* ordinary tool call / commit / child session initiated
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

### D8. Division of labor: cooperative read = advance-guard (pull); future-cancel/interrupt = wait-wakeup (push)

(Removed-run stop is the pull read-path check on run-absence, not a push backstop
— see D10.)

Both mechanisms are required and cover **different runtime states**; neither is
merely a backstop:

- **Cooperative read-path check (D2) — primary advance-guard (pull).** At each
  step boundary and after each wait wake-up the loop reads the canonical
  `:cancelled` signal via the read path and refuses to start the next
  step/sub-run/child-session. This is the authoritative "do not advance"
  mechanism, and the only one needed when a cancel arrives while the loop is
  running between steps.
- **`future-cancel(true)` / interrupt — wait-wakeup (push).** Used to wake a
  thread parked on a `send-and-drain` deref so the cooperative check can run —
  both for a `:cancelled` run (D7) and for a `remove`d run (D5) whose worker is
  parked between checkpoints. Push never *replaces* the cooperative read: it only
  gets a parked worker *to* the next checkpoint, where the pull read decides to
  stop. For a removed run the stop signal is run-absence read via the pull path,
  not the push interrupt — see D10.

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

**Executor (refined by D12):** the "runtime-boundary effect handler" is the
`execute-effect!` method for the canonical `:runtime/*` cancellation effect,
invoked by the dispatch **`:effects` interceptor** (not an orchestration-layer
handler). Concretely the child-session-abort effect reuses the existing
`:runtime/agent-abort` effect type (whose `execute-effect!` already drives the
`:session/abort` path), per D12.

## Consistency Reconciliations (ψ, 2026-06-10)

Resolves the inconsistency follow-ups raised by the design review. These remove
two internal contradictions without redesigning the step machine.

### D10. Removed run = pull stop signal (run-absence) at the checkpoint; push only wakes a parked worker (reconciles D2/Scope vs D8)

A `remove`d run is observed by the cooperative read-path check as **absence** of a
`workflow-run-in` result. The cooperative checkpoint treats a missing run record
**identically** to a `:cancelled` status: both are stop signals read via the
**pull** path. So D2 ("exits promptly when it observes `:cancelled` (or a removed
run)") and Scope ("keyed on run status (`:cancelled`/removed)") are correct — the
removed case *is* handled by the cooperative read-path check, with run-absence as
the readable stop signal.

D8(b)'s earlier "no signal remains to read" is corrected: what is gone after
`remove` is the `:cancelled` *status value*, not the observability of the stop
condition — run-absence is itself the readable stop signal. `future-cancel(true)`
push is therefore **not** the sole stop mechanism for a removed run; its role is
unchanged from D8 — wake a *parked* worker so it reaches the checkpoint where the
pull read (now seeing run-absence) stops it. A worker between checkpoints stops via
pull; a worker parked on a wait is woken by push, then stops via pull.

Stop-signal predicate at the checkpoint (single rule covering both cases):
`(let [r (workflow-run-in state run-id)] (or (nil? r) (= :cancelled (:status r))))`.

### D11. In-scope cooperative `future-cancel(true)` interrupt vs out-of-scope force-kill (reconciles Scope Out-of-scope vs D7/D8)

`future-cancel(true)` and the rejected "force-kill / manual `Thread.interrupt`"
are distinct mechanisms with opposite safety properties:

- **In-scope — cooperative `future-cancel(true)`.** Delivers a JVM thread
  interrupt to an **interrupt-aware** worker: the `send-and-drain` wait
  propagates/handles `InterruptedException` (D8 interrupt-safety) and returns
  control to the cooperative checkpoint, where the worker reads the stop signal
  (D10) and **terminates itself cleanly**. The interrupt only *wakes* the wait; the
  thread is never abandoned mid-mutation. This is the intended API.
- **Out-of-scope — force-kill / ad-hoc manual interrupt.** The one-off REPL
  `Thread.interrupt` (and any `Thread.stop`-style abrupt termination) used during
  the Evidence recovery targeted a worker that was *not* interrupt-aware, with no
  cooperative checkpoint to return to — unsafe abrupt termination as the primary
  stop mechanism.

The two sections do not assign thread interruption opposite statuses: the *intended
mechanism* is the cooperative wait-wakeup interrupt (in scope); what is out of
scope is *unsafe abrupt termination as the primary stop mechanism*. Scope
Out-of-scope is updated to name the rejected thing precisely and point here.

## Dispatch-Effect Parity Decisions (ψ pass 2, 2026-06-10)

Resolves the two architecture-fit (pass 2) follow-ups. These commit the
cancellation effects to the project's canonical dispatch-effect pathway and reuse
the existing terminalization effect — boundary commitments, not step-machine
redesigns.

### D12. Cancellation effects are canonical dispatch `:runtime/*` effects executed by the `:effects` interceptor (parity)

The three cancellation side effects (worker `future-cancel`/interrupt and
child-session abort) are **canonical dispatch effect types**, registered in the
agent-session `effect-schema` (`dispatch_schema.clj`) with matching
`execute-effect!` methods (`dispatch_effects.clj`) — **parity** (AGENTS.md
`λ parity`). They are executed by the dispatch **`:effects` interceptor**, the same
path used by every other `:runtime/*` effect — **not** by an out-of-dispatch
"orchestration runtime boundary" execution path. This refines D1/D9's
runtime-boundary wording: the layer that owns `inflight-runs` only supplies the
handle (through `ctx`) on which the canonical `execute-effect!` method acts. The
`inflight-runs` handle is reached via `ctx` by a `context.clj` injection (D25) — not
as a namespace global — with parity to every other `:runtime/*` handler.

Mapping:

- **Child-session abort** reuses the **existing** `:runtime/agent-abort` effect
  (already present with an `execute-effect!` method driving the `:session/abort` /
  `agent-core/abort-in!` path) — `λ extend` compose > new mechanism (consistent
  with D3/D9). Workflow-cancellation emissions add the guarded metadata defined by
  D28 so D22.2's execute-time liveness re-check can be performed without changing
  existing non-workflow abort callers.
- **Worker `future-cancel(true)` / interrupt** is emitted as a canonical
  `:runtime/*` cancellation effect carrying the `run-id`; its `execute-effect!`
  method cancels the future held in the `inflight-runs` runtime handle (reached via
  `ctx`). A new effect type is added only where no existing one matches, and it is
  registered in `effect-schema` with a parity `execute-effect!` method. **Target
  (D14):** this effect targets only the **single top-level run's** future (the
  run-tree root that owns the `inflight-runs` entry); nested sub-runs run
  synchronously on that one worker thread and carry no future of their own, so the
  `run-id` resolves to (or is walked up to) the top-level run. The child-session
  abort target session-id is pinned by D15 (the in-flight attempt's
  `:execution-session-id`).

Routing through the dispatch `:effects` interceptor is required so the cancellation
effects:

1. pass the **validate-interceptor** effect-schema check (malli `effect-schema`);
2. are suppressed by **`:trim-effects-on-replay`** — preserving the S5
   `∀change → event → log → replayable` closure for the real side effects
   (`future-cancel`/interrupt/abort) so replay does not re-fire them;
3. emit dispatch-trace **`:dispatch/effect-start` / `:dispatch/effect-finish`**
   observability (the very diagnostic signal whose absence made the Evidence
   runaway hard to trace).

Executing them at the orchestration layer would bypass all three. Decision: no
orchestration-layer execution path for cancellation effects; they are canonical
dispatch effects only.

### D13. Background-job terminalization reuses the existing `:runtime/mark-workflow-jobs-terminal` effect (no second writer)

"The background job for a cancelled run is marked terminal" is **not** a separate
ad-hoc registry write. It falls out of the D2/D4 terminal run transition by
reusing the **existing** `:runtime/mark-workflow-jobs-terminal` effect (already
registered in `effect-schema` with an `execute-effect!` method) — `λ extend`
compose > new mechanism. The background job is a projection of the
workflow-registry runtime handle into `:state*` (doc/architecture.md State-boundary
table), so terminalizing it must go through the one existing writer for the
**background-job (projected) terminal status**, not a second out-of-band path that
would re-introduce a double-writer for the same projected status.

**Two distinct writers (not conflated):** the run's own `:status :cancelled` is
written by the D4 dispatch terminal transition (the single *logical* writer of
*run* status — atomicity from the apply-phase atom CAS with the guard inside the
`:root-state-update` fn, D20, not dispatch serialization);
`:runtime/mark-workflow-jobs-terminal` is the single writer of the
*background-job* terminal status, which it **reconciles from** that run status.
D13 governs only the latter; it does not (and must not) write run `:status`. The
earlier "single writer for run-terminal status" label conflated the two — corrected
here, in Desired Behaviour, and in Scope.

Concretely: the **cancel dispatch** terminal transition (D4 — single *logical*
writer, atomicity from the apply-phase atom CAS with the guard inside the
`:root-state-update` fn per D20, not dispatch serialization) emits
`:runtime/mark-workflow-jobs-terminal` as part of its effect
set (alongside the D12 cancellation effects), and the job reaches terminal via the
existing handler **while the run record is still present** — subject to the D16
cancelled-path constraint + the D17 two-dispatch ordering (the current handler
reconciles only when the run record is still resolvable via `workflow-in` and only
for `:done?`/`:error?`, so terminalization must happen in the cancel dispatch,
before the separate D17 remove dispatch drops the record, and the handler must
gain a `:cancelled` branch). Scope and Desired Behaviour are updated to name this
reuse and the single owner.

## Transitive-Cancellation Target Decisions (ψ pass 2, 2026-06-10)

Resolves the two ambiguity (pass 2) follow-ups. These pin the cancellation
*effect targets* to the actual single-thread synchronous execution structure —
contract clarifications grounded in the code, not step-machine redesigns.

Code premises confirmed:

- Only **top-level** runs register a `{:future :job-id}` in `inflight-runs`
  (`orchestration/execute-async!` and `continue-blocked-run-async!`); a nested
  delegate sub-run is created and driven **synchronously on the parent worker
  thread** by `delegate/delegate-step-runtime-result` via `send-and-drain-fn`,
  with **no** `inflight-runs` entry of its own.
- A run records its delegating parent run via `:delegating-run-id` (sub-run →
  parent) and its delegating session via `:parent-session-id`. The in-flight
  child agent turn of a run is the latest attempt of the run's `:current-step-id`
  step; that attempt carries `:execution-session-id` — the child-session-id UUID
  that performs the LLM call / commits (`workflow-runtime/attempts.clj`).
- Run statuses: `#{:pending :running :blocked}` are non-terminal (in-flight);
  `#{:completed :failed :cancelled}` are terminal. Attempt statuses: the active
  child turn is `:running` (`:validating` is also live); terminal-ish attempt
  statuses are `#{:succeeded :validation-failed :execution-failed :cancelled}`.

### D14. Worker `future-cancel` targets the single top-level run only; sub-runs wind down cooperatively (resolves Q-pass2-1)

A nested delegate sub-run has **no `inflight-runs` entry and no cancellable worker
future of its own** — it executes synchronously on the one top-level worker
thread. Therefore the D12 worker `future-cancel(true)` effect targets **only the
single top-level run's future**: the run-tree root, i.e. the ancestor reached by
walking `:delegating-run-id` upward until the run that owns the `inflight-runs`
entry. There is exactly **one** such future for the whole synchronous sub-tree.

**Emission rule (refined by D19).** The walk-up framing here describes how the
single worker interrupt is *targeted* during a **top-level** run's top-down
cascade. It is **not** emitted when a nested sub-run is cancelled **directly** —
interrupting the shared top-level worker would wrongly disrupt the still-`:running`
parent. So the worker `future-cancel(true)` is emitted **iff the directly-cancelled
run is itself the top-level run** that owns the `inflight-runs` entry; a direct
sub-run cancel emits no worker interrupt and relies on the downward child-session
abort to unblock the parked worker (D19).

The recursive D3 sub-run cancel therefore contributes, **per in-flight sub-run**
(not a per-sub-run future-cancel — there is no target):

- (a) the cooperative `:cancelled` **signal** — the canonical-state terminal
  transition (D2/D4), applied as **part of the single multi-run apply-phase
  `:root-state-update` of the one parent-cancel dispatch** (D23), not a separate
  per-sub-run dispatch — which is the **pull** stop the synchronous parent worker
  reads at its next checkpoint when it returns up from that sub-run's
  `send-and-drain`; and
- (b) the **child-session-abort** effect for that sub-run's in-flight attempt's
  `:execution-session-id` (D15), emitted in the **cancel dispatch's effect set**
  through the `:effects` interceptor (D23).

The single parent-thread `future-cancel(true)` interrupt (D7/D8 push) wakes the
parent worker wherever it is parked in the synchronous sub-tree (a sub-run's
`send-and-drain` deref) so it returns to the nearest cooperative checkpoint, where
the per-sub-run pull signals (a) stop further advancement. So the sub-tree winds
down via: per-sub-run cooperative `:cancelled` signals + the one parent-thread
interrupt + per-in-flight-run child-session abort. Option (a) of the follow-up is
chosen; sub-runs do **not** carry their own futures.

**In-flight sub-run status filter for the D3 cascade enumeration:** enumerate
descendant runs from canonical run-tree state by `:delegating-run-id` parentage
(transitively from the cancelled run), keeping only runs whose status ∈
`#{:pending :running :blocked}` (non-terminal). Terminal sub-runs
(`#{:completed :failed :cancelled}`) are skipped — nothing to cancel.

Intent ("transitively across nested sub-runs"), Desired Behaviour
("cancelling a parent run cancels its in-flight nested delegate sub-runs"), Scope,
and D3/D12 are reconciled: the *signal* cascades to every in-flight sub-run; the
*worker-future cancel effect* has a single target (the top-level run); the
child-abort effect is per in-flight run.

### D15. Child-session-abort session-id = the in-flight attempt's `:execution-session-id` (resolves Q-pass2-2)

The `:runtime/agent-abort` effect's required `:session-id` argument is the
**in-flight child turn's `:execution-session-id`**, read from canonical run state —
**not** the run's `:parent-session-id` (the delegating/caller session, which must
**not** be aborted).

**Read rule (from canonical `:state*`, per cascade run `r`):**

```
step    = (:current-step-id r)
attempt = (last (get-in r [:step-runs step :attempts]))
sid     = (:execution-session-id attempt)   ; child-session-id UUID
```

Emit `{:effect/type :runtime/agent-abort :session-id sid}` **iff** `r` is
non-terminal, `attempt` is live (status ∈ `#{:running :validating}`), and `sid` is
present. A run with no live attempt (e.g. parked between steps with no active
child turn) emits **no** abort effect — there is no in-flight turn to abort.

**Set of sessions aborted:** the directly-cancelled run **plus each in-flight
descendant sub-run** (the D14 cascade set), one abort per run that currently has a
live in-flight attempt — i.e. the chain of currently-executing child turns, not
every descendant run's historically-recorded session, and never the
`:parent-session-id` of any run. In the common synchronous single-chain case this
is the one deepest in-flight child turn plus any ancestor turns still mid-flight.

This makes the D9/D12 reuse of `:runtime/agent-abort` well-defined: its keyed
`session-id` (`effect-session-id`) is supplied from the canonical in-flight
attempt's `:execution-session-id`.

**Workflow-cancellation guard payload/read rule (D28).** A workflow-cancellation
abort effect carries both the existing `:session-id` and workflow guard metadata:
`{:workflow-run-id run-id :workflow-step-id step-id :workflow-attempt-id attempt-id}`
plus `:expected-session-id sid` (equal to `:session-id`). At execute time the
`:runtime/agent-abort` handler uses this metadata to re-read canonical `:state*`,
locate the same run/step/latest attempt, and abort only if the latest attempt still
has the same `:attempt-id`, live status, and the same `:execution-session-id`.
Existing non-workflow `:runtime/agent-abort` emissions omit the metadata and keep
the current unguarded session-id-only behaviour.

## Consistency Reconciliations (ψ pass 2, 2026-06-10)

Resolves the two inconsistency (pass 2) follow-ups. The first (writer-label
conflation) is addressed inline at D13, Desired Behaviour, and Scope (run `:status`
single-writer = D4 dispatch transition; background-job projected terminal status
single-writer = `:runtime/mark-workflow-jobs-terminal`). The second is D16 below.

### D16. Cancel-then-remove must not leave a lingering job: terminalize-before-remove ordering + a `:cancelled` reconcile path (reconciles D5 + Desired vs D13 + code)

The "no lingering `:running` job" guarantee (Desired Behaviour) is at risk under
D5 cancel-then-remove because the reused `:runtime/mark-workflow-jobs-terminal`
handler (`background-job-runtime/maybe-mark-workflow-jobs-terminal!`) as it stands:

- reconciles a job **only `(when wf …)`** — i.e. only while the run/workflow
  instance is still resolvable via `extension-workflow-runtime/workflow-in`; once
  D5 step 3 removes the run record, `workflow-in` returns `nil` and the job is
  **skipped**, leaving it non-terminal; and
- branches only on `:error?`(→`:failed`) / `:done?`(→`:completed`) — there is **no
  `:cancelled` / removed-run branch**, so even a cancel-*without*-remove would
  reconcile a cancelled run through the wrong outcome (`:done?`→`:completed`)
  rather than `:cancelled`.

Decision — both constraints apply (the effect set is ordered, and the handler gains
a cancelled path):

1. **Ordering (terminalize-before-remove).** The
   `:runtime/mark-workflow-jobs-terminal` reconcile must run **before** the
   run-record removal, so the job is terminalized while the run is still
   resolvable via `workflow-in`. This ordering is **not** expressible inside a
   single dispatch's effect set — the run-record removal is a pure `:state*`
   dissoc (`remove-run`) that runs in the `:apply` phase, which the dispatch
   sequencing contract places **before all effects** (`:apply → :validate →
   :trim-effects-on-replay → :effects`); a pure state removal cannot be sequenced
   *after* an effect within one dispatch. The ordering is therefore realized by
   **splitting cancel-then-remove across two serialized dispatches** — see D17.

2. **Cancelled reconcile path.** `maybe-mark-workflow-jobs-terminal!` gains a
   `:cancelled` reconciliation branch: a run whose status is `:cancelled` (or whose
   `wf` reports cancellation) terminalizes its job with **`:outcome :cancelled`**
   (not `:completed`). This also makes plain cancel-without-remove terminalize with
   the correct outcome. Whether cancellation is surfaced via a `:cancelled?`
   predicate on `wf` or read from the canonical run `:status` is an implementation
   choice for the builder; the contract is: a cancelled run's job reaches terminal
   with `:cancelled` outcome.

Constraint (1) alone is insufficient (outcome would still be mislabeled and a
pure-removal with no preceding cancel could still skip), and (2) alone is
insufficient (post-removal `workflow-in` returns `nil`, so there is nothing to
reconcile). Both are required for the guarantee to hold across cancel,
cancel-then-remove, and remove-of-live-run. This is a handler reconcile-path
extension (`λ extend` compose), not a step-machine redesign; it stays within the
single existing background-job terminal writer (no second writer introduced).

## Dispatch-Sequencing Reconciliation (ψ pass 3, 2026-06-10)

Resolves the pass-3 architecture-fit follow-up: D16(1)'s "terminalize-before-
remove" ordering is not expressible within a single dispatch under the
apply-before-effects sequencing contract. States the fit resolution.

### D17. Cancel-then-remove is two serialized dispatches (terminalize in the cancel dispatch; drop the record in a subsequent remove dispatch)

**Premise (code- + contract-confirmed).** The dispatch sequencing contract
(doc/architecture.md "Dispatch sequencing contract") fixes the effective
after-order as `:apply → :validate → :trim-effects-on-replay → :effects`: in a
single dispatch **all** pure state application precedes **all** effects. The
cancel-then-remove run-record removal is the pure `remove-run` dissoc on canonical
`:state*` (`workflow-runtime/core.clj`: `(update-in (runs-path) dissoc run-id)`,
`state → [state', run]`), so it runs in the `:apply` phase. The job
terminalization is the `:runtime/mark-workflow-jobs-terminal` effect (D13), which
runs in the `:effects` phase and re-reads the run via
`extension-workflow-runtime/workflow-in`. So **within one dispatch** the apply-phase
removal necessarily precedes the terminalize effect → `workflow-in` → `nil` → the
job is skipped (the exact lingering-job failure D16 set out to prevent). A pure
`:state*` removal **cannot** be ordered after an effect inside one dispatch.

**Decision — split cancel-then-remove across two serialized dispatches** (chosen
over carrying run identity + outcome in the effect payload):

1. **Cancel dispatch.** Applies the D4 `:cancelled` terminal transition (run
   record **still present**) and emits, in its effect set, the D12 cancellation
   effects (worker `future-cancel`/interrupt + the D3/D14/D15 transitive cascade)
   **and** the D13 `:runtime/mark-workflow-jobs-terminal` effect. Because the run
   is still resolvable via `workflow-in` during this dispatch's `:effects` phase,
   the D16(2) `:cancelled` reconcile branch terminalizes the background job with
   `:outcome :cancelled`.
2. **Remove dispatch.** A **distinct, subsequent** dispatch drops the run on **two
   distinct stores** (D2/D24): its apply phase applies the pure `remove-run` dissoc,
   dropping the **canonical run record**; its effect set emits the
   `:runtime/drop-inflight-run` cleanup effect (D24) that drops the **`inflight-runs`
   handle entry** (the pure `remove-run` dissoc does **not** touch `inflight-runs` —
   D24). The job is already terminal from dispatch 1, so this dispatch performs no
   terminalization and the dropped record cannot leave a lingering job.

The two-dispatch ordering holds **not** via dispatch serialization (which does not
exist — D20) but via **in-thread sequencing** (D20): the remove dispatch is the
re-entrant `:runtime/dispatch-event` effect (D18) executed synchronously on the
**same dispatch-invoking (operator/command) thread** — the agent tool-dispatch
thread that runs the `cancel-run` / `remove-run` mutation (`canonical_workflows.clj`
`cancel-workflow-run`/`remove-workflow-run`; `delegate-remove`
`workflow/core.clj:474`), **not** the workflow worker thread (the separate
`clojure-agent-send-off-pool` thread parked on `send-and-drain`, which the cancel's
`future-cancel(true)` interrupts) — in the cancel dispatch's `:effects` phase,
**after** the terminalize effect — so dispatch 2 runs strictly after dispatch 1's
apply+effects on one thread and observes dispatch 1's applied `:state*`. The
ordering (terminalize → then remove) thus holds across the two dispatches even
though it is impossible within one. (This matches D20's "the same thread" and D21's
"the operator/command thread"; the earlier "same worker thread" qualifier was a
misnomer — the worker thread is the *target* of the interrupt, never the *runner*
of the cancel/remove dispatches.)

**Trigger/chaining mechanism (D18).** Dispatch 2 is issued by dispatch 1 as
**effects-as-data**: the cancel dispatch's effect set ends with a re-entrant
`:runtime/dispatch-event` follow-on effect (an existing canonical effect, no new
type) that enqueues/re-enters the remove dispatch, ordered after the
terminalization + cancellation effects. See D18 for the wiring and why option (a)
(re-entrant effect) is chosen over option (b) (synchronous two `dispatch` calls in
the command layer).

**Why split, not effect-payload self-containment.** The rejected alternative —
make `:runtime/mark-workflow-jobs-terminal` carry the run identity + `:cancelled`
outcome in its payload so it terminalizes without re-reading the canonical run —
would (a) duplicate canonical run state (the `:cancelled` outcome) into the effect
payload, a second source of truth contrary to `source_of_truth ≡ … :state*`; (b)
fork the effect into a hybrid reconcile-all-from-canonical-state **plus**
terminalize-this-named-run path, diverging from its single
reconcile-from-canonical-state semantics and from its other call site
(`statechart_actions` emits it payload-free); and (c) re-read-free terminalization
still needs the run's job mapping, which lives in canonical state anyway. The
two-dispatch split keeps the effect's reconcile-from-canonical-state contract
intact (`λ extend` compose; no divergent payload path) and reuses D4's existing
ordering — realized by the apply-phase atom CAS plus in-thread sequencing of the
re-entrant remove dispatch, not dispatch serialization (D20).

D5 step 3, D13, and D16(1) are updated to name the two-dispatch split as the
ordering mechanism. D16(2)'s `:cancelled` reconcile branch remains required (it is
what terminalizes the still-present cancelled run in the cancel dispatch);
together D16(2) + D17 satisfy the "no lingering job" guarantee under
apply-before-effects.

**Entry event (D26).** Both dispatches of the cancel-then-remove split are issued
under the **`remove-run` handler** (D26): the cancel dispatch is the `remove-run`
handler's live-run first pass (shared cancel-transition helper + the re-entrant
remove dispatch-event in its effect tail, no dissoc), and the remove dispatch is the
re-entrant `remove-run` re-entry whose handler-`:before` now reads the run as
`:cancelled`/terminal and takes the bare-dissoc branch. The re-entrant remove
dispatch runs on the **dispatch-invoking operator/command thread** (the same thread
as the cancel dispatch, per the corrected in-thread-sequencing wording above), never
the workflow worker thread.

## Ambiguity Reconciliations (ψ pass 3, 2026-06-10)

Resolves the two pass-3 ambiguity follow-ups: the D17 two-dispatch chaining
mechanism, and the contract for **direct** cancellation of a nested sub-run.
Neither redesigns the step machine.

### D18. D17 two-dispatch chaining = a re-entrant `:runtime/dispatch-event` follow-on effect emitted by the cancel dispatch (reuse existing effect; option (a))

D17 splits cancel-then-remove across two serialized dispatches but did not state
**how** the second (remove) dispatch is issued/ordered after the cancel dispatch
for a single remove-of-live-run request. Decision: option (a) — the cancel
dispatch emits the remove dispatch as **effects-as-data**, not option (b)
(synchronous two `dispatch` calls in the `remove` command/mutation layer, which
would put orchestration logic in the mutation in tension with D1's
no-inline-orchestration boundary).

**Mechanism — reuse the existing `:runtime/dispatch-event` effect; no new effect
type is in scope.** A re-entrant dispatch-emits-dispatch effect **already exists**
in the codebase: `:runtime/dispatch-event` (`dispatch_effects.clj`) whose
`execute-effect!` calls `dispatch/dispatch!` from within the `:effects`
interceptor. `dispatch!` runs the interceptor chain synchronously on the calling
thread with no global lock, so the nested dispatch is a reentry-safe in-thread call
(the pattern is already used by scheduler drain/post-tool flows). The follow-up's
premise "doc/architecture.md documents no re-entrant dispatch-emits-dispatch effect
today" is accurate only about the *documentation*: the effect exists in code but is
not described in the "Dispatch sequencing contract" section. So no new
follow-on-dispatch effect type is added; the only artifact gap is a doc update
(document `:runtime/dispatch-event` re-entrancy in the sequencing contract),
handled by the change-chain doc step when implemented.

**Wiring (within the cancel dispatch's effect set, ordered):**

1. the D13 `:runtime/mark-workflow-jobs-terminal` effect (terminalize the job with
   the run still present), then
2. the D12 cancellation effects (worker `future-cancel`/interrupt + D3/D14/D15
   cascade), then
3. a `:runtime/dispatch-event` effect targeting the remove event
   (`{:effect/type :runtime/dispatch-event :event-type <remove-run dispatch>
   :event-data {:run-id …}}`), which re-enters dispatch to apply the pure
   `remove-run` dissoc.

Effects within a dispatch execute in declared order, so the terminalize effect (1)
runs before the re-entrant remove dispatch (3) — i.e. the job is terminalized while
the run record is still resolvable via `workflow-in`, satisfying D17/D16(1)
terminalize-before-remove. The re-entrant remove dispatch runs synchronously
in-thread (D18/D20) strictly after the cancel dispatch's apply+effects, so it
observes the applied `:cancelled` state — ordering by in-thread sequencing, not
dispatch serialization (D20). Plain
`cancel` (no remove) simply omits effect (3); a direct `remove` of a live run is
the only flow that emits (3). This keeps the chaining as effects-as-data at the
dispatch boundary (consistent with D1/D12) rather than command-layer orchestration.

**Entry-event placement (D26).** Effect (3) (the re-entrant `:runtime/dispatch-event`
remove follow-on) is appended **only by the `remove-run` handler's live-run first
pass** — that is precisely why "a direct `remove` of a live run is the only flow that
emits (3)": the `cancel-run` handler invokes the *same* shared cancel-transition
helper *without* appending effect (3). The live-vs-(`remove`)-flow distinction is
therefore the entry event (`cancel-run` vs `remove-run`), not a runtime flag on a
shared event — see D26 for the full taxonomy and the shared-helper / handler-`:before`
branch placement.

### D19. Direct cancellation of a nested sub-run: in scope; parent observes a failed delegate step and continues (does not halt the parent)

Direct cancellation of a nested sub-run (Evidence step 2) is **in scope** with a
pinned contract that reuses the existing delegate result-delivery path — it is
**not** a redesign of delegate result-delivery (which stays out of scope).

**Cascade direction.** The transitive cascade (D3/D14/D15) always operates
**downward** from the directly-cancelled run: the `:cancelled` signal (pull) and
the per-in-flight-attempt child-session abort (D15) are emitted for the cancelled
run **and each of its in-flight descendant sub-runs** — never upward to ancestors.

**Worker future-cancel emission rule (refines D14).** The worker
`future-cancel(true)` interrupt (D12/D14) is emitted **only when the
directly-cancelled run is itself the top-level run** that owns the `inflight-runs`
entry. When the directly-cancelled run is a **nested sub-run**, **no** worker
`future-cancel(true)` is emitted — interrupting the shared top-level worker would
wrongly disrupt the still-`:running` parent (and every sibling sub-run on that
thread). D14's "walk `:delegating-run-id` upward to the top-level future" describes
how the single worker interrupt is *targeted* during a **top-level** cancel's
top-down cascade; it is **not** a license to interrupt the top-level worker when a
sub-run is cancelled directly.

**How the shared worker is unblocked for a direct sub-run cancel.** The parent
worker is parked in the cancelled sub-run's (or a deeper descendant's)
`send-and-drain` deref of an in-flight child turn. The **downward child-session
abort** (D15) for that in-flight attempt terminates the turn, which lets the
sub-run's cooperative checkpoint observe its `:cancelled` signal (D2/D10) and drive
the sub-run statechart to its `:cancelled` terminal — returning control to the
parent worker from `send-and-drain`. So child-abort, not a worker interrupt, is the
wake mechanism in the sub-run case. (If the cancelled sub-run has no live LLM
attempt — e.g. it is itself parked awaiting a *deeper* delegate sub-run — the D3
downward cascade aborts that deeper descendant's in-flight turn, which unblocks the
worker the same way.)

**Parent-run + cancelled-sub-run result-delivery contract.** When `send-and-drain`
returns, `delegate-step-runtime-result` reads the sub-run's
`(:status delegate-run) = :cancelled` and returns its **existing** `:cancelled`
case — `{:pending-kind :failure :payload {:message "Delegated workflow cancelled"
…}}`. The parent run (still `:running`) therefore observes the directly-cancelled
sub-run as a **failed delegate step** via the already-present delegate
result-delivery path, and **continues** per its normal step-failure handling
(fail-step / step recovery as the parent definition specifies). The parent is **not
halted**: a child cancel must not kill a still-running parent. This is exactly the
existing `delegate.clj` `:cancelled` → failure mapping (`λ extend` compose; no new
mechanism), so it sits inside the "Redesigning … delegate result-delivery paths"
out-of-scope boundary.

Top-down cancellation of the **parent** (top-level) run is unchanged: there the
parent itself is the runaway, so the single top-level worker `future-cancel(true)`
*is* emitted (D14) and the parent terminates — distinct from the direct-sub-run
case above. Scope is updated to name direct sub-run cancellation in scope with this
failed-delegate-step contract.

## Atomicity-Basis Reconciliation (ψ pass 3, 2026-06-10)

Resolves the pass-3 inconsistency follow-up: D4's "serialized single-writer
dispatch" race-safety claim contradicts D18's "no global lock" and the dispatch
code. States the real atomicity basis and aligns the dependent D4/D13/D16/D17
wording. No change to the cancellation mechanism.

### D20. Race-safety atomicity = the apply-phase atom CAS with the terminal guard inside the `:root-state-update` fn (not dispatch serialization)

**Premise (code- + contract-confirmed).** `kernel/dispatch!`
(`state-kernel/dispatch.clj`) runs the interceptor chain **synchronously on the
calling thread with no global lock**; worker futures dispatch from
`clojure-agent-send-off-pool` threads (Evidence), so two cancels on two threads run
two **unsynchronized** `dispatch!` calls — dispatch is **not** a serialized
single-writer against concurrent threads. doc/architecture.md's "Dispatch
sequencing contract" specifies only the per-dispatch phase order
(`:apply → :validate → :trim-effects-on-replay → :effects`), **not** cross-thread
serialization. The only atomicity primitive in the pipeline is the per-`swap!`
CAS on `:state*` in the `:apply` phase (`apply-root-state-update!` =
`(swap! (:state* env) root-update-fn)`). Crucially, the handler computes its
`:root-state-update` fn in the `:handler` `:before` (reading `:state*` then), and
that fn is applied later by the `:apply` `swap!`: so a terminal-status guard read in
the handler, separate from the update fn, is a TOCTOU race — **not** made atomic by
dispatch.

**Decision — option (a): the guard lives inside the `swap!` update fn.** The
terminal transition's read-guard-and-commit is atomic **iff** the terminal-status
precondition is re-evaluated **inside** the `:root-state-update` fn passed to the
`:apply` `swap!` (the pure transition `cancel-run`/`remove-run` receives the current
state as its argument and itself decides terminal-or-no-op). `swap!` re-runs that fn
on CAS contention, so:

- **Two concurrent cancels / cancel-racing-completion:** the first CAS commits
  `:cancelled`; the second CAS re-runs its update fn against the now-`:cancelled`
  state, the in-fn guard sees the terminal precondition, and it commits a **no-op**
  — no double-terminal, no resurrection. The atom CAS, not a lock, provides this.
- **Idempotency:** a second terminal request on an already-terminal run is a no-op
  via the same in-fn guard.
- **Cancel during a blocking wait:** the signal commits in one CAS; the step loop
  observes it at the next read-path checkpoint (D2).

Option (b) (naming an actual serialization point) is rejected — **none exists**;
`dispatch!` holds no lock and the atom is the only synchronizer.

**Alignment of dependent wording (D4/D13/D16/D17).** Everywhere this design says
"serialized (single-writer) dispatch" / "atomicity-from-dispatch-serialization" /
"D4 single-writer" as the *atomicity / race-safety* basis, read it as **the
apply-phase atom CAS with the guard inside the update fn** (D20). Two consequences:

1. **Run-`:status` "single writer" (D4/D13/D16/D17).** The run's `:status :cancelled`
   is still authored by exactly one logical writer — the `cancel-run` terminal
   transition applied through the `:apply` `swap!`. "Single writer" remains true as
   a *logical* statement (one transition function owns run-`:status`); it is **not**
   underwritten by thread serialization. The CAS + in-fn guard is what makes
   concurrent attempts converge to one terminal commit.
2. **D17 two-dispatch cross-ordering.** The cancel-dispatch-then-remove-dispatch
   ordering holds via **in-thread sequencing** of the re-entrant
   `:runtime/dispatch-event` effect (D18) — the remove dispatch executes
   synchronously on the same thread, in the cancel dispatch's `:effects` phase,
   after the terminalize effect — **not** via serialization. Same applied-state
   visibility (dispatch 2 sees dispatch 1's CAS-applied `:state*`), different
   (correct) mechanism.

**Implementation constraint for the builder.** The terminal-status guard must be
expressed *inside* the pure `:root-state-update` fn (so it rides the `swap!` CAS
retry), never as a handler-level pre-read followed by an unconditional update — the
latter reintroduces the very TOCTOU the current `reset!`-after-`when` mutations have
(D4). This is the concrete shape that earns the idempotent / no-double-terminal /
no-resurrection guarantees on a no-global-lock dispatch.

D4, D13, D16, and D17's "serialized"/"single-writer" phrasings are reconciled here;
no step-machine redesign and no change to the cancellation effect set.

## Edge-Case & Idempotency Reconciliations (ψ pass 4, 2026-06-10)

Resolves the two pass-4 ambiguity follow-ups: the D5 × D17 × D19 intersection for a
direct `remove` of a live nested sub-run, and whether the cancellation effect set is
suppressed when the D20 terminal guard no-ops the transition. Neither redesigns the
step machine.

### D21. Direct `remove` of a live nested sub-run: in scope; run-absence ≡ `:cancelled` at the delegate result (reconciles D5 × D17 × D19)

Direct `remove` of a live (non-terminal) nested sub-run is **in scope** — D5
cancel-then-remove applies to **any** live run, sub-runs included. The contract:

- The remove is cancel-then-remove (D5/D17): a **cancel dispatch** terminalizes the
  sub-run (signal `:cancelled` + the downward, **no-worker-future-cancel** child
  abort of D19 + job terminalize per D13/D16), then a **subsequent re-entrant remove
  dispatch** (D18) drops the sub-run record.
- The remove dispatch runs on the **operator/command thread**, concurrently with the
  **parent worker thread** parked in the sub-run's `send-and-drain` deref. So when
  the child abort unblocks the sub-run and control returns to
  `delegate-step-runtime-result`, the parent worker may read **either**
  `(:status delegate-run) = :cancelled` (record not yet removed → D19 `:cancelled`
  branch) **or** `delegate-run = nil` / run-absence (record already removed). Code-
  confirmed: after removal `workflow-run-in` → `nil`, `(:status nil) = nil`, and the
  existing `delegate.clj` `case` hits the **default** branch ("Delegated workflow did
  not reach terminal or blocked status"), **not** the `:cancelled` branch. This is a
  genuine cancel-vs-remove timing race.
- **Decision.** The parent must observe the **same failed-delegate-step semantics**
  in both readings, so D19's "parent observes a failed delegate step and **continues**
  (not halted)" holds regardless of the race. **Run-absence specifically** (`nil`
  delegate-run / `nil` status) at the delegate result is treated **identically to
  `:cancelled`**: it maps to the `:cancelled` failed-step result
  (`:pending-kind :failure`, message "Delegated workflow cancelled or removed"), **not**
  the generic "did not reach terminal or blocked status" default. This is folded into
  the existing `delegate.clj` `:cancelled` → failure mapping (`λ extend` compose;
  absence routed into the cancelled case via an explicit `nil`/absent-run guard before
  the status `case`), staying inside the out-of-scope "no new result-delivery path"
  boundary.

**Scope precision.** Only **run-absence** (`nil` delegate-run) is relabeled to the
cancelled result; a genuinely non-terminal *present* status
(`:running`/`:pending`/`:blocked`, all non-`nil`) still falls through to the existing
default branch — so this does not mask real "did not reach terminal" anomalies, it
only covers the removed-mid-delegate case.

Reconciliation: D5 (cancel-then-remove applies to sub-runs), D17 (the two-dispatch
remove drops the sub-run record), and D19 (parent continues on a failed delegate
step) are consistent — D19's contract is extended to cover run-absence so the
parent's continue-as-failed-step outcome is **race-independent**. Scope is updated to
name direct sub-run remove in scope with this contract.

### D22. Cancellation effects gated on the terminal transition actually applying: handler-before terminal-precondition gate + effect-level idempotency (reconciles D4/D20 idempotency with the pure-result effect-emission shape)

The pure-result effect-emission shape computes `:effects` **in the handler `:before`
(pre-CAS)** and the `:apply` interceptor takes them verbatim as `:applied-effects`
**regardless** of whether the apply-phase `swap!` (D20) actually changed state.
Code-confirmed (`state-kernel/dispatch.clj`): `handler-interceptor` `:before` builds
`:pure-result {:root-state-update f :effects effs}`; `apply-pure-result` runs the
`swap!` and then sets `:applied-effects (:effects pure-result)` **unconditionally**;
`effect-interceptor` executes `:applied-effects`. So D20's in-`swap!`-fn terminal
guard making a racing/second terminal request a **no-op for `:state*`** does **not**,
by itself, suppress the cancellation effects — without further gating a no-op'd
terminal request would still fire worker `future-cancel`, `:runtime/agent-abort`,
`:runtime/mark-workflow-jobs-terminal`, and the remove path's re-entrant
`:runtime/dispatch-event`, contradicting the "second terminal request is a no-op"
idempotency claim (D4/D20) and risking an `:runtime/agent-abort` against an
already-completed run's (possibly reused) `:execution-session-id`.

**Decision — gate effect emission in two complementary layers:**

1. **Handler-before terminal-precondition gate (primary) — gates the
   cancellation/terminal-transition only, never the record drop.** The
   *cancellation/terminal-transition* computation — the `:cancelled` `:state*`
   commit plus the cancellation effect set (worker `future-cancel`,
   `:runtime/agent-abort`, `:runtime/mark-workflow-jobs-terminal`, and the
   cancel-then-remove re-entrant `:runtime/dispatch-event` remove-trigger) — is
   conditioned on the handler-`:before` `:state*` read: if the target run is
   **already terminal (or absent)** at that read, this portion contributes
   `identity` to the state update and an **empty** cancellation-effect set. This
   emits **no** cancellation effects for every *sequentially* later terminal request
   — a second `cancel`, the `cancel`-half of a `remove`-of-already-terminal, or a
   `cancel` arriving after natural completion — the dominant idempotency case,
   realizing "a no-op'd terminal request emits no effects" within the pure-result
   shape (effects conditioned on the same terminal precondition D20 guards inside the
   `swap!` fn).

   **The `remove-run` record-drop is *not* gated by this terminal precondition.**
   The gate suppresses only the cancellation/terminal-transition effects above; the
   record-removal `remove-run` dissoc (`workflow-runtime/core.clj`) **still applies**
   to an already-terminal run. This is required because (a) `remove` of an
   already-terminal run is **plain record removal** (D5) — the record must be dropped
   — and (b) the cancel-then-remove **remove dispatch** (D17/D18 dispatch 2) by
   construction runs *after* the cancel dispatch already applied `:cancelled`, so its
   handler-`:before` **always** reads the run as terminal. Conflating the two —
   applying the no-op `identity` gate to the record drop as well — would make
   `remove-run` a no-op for any terminal run, so cancel-then-remove (D5/D17) would
   never drop the record and plain remove-of-terminal (D5) would leave the record
   lingering, re-orphaning exactly the case this task fixes. Concretely: the *cancel*
   transition + its cancellation effects carry the gated terminal-precondition; the
   *remove* record-drop is an **unconditional dissoc independent of run `:status`**
   (and is itself idempotent on an absent record — D22.2), so both the sequenced
   remove dispatch and the plain remove-of-terminal case drop the record.

2. **Effect-level idempotency (covers the residual true-concurrent CAS race).** Two
   cancels racing on two threads can **both** read non-terminal in their respective
   handler-before gates and **both** emit effects, while the D20 CAS applies
   `:cancelled` exactly once (the loser's `swap!` fn no-ops the state). The pre-CAS
   pure-result shape cannot retract the loser's already-computed effects, so the
   cancellation effects must be **execution-time idempotent / liveness-rechecking** so
   a redundant emission is harmless:
   - workflow-cancellation `:runtime/agent-abort` re-reads the **D15 live-attempt
     predicate** using the D28 guard metadata (`run-id`, `step-id`, `attempt-id`,
     expected `execution-session-id`) from canonical `:state*` **at execute time**
     and **no-ops** when the guarded attempt is no longer the latest live attempt —
     so it cannot abort an already-completed/superseded turn or a reused
     `:execution-session-id`; non-workflow abort emissions that omit the guard keep
     the existing session-id-only behaviour;
   - worker `future-cancel(true)` of an already-cancelled / completed / absent future
     is a JVM no-op;
   - `:runtime/mark-workflow-jobs-terminal` reconciles idempotently from canonical
     state (D13/D16);
   - the re-entrant `:runtime/dispatch-event` remove re-enters a dispatch whose
     apply-phase `remove-run` dissoc is idempotent (removing an absent record is a
     no-op).

Both layers are required: (1) makes the common sequential idempotency case emit zero
effects (and is what backs the "no-op terminal request emits no effects" claim); (2)
makes the narrow concurrent-CAS race harmless where (1) cannot suppress an
already-computed effect list. Together they align D4/D20's idempotency /
no-double-terminal claim with the actual pre-CAS pure-result effect-emission shape: a
terminal transition that does not apply `:cancelled` initiates no **observable**
cancellation side effect.

**Cross-reference (D4/D20).** D4 and D20's "second terminal request is a no-op" now
reads: the **state** no-op is the in-`swap!`-fn guard (D20); the **effect** no-op is
the handler-before gate (D22.1) for sequential requests plus effect-level idempotency
(D22.2) for the concurrent-CAS race.

**Cross-reference (D5/D17/D18) — scope of the D22.1 gate.** The D22.1
terminal-precondition gate is scoped to the **cancellation/terminal-transition** (the
`:cancelled` commit + cancellation effect set). It does **not** gate the `remove-run`
record drop, which still applies to an already-terminal run (D5 plain
remove-of-terminal; D17/D18 cancel-then-remove dispatch 2, whose handler always reads
the run as already `:cancelled`). The record drop is an unconditional, status-independent
dissoc — only the cancellation effects are suppressed when the run is already terminal —
so the gate never re-orphans a terminal run.

## Transitive-Cascade Re-Dispatch Reconciliation (ψ pass 5, 2026-06-10)

Resolves the pass-5 architecture-fit follow-up: the transitive cascade's
per-descendant terminal transitions had **no stated issue mechanism** (unlike the
cancel-then-remove second dispatch, which D18 pins to a re-entrant
`:runtime/dispatch-event` effect), leaving an implementer free to place the
recursion in a command-layer loop / inline cross-handle reach-in (D1/D3/D18
violation), and was unreconciled with the D4/D20 single-run atom-CAS atomicity
basis. States the cascade's issue mechanism and atomicity shape. No step-machine
redesign; the cancellation effect set is unchanged.

### D23. Cascade = one multi-run apply-phase `:root-state-update` within the single parent-cancel dispatch (option (a); not N re-entrant dispatches)

The follow-up's two candidates are: (a) a **single multi-run apply-phase
`:root-state-update`** over the enumerated descendant set within the one
parent-cancel dispatch, or (b) **N re-entrant `:runtime/dispatch-event` cancel
dispatches**, one per descendant (reusing D18's mechanism). **Decision: option
(a).**

**Why (a), not (b).** The cancel-then-**remove** split needs a re-entrant
`:runtime/dispatch-event` (D18) **only because** the record-removal is a pure
`:state*` dissoc that must be sequenced **after** the terminalize *effect*, and
apply-before-effects makes that intra-dispatch ordering impossible (D17) — so the
removal is forced into a *subsequent* dispatch. The cascade has **no such
after-effect ordering constraint**: every per-descendant `:cancelled` signal is a
**pure `:state*` transition** with no dependency on any effect. Pure transitions
**compose into one apply-phase update fn**; there is nothing to sequence after an
effect, so re-dispatch buys nothing and option (b) would only multiply dispatches,
event-log entries, and CASes while complicating the ordering of the parent's own
terminal transition + the D14 single top-level `future-cancel` against N child
dispatches. Option (a) is the simpler fit (`λ build` simple > complex; one-pass
resonance) and keeps the recursion as a **pure canonical-state read + a single
multi-run update fn**, never a command-layer loop or cross-handle reach-in
(D1/D3/D18).

**Mechanism.** The single parent-cancel dispatch:

1. **Enumerate (handler-`:before`, pure read).** Read the transitive descendant set
   from canonical `:state*` by `:delegating-run-id` parentage, keeping non-terminal
   (`#{:pending :running :blocked}`) runs (D14). The cancelled run ∪ this descendant
   set = the **cascade set**.
2. **Apply (one `:root-state-update`, one CAS).** The update fn applies the
   `:cancelled` terminal transition to **every** run in the cascade set, with each
   run's terminal-status precondition guard evaluated **inside** the same
   `:root-state-update` fn (D20) — so the whole subtree terminalization rides **one
   atom CAS**. A descendant already terminal at apply time is a per-run no-op via its
   in-fn guard.
3. **Effects (cancel dispatch's effect set, `:effects` interceptor).** Emit, through
   the dispatch `:effects` interceptor (D1/D12 — canonical `:runtime/*`, parity,
   replay-trim, trace): the **single** top-level worker `future-cancel(true)` **iff
   the directly-cancelled run is the top-level run** (D14/D19); one
   `:runtime/agent-abort` per in-flight descendant attempt (D15 live-attempt
   predicate); and the D13 `:runtime/mark-workflow-jobs-terminal` terminalization.
   No per-descendant re-dispatch.

**Reconciliation with D4/D20 (atomicity).** D20's single-run atomicity (terminal
guard inside the `:root-state-update` fn riding one CAS) **generalises directly** to
the multi-run cascade: the cascade's per-run guards all live inside the *one*
`:root-state-update` fn applied by the *one* apply-phase `swap!` CAS, so the entire
subtree transition is **a single atomic CAS** — strictly stronger than option (b)'s
N independent CASes. Concurrency cases converge identically: a cancel racing a
descendant's natural completion re-runs the multi-run update fn against the
already-`:cancelled` descendant and no-ops that run's portion (no double-terminal, no
resurrection), exactly as D20 for a single run.

**Enumeration-race bound.** The cascade set is captured at handler-`:before`; a
descendant that becomes terminal before the CAS is no-op'd by its in-fn guard
(harmless), and its already-computed `:runtime/agent-abort` is no-op'd at execute
time by the D22.2 live-attempt re-check. A descendant **spawned after** enumeration
is bounded by D6/D2/D10: once the parent run is `:cancelled`, the cooperative pull
checkpoint refuses to advance/spawn further sub-runs, so a late descendant cannot be
driven past its checkpoint — the enumeration snapshot is sufficient and no
re-enumeration loop is required.

**Cross-reference (D18).** D18's re-entrant `:runtime/dispatch-event` is reserved for
the cancel-then-**remove** record drop (a pure transition that must run *after* an
effect — forced cross-dispatch). The **cascade** is *all* pure transitions with no
after-effect ordering, so it stays **inside one dispatch's apply phase** (D23) — the
two re-dispatch questions have different answers because their ordering constraints
differ. D3/D14 updated to name the single multi-run apply-phase transition + the
cancel-dispatch effect set as the cascade's issue mechanism.

## Runtime-Handle Cleanup Reconciliation (ψ pass 6, 2026-06-10)

Resolves the pass-6 inconsistency follow-up: D17 step 2 and Acceptance #2 attribute
the `inflight-runs` entry-drop to the pure `remove-run` `:state*` dissoc, but the
code-confirmed mechanism is a separate command-layer handle mutation — contradicting
D1/D2 and leaving the cancellation effect set with no effect to clear the entry.
States the effects-as-data mechanism. No step-machine redesign; one new cleanup
effect added to the existing dispatch-effect pathway.

### D24. `inflight-runs` entry-drop = a canonical `:runtime/*` cleanup effect in the remove dispatch's effect set (not the pure `remove-run` dissoc)

**Premise (code-confirmed).** Pure `remove-run` (`workflow-runtime/core.clj:217`,
`state → [state', run]`) dissocs **only** canonical `:state*` (`runs-path` /
`run-order-path`); it never touches `inflight-runs`. `inflight-runs` is a separate
`defonce` runtime-handle atom (`agent-session/workflow/runtime_state.clj:11`);
today its remove-flow entry is dropped by a **command-layer side effect**
`(swap! inflight-runs dissoc run-id)` (`agent-session/workflow/core.clj:493`,
`delegate-remove`). So attributing the `inflight-runs` drop to the pure
`remove-run` `:state*` dissoc (D17 step 2 / Acceptance #2 wording) is code-
inaccurate, and it contradicts **D2** (`inflight-runs` ∈ pure runtime handle, **not**
the canonical `:state*` a pure transition mutates) and **D1** (pure transitions
perform no side effects; handle mutations flow as effects-as-data). The D12/D23
cancellation effect set defines no effect to clear the entry.

**Decision — option (a): a dedicated canonical `:runtime/*` cleanup effect.** The
`inflight-runs` entry-drop is its **own canonical `:runtime/*` effect** (e.g.
`:runtime/drop-inflight-run`, carrying `run-id`), registered in the agent-session
`effect-schema` with a **parity** `execute-effect!` method that dissocs the entry
from the `inflight-runs` handle reached via `ctx` (the `context.clj` injection of
D25) — exactly the parity shape of the D12 worker `future-cancel` effect, which
likewise reaches the `ctx`-injected `inflight-runs` handle (D25).
It is emitted in the **remove dispatch's** (D17 dispatch 2) effect set and executed
by the dispatch **`:effects` interceptor** (D1/D12), so it passes the
validate-interceptor `effect-schema` check, is suppressed by
`:trim-effects-on-replay` (preserving the S5 replay closure for the real handle
mutation), and emits dispatch-trace `:dispatch/effect-start`/`-finish`.

**Mechanism within the remove dispatch (D17 dispatch 2).** The pure `remove-run`
dissoc (apply phase) drops the **canonical run record**; the
`:runtime/drop-inflight-run` cleanup effect (effects phase) drops the **handle
entry** — two distinct mechanisms for the two distinct stores, consistent with D2's
signal(canonical)/handle(`inflight-runs`) split. There is no apply-vs-effect
ordering hazard between them (the canonical record removal does not depend on the
handle entry, and vice versa).

**Cross-dispatch ordering vs the worker `future-cancel` (D12/D14).** The handle
entry-drop runs in the **remove dispatch** (dispatch 2), strictly after the **cancel
dispatch's** (dispatch 1) worker `future-cancel(true)` effect — which reads the
future *from* `inflight-runs` via `ctx`. So the future is cancelled **before** its
handle entry is dropped (drop-after-cancel; never drop-then-orphan — the exact
Evidence-step-3 orphaning this task fixes). The two-dispatch split (D17/D18)
sequences them via in-thread re-entrancy (D20), so dispatch 2's drop observes
dispatch 1's future-cancel.

**Idempotency.** `(swap! inflight-runs dissoc run-id)` is a no-op on an absent
entry, so the cleanup effect is execution-time idempotent (D22.2): if the
interrupted worker's own natural-completion cleanup
(`orchestration.clj` on-async-completion) already dropped the entry, the
`:runtime/drop-inflight-run` effect is harmless, and vice versa.

**Why option (a), not option (b).** Option (b) — keep the command-layer
`(swap! inflight-runs dissoc …)` and merely re-label D17/Acceptance to name it —
is rejected: it perpetuates a **command-layer side effect on the handle outside the
dispatch effects pathway**, exactly the boundary this task moves
cancellation/cleanup away from (D1/D12/D23), and would leave the remove-flow handle
mutation un-trimmed on replay and trace-invisible. Option (a) keeps the entry-drop
on the canonical effects-as-data pathway, parity with the D12 future-cancel effect.

**Scope.** D24 covers only the **remove-flow** `inflight-runs` entry-drop (the
removal path this task owns). The existing **natural-completion** `inflight-runs`
cleanups in `orchestration.clj` (the handle owner's own bookkeeping on the worker
thread, at run completion) are **out of scope** — not part of the cancellation
effect set; migrating those handle mutations is not this task's concern.

D17 step 2, D5 step 3, and Acceptance #2 are updated to attribute the
`inflight-runs` entry-drop to the `:runtime/drop-inflight-run` cleanup effect rather
than the pure `remove-run` dissoc.

## Handle-Reachability Reconciliation (ψ pass 7, 2026-06-10)

Resolves the pass-7 architecture-fit follow-up: D12 and D24 both assert the new
worker `future-cancel` and `:runtime/drop-inflight-run` `execute-effect!` methods
reach `inflight-runs` "via `ctx`", but that premise is code-false — `inflight-runs`
is a process-global `defonce` atom not on the dispatch `ctx`. Commits the
handle-reachability mechanism so the "via ctx" parity is real. No step-machine
redesign; one `context.clj` injection added.

### D25. `inflight-runs` is injected onto the dispatch `ctx`; the D12/D24 effect handlers reach it via `ctx` with parity (option (a); not direct defonce-global access)

**Premise (code-confirmed).** `inflight-runs` is a free-standing
`(defonce inflight-runs (atom {}))` (`agent-session/workflow/runtime_state.clj:11`,
aliased `workflow/core.clj:31`) and is **not** on the dispatch `ctx` (absent from
`context.clj`; it is only passed as a plain arg in local orchestration option maps).
Every existing `:runtime/*` `execute-effect!` that touches workflow runtime state
reaches it through a **ctx-injected fn/handle** wired in the `context.clj` ctx map —
e.g. `:runtime/mark-workflow-jobs-terminal` → `((:mark-workflow-jobs-terminal-fn
ctx) ctx)` (injected at `context.clj:248` as `bg-rt/maybe-mark-workflow-jobs-terminal!`);
`:runtime/agent-abort` keys off `(effect-session-id ctx effect)`. So the project's
`:runtime/*` effect pattern is dependency-injection-through-`ctx`, never direct
namespace-global access.

**Decision — option (a): thread `inflight-runs` onto the dispatch `ctx` via a
`context.clj` injection.** The `context.clj` ctx map gains an injected handle key
(e.g. `:workflow-inflight-runs-handle runtime-state/inflight-runs`, alongside the
existing `:mark-workflow-jobs-terminal-fn` / workflow-runtime injections). The new
D12 worker `future-cancel` and D24 `:runtime/drop-inflight-run` `execute-effect!`
methods read that handle from `ctx` — `(:workflow-inflight-runs-handle ctx)` — and
`future-cancel` / `dissoc` against it. This makes the D12/D24 "the `inflight-runs`
handle reached via `ctx`" wording **true**, with parity to every other `:runtime/*`
handler (handle/fn supplied by `ctx`, not reached as a namespace global). This
`context.clj` injection is **in scope** for this task (it is the wiring the new
effect handlers require).

**Why (a), not (b).** Option (b) — let the new `execute-effect!` methods reach the
`defonce inflight-runs` global directly (`(swap! workflow-core/inflight-runs dissoc
…)`) and document it as an exception — is rejected:

- It diverges from the ctx-injection parity of every other `:runtime/*` handler
  (the consistency `λ parity`/`λ(state)` mandate), making the two new effects the
  lone direct-global reach-ins in the effect dispatch surface.
- It is exactly the **extension-local hidden state** META.md cautions against
  ("managed services keyed by logical identity … reused within `ctx`, ¬extension-
  local hidden state"): coupling the dispatch effect handlers to a process-global
  atom is a replay/test-isolation hazard (no `ctx`-scoped seam to substitute a
  fresh handle per test/replay), undercutting the D12/D24 parity + replay-closure
  rationale those decisions are built on.
- Threading the handle onto `ctx` (a) is a one-line injection — the cheaper, more
  consistent fit (`λ build` simple > complex), and it gives tests/replay a `ctx`
  seam to inject an isolated `inflight-runs` handle without rebinding a global.

**Scope note.** The injected handle is still backed by the same
`runtime-state/inflight-runs` atom in production (the orchestration handle owner's
natural-completion cleanups in `orchestration.clj` continue to mutate that same
atom directly — out of scope per D24); `ctx` injection only adds the dispatch-side
**reach-path** the D12/D24 effects need, plus the test/replay substitution seam. No
change to the handle's identity or to the out-of-scope natural-completion cleanups.

D12 and D24's "reached via `ctx`" / "supplies the handle through `ctx`" wording is
made true by this injection; both are annotated with the D25 pointer.

## Entry-Event Taxonomy Reconciliation (ψ pass 8, 2026-06-10)

Resolves the pass-8 ambiguity follow-up: the design pins the cancel-then-remove
*effect set* (D17/D18) and the *atomicity shape* (D20/D22/D23) but never states the
**event/handler structure** — which entry event(s) own the cancel transition, where
the live-vs-terminal branch lives, and whether the cancel-transition logic is shared
or duplicated across the two existing mutations `psi.workflow/cancel-run`
(`canonical_workflows.clj:217`, op-name `'psi.workflow/cancel-run`) and
`psi.workflow/remove-run` (`canonical_workflows.clj:244` + the `delegate-remove`
tool flow `workflow/core.clj:474`). States the entry-event taxonomy. No
step-machine redesign; the effect set is unchanged.

### D26. Entry-event taxonomy: `cancel-run` and `remove-run` are the two entry events; both reuse one shared cancel-transition helper; the live-vs-terminal branch lives in the handler-`:before` (D22.1 gate)

**Premise (code-confirmed).** Two distinct mutations exist today, both performing the
TOCTOU `reset!`-after-guard that D4/D20 supersede: `cancel-workflow-run`
(`canonical_workflows.clj:217`, status-guard then `(reset! :state* …)`) and
`remove-workflow-run` (`canonical_workflows.clj:244`, pure `remove-run` dissoc then
`reset!`); the `delegate-remove` tool flow (`workflow/core.clj:474`) additionally
drops the `inflight-runs` entry by a command-layer `(swap! inflight-runs dissoc
run-id)` (`:493`, superseded by D24). Both mutations move under dispatch (D1/D4/D20).

**Decision — option (a): the `remove-run` handler owns the cancel-then-remove flow;
no command-layer orchestration; the cancel-transition logic is a shared helper.**

- **(a) Owner of the remove-of-live cancel transition.** The **`remove-run`
  handler itself** produces the cancel-then-remove on a live run — it is **not**
  the remove command dispatching a `cancel-run` event first (rejected option (b)).
  Option (b) would require either command-layer sequencing of two dispatches (in
  tension with D18's rejection of command-layer orchestration) or parameterizing
  `cancel-run` with a "then-remove" flag so its effect set conditionally chains the
  remove — which contradicts D18's "plain `cancel` (no remove) simply omits effect
  (3); a direct `remove` of a live run is the only flow that emits (3)." Keeping the
  remove flow inside the `remove-run` handler keeps the chaining as effects-as-data
  (D18) with no orchestration leaking into the command/mutation layer.

- **Two entry events, distinguished by their effect tails.** `cancel-run` and
  `remove-run` remain the two distinct entry events:
  - **`cancel-run` (live run):** the shared cancel transition (the D23 multi-run
    `:cancelled` apply-phase `:root-state-update`) + the D12/D14/D15 cancellation
    effect set + the D13 `:runtime/mark-workflow-jobs-terminal` terminalize. It does
    **not** chain a re-entrant remove dispatch and never drops the record.
  - **`remove-run` (live run, first pass):** the **same** shared cancel transition +
    cancellation effects + terminalize, **plus** the D18 re-entrant
    `:runtime/dispatch-event` follow-on effect targeting `remove-run` (dispatch 2).
    This first pass **does not** apply the `remove-run` dissoc (apply-before-effects
    would drop the record before the terminalize effect — D17); it only commits
    `:cancelled` and emits the effect tail.
  - **`remove-run` (terminal/absent run — the re-entrant second pass, and plain
    remove-of-terminal):** the **bare unconditional `remove-run` dissoc** (D22.1
    record-drop, status-independent) + the D24 `:runtime/drop-inflight-run` cleanup
    effect; **no** cancellation effects (the run is already terminal at the
    handler-`:before` read, so the D22.1 gate contributes none).

- **(c) Where the live-vs-terminal branch lives.** Inside the `remove-run`
  **handler-`:before`** (the canonical-state read), **not** the command layer — it
  is exactly the **D22.1 terminal-precondition gate** read, reused as the
  live-vs-terminal selector: a non-terminal run takes the first-pass branch (cancel
  transition + effects + chained remove dispatch, no dissoc); a terminal/absent run
  takes the record-drop branch (bare dissoc + drop-inflight-run, no cancellation
  effects). No new branching mechanism is introduced; it is the D22.1 read already
  required. Putting the branch in the handler-`:before` keeps it out of the command
  layer (D18) and rides the D20 in-`swap!`-fn guard for atomicity.

- **(d) Shared, not duplicated.** The cancel-transition + cancellation-effect-set
  construction (the D23 multi-run `:cancelled` `:root-state-update` builder + the
  D12/D14/D15 cancellation effect set + the D13 terminalize effect, all gated by the
  D22.1 terminal-precondition) is **one shared helper** invoked by both the
  `cancel-run` handler and the `remove-run` first-pass branch. `remove-run`'s
  first pass = `(shared-cancel-transition …)` **with** the re-entrant remove
  dispatch-event appended to its effect set; `cancel-run` = the same helper
  **without** that appended effect. This avoids duplicating the cascade-enumeration
  / guard / effect-emission logic across the two handlers (`λ build` compose;
  `consistent(idioms)`), and guarantees `cancel`-then-`remove` and direct `remove`
  reach the identical terminal transition.

So the taxonomy is: **`cancel`** = shared-cancel-transition (no re-entrant remove);
**`remove`-of-live** = `remove-run` handler → first pass = shared-cancel-transition +
re-entrant `remove-run` dispatch (no dissoc) → second (re-entrant) pass = bare dissoc
+ drop-inflight-run; **`remove`-of-terminal** = `remove-run` handler → directly the
bare-dissoc branch. D5/D17/D18 are updated to name `remove-run` as the owner of the
cancel-then-remove flow, the shared helper, and the handler-`:before` live-vs-terminal
branch (the D22.1 gate read).

## Direct-Sub-Run-Cancel Spawn-Race Reconciliation (ψ pass 8, 2026-06-10)

Resolves the pass-8 ambiguity follow-up: whether the D23 enumeration-race bound
holds for a **direct sub-run cancel** — which emits **no** worker `future-cancel`
(D19) — or is an accepted true-concurrency exception. Reconciles D6/D14/D19/D23. No
step-machine redesign; the effect set is unchanged.

### D27. Direct sub-run cancel: the D6 "no new child session after the checkpoint" guarantee holds via the cascade-set's own per-run cooperative checkpoints; one accepted, bounded true-concurrency spawn-race exception (analogous to D22.2 / criterion #9)

**The gap.** D23's enumeration-race bound leans on two stops: (i) the cancelled
run's cooperative pull checkpoint refusing to advance/spawn further once it reads
`:cancelled` (D6/D2/D10), and (ii) — for a **top-level** cancel — the single
`future-cancel(true)` (D14) interrupting the whole synchronous worker stack so a
parked deeper frame wakes to its checkpoint promptly. A **direct sub-run cancel**
emits **no** worker interrupt (D19), so (ii) is absent: a deeper descendant child
turn/session spawned in the window between the D23 handler-`:before` enumeration and
the worker reaching its next checkpoint is neither in the cascade set (not
D15-aborted) nor interrupted.

**Decision — the guarantee holds, with one bounded exception.** Resolve in two
parts:

1. **The cascade *set* is fully covered (no exception there).** Every run in the
   cascade set (the cancelled sub-run ∪ its non-terminal `:delegating-run-id`
   descendants enumerated at handler-`:before`, D23) receives the `:cancelled`
   signal in the single multi-run apply CAS (D23) **and** a per-in-flight-attempt
   `:runtime/agent-abort` (D15). Each such run therefore stops at **its own**
   cooperative pull checkpoint (D2/D10) — the synchronous worker, unblocked frame by
   frame as each in-flight child turn is aborted, returns up the stack and at every
   level reads that level's `:cancelled` signal and refuses to spawn the next
   step/sub-run/child-session. So D6's "no new child session spawns after the cancel
   checkpoint" is upheld **across the whole enumerated subtree** by the per-run
   cooperative checkpoints (pull) + the per-attempt aborts (push wake), **without**
   needing the worker `future-cancel` — child-abort, not a worker interrupt, is the
   wake mechanism in the sub-run case (D19). The parent run (above the cancelled
   sub-run) is **not** in the cascade and legitimately continues (D19); D6's
   guarantee is scoped to the cancelled subtree, not the parent's own ongoing work.

2. **One bounded true-concurrency exception (accepted, not a defect).** A descendant
   **spawned after** the handler-`:before` enumeration snapshot but **before** the
   already-aborting frame reaches the checkpoint that would refuse it is, narrowly,
   neither enumerated (so not D15-aborted) nor interrupted (no D19 worker
   `future-cancel`). This is the **same** class of residual race already accepted at
   D22.2 / acceptance criterion #9 (the true-concurrent CAS race whose harmlessness
   is asserted by construction, not a deterministic test) — here a spawn racing the
   abort-driven checkpoint rather than a CAS racing a second cancel. It is **bounded**:
   (a) it requires the cancelled-subtree worker to spawn a *new* child session in the
   sub-millisecond window between enumeration and the abort unblocking its current
   frame — and the spawn itself goes through a cooperative checkpoint that reads the
   already-committed `:cancelled` signal (the CAS applied before any effect runs,
   D20/D23 apply-before-effects), so in practice the checkpoint that *would* spawn the
   new child already sees `:cancelled` and refuses; (b) the window closes the instant
   the per-attempt abort returns control to the checkpoint; (c) a child session that
   does momentarily start is itself a cancellable run that the *parent's* eventual
   handling does not adopt, and any further descent re-reads `:cancelled`. So the
   residual is a single momentarily-spawned turn at most, never an unbounded runaway —
   the exact harm class (a bounded, self-terminating extra turn) D22.2 already accepts.

**Why not require a worker interrupt for the sub-run case.** Emitting
`future-cancel(true)` on a direct sub-run cancel would interrupt the **shared
top-level worker** and thereby the **still-`:running` parent** (and sibling sub-runs)
— exactly the D14/D19 prohibition ("a child cancel must not kill a running parent").
The cooperative per-run checkpoints already bound the subtree; adding the interrupt to
close a sub-millisecond spawn window would violate the larger parent-survival
invariant. The cost/benefit favors the cooperative bound + the accepted narrow
exception (`λ build` simple > complex).

**Reconciliation (D6/D14/D19/D23).** D6's guarantee is restated for the direct
sub-run case as: *no new child session spawns after each cascade-set run's own cancel
checkpoint*, upheld by the per-run cooperative checkpoints (D2/D10) + per-attempt
aborts (D15) — the worker `future-cancel` (D14) is the **top-level** case's promptness
mechanism, not a correctness prerequisite for the subtree bound. D19's no-worker-
interrupt-on-sub-run-cancel stands. D23's enumeration-snapshot sufficiency stands for
the cascade set; the post-enumeration spawn is the one bounded true-concurrency
exception (D22.2 / criterion #9 class), now made explicit. A new acceptance note marks
this exception **[out-of-test-scope]** (a bounded true-concurrency race, asserted by
construction, not a deterministic test).

## Ambiguity Reconciliations (ψ pass 11, 2026-06-11)

Resolves the pass-11 ambiguity follow-ups. These pin effect guard payloads, public
terminal/absent result semantics, cancellation-control side-effect boundaries, and
the testable meaning of "cancel checkpoint". No implementation work is performed
here.

### D28. Workflow-cancellation `:runtime/agent-abort` uses guarded metadata; non-workflow aborts remain session-id-only

D15 identifies the child session to abort as the in-flight attempt's
`:execution-session-id`, while D22.2 requires the executor to re-read liveness from
canonical run state at execute time. The missing piece is how the existing
`:runtime/agent-abort` effect, historically keyed only by `:session-id`, knows which
workflow attempt to validate.

**Decision.** Workflow-cancellation emissions of `:runtime/agent-abort` carry
workflow guard metadata in addition to the existing `:session-id`:

```clojure
{:effect/type :runtime/agent-abort
 :session-id sid
 :workflow-run-id run-id
 :workflow-step-id step-id
 :workflow-attempt-id attempt-id
 :expected-session-id sid}
```

At execute time the effect handler branches on presence of the workflow guard:

- **Guarded workflow-cancel abort:** re-read canonical `:state*`, locate
  `:workflow-run-id`, then `:workflow-step-id`, then the **latest** attempt. Abort
  only if the latest attempt's `:attempt-id` equals `:workflow-attempt-id`, its
  `:execution-session-id` equals `:expected-session-id`/`:session-id`, and its
  status remains live (`#{:running :validating}`, D15). Otherwise no-op. This is
  D22.2's execution-time idempotency rule and prevents aborting a completed,
  superseded, or unrelated/reused session.
- **Unguarded non-workflow abort:** effects that omit the workflow guard retain the
  existing session-id-only behaviour. They are not forced to invent workflow state
  they do not have, and their schemas remain valid.

**Schema/executor implication.** `effect-schema` extends the existing
`:runtime/agent-abort` shape with optional workflow guard keys (or a nested optional
`:workflow-abort-guard` map) while keeping `:session-id` required. No new abort
effect type is introduced; this is a guarded workflow-cancellation variant of the
existing effect. D12/D15/D22.2 are aligned to this rule.

### D29. Public result semantics for terminal/absent `cancel-run` and `remove-run`

The public API must be idempotent in the same way the state/effect model is
idempotent: terminal/absent requests are not runaway errors that re-fire effects.
They return stable success/no-op shapes that make operator retries safe.

**`cancel-run` result contract.**

- **Live run:** applies the shared cancel transition and returns success:
  `{:psi.workflow/run-id run-id :psi.workflow/status :cancelled
    :psi.workflow/cancelled? true :psi.workflow/noop? false
    :psi.workflow/error nil}`.
- **Already terminal run (`:completed`, `:failed`, or `:cancelled`):** returns
  success/no-op with the current status and emits no cancellation effects:
  `:psi.workflow/cancelled?` is true only when the current status is already
  `:cancelled`; `:psi.workflow/noop? true`; `:psi.workflow/error nil`. This covers
  a repeated cancel and a cancel racing natural completion after the terminal state
  is visible at handler read time (D22.1).
- **Absent run:** returns success/no-op absent, not an exception:
  `{:psi.workflow/run-id run-id :psi.workflow/status nil
    :psi.workflow/found? false :psi.workflow/noop? true
    :psi.workflow/error nil}`. No cancellation effects are emitted.

**`remove-run` result contract.**

- **Live run:** first pass returns/remains a successful remove request while the
  handler performs cancel-then-remove (D26):
  `{:psi.workflow/run-id run-id :psi.workflow/removed? true
    :psi.workflow/cancelled? true :psi.workflow/noop? false
    :psi.workflow/error nil}`. The canonical record is removed by the re-entrant
  second pass; the public command reports the requested remove as accepted/successful.
- **Already terminal run:** performs the bare record-drop branch (D22.1/D26) and
  returns `:psi.workflow/removed? true`, `:psi.workflow/noop? false`,
  `:psi.workflow/error nil`. The cancellation-effect set is empty; only the
  record-drop and `:runtime/drop-inflight-run` cleanup run.
- **Absent run:** returns success/idempotent no-op removal:
  `:psi.workflow/removed? false`, `:psi.workflow/found? false`,
  `:psi.workflow/noop? true`, `:psi.workflow/error nil`.

This intentionally changes the current "not found" / "already terminal" exception
shape for cancel/remove. Unexpected validation/schema failures may still return
`:psi.workflow/error`; idempotent terminal/absent states do not. Tests should assert
these public result fields along with the D22 effect-emission rules.

### D30. Forbidden workflow side effects vs allowed cancellation-control effects

Acceptance #3's "no new side effects" refers to **ordinary workflow/child-turn
advancement**, not to the cancellation machinery required to stop that advancement.
The boundary is:

- **Forbidden after the cancel checkpoint (D31):** new workflow step attempts, new
  delegate sub-runs, new ordinary child agent sessions for workflow steps, new tool
  calls, commits, ordinary child-turn journal writes, and other user/worktree
  effects initiated by the cancelled subtree.
- **Allowed/required cancellation-control effects:** the `:cancelled` canonical
  state transition, background-job terminalization, guarded `:runtime/agent-abort`,
  worker `future-cancel(true)` wait wake-up, abort/interruption records/events if
  the existing abort path writes them, dispatch trace (`:dispatch/effect-start` /
  `:dispatch/effect-finish`), the re-entrant `remove-run` dispatch for
  cancel-then-remove, the pure canonical record drop, and
  `:runtime/drop-inflight-run` handle cleanup.

Tests must therefore assert absence of **forbidden advancement effects**, not a
literal absence of all writes/effects after cancellation. Cancellation bookkeeping is
expected evidence that cancellation executed. D6 and Acceptance #3 use this boundary.

### D31. "Cancel checkpoint" = the apply-phase CAS that commits `:cancelled`

The term "cancel checkpoint" is now testable and consistent. It denotes the moment
the apply-phase `swap!` CAS first commits `:status :cancelled` for the directly
cancelled run; in a cascade, each enumerated descendant's checkpoint is the same
multi-run D23 CAS committing that descendant's `:cancelled` status. It is **not**
the operator request arrival, handler-before read, interrupt delivery, or the
worker's later cooperative read.

Consequences:

- **Request → CAS window:** work that starts before the CAS commits is not a
  post-checkpoint violation. The implementation should make this window small, but
  the guarantee begins when the canonical cancellation signal exists.
- **CAS → interrupt/effect execution window:** cancellation-control effects run
  after apply (D20/D23). Ordinary advancement that starts after the CAS is
  forbidden; the cooperative checkpoint reads `:cancelled`/run-absence and refuses
  it.
- **Interrupt delivery / child abort:** these are promptness mechanisms that wake
  blocked work so it can reach the cooperative read; they do not define the
  checkpoint.
- **Worker cooperative read:** this observes/enforces the signal; it is not the
  start of the guarantee. If a worker attempts to start a next step after the CAS
  but before reading, that is a bug in the required checkpoint placement.
- **Nested runs:** for top-down cancellation the D23 multi-run CAS is the checkpoint
  for the whole cascade set. For a direct sub-run cancel, the checkpoint applies to
  the cancelled subtree; the still-running parent above it may continue by design
  (D19). The bounded post-enumeration spawn race remains the explicit
  out-of-test-scope exception in D27/Acceptance #9a.

Acceptance #1/#3/#4/#6 now use this definition: tests should record/observe the
canonical `:cancelled` commit (or a controlled hook immediately after the apply
phase) and assert no forbidden advancement begins after that point.

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

Each criterion is tagged **[guaranteed]** (a definition-of-done requirement an
implementer must cover with a test) or **[out-of-test-scope]** (a true-concurrency
race whose harmlessness is asserted by construction/code review, not a deterministic
test). The criteria are reconciled with D14/D19/D21/D22 and the pass-11 refinements
(D28–D31) so the test surface covers the motivating Evidence cases.

### Top-level run cancellation

1. **[guaranteed]** A test cancels a multi-step **top-level** workflow run mid-flight
   and asserts that **no step attempt is started after the cancel checkpoint**
   (defined by D31 as the apply-phase CAS that commits `:cancelled`) and the run
   reaches a clean `:cancelled` terminal state with its background job terminal.
2. **[guaranteed]** A test asserts `remove` of a live **top-level** run does not leave
   a running worker future / orphaned thread: the single top-level run's future is
   `future-cancel`'d (D14) and its `inflight-runs` entry is cleared by the
   `:runtime/drop-inflight-run` cleanup effect (D24) — emitted in the remove dispatch
   after the cancel dispatch has `future-cancel`'d the future and terminalized the job
   (cancel-then-remove, D5/D17). The entry-drop is the cleanup effect, **not** the pure
   `remove-run` `:state*` dissoc (which clears only the canonical record — D24).
   (Criterion #2 is explicitly **qualified to a top-level run** — the worker
   `future-cancel` target is the single top-level run, never a sub-run, per D14.)
3. **[guaranteed]** No **new forbidden ordinary workflow side effects** (new step
   attempts, delegate sub-runs, ordinary child sessions, tool calls, commits, or
   ordinary child-turn journal writes) are initiated after the D31 cancel checkpoint
   — the in-flight turn is interrupted and at most one already-in-flight tool call
   may complete (D6). Required cancellation-control writes/effects (cancelled state,
   job terminalization, guarded aborts, dispatch trace, re-entrant remove,
   `inflight-runs` cleanup) are allowed and should not fail this assertion (D30).
   Verified in a nullable/controlled harness.

### Transitive / nested sub-run propagation

4. **[guaranteed]** A test asserts top-down cancellation propagates to a nested
   delegate sub-run: each in-flight descendant's child session is aborted using the
   D28 guarded `:runtime/agent-abort` payload/read rule so its turn does not advance,
   and the cancelled run plus its in-flight descendants reach `:cancelled` terminal
   via the single multi-run apply-phase transition (D23). **No** per-sub-run worker
   `future-cancel` is emitted (sub-runs are synchronous, carry no own future — D14);
   only the single top-level future is interrupted.
5. **[guaranteed]** A test asserts `remove` of a live **nested sub-run** (the nested
   variant of criterion #2): its guarantee is child-turn abort + the parent observing
   **run-absence ≡ `:cancelled`** and **continuing** (not halted) — and **no** worker
   `future-cancel` is emitted (D14/D19/D21). (Distinct from criterion #2's top-level
   future cancellation.)

### Evidence-step-2 direct cases

6. **[guaranteed]** A test asserts **direct cancel of a nested sub-run** (Evidence
   step 2): the downward child abort unblocks the shared parent worker, the sub-run
   reaches `:cancelled`, and the parent observes a **failed delegate step** via the
   existing `delegate-step-runtime-result` `:cancelled` case and **continues, not
   halted** (D19). No worker `future-cancel` is emitted for the sub-run.
7. **[guaranteed]** A test asserts **direct `remove` of a live sub-run**: after the
   record is dropped, the parent reading **run-absence** (`nil` delegate-run) maps to
   the **same** failed-delegate-step result as `:cancelled` (D21), so the parent's
   continue-not-halt outcome is race-independent across the cancel-vs-remove timing.

### Idempotency / race-safety

8. **[guaranteed]** A test asserts a repeated/**sequential** terminal request (a
   second `cancel`, the `cancel`-half of `remove`-of-already-terminal, or a `cancel`
   after natural completion) is a no-op that **emits no cancellation effects** — the
   handler-before terminal-precondition gate contributes `identity` + empty effect set
   when the run is already terminal/absent (D22.1) — while the `remove-run` record-drop
   **still applies** to an already-terminal run (D22.1; not re-orphaned). Public
   result fields follow D29: terminal/absent cancel/remove are success/no-op shapes,
   not "already terminal" / "not found" errors.
9. **[out-of-test-scope]** Execute-time idempotency on the **true-concurrent** CAS
   race (two cancels racing on two threads both emitting effects, D20 applying
   `:cancelled` once): workflow-cancellation `:runtime/agent-abort` re-checks the D15
   live-attempt predicate using D28 guard metadata (`run-id`, `step-id`, `attempt-id`,
   expected `execution-session-id`) at execute time and no-ops a non-live/superseded
   attempt; unguarded non-workflow aborts remain session-id-only; `future-cancel`,
   terminalize, and the re-entrant remove are inherently idempotent (D22.2).
   Harmlessness is established by construction/code review (the narrow concurrent
   race is not deterministically reproducible), not a deterministic test.
9a. **[out-of-test-scope]** The **direct-sub-run-cancel spawn race** (D27): a
    descendant child session spawned in the sub-millisecond window between the D23
    handler-`:before` enumeration and the abort-driven checkpoint refusing it — absent
    a worker `future-cancel` on a sub-run cancel (D19). The cascade *set* is fully
    covered by per-run cooperative checkpoints (D2/D10) + per-attempt aborts (D15), so
    D6's "no new child session after each cascade-set run's checkpoint" holds; the
    post-enumeration spawn is one bounded, self-terminating exception of the same class
    as criterion #9, asserted by construction (a spawn checkpoint reads the
    already-committed `:cancelled` signal), not a deterministic test (D27).

### Public result semantics

10. **[guaranteed]** Tests assert the D29 public result contract: live cancel returns
    success with `:status :cancelled`; terminal cancel and absent cancel return
    success/no-op without cancellation effects; live remove returns successful
    cancel-then-remove; terminal remove drops the record without cancellation
    effects; absent remove returns success/idempotent no-op (`:removed? false`,
    `:found? false`, `:noop? true`).

### Build gates

11. **[guaranteed]** `bb test` green; clj-kondo clean; CHANGELOG updated
    (user-visible: cancelling a delegated workflow now actually stops it).
