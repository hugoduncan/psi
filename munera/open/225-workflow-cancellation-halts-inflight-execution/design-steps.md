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

## Inconsistency follow-ups (ψ pass 3, 2026-06-10)

- [x] Reconcile D4's race-safety mechanism with the actual dispatch concurrency
      model now made explicit by D18. D4 attributes terminal-transition idempotency
      / no-double-terminal / no-resurrection / "two concurrent cancels cannot both
      apply a terminal transition" to "the single serialized writer (dispatch)" /
      "atomicity-from-dispatch-serialization" and disavows the mutation's outer
      guard. But D18 and the referenced `state-kernel/dispatch.clj:387` establish
      that `dispatch!` runs the interceptor chain synchronously on the **calling
      thread with no global lock** (worker futures dispatch from pool threads), and
      doc/architecture.md's "Dispatch sequencing contract" describes only phase
      ordering, not single-writer serialization. So dispatch is not serialized
      against concurrent threads; the only atomicity is the per-`swap!` CAS on
      `:state*` in `:apply` (`apply-root-state-update!`), and the terminal-status
      guard is computed in the `:handler` `:before` separately from that `swap!` —
      so the read-guard-and-commit is not atomic unless the guard lives inside the
      `:root-state-update` fn. State the real atomicity basis: either (a) require
      the guard-and-commit to be performed inside the `:apply` `swap!`
      root-update-fn (atom CAS) and correct D4's "dispatch serialization" wording
      (and the dependent "D4 single-writer" phrasing in D13/D16/D17), or (b) name
      the actual serialization point if one exists. Align D4/D13/D16/D17 with
      D18 and the dispatch code/doc so the race-safety claim is backed by a real
      mechanism.

## Ambiguity follow-ups (ψ pass 4, 2026-06-10)

- [x] Resolve the D5 × D19 intersection for **direct `remove` of a live nested
      sub-run**. D5 states cancel-then-remove for any "remove of a live
      (non-terminal) run"; D17 dispatch 2 drops the run record; but D19's
      parent-observes-failed-delegate-step contract is pinned only for direct
      *cancel* of a sub-run (parent reads `(:status delegate-run) = :cancelled`).
      After a sub-run *remove*, `workflow-run-in` returns `nil` and the existing
      `delegate-step-runtime-result` `case` (code-confirmed `delegate.clj`) hits
      the **default** branch ("Delegated workflow did not reach terminal or blocked
      status"), not the `:cancelled` branch. State whether direct
      remove-of-a-live-sub-run is in scope and, if so, which delegate-result
      contract the parent observes after run-absence (e.g. treat run-absence the
      same as `:cancelled` for delegate-result purposes, or accept the generic
      "did not reach terminal" failure) — or scope direct sub-run *remove* out
      explicitly in Scope. Reconcile D5/D17/D19.
      → design.md D21: direct remove of a live sub-run = in scope (D5 applies to any
      live run). Cancel-vs-remove race means the parent reads either `:cancelled`
      (record present, D19) or run-absence (record dropped, D17 dispatch 2 →
      `(:status nil)` default branch). Decision: run-absence specifically is mapped
      identically to `:cancelled` at the delegate result (existing `:cancelled`
      failure mapping, via a `nil`/absent guard before the `case`) so D19's
      parent-continues-not-halted outcome is race-independent; non-`nil` non-terminal
      statuses still hit the existing default. Scope updated; D5/D17/D19 reconciled.
- [x] Specify whether the cancellation effect set is **suppressed when the D20
      terminal guard makes the transition a no-op** (run already terminal / lost
      the CAS race to natural completion). D20 establishes the `:state*` CAS no-op
      for racing/second terminal requests, but the handler computes `:effects` in
      the `:before` pre-CAS, so the effects (worker `future-cancel`,
      `:runtime/agent-abort`, `:runtime/mark-workflow-jobs-terminal`, and the
      remove path's re-entrant `:runtime/dispatch-event`) would still fire even
      when the guard no-ops. State whether effects are gated on the guard actually
      applying `:cancelled` (so a no-op'd terminal request emits no effects), to
      back the "second terminal request is a no-op" idempotency claim and avoid an
      `:runtime/agent-abort` against an already-completed run's
      `:execution-session-id`. Align D4/D20 (and the idempotency wording) with the
      pure-result effect-emission shape.
      → design.md D22: code-confirmed `apply-pure-result` takes pre-CAS `:effects`
      verbatim, so the D20 in-`swap!` no-op does not suppress effects. Two-layer gate:
      (1) handler-before terminal-precondition gate emits empty `:effects` when the
      run is already terminal/absent at the handler read (covers all sequential
      idempotency cases — "no-op terminal request emits no effects"); (2) effect-level
      idempotency for the residual true-concurrent CAS race — `:runtime/agent-abort`
      re-checks the D15 live-attempt predicate at execute time and no-ops a non-live
      attempt; future-cancel/terminalize/re-entrant-remove are inherently idempotent.
      D4/D20 cross-referenced: state no-op = in-`swap!` guard, effect no-op =
      D22.1 gate + D22.2 idempotency.

## Inconsistency follow-ups (ψ pass 4, 2026-06-10)

- [x] Reconcile D22.1's "already-terminal ⇒ `{:root-state-update identity
      :effects []}`" handler-before gate with D5 (remove of an already-terminal
      run = **plain record removal**) and D17/D18 (the cancel-then-remove **remove
      dispatch** runs *after* the cancel dispatch already applied `:cancelled`, so
      its handler always reads the run as terminal). As written, applying the
      D22.1 no-op gate to "the cancel/remove handler" makes the `remove-run` dissoc
      a no-op for any already-terminal run — so cancel-then-remove (D5/D17) never
      drops the record and plain remove-of-terminal (D5) leaves the record
      lingering, re-orphaning the exact case this task fixes. State explicitly that
      the handler-before terminal-precondition gate suppresses only the
      **cancellation / terminal-transition effects** (future-cancel, agent-abort,
      mark-jobs-terminal, the re-entrant remove-trigger), while the **record-removal
      `remove-run` dissoc still applies** to an already-terminal run (both the
      cancel-then-remove sequenced remove dispatch and the plain remove-of-terminal
      case). Update D22 (and D5/D17 cross-refs) so the gate does not suppress the
      record drop.
      → design.md D22.1 rewritten: the terminal-precondition gate is scoped to the
      cancellation/terminal-transition (the `:cancelled` commit + cancellation effect
      set); the `remove-run` record-drop dissoc is an unconditional, status-independent
      dissoc that **still applies** to an already-terminal run (both cancel-then-remove
      dispatch 2 and plain remove-of-terminal). Added a "Cross-reference (D5/D17/D18) —
      scope of the D22.1 gate" paragraph stating the gate never re-orphans a terminal
      run. Conflation explicitly called out as the rejected wording.

## Architecture-fit follow-ups (ψ pass 5, 2026-06-10)

- [ ] Pin the transitive cascade re-dispatch mechanism to the effects-as-data
      dispatch boundary (consistent with D1/D12/D18) and reconcile it with the
      D4/D20 single-run apply-phase atom-CAS atomicity basis. D3 describes the
      cascade as "enumerates in-flight nested sub-runs from canonical run-tree
      state … and **dispatches a cancel for each (recursively)**," and D14 reframes
      each sub-run cancel as a per-sub-run `:cancelled` terminal transition (D2/D4)
      + per-in-flight child-abort effect — but, unlike the cancel-then-remove
      second dispatch (which D18 explicitly pins to a re-entrant
      `:runtime/dispatch-event` follow-on effect precisely because D1 forbids
      command-layer/inline orchestration of a re-dispatch), the cascade's
      per-sub-run terminal transitions have **no stated issue mechanism**. State
      explicitly whether the cascade is (a) a single multi-run apply-phase
      `:root-state-update` over the enumerated descendant set within the one
      parent-cancel dispatch (with each descendant's terminal-status guard inside
      the `swap!` fn, per D20), or (b) N re-entrant `:runtime/dispatch-event`
      cancel dispatches (one per descendant, reusing D18's mechanism). In either
      case keep the recursion out of a command-layer loop / inline cross-handle
      reach-in (D1/D3/D18). Then reconcile with D4/D20: under (a) state the
      multi-run guard/idempotency shape; under (b) state the per-descendant
      CAS/ordering vs the parent's own terminal transition and the D14 single
      top-level `future-cancel`. Update D3/D14 (and D18 cross-ref) so the cascade
      side-effecting re-dispatch is committed to the canonical dispatch `:effects`
      pathway the design otherwise mandates. (AGENTS.md S1 effects / S3 dispatch,
      `λ(state)`, `λ shims_adapters`; doc/architecture.md dispatch sequencing +
      State boundary; design.md D1/D4/D12/D18/D20)
