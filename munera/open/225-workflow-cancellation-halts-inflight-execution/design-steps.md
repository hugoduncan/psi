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

- [x] Resolve Design Question 3: pick one explicit contract for `remove` of a
      live run — cancel-then-remove vs reject-while-live — and update Desired
      Behaviour, Scope, and Q3 to state the single chosen semantics (no "(or …)"
      alternatives).
      → design.md D5: cancel-then-remove; Desired Behaviour/Scope/Q3 updated to
      drop the "or refuse while live" / "(or rejects)" alternatives.
- [x] Resolve Design Question 1: state whether interrupting the directly-cancelled
      run's in-flight child turn (preventing the one in-flight commit) is a
      *guaranteed* acceptance requirement or best-effort. Reconcile the absolute
      Intent/Acceptance "no further side effects after cancel" with Desired
      Behaviour's "at minimum … ideally … interrupted" and acceptance criterion
      #3's "signalled to stop / turn does not advance" — remove the "/" ambiguity.
      → design.md D6: interrupt is a guaranteed action; contract restated as "no
      new side effects initiated after the cancel checkpoint" (one already-in-flight
      tool call may complete — physics). Intent, Desired Behaviour, Scope, and
      Acceptance #3/#4 updated; "/" removed.
- [x] Define the cancel-during-blocking-`send-and-drain`-wait contract: is a cancel
      arriving mid-wait observed only at the next between-steps checkpoint (turn
      runs to natural completion) or is the wait actively interrupted to stop
      promptly? State the guaranteed stop bound (ties D2 "interrupt-aware wait
      wake-ups" to a concrete behaviour).
      → design.md D7: wait is actively interrupted (`future-cancel(true)`);
      guaranteed stop bound = interrupt delivery + child abort, not natural turn
      completion.
- [x] Specify the division of labor between the cooperative signal-read stop (D2)
      and `future-cancel`/interrupt of the worker future: which is primary for
      unblocking a parked wait, and whether `future-cancel` is a backstop after
      cooperative exit. Determines whether wait interrupt-safety is in scope.
      → design.md D8: read-path check = primary advance-guard (pull);
      `future-cancel(true)` = wait-wakeup + removed-run backstop (push); both
      required for different states; wait interrupt-safety is in scope.
- [x] Make explicit whether D1 (child-session abort executed at the orchestration
      runtime boundary as effect-as-data) and D3 (child-session abort via the
      agent-session `:session/abort` dispatch authority) describe one path (effect
      handler invokes the dispatch authority) or two owners; state the single path.
      → design.md D9: one path — D1 effect handler invokes D3's `:session/abort`
      dispatch; single owner (agent-session) reached through a single effect path.
- [x] Update the "Design Questions (resolve during refinement)" section to mark
      each of Q1–Q4 as resolved (with pointer to its D-decision) or still-open, so
      an implementer can tell which questions remain live.
      → design.md: Q1–Q4 each tagged RESOLVED inline with D-pointers, plus a
      "Design Questions — Resolution status" summary section; no questions remain
      live.

## Inconsistency follow-ups (ψ, 2026-06-10)

- [x] Reconcile the removed-run stop path: D2 ("exits promptly when it observes
      `:cancelled` (or a removed run)") and Scope in-scope ("cooperative
      cancellation check … keyed on run status (`:cancelled`/removed)") assign the
      removed case to the cooperative read-path check, but D8(b) says the removed
      case has "no signal remains to read" and is push-only. State explicitly
      whether the cooperative checkpoint treats a missing `workflow-run-in` result
      (removed run) as a stop signal (pull) or whether removed is exclusively the
      `future-cancel(true)` push backstop, and align D2/Scope/D8 wording.
      → design.md D10: removed run = pull stop signal (run-absence) at the
      checkpoint, identical to `:cancelled`; push only wakes a parked worker so it
      reaches the checkpoint. D8(b)'s "no signal remains to read" corrected; D2 and
      Scope confirmed correct; single stop-signal predicate stated.
- [x] Disambiguate thread-interrupt disposition: Scope Out-of-scope rejects
      "Force-killing threads … (manual `Thread.interrupt` … not the intended API)"
      while D7/D8 make `future-cancel(true)` (a JVM thread interrupt that wakes the
      parked `send-and-drain` deref) a required in-scope mechanism. Explicitly
      distinguish the in-scope cooperative `future-cancel(true)` interrupt from the
      out-of-scope force-kill / manual `Thread.interrupt`, so the two sections do
      not appear to assign thread interruption opposite statuses.
      → design.md D11: cooperative `future-cancel(true)` wait-wakeup (interrupt-aware
      worker exits cleanly at checkpoint) is in scope; out-of-scope is unsafe abrupt
      termination / ad-hoc manual `Thread.interrupt` as primary stop mechanism. Scope
      Out-of-scope bullet reworded to name the rejected thing precisely and point to
      D11.

## Architecture-fit follow-ups (ψ pass 2, 2026-06-10)

- [x] State that the cancellation effects (worker `future-cancel`/interrupt and
      child-session abort) are canonical dispatch `:runtime/*` effect types
      registered in the agent-session `effect-schema` with matching
      `execute-effect!` methods (parity), executed by the dispatch `:effects`
      interceptor — not at an out-of-dispatch "orchestration runtime boundary"
      execution path. Required so they pass the validate-interceptor effect-schema
      check, are suppressed by `:trim-effects-on-replay` (preserving the replay
      closure), and emit dispatch-trace `:dispatch/effect-start`/`-finish`. Update
      D1/D9 to name the dispatch `:effects` interceptor as the executor. (AGENTS.md
      `λ parity`, S1/S3; doc/architecture.md replay-trim + dispatch trace)
      → design.md D12: canonical `:runtime/*` effects (parity: `effect-schema` +
      `execute-effect!`) executed by the dispatch `:effects` interceptor; child
      abort reuses existing `:runtime/agent-abort`; routes through
      validate/`:trim-effects-on-replay`/dispatch-trace; D1/D9 refined to name the
      `:effects` interceptor as executor.
- [x] Assign ownership of "background job marked terminal" to the D2/D4 terminal
      run transition by reusing the existing `:runtime/mark-workflow-jobs-terminal`
      effect (λ extend compose > new mechanism), rather than a separate ad-hoc
      registry write — avoids a second writer for run-terminal status. State this
      in Scope/Desired Behaviour. (doc/architecture.md State-boundary projection)
      → design.md D13: terminalization emitted by the D2/D4 terminal transition
      reusing existing `:runtime/mark-workflow-jobs-terminal` (single writer);
      Scope + Desired Behaviour updated to name the reuse.
