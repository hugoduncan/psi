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

- [x] Pin the transitive cascade re-dispatch mechanism to the effects-as-data
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
      → design.md D23: option (a) — the cascade is a **single multi-run apply-phase
      `:root-state-update`** over the enumerated descendant set (cancelled run ∪
      non-terminal `:delegating-run-id` descendants, D14) within the **one
      parent-cancel dispatch**; per-run terminal guards live inside the one
      `:root-state-update` fn → the whole subtree terminalization rides **one atom
      CAS** (D20 generalised; strictly stronger than option (b)'s N CASes). Effects
      (single top-level `future-cancel` iff top-level cancel per D14/D19; one
      `:runtime/agent-abort` per in-flight descendant attempt per D15; D13
      terminalize) emitted as the cancel dispatch's effect set through the
      `:effects` interceptor (D1/D12). Option (b) (N re-entrant
      `:runtime/dispatch-event`) rejected: the cascade's per-descendant `:cancelled`
      signals are **pure `:state*` transitions with no after-effect ordering
      constraint** (unlike the D17/D18 record-drop, which must run *after* the
      terminalize effect → forced cross-dispatch), so they compose into one
      apply-phase update fn; re-dispatch buys nothing and only multiplies
      dispatches/CASes. Enumeration-race bound stated (in-fn guard + D22.2 execute-time
      no-op for terminal-by-apply descendants; D6/D2/D10 cooperative checkpoint bounds
      late-spawned descendants). D3/D14 updated to name the single multi-run
      apply-phase transition + cancel-dispatch effect set; D18 cross-ref added
      distinguishing the cascade (intra-dispatch, all pure) from the remove
      re-dispatch (cross-dispatch, ordering-forced). No command-layer loop / no
      cross-handle reach-in. No blocker.

## Ambiguity follow-ups (ψ pass 5, 2026-06-10)

- [x] Reconcile the Acceptance Criteria section with D14/D19/D21/D22 so the
      test / definition-of-done surface is unambiguous and covers the motivating
      Evidence cases. (a) Qualify criterion #2 ("`remove` of a live run … future is
      cancelled") to a **top-level** run, and add (or explicitly note) the nested
      sub-run remove variant whose guarantee is child-turn abort + the parent
      observing run-absence ≡ `:cancelled` and continuing — **no** worker
      `future-cancel` is emitted (D14/D19/D21). (b) Add acceptance criteria for the
      Evidence-step-2 direct cases: direct cancel of a nested sub-run → parent
      observes a failed delegate step and **continues, not halted** (D19); direct
      remove of a live sub-run → run-absence treated identically to `:cancelled`
      (D21); repeated/concurrent terminal-request **idempotency** → a no-op'd
      terminal request emits no cancellation effects (D22.1) with execute-time
      idempotent effects on the concurrent-CAS race (D22.2). State which are
      guaranteed acceptance requirements (vs out-of-test-scope) so an implementer
      deriving tests from the acceptance criteria covers them.
      → design.md Acceptance Criteria rewritten: 10 numbered criteria grouped
      (top-level / transitive / Evidence-step-2 direct / idempotency / build),
      each tagged [guaranteed] or [out-of-test-scope]. Criterion #2 qualified to a
      top-level run (single top-level future, D14); criterion #5 adds the nested
      sub-run remove variant (child abort + parent observes run-absence ≡
      `:cancelled` + continues, no worker future-cancel — D14/D19/D21). #6 direct
      sub-run cancel → parent failed-delegate-step + continues-not-halted (D19);
      #7 direct sub-run remove → run-absence ≡ `:cancelled` race-independent (D21);
      #8 sequential terminal idempotency → no cancellation effects + record-drop
      still applies [guaranteed] (D22.1); #9 concurrent-CAS execute-time idempotency
      [out-of-test-scope] (D22.2).

## Inconsistency follow-ups (ψ pass 6, 2026-06-10)

- [x] Reconcile the `inflight-runs` runtime-handle entry-drop with D1/D2 and the
      code. D17 step 2 says the remove dispatch "applies the pure `remove-run`
      dissoc, dropping the canonical run record **and its `inflight-runs` entry**,"
      and Acceptance #2 requires "its `inflight-runs` entry is cleared … (D5/D17)."
      But code-confirmed: pure `remove-run` (`workflow-runtime/core.clj:217`)
      dissocs only canonical `:state*` (`runs-path`/`run-order-path`) and never
      touches `inflight-runs`; the `inflight-runs` entry is a separate `defonce`
      runtime-handle atom (`runtime_state.clj:11`) dropped by a distinct
      command-layer side effect `(swap! inflight-runs dissoc run-id)`
      (`workflow/core.clj:493`). This contradicts D2 (`inflight-runs` ∈ pure
      runtime handle, not the canonical `:state*` a pure transition mutates) and D1
      (pure transitions perform no side effects; handle mutations flow as
      effects-as-data), and the D12/D23 cancellation effect set defines **no**
      effect to clear the `inflight-runs` entry. State explicitly either (a) the
      `inflight-runs` entry-drop is its own canonical `:runtime/*` cleanup
      effect emitted in the remove dispatch's effect set (parity with the
      `future-cancel` effect that already reaches `inflight-runs` via `ctx`,
      executed by the `:effects` interceptor — D1/D12), or (b) correct D17 step 2 /
      Acceptance #2 to stop attributing the `inflight-runs` drop to the pure
      `remove-run` `:state*` dissoc and name the actual handle-mutation mechanism.
      Update D17, D5 step 3, and Acceptance #2 so the remove flow's
      `inflight-runs` entry-drop has a defined effects-as-data mechanism consistent
      with D1/D2. (AGENTS.md S1 effects / S3 dispatch, `λ(state)`, `λ parity`;
      design.md D1/D2/D12/D17/D23)
      → design.md D24: option (a). The `inflight-runs` entry-drop is its own
      canonical `:runtime/drop-inflight-run` cleanup effect (parity: `effect-schema`
      + `execute-effect!`, dissoc via `ctx` handle) emitted in the remove dispatch's
      (D17 dispatch 2) effect set and run by the `:effects` interceptor — parity with
      the D12 worker `future-cancel` effect that already reaches `inflight-runs` via
      `ctx`. Ordering: handle-drop (dispatch 2) runs after the cancel dispatch's
      future-cancel (dispatch 1) → drop-after-cancel, never drop-then-orphan
      (Evidence-step-3). Idempotent dissoc (D22.2) tolerates the worker's own
      natural-completion cleanup. Option (b) (re-label the command-layer `swap!`)
      rejected: perpetuates an off-dispatch handle side effect (un-trimmed on replay,
      trace-invisible) — the exact boundary the task moves away from. Code-confirmed:
      pure `remove-run` (`workflow-runtime/core.clj:217`) dissocs only canonical
      `:state*`; `inflight-runs` is a `defonce` atom (`runtime_state.clj:11`) dropped
      by `(swap! inflight-runs dissoc run-id)` (`workflow/core.clj:493`). Scope's
      natural-completion `orchestration.clj` cleanups stay out of scope. D17 step 2,
      D5 step 3, Acceptance #2, and the Scope effects bullet updated.

## Architecture-fit follow-ups (ψ pass 7, 2026-06-10)

- [x] Commit a handle-reachability mechanism for the D12 worker `future-cancel` and
      D24 `:runtime/drop-inflight-run` cancellation/cleanup effects, reconciling the
      design's repeated "the `inflight-runs` handle reached **via `ctx`**"
      (D12/D24) with the code-confirmed fact that `inflight-runs` is a process-global
      `(defonce inflight-runs (atom {}))` (`runtime_state.clj:11`,
      aliased `workflow/core.clj:31`) **not on the dispatch `ctx`** (absent from
      `context.clj`). Every existing `:runtime/*` handler reaches workflow runtime
      state through a **ctx-injected fn/handle** wired in `context.clj`
      (`:runtime/mark-workflow-jobs-terminal` → `((:mark-workflow-jobs-terminal-fn
      ctx) ctx)`; `:runtime/agent-abort` via `(effect-session-id ctx …)`). State
      either (a) thread `inflight-runs` onto the dispatch `ctx` (a `context.clj`
      injection, in scope) so the new `execute-effect!` methods reach it via `ctx`
      with parity to the existing `:runtime/*` handlers (honoring the asserted "via
      ctx" wording), or (b) explicitly justify the new handlers reaching the
      `defonce` global directly as a documented exception — noting that (b) diverges
      from the ctx-injection parity of every other `:runtime/*` effect and is the
      extension-local-hidden-state pattern META.md cautions against (managed services
      keyed on ctx, ¬extension-local hidden state), coupling the new effects to a
      process-global atom (replay/test-isolation hazard). Update D12/D24 so the
      handle-reachability mechanism is committed and the "via ctx" premise is either
      made true (a) or replaced (b). (AGENTS.md S1 effects / `λ parity` / `λ(state)`;
      META.md managed-services-on-ctx; design.md D1/D2/D12/D24)
      → design.md D25: option (a) — thread `inflight-runs` onto the dispatch `ctx`
      via a `context.clj` injection (e.g. `:workflow-inflight-runs-handle
      runtime-state/inflight-runs`, alongside the existing `:mark-workflow-jobs-terminal-fn`
      / workflow-runtime injections); the D12 worker `future-cancel` and D24
      `:runtime/drop-inflight-run` `execute-effect!` methods read
      `(:workflow-inflight-runs-handle ctx)` with parity to every other `:runtime/*`
      handler — making the asserted "via ctx" premise true. The `context.clj`
      injection is in scope. Option (b) (direct `defonce` global reach-in,
      documented exception) rejected: diverges from ctx-injection parity, is the
      extension-local-hidden-state pattern META.md cautions against, and couples the
      effects to a process-global atom (replay/test-isolation hazard) — undercutting
      the D12/D24 parity + replay-closure rationale. Code-confirmed: `inflight-runs`
      = `defonce` atom (`runtime_state.clj:11`, aliased `workflow/core.clj:31`),
      absent from `context.clj`; existing `:runtime/*` handlers reach workflow runtime
      state via ctx-injected fns (`:mark-workflow-jobs-terminal-fn` at
      `context.clj:248` → `((:mark-workflow-jobs-terminal-fn ctx) ctx)`). D12/D24
      "via ctx" wording annotated with the D25 pointer. No blocker.

## Ambiguity follow-ups (ψ pass 8, 2026-06-10)

- [x] Correct D17's "the same **worker thread**" claim: the cancel/remove
      dispatches and the re-entrant `:runtime/dispatch-event` remove run on the
      **dispatch-invoking (command/operator) thread**, not the workflow worker
      thread. Code-confirmed the operator-initiated cancel/remove path runs on the
      agent tool-dispatch thread (`delegate-remove` `workflow/core.clj:474`;
      `cancel-run` `psi_tool_workflow.clj:227` / `canonical_workflows.clj:220`),
      while the workflow worker is a separate `clojure-agent-send-off-pool` thread
      parked on `send-and-drain`. D20 already says "the same thread" and D21 says
      "the operator/command thread"; align D17 to remove the contradictory "worker
      thread" qualifier so the in-thread sequencing claim names the correct thread
      and an implementer does not try to run the remove dispatch on the
      parked/interrupted worker.
      → design.md D17 "in-thread sequencing" paragraph corrected: re-entrant remove
      dispatch runs on the **dispatch-invoking (operator/command) thread** (the agent
      tool-dispatch thread running `cancel-run`/`remove-run`/`delegate-remove`), not
      the workflow worker (`clojure-agent-send-off-pool`) thread — which is the
      *target* of the `future-cancel(true)` interrupt, never the *runner* of the
      cancel/remove dispatches; aligned with D20 "the same thread" / D21
      "operator/command thread". Code citations verified
      (`canonical_workflows.clj:217/244`, `workflow/core.clj:474/493`).

- [x] Pin the **entry-event taxonomy** for cancel vs cancel-then-remove vs
      plain-remove-of-terminal and the owner of the shared cancel-transition logic.
      The design pins the effect set (D18: cancel dispatch emits the re-entrant
      remove effect only for a remove) but not the event/handler structure, given
      two distinct existing mutations `psi.workflow/cancel-run`
      (`canonical_workflows.clj:220`) and `psi.workflow/remove-run`
      (`delegate-remove`). State, for a `remove` of a **live** run (D5): (a) whether
      the `remove-run` handler itself produces the `:cancelled` transition +
      cancellation effects (D12/D23) + terminalize (D13) + the chained re-entrant
      `remove-run` dispatch (with the bare dissoc on the terminal/re-entrant pass),
      or (b) whether the remove command dispatches the existing `cancel-run` event
      first and chains a `remove-run` dispatch; (c) where the live-vs-terminal
      branch lives (handler-before vs command layer — the latter in tension with
      D18's rejection of command-layer orchestration); and (d) whether the
      cancel-transition+cancellation-effect logic is shared (one helper across the
      `cancel-run` and `remove-run` handlers) or duplicated. Update D5/D17/D18 so
      the entry-event structure is expressible.
      → design.md D26 (new "Entry-Event Taxonomy Reconciliation"): option (a) — the
      **`remove-run` handler itself** owns cancel-then-remove (option (b) rejected:
      command-layer orchestration / a cancel-run "then-remove" flag both contradict
      D18). Two entry events: `cancel-run` = shared cancel-transition helper (no
      re-entrant remove); `remove-run` live first-pass = same shared helper +
      re-entrant `:runtime/dispatch-event` (no dissoc), re-entrant/terminal
      second-pass = bare unconditional dissoc + `:runtime/drop-inflight-run` (no
      cancellation effects). (c) live-vs-terminal branch lives in the `remove-run`
      handler-`:before` = the reused D22.1 terminal-precondition gate (not command
      layer). (d) one **shared** cancel-transition+effect helper across both handlers
      (not duplicated). D5/D17/D18 annotated with D26 pointers; code-confirmed both
      mutations (`canonical_workflows.clj:217/244`) + `delegate-remove`
      (`workflow/core.clj:474/493`).

- [x] State whether the D23 enumeration-race bound holds for a **direct sub-run
      cancel** (no worker `future-cancel`, D19), or classify the residual spawn race
      as an accepted true-concurrency exception. D23 argues the enumeration-race
      bound from the cancelled run's own cooperative checkpoint refusing further
      spawns; for a top-level cancel the single `future-cancel(true)` (D14) also
      interrupts the whole synchronous stack. A **direct sub-run cancel** emits no
      worker interrupt (D19), so a deeper descendant child turn/session spawned in
      the window between the D23 handler-before enumeration and the worker reaching
      its next checkpoint is neither in the cascade set (not D15-aborted) nor
      interrupted — it runs to natural completion, potentially violating D6's stated
      **guarantee** "no further child session spawns after the cancel checkpoint"
      (D6's physics exception is scoped to a single in-syscall-flight tool call, not
      a spawned child session). State whether this direct-sub-run-cancel spawn race
      upholds the D6 no-new-child-session guarantee (and how, absent a worker
      interrupt) or is an accepted true-concurrency exception analogous to
      D22.2/criterion #9 — and reconcile D6/D14/D19/D23 accordingly.
      → design.md D27 (new "Direct-Sub-Run-Cancel Spawn-Race Reconciliation"): the
      D6 guarantee **holds** for the cascade set via per-run cooperative checkpoints
      (D2/D10) + per-attempt aborts (D15) — child-abort, not a worker interrupt, is
      the sub-run wake mechanism (D19), so no `future-cancel` is required for subtree
      correctness (and emitting it would violate D14/D19 parent-survival). The
      residual post-enumeration spawn (a child spawned in the window between the D23
      handler-`:before` enumeration and the abort-driven checkpoint) is **one bounded,
      accepted true-concurrency exception** of the same class as D22.2 / criterion #9:
      bounded because the spawn's own cooperative checkpoint already reads the
      pre-effect-committed `:cancelled` signal (apply-before-effects, D20/D23) and
      refuses, the window closes when the abort returns control, and any momentary
      turn is itself self-terminating — never an unbounded runaway. D6 restated for
      the sub-run case; D14's `future-cancel` reframed as the top-level promptness
      mechanism (not a subtree-bound prerequisite); added Acceptance #9a
      [out-of-test-scope].

## Inconsistency follow-ups (ψ pass 9, 2026-06-10)

- [x] Strip/correct the residual "serialized" qualifier in the three run-`:status`
      writer-identity sentences that survived D20's "dispatch is not serialized
      (no global lock)" reconciliation, so they no longer describe the D4 terminal
      transition as *serialized*: Desired Behaviour (line ~82, "the run's own
      `:status` is written by the **D4 serialized dispatch transition**"); D13 "Two
      distinct writers" (line ~522, "written by the **D4 serialized dispatch
      terminal transition**"); D13 "Concretely" (line ~529, "the cancel dispatch
      terminal transition (**D4, serialized single-writer**)"). Replace with D20's
      atom-CAS basis (the transition routes through dispatch and is the single
      *logical* writer of run `:status`, atomicity from the apply-phase atom CAS
      with the guard inside the `:root-state-update` fn — **not** dispatch
      serialization). D20's pass-3 resolution claimed to align D13 but the literal
      qualifiers remain, and Desired Behaviour was outside that scope. (design.md
      Desired Behaviour, D13, D20)
      → design.md all three writer-identity sentences corrected to D20's atom-CAS
      basis. Desired Behaviour: "D4 serialized dispatch transition" → "D4 dispatch
      terminal transition, the single *logical* writer of run `:status` (atomicity
      from the apply-phase atom CAS with the guard inside the `:root-state-update`
      fn, D20 — not dispatch serialization)". D13 "Two distinct writers": "D4
      serialized dispatch terminal transition (the single writer of *run* status)" →
      "D4 dispatch terminal transition (the single *logical* writer of *run* status —
      atomicity from the apply-phase atom CAS with the guard inside the
      `:root-state-update` fn, D20, not dispatch serialization)". D13 "Concretely":
      "(D4, serialized single-writer)" → "(D4 — single *logical* writer, atomicity
      from the apply-phase atom CAS with the guard inside the `:root-state-update`
      fn per D20, not dispatch serialization)". Remaining "serialized" occurrences
      are out of scope: line 269 already quotes the phrase as superseded-by-D20;
      D17's "two serialized dispatches" (686/711/727/809) describes in-thread
      *sequencing/ordering* (reconciled by D20, not the race-safety single-writer
      claim); 917/928/960/985 are D20's own reconciliation text. No blocker.

## Ambiguity follow-ups (ψ pass 11, 2026-06-11)

- [x] Pin the execution-time idempotency payload/read rule for workflow-cancellation
      `:runtime/agent-abort`. D15 emits the existing effect with `:session-id` = the
      in-flight attempt's `:execution-session-id`, while D22.2 requires the executor
      to re-read the D15 live-attempt predicate from canonical run state at execute
      time. State whether workflow-cancel abort effects carry guard metadata
      (`run-id`, `step-id`, attempt identity, expected `execution-session-id`) or the
      executor locates the attempt by `:execution-session-id`; also state how existing
      non-workflow `:runtime/agent-abort` emissions remain unguarded (or are otherwise
      handled). Update D12/D15/D22.2 and any effect-schema/executor implications.
      → design.md D28: workflow-cancel aborts carry guard metadata (`run-id`,
      `step-id`, `attempt-id`, expected `execution-session-id`) plus `:session-id`;
      executor re-reads canonical run state and aborts only when the guarded latest
      attempt remains live with matching session. Existing non-workflow
      `:runtime/agent-abort` emissions omit the guard and keep session-id-only
      behaviour; effect schema gains optional guard keys/nested guard map.

- [x] Define public result/error semantics for idempotent terminal/absent
      `cancel-run` and `remove-run`. When the target run is already terminal,
      absent, or naturally completes before cancel applies, state whether the API
      returns success with the current/removed status, `:removed? true`, or an error,
      while still emitting no cancellation effects. Align D4/D20/D22/D26, the
      mutation output fields, and the acceptance tests.
      → design.md D29: terminal/absent cancel/remove are success/no-op public
      results, not `already terminal` / `not found` errors; live cancel returns
      `:status :cancelled`; live remove reports successful cancel-then-remove;
      terminal remove performs bare record drop; absent remove returns
      `:removed? false`, `:found? false`, `:noop? true`. Acceptance criteria updated.

- [x] Scope Acceptance #3's "no new side effects (commits, journal writes, new child
      sessions)" against required cancellation bookkeeping. Explicitly distinguish
      forbidden child workflow/turn side effects after cancellation (new tool calls,
      commits, ordinary child-turn journal writes, new child sessions) from
      allowed/required cancellation-control writes/effects (`:cancelled` state,
      background-job terminalization, abort/interruption records if any, and
      `inflight-runs` cleanup). Update D6 and Acceptance #3 so tests assert the
      intended boundary.
      → design.md D30: forbidden effects = ordinary workflow/child-turn advancement
      after the checkpoint (new steps, sub-runs, ordinary child sessions, tool calls,
      commits, ordinary child-turn journal writes); allowed/required cancellation
      control = `:cancelled`, terminalization, guarded aborts, future-cancel,
      abort/interruption records, dispatch trace, re-entrant remove, record drop, and
      `inflight-runs` cleanup. D6 and Acceptance #3 updated.

- [x] Define the testable meaning of "cancel checkpoint" used by D6/D7/D27 and
      Acceptance #1/#3. Choose whether it denotes the cancel request, the apply-phase
      CAS that writes `:cancelled`, interrupt delivery, the worker's cooperative read
      observing `:cancelled`/run-absence, or another event; state what work may
      legally start in the request→CAS→interrupt→read window. Apply the same term
      consistently to top-level and nested-run acceptance criteria.
      → design.md D31: cancel checkpoint = the apply-phase CAS that commits
      `:status :cancelled` (the D23 multi-run CAS for cascades), not request arrival,
      interrupt delivery, or worker read. Work started before the CAS is not a
      post-checkpoint violation; ordinary advancement after the CAS is forbidden;
      interrupt/abort only wake blocked work to observe the signal. Acceptance
      criteria updated; D27's bounded spawn race remains out-of-test-scope.

## Inconsistency follow-ups (ψ pass 12, 2026-06-11)

- [x] Reconcile D28's `:runtime/agent-abort` `:session-id` schema requirement with
      existing unguarded abort effects and the dispatch validation/effect order.
      D28 says the effect schema keeps `:session-id` required, but existing
      non-workflow aborts such as `:on-abort` emit only
      `{:effect/type :runtime/agent-abort}` and rely on the effect interceptor to
      inject the dispatching `:session-id`; validation runs before that injection
      (`:apply → :validate → :trim-effects-on-replay → :effects`). State the chosen
      contract: either keep `:session-id` optional in the schema for unguarded
      aborts while requiring/validating it for guarded workflow-cancel abort
      payloads (or documenting the injected-session path), or require every abort
      emitter to include `:session-id` before validation. Align D28, D12, the
      effect-schema implication, and existing non-workflow abort behaviour so
      validation parity does not reject current `:runtime/agent-abort` effects.
      → design.md D32: option (a). `:runtime/agent-abort` keeps `:session-id`
      optional for unguarded/non-workflow aborts so current `:on-abort` effects can
      validate before the `:effects` interceptor injects the dispatching session id;
      guarded workflow-cancel aborts require explicit `:session-id`,
      `:expected-session-id`, and complete workflow guard metadata before validation.
      D28/D15 schema implication updated; executor keeps existing session-id-only
      behaviour when no guard is present and applies the D28 liveness re-check only
      for guarded payloads.

## Ambiguity follow-ups (ψ pass 14, 2026-06-11)

- [x] Pin one canonical workflow-cancellation `:runtime/agent-abort` guard payload shape. D28/D15 show flat top-level keys (`:workflow-run-id`, `:workflow-step-id`, `:workflow-attempt-id`, `:expected-session-id`) while D32 says the schema may instead use a required nested `:workflow-abort-guard` map. Choose flat or nested, update D28/D32/effect-schema implications, and make emitters/executor/tests target that single shape.
      → design.md D33: chose the flat top-level key shape (`:session-id`, `:workflow-run-id`, `:workflow-step-id`, `:workflow-attempt-id`, `:expected-session-id`) as canonical; nested `:workflow-abort-guard` rejected. D28/D32/effect-schema implications updated: guarded workflow-cancel aborts require all-or-none flat guard keys; unguarded aborts retain optional `:session-id`; emitters/executor/tests target the flat shape only.
- [x] Clarify absent `remove-run` side effects and `:noop?` meaning. D26's terminal/absent branch emits the bare record-drop plus `:runtime/drop-inflight-run`, but D29/Acceptance #10 call absent remove a success/idempotent no-op (`:removed? false`, `:found? false`, `:noop? true`). State whether an absent remove still emits `:runtime/drop-inflight-run` to clear any orphaned handle, and whether `:noop?` means "no canonical record removed" rather than "no effects emitted".
      → design.md D34: absent remove still emits only the idempotent D24 `:runtime/drop-inflight-run` cleanup to clear possible orphaned handles; it emits no cancellation effects and removes no canonical record. `:noop? true` means no canonical record was found/removed and no cancel transition applied, not "no effects emitted". Acceptance #10 updated.

## Inconsistency follow-ups (ψ pass 15, 2026-06-11)

- [x] Qualify the generic live-`remove` worker/future-stop wording so it does not
      contradict the direct nested-sub-run remove contract. D5 currently states
      "remove of a live run" and then says the `future-cancel` interrupt guarantees
      the worker stops, and Scope's test bullet says "`remove` of a live run does
      not leave a running future." But D19/D21 and Acceptance #5/#7 put direct live
      nested-sub-run remove in scope, require **no** worker `future-cancel`, and
      require the shared parent worker to continue. Update D5 and the Scope tests
      bullet to either qualify the future/worker-stop guarantee to **top-level**
      runs or split top-level vs nested-sub-run remove behaviour explicitly.
      → design.md: Desired Behaviour, Scope, test-scope bullet, and D5 now split
      top-level live remove (future-cancel + no orphaned worker) from direct live
      nested-sub-run remove (no worker `future-cancel`; child abort wakes the shared
      parent worker; parent observes run-absence ≡ `:cancelled` and continues per
      D19/D21). The future/worker-stop guarantee is qualified to top-level runs.
- [x] Align D15's workflow-cancellation abort emit rule with D28/D33. D15 still
      says to emit a bare `{:effect/type :runtime/agent-abort :session-id sid}`
      for workflow-cancellation aborts, while D28/D33 require the complete flat
      guarded payload (`:session-id`, `:workflow-run-id`, `:workflow-step-id`,
      `:workflow-attempt-id`, `:expected-session-id`) and reserve unguarded
      session-id-only aborts for non-workflow effects. Update the D15 emission
      example/read rule to show the guarded shape or explicitly point to D28/D33,
      so emitters/tests do not implement the stale bare workflow abort.
      → design.md D15 now reads `attempt-id` and shows the canonical D28/D33 flat
      guarded `:runtime/agent-abort` payload (`:session-id`, `:workflow-run-id`,
      `:workflow-step-id`, `:workflow-attempt-id`, `:expected-session-id`) as the
      workflow-cancellation emit rule. It explicitly reserves bare session-id-only
      aborts for unguarded non-workflow emissions.

## Ambiguity follow-ups (ψ pass 17, 2026-06-11)

- [x] Pin the canonical worker-future-cancel effect representation: exact
      `:effect/type` keyword, required payload keys, and target semantics for the
      top-level workflow run's `inflight-runs` future. Update D12/D14/D18/D23,
      effect-schema/executor implications, and tests to use that single shape.
      → design.md D35: canonical effect is
      `{:effect/type :runtime/cancel-inflight-run :run-id top-level-run-id}`.
      `:run-id` is the top-level run that owns the `inflight-runs` entry; emitters
      emit it only for top-level cancel/live top-level remove, never for direct
      nested sub-run cancel/remove. Executor reads the D25 ctx-injected
      `:workflow-inflight-runs-handle`, looks up exactly `:run-id`, calls
      `future-cancel`, and treats missing handle/future as idempotent no-op.
      Schema/executor/test implications pinned; D12/D14/D18/D23/Acceptance updated.

## Inconsistency follow-ups (ψ pass 18, 2026-06-11)

- [x] Reconcile the child-session abort path with the existing `:runtime/agent-abort`
      executor. D3/D9 say the workflow-cancellation abort effect handler invokes the
      agent-session `:session/abort` dispatch authority, and D9 claims the existing
      `:runtime/agent-abort` effect already drives that path. Code shows the inverse:
      `:session/abort` statechart handling emits `{:effect/type :runtime/agent-abort}`
      and `execute-effect! :runtime/agent-abort` directly performs abort side effects
      without dispatching `:session/abort`. Decide whether workflow cancellation uses
      (a) guarded `:runtime/agent-abort` as the direct abort-side-effect executor
      (updating D3/D9 to stop claiming a `:session/abort` dispatch), or (b) a guarded
      follow-on `:session/abort` dispatch path with explicit recursion/guard handling.
      Align D3/D9/D12/D15/D28/D33 and the effect-schema/executor/test implications.
      → design.md D36: option (a). Workflow cancellation emits the D28/D33 guarded
      `:runtime/agent-abort` effect and its `execute-effect!` directly performs the
      existing abort side effects after the guarded live-attempt re-read. It does not
      dispatch a follow-on `:session/abort`; `:session/abort` remains the public /
      statechart abort entry event that emits the same effect. D3/D9/D12/D15/D28/D33
      aligned; "reuse `:session/abort`" now means reuse the abort side-effect
      mechanism that event emits, not recursively dispatch it.
- [x] Reconcile absent `remove-run` cleanup with the drop-after-cancel / no-orphan
      guarantee. D34 says absent `remove-run` emits `:runtime/drop-inflight-run` to
      clear a possible orphaned handle, while D29/D34 say absent remove emits no
      cancellation effects and D24 says handle drop happens after future cancel.
      For an absent canonical run with a live `inflight-runs` future, dropping the
      handle without first cancelling it recreates the original orphaned-worker
      failure. Decide whether absent-remove cleanup must emit D35
      `:runtime/cancel-inflight-run` before D24 drop, make the drop effect cancel+drop
      atomically when a live future is present, or only drop when the handle is known
      absent/done/cancelled. Align D24/D26/D29/D34/D35 and Acceptance #10 so absent
      cleanup cannot orphan a live worker.
      → design.md D36b: absent remove emits an ordered handle-cleanup pair —
      `{:effect/type :runtime/cancel-inflight-run :run-id requested-run-id}` before
      `{:effect/type :runtime/drop-inflight-run :run-id requested-run-id}`. This is
      stale top-level-handle cleanup, not a canonical cancel/cascade; no guarded
      `:runtime/agent-abort`, job terminalization, or re-entrant remove emits. The
      public D29 no-op result is unchanged because no canonical record was removed
      and no cancel transition applied. D24/D26/D29/D34/D35 and Acceptance #10 aligned
      so a stale live handle is interrupted before being dropped.

## Ambiguity follow-ups (ψ pass 20, 2026-06-11)

- [x] Pin the canonical state-kernel dispatch event types for workflow
      `cancel-run` and `remove-run`. D18's re-entrant `:runtime/dispatch-event`
      requires a keyword `:event-type`, but the current public operations are
      Pathom mutation symbols (`'psi.workflow/cancel-run`,
      `'psi.workflow/remove-run`) and D18 still uses a placeholder
      (`<remove-run dispatch>`). Choose exact event keywords, state the event-data
      shape, and state how the Pathom mutations / delegate tool route into those
      events so emitters, schema/tests, and the re-entrant remove dispatch have one
      representation.
      → design.md D37: canonical state-kernel events are
      `:psi.workflow/cancel-run` and `:psi.workflow/remove-run` (keywords, distinct
      from Pathom symbols and workflow-runtime `:workflow/cancel`). Event data =
      required `:run-id` plus optional `:reason` and optional dispatch-context
      `:session-id`; no `:then-remove?`/`:reentrant?` flag. D18's follow-on effect
      is now concrete: `{:effect/type :runtime/dispatch-event :event-type
      :psi.workflow/remove-run :event-data {:run-id … :reason … :session-id …}
      :origin :core}`. Pathom mutations, psi-tool cancel, and delegate remove route
      into these keyword events and do not call workflow-runtime pure functions or
      mutate `inflight-runs` directly. D18 placeholder replaced.

## Inconsistency follow-ups (ψ pass 21, 2026-06-11)

- [x] Align D1's owner wording with the D26/D37 state-kernel entry-event taxonomy.
      D1 still says the "agent-session cancel/remove mutation" commits the
      canonical `:cancelled` transition and emits cancellation effects, and that the
      mutation's only canonical-state write is the pure status transition. D26/D37
      instead make the Pathom mutation symbols thin adapters: the registered
      state-kernel keyword handlers `:psi.workflow/cancel-run` and
      `:psi.workflow/remove-run` own the shared cancel-transition helper, effect
      set, and remove branch; Pathom/psi-tool/delegate surfaces route into those
      events and must not call workflow-runtime pure functions or mutate handles
      directly. Reword D1 so the **state-kernel handlers/events** commit the pure
      transition and return the canonical effects-as-data, while Pathom mutations
      are public adapters only. This avoids two owners for the same transition/effect
      boundary and prevents reintroducing direct Pathom mutation writes.
      → design.md D1 now assigns the transition/effect boundary to the registered
      state-kernel keyword handlers `:psi.workflow/cancel-run` and
      `:psi.workflow/remove-run` (D37). Pathom mutations, psi-tool cancel, and
      `delegate remove` are explicitly adapters only: they route to those events and
      do not call workflow-runtime pure functions, `reset!` canonical state, or
      mutate `inflight-runs` / background-job handles inline. D1's concrete flow now
      names the shared cancel-transition helper (D26), the live-remove first pass,
      the terminal/absent record-drop branch, and the dispatch `:effects` interceptor
      as executor. No blocker.

## Inconsistency follow-ups (ψ pass 24, 2026-06-11)

- [x] Reconcile the residual D6 / Acceptance #3 absolute no-new-child-session
      guarantee with D27 / Acceptance #9a's accepted direct-sub-run-cancel
      post-enumeration spawn exception. D6 says that after the D31 cancel
      checkpoint no further delegate sub-run is created by the cancelled subtree and
      no further ordinary child agent session spawns; Acceptance #3 marks the same
      no-new-child-session claim as [guaranteed]. But D27 and Acceptance #9a accept
      a descendant child session spawned after the D23 handler-before enumeration /
      D31 CAS checkpoint as a bounded, out-of-test-scope exception. Qualify D6 and
      Acceptance #3 (or restate the guarantee as applying only to the enumerated
      cascade set) so the guaranteed contract and accepted exception have one
      consistent scope.
      → design.md D6 + Acceptance #3 now scope the guaranteed no-new ordinary
      workflow/child-turn side effects to runs in the D23 enumerated cascade set
      after their D31 cancel checkpoint. D27 / Acceptance #9a remains the explicit
      bounded, out-of-test-scope exception for a descendant spawned after enumeration
      but before the abort-driven checkpoint refuses it. No blocker.

## Inconsistency follow-ups (ψ pass 27, 2026-06-11)

- [x] Qualify the Intent section's absolute cancellation guarantee so it matches the
      later scoped contract. Intent currently says that after cancel no further child
      sessions spawn and no new side effects are initiated (commits, journal writes)
      transitively across nested sub-runs. D6/Acceptance #3 scope the deterministic
      guarantee to **runs in the D23 enumerated cascade set** after the D31 cancel
      checkpoint; D27/Acceptance #9a accept the bounded direct-sub-run
      post-enumeration spawn exception; and D30 allows required cancellation-control
      writes/effects while forbidding ordinary workflow/child-turn advancement.
      Reword Intent to state the D23 cascade-set + D31 checkpoint + D30 forbidden-vs-
      allowed boundary, and explicitly point to the D27 exception, so the top-level
      intent no longer contradicts the refined contract.
      → design.md Intent now states the D23 cascade-set + D31 checkpoint + D30
      forbidden-vs-allowed boundary, names the D27 bounded direct-sub-run
      post-enumeration spawn exception, and no longer presents the refined contract
      as an absolute no-new-child-session/no-new-side-effect guarantee.

## Inconsistency follow-ups (ψ pass 30, 2026-06-11)

- [x] Reconcile D23's child-abort effect-set wording with D6/D15/D3. D6 guarantees
      the directly-cancelled run's in-flight child turn is interrupted; D15 states
      the aborted-session set is the directly-cancelled run plus each in-flight
      descendant with a live attempt; D3 defines the cascade set as cancelled run ∪
      descendants. But D23's effect list emits one guarded `:runtime/agent-abort`
      only per "in-flight descendant attempt", omitting the directly-cancelled run
      itself. Update D23 (and any acceptance/test wording if needed) so the abort
      effect is emitted for each **cascade-set** run with a live attempt — including
      the directly-cancelled run — while preserving D15's guarded payload/read rule.
      → design.md D23 effect-set wording now emits one guarded `:runtime/agent-abort`
      per **cascade-set run** whose current attempt satisfies the D15 live-attempt
      predicate, explicitly including the directly-cancelled run plus descendants;
      Acceptance #4 updated to require abort coverage for each cascade-set live
      attempt while preserving the D28/D33 guarded payload/read rule.

- [x] Reconcile already-terminal `remove-run` cleanup with the no-orphan
      runtime-handle invariant. D26/D29 currently make terminal remove a bare
      record drop plus D24 `:runtime/drop-inflight-run` only, while D36b's
      cancel-before-drop ordered cleanup pair applies only to absent remove. A
      top-level run can be terminal in canonical state (for example after a prior
      cancel request) while its `inflight-runs` future is still unwinding or stale;
      dropping that handle without first emitting D35 `:runtime/cancel-inflight-run`
      can recreate the Evidence-step-3 orphaned-worker failure. Decide and state
      either (a) terminal canonical records cannot have live/stale handles by
      construction, or (b) terminal-remove cleanup also emits the ordered
      `:runtime/cancel-inflight-run` → `:runtime/drop-inflight-run` pair for the
      requested run id (with clear top-level/stale-handle semantics so nested
      sub-run removes still do not infer or interrupt a parent worker). Align
      D24/D26/D29/D34/D36b and Acceptance #10.
      → design.md D38 chooses option (b) for terminal **top-level** remove: emit the
      ordered runtime-handle cleanup pair (`:runtime/cancel-inflight-run` before
      `:runtime/drop-inflight-run`) without any canonical cancellation/cascade
      effects. Terminal **nested** remove emits no worker cancel effect and does not
      infer/interrupt the parent/top-level worker; it may only exact-key drop. D24,
      D26, D29, D34, D36b, Acceptance #8/#10 aligned.

## Inconsistency follow-ups (ψ pass 33, 2026-06-11)

- [ ] Reconcile D35's worker-future-cancel emitter/test wording with D38 terminal
      top-level remove cleanup. D38 requires already-terminal **top-level**
      `remove-run` cleanup to emit the ordered runtime-handle pair
      `:runtime/cancel-inflight-run` before `:runtime/drop-inflight-run`, but D35
      still says `:runtime/cancel-inflight-run` is emitted only for top-level cancel
      / **live** top-level remove and that the only exception is D36b absent-remove
      stale-handle cleanup; its test implication likewise omits terminal top-level
      remove. Update D35 to distinguish canonical cancellation emissions from
      runtime-handle cleanup emissions, include D38 terminal top-level remove as an
      allowed cleanup emitter, and keep the direct/terminal nested-sub-run rule that
      no parent/top-level worker is inferred or interrupted.
