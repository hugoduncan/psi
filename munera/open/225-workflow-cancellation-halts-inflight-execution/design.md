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
  written by the D4 serialized dispatch transition — not a separate registry write.

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
- Modelling the cancellation side effects as canonical dispatch `:runtime/*`
  effects (parity: `effect-schema` + `execute-effect!`) executed by the dispatch
  `:effects` interceptor — child-session abort reusing the existing
  `:runtime/agent-abort` effect (D12); not an out-of-dispatch execution path.
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
  beyond what cancellation propagation requires.
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
  dispatches a cancel for each (recursively), reusing the same cancel mutation
  path. Discovery is from canonical state via the read path; no cross-handle
  reach-in. Per-sub-run *effect* targets are pinned by D14 (worker future-cancel
  hits only the single top-level run; sub-runs wind down via cooperative signals)
  and D15 (child-session abort targets each in-flight attempt's
  `:execution-session-id`).
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
handle (through `ctx`) on which the canonical `execute-effect!` method acts.

Mapping:

- **Child-session abort** reuses the **existing** `:runtime/agent-abort` effect
  (already present with an `execute-effect!` method driving the `:session/abort` /
  `agent-core/abort-in!` path) — `λ extend` compose > new mechanism (consistent
  with D3/D9).
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
written by the D4 serialized dispatch terminal transition (the single writer of
*run* status); `:runtime/mark-workflow-jobs-terminal` is the single writer of the
*background-job* terminal status, which it **reconciles from** that run status.
D13 governs only the latter; it does not (and must not) write run `:status`. The
earlier "single writer for run-terminal status" label conflated the two — corrected
here, in Desired Behaviour, and in Scope.

Concretely: the cancel/remove terminal transition (D4, serialized single-writer)
emits `:runtime/mark-workflow-jobs-terminal` as part of its effect set (alongside
the D12 cancellation effects), and the job reaches terminal via the existing
handler — subject to the D16 ordering + cancelled-path constraints (the current
handler reconciles only when the run record is still present and only for
`:done?`/`:error?`, so a naive cancel-then-remove would leave the job lingering).
Scope and Desired Behaviour are updated to name this reuse and the single owner.

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

The recursive D3 sub-run cancel therefore emits, **per in-flight sub-run** (not a
per-sub-run future-cancel — there is no target):

- (a) the cooperative `:cancelled` **signal** — the canonical-state terminal
  transition (D2/D4) — which is the **pull** stop the synchronous parent worker
  reads at its next checkpoint when it returns up from that sub-run's
  `send-and-drain`; and
- (b) the **child-session-abort** effect for that sub-run's in-flight attempt's
  `:execution-session-id` (D15).

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

1. **Ordering (terminalize-before-remove).** In the D5 cancel-then-remove effect
   set, the `:runtime/mark-workflow-jobs-terminal` reconcile is emitted/ordered to
   run **before** the run-record removal, so the job is terminalized while the run
   is still resolvable. The removal of the registry/`inflight-runs` record is the
   last step of the cancel-then-remove sequence (after the D4 `:cancelled` status
   transition and after job terminalization).

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
