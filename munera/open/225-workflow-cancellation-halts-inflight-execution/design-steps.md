# Design follow-up steps

## Architecture-fit follow-ups (ψ, 2026-06-10)

- [x] Decide and document whether the new cancellation side effects
      (`future-cancel` / interrupt / child-session abort) are modeled as
      effects-as-data executed at the dispatch/runtime boundary, or are an
      explicitly-justified legacy-mutation exception (no silent inline side
      effects in `cancel-run`/`remove-run`). (AGENTS.md S1/S3, `λ(state)`)
      → design.md D1: effects-as-data executed at the orchestration runtime
      boundary; pure transitions stay side-effect-free; no exception taken.
- [x] Commit the cancellation signal to canonical `:state*` and have the
      cooperative step-loop check read it via the read path, keeping
      `inflight-runs`/the future a pure runtime handle (State-boundary +
      one-way reads-through-resolvers). State the signal/handle split in design.
      → design.md D2: signal (`:status :cancelled`) ∈ `:state*`, read via
      `workflow-run-in`; future/`inflight-runs` ∈ runtime handle.
- [x] Assign ownership of the transitive cancellation cascade (nested sub-runs +
      child agent sessions) to a coordinated dispatch path routed through the
      agent-session session-dispatch authority; avoid ad-hoc cross-handle
      reach-in / a propagation shim. (AGENTS.md authority + `λ shims_adapters`)
      → design.md D3: agent-session-owned cascade; nested sub-runs enumerated
      from canonical run-tree and re-dispatched; child sessions via existing
      `turn/abort` dispatch path.
- [x] Decide whether terminal-state transitions (cancel/remove/complete) must
      route through serialized dispatch (single-writer) to obtain the design's
      idempotent / no-double-terminal / cancel-during-wait race-safety, instead of
      ad-hoc guards on a directly-`reset!`'d atom (cf. task 224
      atomicity-from-dispatch-serialization).
      → design.md D4: yes — terminal transitions route through serialized
      dispatch single-writer; supersedes ad-hoc `reset!` guards.

## Ambiguity follow-ups (ψ, 2026-06-10)

- [ ] Resolve Design Question 3: pick one explicit contract for `remove` of a
      live run — cancel-then-remove vs reject-while-live — and update Desired
      Behaviour, Scope, and Q3 to state the single chosen semantics (no "(or …)"
      alternatives).
- [ ] Resolve Design Question 1: state whether interrupting the directly-cancelled
      run's in-flight child turn (preventing the one in-flight commit) is a
      *guaranteed* acceptance requirement or best-effort. Reconcile the absolute
      Intent/Acceptance "no further side effects after cancel" with Desired
      Behaviour's "at minimum … ideally … interrupted" and acceptance criterion
      #3's "signalled to stop / turn does not advance" — remove the "/" ambiguity.
- [ ] Define the cancel-during-blocking-`send-and-drain`-wait contract: is a cancel
      arriving mid-wait observed only at the next between-steps checkpoint (turn
      runs to natural completion) or is the wait actively interrupted to stop
      promptly? State the guaranteed stop bound (ties D2 "interrupt-aware wait
      wake-ups" to a concrete behaviour).
- [ ] Specify the division of labor between the cooperative signal-read stop (D2)
      and `future-cancel`/interrupt of the worker future: which is primary for
      unblocking a parked wait, and whether `future-cancel` is a backstop after
      cooperative exit. Determines whether wait interrupt-safety is in scope.
- [ ] Make explicit whether D1 (child-session abort executed at the orchestration
      runtime boundary as effect-as-data) and D3 (child-session abort via the
      agent-session `:session/abort` dispatch authority) describe one path (effect
      handler invokes the dispatch authority) or two owners; state the single path.
- [ ] Update the "Design Questions (resolve during refinement)" section to mark
      each of Q1–Q4 as resolved (with pointer to its D-decision) or still-open, so
      an implementer can tell which questions remain live.
