# 225 Fix: workflow/delegate cancellation does not halt in-flight async execution

## Intent

Cancelling a running delegated workflow must actually stop it. Today, cancelling
(or removing) a run updates registry/run state but leaves the in-flight worker
**future** driving the workflow forward: it keeps advancing steps, spawning child
agent sessions, and committing to the worktree. A cancelled run is a runaway.

This task makes cancellation authoritative: after a cancel, no further steps
execute, no further child sessions spawn, and no further side effects (commits,
journal writes) are produced — transitively across nested sub-runs.

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
  `remove` of a live run must cancel it first (or refuse while live) — never drop
  the handle and leave the thread running.
- **Transitive propagation:** cancelling a parent run cancels its in-flight nested
  delegate sub-runs and signals their child agent sessions to stop, so the whole
  tree winds down.
- **In-flight child turn:** define and implement the intended behaviour for a
  child agent turn already mid-execution when cancel arrives (cooperative signal
  vs. thread interrupt) — at minimum the workflow must not advance past that step,
  and ideally the child turn is interrupted so no further tool calls / commits run.
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
  cancel; `remove` of a live run cancels-then-removes (or rejects) rather than
  orphaning.
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

1. **Checkpoint granularity.** Between-steps cooperative check only, vs. also
   interrupting an in-flight child agent turn. Between-steps is simplest and stops
   runaway advancement; interrupting the current turn additionally prevents the
   one in-flight commit. Decide the guaranteed contract.
2. **Child-session stop mechanism.** Cooperative cancel signal threaded into the
   child session turn vs. thread interrupt of the executing worker. Prefer the
   existing session interrupt/abort pathway if it cleanly aborts a turn.
3. **`remove` semantics on a live run.** Cancel-then-remove vs. reject-with-error
   while running. Cancel-then-remove is friendlier; reject is safer against
   orphaning. Pick one and make it explicit.
4. **Synchronous `execute-run` boundary.** Whether cancellation needs the step
   loop to poll run status (pull) or to receive an interrupt/flag (push), given
   the loop blocks on `send-and-drain` promises — likely both: a status poll at
   each step boundary plus interrupt-aware waits.

## Acceptance Criteria

- A test cancels a multi-step workflow run mid-flight and asserts that **no step
  attempt is started after the cancel checkpoint** and the run reaches a clean
  `:cancelled` terminal state with its background job terminal.
- A test asserts `remove` of a live run does not leave a running worker future /
  orphaned thread (future is cancelled, inflight cleared only after cancel).
- A test asserts cancellation propagates to a nested delegate sub-run (and its
  child session is signalled to stop / its turn does not advance the parent).
- No side effects (commits, journal writes, new child sessions) occur after a run
  is cancelled, verified in a nullable/controlled harness.
- `bb test` green; clj-kondo clean; CHANGELOG updated (user-visible: cancelling a
  delegated workflow now actually stops it).
