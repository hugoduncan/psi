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

## Ambiguity follow-ups (ψ pass 2, 2026-06-10)

- [x] Resolve the per-sub-run cancellation-effect target for nested sub-runs.
      Nested delegate sub-runs run **synchronously on the parent worker thread**
      (`delegate/delegate-step-runtime-result` calls `send-and-drain-fn` inline);
      only top-level runs register a `{:future :job-id}` in `inflight-runs`. State
      explicitly whether the D12 worker `future-cancel(true)` effect targets only
      the single top-level run's future (with the synchronous sub-tree winding down
      via per-sub-run cooperative `:cancelled` signals + the one parent-thread
      interrupt + child-session abort), or whether sub-runs carry their own futures.
      Reconcile Intent ("transitively across nested sub-runs"), Desired Behaviour,
      Scope, and D3/D12 so the recursive sub-run cancel's *effect* (not just its
      signal) has a defined target. Include the "in-flight sub-run" status filter
      used for the D3 cascade enumeration.
      → design.md D14: worker `future-cancel(true)` targets only the single
      top-level run's future (run-tree root owning the `inflight-runs` entry);
      sub-runs are synchronous, carry no own future, and wind down via per-sub-run
      cooperative `:cancelled` signals + the one parent-thread interrupt +
      per-in-flight-run child abort. D3 cascade enumerates non-terminal
      (`#{:pending :running :blocked}`) descendants by `:delegating-run-id`
      parentage. Intent/Desired Behaviour/Scope/D3/D12 reconciled.
- [x] Specify how the cancel/cascade path resolves the **session-id** argument for
      the `:runtime/agent-abort` child-session-abort effect. Its `execute-effect!`
      is keyed on a session-id (`effect-session-id`), but D9/D12 do not state whether
      that session-id comes from the in-flight attempt's `:execution-session-id`
      (working-memory `:sessions` / step-run attempts) or the run's
      `:parent-session-id`, nor whether a parent cancel aborts only the single
      in-flight child turn or every descendant run's recorded child session. State
      the read rule (from canonical run state) and the set of sessions aborted.
      → design.md D15: abort `:session-id` = the in-flight child turn's
      `:execution-session-id` (latest live attempt of the run's `:current-step-id`,
      attempt status ∈ `#{:running :validating}`), read from canonical `:state*`;
      never the run's `:parent-session-id`. Set aborted = the directly-cancelled
      run + each in-flight descendant sub-run with a live attempt (one abort per
      currently-executing child turn), not every descendant's historical session.

## Inconsistency follow-ups (ψ pass 2, 2026-06-10)

- [x] Relabel `:runtime/mark-workflow-jobs-terminal` in D13 and Desired Behaviour:
      it is the single writer for the **background-job (projected) terminal
      status** (reconciled *from* run status), not "the single writer for
      run-terminal status." D4 already owns the run's `:status` single-writer
      (serialized dispatch transition). Fix the conflated wording so the two
      writers (run `:status` = D4 dispatch transition; job/projection terminal =
      `:runtime/mark-workflow-jobs-terminal`) are not both titled "single writer
      for run-terminal status."
      → design.md: relabeled at Desired Behaviour, Scope, and D13 (added explicit
      "Two distinct writers" paragraph); run `:status` = D4 dispatch writer,
      background-job projected terminal = `:runtime/mark-workflow-jobs-terminal`.
- [x] Reconcile cancel-then-remove (D5) with the "no lingering `:running` job"
      guarantee (Desired Behaviour) and D13's reuse of
      `:runtime/mark-workflow-jobs-terminal`. As implemented,
      `maybe-mark-workflow-jobs-terminal!` reconciles each job only `(when wf ...)`
      (skips when the run/workflow instance is absent) and has branches only for
      `:error?`/`:done?` — no `:cancelled`/removed-run path. After D5 step 3
      removes the run record, the effect cannot terminalize that run's job, leaving
      it lingering. State either (a) an ordering constraint that job
      terminalization runs **before** the run record is removed, and/or (b) that
      the reused effect must gain a cancelled/removed-run terminalization path
      (and a correct `:cancelled` outcome rather than `:done?`→`:completed`), so
      the guarantee holds for the cancel-then-remove path — not just natural
      completion.
      → design.md D16: both (a) terminalize-before-remove ordering (record removal
      is the last cancel-then-remove step) and (b) a new `:cancelled` reconcile
      branch in `maybe-mark-workflow-jobs-terminal!` with `:outcome :cancelled`.
      Both required: (a) alone mislabels outcome/skips pure-removal; (b) alone hits
      post-removal `workflow-in`→nil. Code-confirmed the `(when wf …)` +
      `:error?`/`:done?`-only handler.

## Architecture-fit follow-ups (ψ pass 3, 2026-06-10)

- [x] Reconcile D16's terminalize-before-remove ordering with the dispatch
      apply-before-effects sequencing contract (doc/architecture.md "Dispatch
      sequencing contract": effective after-order `:apply → :validate →
      :trim-effects-on-replay → :effects`). The run-record removal is the pure
      `remove-run` `:state*` dissoc (`workflow-runtime/core.clj`, `state →
      [state', run]`), so it executes in the `:apply` phase — **before** the
      `:runtime/mark-workflow-jobs-terminal` effect (D13), which re-reads the run
      via `workflow-in` in the `:effects` phase. In a single cancel-then-remove
      dispatch the apply-phase removal therefore precedes the terminalize effect →
      `workflow-in`→nil → job skipped, defeating the D16 "no lingering job"
      guarantee. State the fit resolution: either (a) split cancel-then-remove so
      the canonical run-record removal occurs in a **distinct subsequent dispatch**
      (cancel dispatch terminalizes the job while the run is still present; a later
      remove dispatch drops the canonical record), or (b) make the
      `:runtime/mark-workflow-jobs-terminal` reconcile **not depend** on re-reading
      the canonical run (carry run identity + `:cancelled` outcome in the effect
      payload). Update D5/D13/D16 so the ordering is expressible under the
      apply-before-effects pipeline (a pure `:state*` removal cannot be sequenced
      after an effect within one dispatch).
      → design.md D17: option (a) — cancel-then-remove is two serialized
      dispatches; the cancel dispatch terminalizes the job (D13) + emits the D12
      cancellation effects while the run record is still present, the subsequent
      remove dispatch applies the pure `remove-run` dissoc. Option (b) rejected
      (duplicates canonical run state into the effect payload, forks the
      reconcile-from-canonical-state contract). D5 step 3, D13, and D16(1) updated
      to name the two-dispatch split; D16(2) `:cancelled` branch still required.

## Ambiguity follow-ups (ψ pass 3, 2026-06-10)

- [x] Specify the D17 two-dispatch trigger/sequencing mechanism for
      remove-of-live-run: state how the second (remove) dispatch is issued and
      ordered after the cancel dispatch for a single `remove` request. Decide
      between (a) the cancel dispatch emitting a re-entrant dispatch effect
      (effects-as-data, e.g. a `:runtime/dispatch`-style follow-on effect) that
      enqueues the remove dispatch — consistent with D1/D12 (no inline orchestration
      in the mutation) — vs (b) the `remove` command flow synchronously issuing two
      `dispatch` calls. Note doc/architecture.md documents no re-entrant
      dispatch-emits-dispatch effect today, so state whether a new follow-on-dispatch
      effect type is in scope. Update D17/D5 step 3 so the chaining mechanism is
      expressible and fits the effects-as-data boundary.
      → design.md D18: option (a) — re-entrant follow-on effect reusing the
      **existing** `:runtime/dispatch-event` effect (no new effect type in scope),
      emitted by the cancel dispatch ordered after terminalize+cancel effects;
      option (b) rejected (command-layer orchestration vs D1). Doc gap noted
      (`:runtime/dispatch-event` re-entrancy undocumented in the sequencing
      contract — change-chain doc step). D17 + D5 step 3 updated.
- [x] Pin (or explicitly scope out) the contract for **direct** cancellation of a
      nested sub-run (Evidence step 2). Because sub-runs share the single top-level
      worker thread and D14's `future-cancel(true)` walks `:delegating-run-id` up to
      the shared top-level future, directly cancelling a sub-run interrupts the
      parent's worker. State whether, after the pull check sees only the sub-run
      `:cancelled` (parent still `:running`), the parent run continues — and if so
      how its delegate step interprets a directly-cancelled sub-run's delegate
      result (fail-step / propagate / continue) — or whether interrupting the shared
      worker halts the parent too. If direct sub-run cancellation is out of scope,
      say so explicitly in Scope; otherwise pin the parent-run + cancelled-sub-run
      result-delivery contract. Reconcile with the "Redesigning … delegate
      result-delivery paths" out-of-scope note.
      → design.md D19: in scope. Cascade runs downward only; worker
      `future-cancel(true)` emitted iff the cancelled run is the top-level run
      (not for a direct sub-run cancel — would disrupt the still-running parent).
      Downward child-abort unblocks the shared parent worker; sub-run reaches
      `:cancelled`; parent observes a **failed delegate step** via the existing
      `delegate-step-runtime-result` `:cancelled` case and continues (not halted).
      D14 emission rule refined; Scope + out-of-scope note updated; reuses existing
      result-delivery path (no redesign).
