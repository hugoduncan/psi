# Implementation notes

## Architecture-fit review (ψ, 2026-06-10)

Reviewed design.md for fit with project architecture/principles (AGENTS.md VSM,
META.md, doc/architecture.md). Not a correctness/ambiguity/consistency review.

Overall the intent fits: cancellation status is already canonical `:state*`
(`workflow-runtime/workflow-run-in`), `inflight-runs`/the future is a runtime
handle, and reusing the existing session interrupt/abort path
(`turn/abort-in!` / `agent-core/abort-in!`, context.clj thread interrupt) aligns
with `λ extend` (compose > new mechanism). Four architectural-fit misfits found
where the design does not yet commit to the project's boundaries:

1. **Side effects not placed at the effects-as-data boundary.** `future-cancel`,
   thread interrupt, and child-session abort are new side effects. AGENTS.md S1/S3
   + `λ(state)` require side effects to flow through mutations_via(dispatch_pipeline)
   as effects-as-data executed at the runtime boundary. Design performs them inline
   in the `cancel-run`/`remove-run` Pathom mutations (which already `reset!`
   `:state*` directly). Design must decide: model cancellation as dispatch effects,
   or explicitly justify the legacy-mutation exception (¬silent).

2. **Cancellation signal store unassigned vs State boundary.** doc/architecture.md
   "canonical root vs runtime handles" + one-way (reads through resolvers): the
   cooperative step-loop check should read the cancellation signal from canonical
   `:state*` via the read path, keeping `inflight-runs`/future a pure thread handle.
   Design says "keyed on run status" without committing the signal to canonical
   state; make the split explicit (signal ∈ `:state*`, handle ∈ runtime).

3. **Transitive-propagation ownership / authority.** Cascade crosses the workflow
   registry handle (run tree) and child agent sessions (turn abort). AGENTS.md:
   agent-session is authoritative owner of session-dispatch invocation; lower
   components expose pure domain APIs; ¬silently introduce reach-in shims. Design
   leaves the cascade owner unassigned — assign it to a coordinated dispatch path
   through the agent-session authority, not ad-hoc cross-handle reach-in.

4. **Race-safe terminal transitions via dispatch serialization.** The idempotent /
   no-double-terminal / cancel-during-wait guarantees are the same shape solved in
   task 224 by atomicity-from-dispatch-serialization. But `cancel-run`/`remove-run`
   `reset!` `:state*` outside the serialized dispatch pipeline. Design should decide
   whether terminal transitions must route through serialized dispatch (single
   writer) to earn its race-safety, vs ad-hoc guards on a directly-`reset!`'d atom.

These are fit/boundary decisions the design should state, not redesigns of the
step machine (which is correctly out of scope).

## Architecture-fit follow-up resolution (ψ, 2026-06-10)

Executed all four architecture-fit follow-up design-steps. Each was a
design-decision step (state a boundary commitment in design.md), all completable
now — no blockers. Resolutions written to design.md as "Architecture & Boundary
Decisions" D1–D4:

- D1 — side effects as effects-as-data executed at the orchestration runtime
  boundary; pure `cancel-run`/`remove-run` stay side-effect-free; no
  legacy-mutation exception taken.
- D2 — signal/handle split: `:status :cancelled` ∈ canonical `:state*` read via
  the read path at step-boundary checkpoints; future/`inflight-runs` stay a pure
  runtime handle (consistent with doc/architecture.md State boundary table).
- D3 — cascade owned by agent-session session-dispatch authority: nested sub-runs
  enumerated from canonical run-tree and re-dispatched; child sessions aborted via
  the existing `turn/abort-active-turn-in!` → `:session/abort` dispatch path
  (compose > new mechanism); no reach-in / propagation shim.
- D4 — terminal transitions (cancel/remove/complete) route through serialized
  dispatch single-writer (state-kernel pipeline) for idempotent / no-double-
  terminal / cancel-during-wait safety; supersedes the current TOCTOU
  check-then-`reset!` guard (task 224 atomicity-from-dispatch-serialization
  precedent).

Code basis confirmed during decision-making: `cancel-run`/`remove-run` are pure
`state → [state', run]` (workflow-runtime/core.clj); the agent-session mutations
currently `reset!` `:state*` directly after a status guard (the TOCTOU D4
addresses); `inflight-runs` holds `{:future :job-id}` (orchestration.clj); the
session abort path `turn/abort-active-turn-in!` → `:session/abort` →
`agent-core/abort-in!` → context thread interrupt already exists for D3 reuse.

## Ambiguity review (ψ, 2026-06-10)

Reviewed design.md for ambiguities (multiple-interpretation statements,
unresolved decisions, contract gaps). Not architecture/correctness. D1–D4 resolved
the boundary questions but left several original Design Questions and behaviour
contracts under-specified:

1. **Q3 `remove` on a live run unresolved.** Desired Behaviour ("cancel it first
   (or refuse while live)"), Scope ("cancels-then-removes (or rejects)"), and
   Design Question 3 ("Pick one and make it explicit") all still present both
   options. No D-decision picks one. Implementer cannot tell which API contract to
   build.

2. **Q1 in-flight child-turn contract ambiguous (and conflicts with Intent).**
   Intent/Acceptance assert "no further side effects (commits, journal writes) …
   after cancel" (absolute), but Desired Behaviour qualifies the in-flight child
   turn as "at minimum … must not advance past that step, and ideally the child
   turn is interrupted so no further … commits run." Acceptance criterion #3's
   "signalled to stop / its turn does not advance the parent" reuses the same "/".
   Whether interrupting the directly-cancelled run's in-flight child turn (thus
   preventing the one in-flight commit) is a *guaranteed* requirement or
   best-effort is undecided — and the absolute "no side effects after cancel"
   acceptance test cannot pass if it is only best-effort.

3. **Cancel during a blocking `send-and-drain` wait: stop latency undefined.**
   D2 says the loop reads the signal "at each step boundary and at interrupt-aware
   wait wake-ups," but the original runaway was the loop *parked* on a
   `send-and-drain` deref. It is unstated whether a cancel arriving mid-wait is
   observed only after the wait returns naturally (next between-steps checkpoint —
   the in-flight turn still runs to completion) or whether the wait is actively
   interrupted to stop promptly. "next safe checkpoint (at minimum, between steps)"
   vs "interrupt-aware waits" are in tension; the guaranteed bound is ambiguous.

4. **Division of labor: cooperative signal-read (D2) vs `future-cancel`/interrupt
   (Scope/Desired).** Both mechanisms are required but their roles are unclear: is
   `future-cancel` (with interrupt) the primary means of unblocking a parked
   `send-and-drain` wait, with the read-path check the between-steps guard — or is
   `future-cancel` a backstop applied only after cooperative exit? Determines
   whether interrupt-safety of the wait is in scope.

5. **D1 vs D3 owner of child-session abort.** D1 models child-session abort as an
   effect-as-data "executed at the orchestration runtime boundary … against … the
   session-dispatch authority"; D3 routes child-session abort "through the
   agent-session session-dispatch authority" via `:session/abort`. Whether these
   name one path (effect handler invokes the dispatch authority) or two owners is
   not made explicit.

6. **Stale "Design Questions" section.** Q1–Q4 are headed "resolve during
   refinement"; D1–D4 only resolve the boundary questions (Q2 via D3, Q4 partly via
   D2), leaving Q1 and Q3 open with no marker of resolution status. An implementer
   cannot tell which questions are still live.

## Ambiguity follow-up resolution (ψ, 2026-06-10)

Executed all six ambiguity follow-up design-steps. Each was a design-decision step
(pick one explicit contract, state in design.md); all completable now — no
blockers. Resolutions written to design.md as "Behaviour-Contract Decisions"
D5–D9 plus a "Design Questions — Resolution status" section, and the ambiguous
prose in Intent / Desired Behaviour / Scope / Acceptance / Design Questions was
reconciled:

- D5 — `remove` of a live run = cancel-then-remove (commit `:cancelled`, emit
  cancel effects, remove record); future-cancel interrupt prevents re-orphaning
  after removal. Resolves Q3; dropped the "(or refuse/reject)" alternatives in
  Desired Behaviour and Scope.
- D6 — in-flight child turn is *always* interrupted (guaranteed action via
  `:session/abort`); the guarantee is "no **new** side effects initiated after the
  cancel checkpoint," with the lone already-in-syscall-flight effect a physics
  exception. Resolves Q1; reconciled the absolute Intent/Acceptance wording and
  removed the "/" in Desired Behaviour and acceptance #3/#4.
- D7 — cancel during a parked `send-and-drain` wait actively interrupts the wait
  (`future-cancel(true)`); guaranteed stop bound = interrupt delivery + child
  abort, not natural turn completion. Concretises D2's "interrupt-aware wait
  wake-ups."
- D8 — division of labor: cooperative read-path check = primary advance-guard
  (pull, for cancels arriving between steps); `future-cancel(true)`/interrupt =
  wait-wakeup + removed-run backstop (push). Both required for different runtime
  states → `send-and-drain` wait interrupt-safety is in scope.
- D9 — D1 and D3 describe one path, not two owners: the D1 effect-as-data handler
  invokes D3's `:session/abort` dispatch (single agent-session owner, single effect
  path).
- Design Questions Q1–Q4 each tagged RESOLVED inline with D-pointers; resolution
  summary section added; no questions remain live.

Self-consistency check: D5–D9 are consistent with D1–D4 (effects-as-data at the
runtime boundary, signal/handle split, agent-session-owned cascade, serialized
terminal transitions) — D8/D9 reuse the D1 effect boundary and D3 dispatch
authority; D7's interrupt is the push companion to D2's pull read; D5's
cancel-then-remove routes its terminal transition through D4's serialized writer.
No new ambiguity introduced; the only acknowledged residual is the physics
exception in D6 (one in-flight effect may land), which is testable as a negative
(no *new* effect initiated).

## Inconsistency review (ψ, 2026-06-10)

Reviewed design.md for internal inconsistency (claims that contradict each other)
and design-vs-artifact consistency. Code premises confirmed accurate: pure
`cancel-run`/`remove-run` (`workflow-runtime/core.clj`), the `reset!`-after-guard
TOCTOU in the agent-session Pathom mutations (`mutations/canonical_workflows.clj`),
and `delegate remove` orphaning the future (no `future-cancel`,
`workflow/core.clj:489`) all match the Evidence/Root-Cause/D4 narrative. Two
internal contradictions remain:

1. **Removed-run: pull vs push contradiction.** D2 ("exits promptly when it
   observes `:cancelled` (or a removed run)") and Scope in-scope ("cooperative
   cancellation check … keyed on run status (`:cancelled`/removed)") both assign
   the *removed* case to the cooperative read-path check. But D8(b) states the
   removed case has "**no signal remains to read**" and is therefore handled by the
   `future-cancel(true)`/interrupt **push** backstop. These contradict on whether a
   removed run is observable via the read path (run absence = stop signal) or only
   via push interrupt. An implementer cannot tell whether the cooperative checkpoint
   must treat a missing `workflow-run-in` result as a stop condition.

2. **Thread-interrupt disposition contradiction.** Scope Out-of-scope lists
   "Force-killing threads as the primary mechanism (manual `Thread.interrupt` was a
   one-off recovery, not the intended API)", while D7/D8 make `future-cancel(true)`
   — which delivers a JVM thread interrupt to wake the parked `send-and-drain` deref
   — a *required, intended, in-scope* mechanism ("neither is merely a backstop";
   wait interrupt-safety "is in scope"). The design never explicitly distinguishes
   the in-scope cooperative `future-cancel(true)` interrupt from the out-of-scope
   "force-kill / manual `Thread.interrupt`", so the two sections appear to assign
   thread interruption opposite statuses. (Contrast D7, which *did* explicitly
   reconcile the "between steps" wording.)

## Inconsistency follow-up resolution (ψ, 2026-06-10)

Executed both inconsistency follow-up design-steps. Each was a design-decision
step (reconcile an internal contradiction in design.md); both completable now —
no blockers. Resolutions written to design.md as "Consistency Reconciliations"
D10–D11, with the cross-referenced wording aligned at D2, D8(b), and Scope
Out-of-scope:

- D10 — removed-run pull/push: a removed run is observed as **absence** of a
  `workflow-run-in` result, which the cooperative checkpoint treats as a pull stop
  signal identical to `:cancelled`. D2/Scope (removed handled by the read-path
  check) stand correct; D8(b)'s "no signal remains to read" was an overstatement
  (the `:cancelled` *status value* is gone, but run-absence is itself the readable
  stop signal). `future-cancel(true)` push keeps its D8 role — wake a parked worker
  so it reaches the checkpoint — and is not the sole removed-run stop mechanism.
  Single stop-signal predicate stated: `(or (nil? r) (= :cancelled (:status r)))`.
  D8(b) bullet reworded to wait-wakeup (push, both cancelled and removed parked
  cases); D2 annotated with the D10 pointer.

- D11 — thread-interrupt disposition: the in-scope *cooperative* `future-cancel(true)`
  wait-wakeup (interrupt-aware `send-and-drain` handles `InterruptedException`,
  returns to the checkpoint, terminates cleanly) is distinct from the out-of-scope
  unsafe force-kill / ad-hoc manual `Thread.interrupt` (non-interrupt-aware worker,
  no checkpoint, abrupt abandonment) used as a one-off Evidence recovery. The two
  Scope/D7-D8 statements do not assign opposite statuses: intended mechanism =
  cooperative interrupt (in scope); rejected = unsafe abrupt termination as the
  primary stop mechanism. Scope Out-of-scope bullet reworded to name the rejected
  thing precisely and point to D11.

Consistency check: D10/D11 are consistent with D2 (signal/handle split, pull read
path), D7/D8 (push wait-wakeup + interrupt-safety), and D5 (cancel-then-remove
removes the status value but the future-cancel push + run-absence pull still stop
the worker). No new contradictions introduced.

## Architecture-fit review (ψ pass 2, 2026-06-10)

Fresh architecture-fit pass over design.md (AGENTS.md VSM + `λ parity` +
`λ extend`, META.md, doc/architecture.md dispatch/effects/replay surfaces). D1–D11
already place the cancellation side effects as effects-as-data, split signal/handle,
own the cascade via the session-dispatch authority, and serialize terminal
transitions — those fit. Two *new* actionable misfits remain where the design does
not commit the effects to the project's canonical dispatch-effect pathway:

1. **Cancellation effects executor placement — out-of-dispatch vs canonical
   `:effects` interceptor (effect-schema parity, replay-trim, trace).** D1 names the
   executor as "the orchestration runtime boundary — the layer that owns
   `inflight-runs` (`psi.agent-session.workflow.orchestration`/`runtime_state`)",
   and D9 as a "runtime-boundary effect handler". But the project's effects-as-data
   contract runs effects through the dispatch `:effects` interceptor against a
   malli `effect-schema` with a matching `execute-effect!` multimethod
   (`agent-session/dispatch_effects.clj` + `dispatch_schema.clj`; see existing
   `:runtime/agent-abort`, `:runtime/mark-workflow-jobs-terminal`). Executing
   cancellation effects at the orchestration layer instead bypasses (a) the
   validate-interceptor effect-schema check, (b) `:trim-effects-on-replay`
   suppression — breaking the `∀change → event → log → replayable` S5 closure for
   the real side effects `future-cancel`/interrupt/abort, and (c) dispatch-trace
   `:dispatch/effect-start`/`-finish` observability (the very signal whose absence
   made the Evidence runaway hard to diagnose). Fit decision the design must state:
   the new effects are canonical `:runtime/*` effect types registered in
   `effect-schema` with parity `execute-effect!` methods, executed by the dispatch
   `:effects` interceptor — not an orchestration-layer execution path. (AGENTS.md
   `λ parity`, S1 effects/S3 dispatch, doc/architecture.md replay-trim + dispatch
   trace.)

2. **Background-job terminalization ownership — reuse existing effect, not a new
   ad-hoc write.** Scope/Desired/Acceptance require "the background job for a
   cancelled run is marked terminal" but assign it no owner. An effect already
   exists for exactly this (`:runtime/mark-workflow-jobs-terminal`), and the
   background job is a projection of the workflow-registry handle into `:state*`
   (doc/architecture.md State-boundary table). Fit decision: job-terminal status
   should fall out of the D2/D4 terminal transition by reusing the existing
   terminalization effect (λ extend compose > new mechanism), not a separate
   out-of-band registry write that re-introduces a second writer for run-terminal
   status.

Both are boundary-commitment decisions for the design to state, not step-machine
redesigns (correctly out of scope).

## Architecture-fit follow-up resolution (ψ pass 2, 2026-06-10)

Executed both pass-2 architecture-fit follow-up design-steps. Both were
design-decision steps (state a boundary commitment in design.md); both completable
now — no blockers. Verified the cited dispatch infrastructure exists before
committing:

- `effect-schema` (`dispatch_schema.clj`) + `execute-effect!` multimethods
  (`dispatch_effects.clj`) confirmed, including existing `:runtime/agent-abort` and
  `:runtime/mark-workflow-jobs-terminal` effects with parity schema entries.
- Dispatch `:effects` interceptor + `:trim-effects-on-replay` + dispatch-trace
  `:dispatch/effect-start`/`-finish` confirmed (`dispatch.clj`, dispatch tests,
  doc/architecture.md §replay-trim and §dispatch trace).

Resolutions written to design.md as "Dispatch-Effect Parity Decisions" D12–D13,
with D1/D9 refined and Scope/Desired Behaviour updated:

- D12 — cancellation effects (worker `future-cancel`/interrupt, child-session
  abort) are canonical dispatch `:runtime/*` effect types (parity: `effect-schema`
  + `execute-effect!`) executed by the dispatch `:effects` interceptor, not an
  out-of-dispatch orchestration path. Child abort reuses the existing
  `:runtime/agent-abort` effect (compose > new mechanism, consistent with D3/D9);
  the worker-future cancel is emitted as a `:runtime/*` effect carrying `run-id`
  whose `execute-effect!` cancels the future in the `inflight-runs` handle via ctx.
  Routing earns validate-interceptor schema check, `:trim-effects-on-replay`
  suppression (replay closure), and dispatch-trace observability. D1's "runtime
  boundary" and D9's "runtime-boundary effect handler" wording refined to name the
  `:effects` interceptor as executor (handle supplied via ctx).
- D13 — background-job terminalization reuses the existing
  `:runtime/mark-workflow-jobs-terminal` effect, emitted from the D2/D4 serialized
  terminal transition (single writer for run-terminal status, projected from the
  workflow-registry handle), not a separate ad-hoc registry write. Scope and
  Desired Behaviour updated to name the reuse and single owner.

Consistency check: D12/D13 are consistent with D1 (effects-as-data, no inline
mutation side effects — they only sharpen *where* executed), D3/D9 (agent-session
session-dispatch authority reached via the `:runtime/agent-abort` effect), and
D2/D4 (signal/handle split; serialized terminal transition emits the
terminalization + cancellation effects together). No new contradictions; no
step-machine redesign.

## Ambiguity review (ψ pass 2, 2026-06-10)

Fresh ambiguity pass over design.md against the actual execution model. D5–D11
resolved the prior contract ambiguities, but checking the code reveals two new
under-specified contracts where the design's transitive-cancellation wording does
not match the single-thread synchronous execution structure:

1. **Per-sub-run `future-cancel` target unspecified for nested sub-runs.** Intent
   ("transitively across nested sub-runs"), Desired Behaviour ("cancelling a parent
   run cancels its in-flight nested delegate sub-runs"), Scope, and D12 ("worker
   `future-cancel(true)` … cancels the future held in the `inflight-runs` handle …
   carrying the `run-id`") together read as if each nested sub-run has its own
   cancellable worker future. But `delegate/delegate-step-runtime-result` drives
   sub-runs **synchronously on the parent worker thread** via `send-and-drain-fn`;
   only top-level runs (`execute-async!`, `continue-blocked-run-async!`) register a
   `{:future :job-id}` in `inflight-runs`. A nested sub-run has no `inflight-runs`
   entry, so a per-sub-run `:runtime/*` future-cancel effect (D12) has no target.
   The design does not state whether (a) only the single top-level worker future is
   `future-cancel`'d/interrupted (the synchronous sub-tree winds down via per-sub-run
   cooperative `:cancelled` signals + the one parent-thread interrupt + child abort),
   or (b) sub-runs are expected to carry their own futures. An implementer cannot
   tell what the recursive D3 sub-run cancel emits as its cancellation *effect*.

2. **Child-session-abort target (session-id) resolution unspecified.** D9/D12 reuse
   the existing `:runtime/agent-abort` effect, but its `execute-effect!` is keyed on
   a **session-id** (`effect-session-id ctx effect`). The design never states how the
   cancel/cascade path derives which session-id(s) to abort — the in-flight attempt's
   `:execution-session-id` (working-memory `:sessions` / step-run attempts) vs the
   run's `:parent-session-id`. Without a stated rule for reading the active child
   session-id from canonical run state, the abort effect's required argument is
   undefined, and "which child session(s) does a parent cancel abort" (only the one
   in-flight turn vs every descendant run's recorded session) is ambiguous.

Both are contract gaps an implementer hits immediately; neither redesigns the step
machine. Note: the run-tree enumeration filter for "in-flight nested sub-run" (which
statuses qualify for cascade) is adjacent but secondary — folded into item 1's
clarification.

## Ambiguity follow-up resolution (ψ pass 2, 2026-06-10)

Executed both ambiguity (pass 2) follow-up design-steps. Both were
design-decision steps (pin a cancellation-effect target / argument and reconcile
the prose with the real execution model); both completable now — no blockers.
Verified the execution structure in code before deciding:

- Only top-level runs register `{:future :job-id}` in `inflight-runs`
  (`orchestration/execute-async!`, `continue-blocked-run-async!`); nested delegate
  sub-runs run synchronously on the parent worker thread via
  `delegate/delegate-step-runtime-result` → `send-and-drain-fn`, with no
  `inflight-runs` entry (confirmed orchestration.clj + delegate.clj).
- A run records `:delegating-run-id` (sub-run → parent run) and `:parent-session-id`
  (delegating session); the in-flight child turn's session is the latest attempt's
  `:execution-session-id` on the run's `:current-step-id` (workflow-runtime
  attempts.clj / model.clj). `:runtime/agent-abort` is keyed on `:session-id`
  (`effect-session-id` = `(:session-id effect)`, dispatch_effects.clj).
- Run statuses `#{:pending :running :blocked}` non-terminal vs
  `#{:completed :failed :cancelled}` terminal; live attempt status ∈
  `#{:running :validating}` (model.clj enums).

Resolutions written to design.md as "Transitive-Cancellation Target Decisions"
D14–D15, with D3/D12 refined and Intent/Desired Behaviour/Scope updated:

- D14 — worker `future-cancel(true)` targets only the single top-level run's
  future (the run-tree root owning the `inflight-runs` entry, reached by walking
  `:delegating-run-id` up). Sub-runs are synchronous, carry no future of their own,
  and wind down via per-sub-run cooperative `:cancelled` signals (pull) + the one
  parent-thread interrupt (push wake-up) + per-in-flight-run child abort. The D3
  cascade enumerates non-terminal (`#{:pending :running :blocked}`) descendants by
  `:delegating-run-id` parentage. Option (a) chosen; sub-runs do not carry futures.
- D15 — child-session-abort `:session-id` = the in-flight child turn's
  `:execution-session-id` (latest live attempt of `:current-step-id`, attempt
  status ∈ `#{:running :validating}`), read from canonical `:state*`; never the
  run's `:parent-session-id` (the delegating/caller session must not be aborted).
  Sessions aborted = the directly-cancelled run + each in-flight descendant sub-run
  with a live attempt (one abort per currently-executing child turn), not every
  descendant's historically-recorded session.

Consistency check: D14/D15 are consistent with D2/D4 (signal/handle split,
serialized terminal transition emitting the cancellation effects), D3/D9 (cascade
via the agent-session `:session/abort`/`:runtime/agent-abort` path), and D12
(canonical `:runtime/*` effects). D14 pins the single worker-future target; D15
pins the abort `session-id` argument the D12 `:runtime/agent-abort` reuse needs. No
step-machine redesign; no new contradictions introduced.

## Inconsistency review (ψ pass 2, 2026-06-10)

Fresh internal-consistency pass over design.md focused on the D12–D15
dispatch-effect/terminalization decisions against the actual effect
implementations. D1–D11 contradictions stay resolved; D14/D15 targets verified
code-accurate (`inflight-runs` only on top-level runs; `:runtime/agent-abort`
keyed on `:session-id`, with the dispatch `:effects` interceptor injecting the
*dispatching* session-id when absent — so D15's explicit `:execution-session-id`
is required to avoid aborting the parent). Two new contradictions found around
the reused `:runtime/mark-workflow-jobs-terminal` effect:

1. **"single writer for run-terminal status" mislabels the job-terminalization
   effect (D13/Desired Behaviour vs D4 + code).** D4 makes the serialized
   dispatch terminal transition (`cancel-run` under single-writer dispatch) the
   single writer of the run's `:status :cancelled`. But D13 and Desired Behaviour
   call `:runtime/mark-workflow-jobs-terminal` "the single writer for run-terminal
   status." That effect (`background_job_runtime/maybe-mark-workflow-jobs-terminal!`)
   does **not** write run status — it reconciles the **background-job** (projected)
   terminal status *from* run status. Two different mechanisms are both titled
   "single writer for run-terminal status," contradicting D4 and the code. The
   correct label is "single writer for the background-job (projected) terminal
   status."

2. **Cancel-then-remove leaves a lingering non-terminal job (D5 + Desired vs D13 +
   code).** Desired Behaviour requires "no lingering `:running` job" after cancel,
   and D5 removes the run record (cancel-then-remove). But
   `maybe-mark-workflow-jobs-terminal!` reconciles each job only `(when wf ...)`
   via `extension-workflow-runtime/workflow-in`, with branches solely for
   `:error?`(→failed) / `:done?`(→completed) — **no `:cancelled` branch**, and it
   **skips** the job entirely when the workflow/run is absent. After D5 step 3
   removes the run record, `workflow-in` returns nil, so the reused effect cannot
   terminalize that run's job → the job lingers non-terminal, contradicting the
   "no lingering job" guarantee. The design states no ordering constraint
   (terminalize before remove) and does not note that the effect's reconcile has
   no cancelled/removed-run path. (Plain cancel-without-remove likely reaches
   terminal via the `:done?` branch but is mislabeled outcome `:completed` — a
   secondary concern folded into the same fix.)

Both are contradictions an implementer hits when wiring D13; neither redesigns
the step machine.

## Inconsistency follow-up resolution (ψ pass 2, 2026-06-10)

Executed both inconsistency (pass 2) follow-up design-steps. Both were
design-decision steps (reconcile an internal contradiction in design.md); both
completable now — no blockers. Code premise re-confirmed before deciding:
`background-job-runtime/maybe-mark-workflow-jobs-terminal!` reconciles each job
only `(when wf …)` (skips when the run/workflow instance is absent via
`extension-workflow-runtime/workflow-in`) and has branches solely for
`:error?`(→`:failed`) / `:done?`(→`:completed`) — no `:cancelled`/removed-run
branch. Resolutions:

- **Writer-label conflation (item 1)** — fixed inline (no new D-section). The
  "single writer for run-terminal status" label is corrected at Desired Behaviour,
  Scope, and D13 to "single writer for the **background-job (projected) terminal
  status**", with an explicit "Two distinct writers" paragraph in D13 separating
  the run `:status` single-writer (D4 serialized dispatch transition) from the
  background-job projected-terminal single-writer
  (`:runtime/mark-workflow-jobs-terminal`, which reconciles *from* run status).
  No contradiction with D4 remains.

- **Cancel-then-remove lingering job (item 2)** — new "Consistency Reconciliations
  (ψ pass 2)" section with D16. Decision: both constraints apply — (1)
  terminalize-before-remove ordering (run-record removal is the last step of the
  D5 cancel-then-remove effect set, so the job is reconciled while the run is still
  resolvable), and (2) `maybe-mark-workflow-jobs-terminal!` gains a `:cancelled`
  reconcile branch terminalizing with `:outcome :cancelled` (not `:completed`).
  Both required: (1) alone mislabels outcome and still skips a pure-removal with no
  preceding cancel; (2) alone hits post-removal `workflow-in`→nil. Stays within the
  single existing background-job terminal writer (`λ extend` compose; no second
  writer). Desired Behaviour and Scope updated to point at the D16 constraints.

Consistency check: D16 is consistent with D4 (run `:status` single-writer), D5
(cancel-then-remove sequence ordering), and D13 (single background-job terminal
writer, reuse not a second writer). No new contradictions introduced; no
step-machine redesign.

## Architecture-fit review (ψ pass 3, 2026-06-10)

Fresh architecture-fit pass over design.md against doc/architecture.md "Dispatch
sequencing contract" and the confirmed pure transition functions. D1–D16 already
fit the effects-as-data / signal-handle / session-dispatch-authority / serialized
single-writer / dispatch-effect-parity boundaries — those stand. One **new**
actionable misfit: D16's terminalize-before-remove ordering collides with the
dispatch apply-before-effects sequencing.

1. **D16(1) "terminalize-before-remove" ordering is unachievable within one
   dispatch because run-record removal is a pure `:state*` transition, not an
   effect.** doc/architecture.md fixes the effective dispatch after-order as
   `:apply → :validate → :trim-effects-on-replay → :effects` — **all** pure state
   application precedes **all** effects in a single dispatch. The run-record
   removal is the pure `remove-run` dissoc on canonical `:state*`
   (`workflow-runtime/core.clj`: `(update-in (runs-path) dissoc run-id)`,
   `state → [state', run]`), so it runs in the `:apply` phase. The job
   terminalization is the `:runtime/mark-workflow-jobs-terminal` effect (D13),
   which runs in the `:effects` phase and re-reads the run via
   `extension-workflow-runtime/workflow-in`. Therefore, if D5 cancel-then-remove is
   a single dispatch, the apply phase removes the canonical run **before** the
   terminalize effect runs → `workflow-in` returns nil → job skipped — exactly the
   lingering-job failure D16 set out to prevent. D16 frames "terminalize before
   remove" as ordering *within the effect set*, but a pure `:state*` removal cannot
   be sequenced after an effect within one dispatch; the architecture's
   apply-before-effects contract forces the removal first. Fit decision the design
   must state: split cancel-then-remove so the canonical run-record removal happens
   in a **distinct, subsequent dispatch** (the cancel dispatch terminalizes the job
   via the effect while the run is still present; a following remove dispatch then
   drops the canonical record), or make the terminalize reconcile **not depend** on
   re-reading the canonical run (carry the run identity/`:cancelled` outcome in the
   `:runtime/mark-workflow-jobs-terminal` effect payload so it terminalizes without
   `workflow-in`). Either reconciles D16 with the dispatch sequencing contract; the
   current "order the removal after the terminalize effect" is not expressible given
   apply-before-effects.

This is a boundary-sequencing decision for the design to state, not a step-machine
redesign (correctly out of scope). It is distinct from the pass-2 inconsistency
note/D16, which identified the `(when wf …)`-skip + missing `:cancelled` branch but
did not reconcile the ordering against the dispatch apply-before-effects pipeline.

## Architecture-fit follow-up resolution (ψ pass 3, 2026-06-10)

Executed the single pass-3 architecture-fit follow-up design-step (a
design-decision step: state how D16's terminalize-before-remove ordering is
expressible under the dispatch apply-before-effects contract). Completable now —
no blocker. Code + contract premises re-confirmed before deciding:

- `remove-run` is a pure `:state*` dissoc (`workflow-runtime/core.clj:217`,
  `state → [state', run]`) → runs in the `:apply` phase.
- `:runtime/mark-workflow-jobs-terminal` `execute-effect!` →
  `background_job_runtime/maybe-mark-workflow-jobs-terminal!` re-reads each run via
  `extension-workflow-runtime/workflow-in` → runs in the `:effects` phase.
- doc/architecture.md "Dispatch sequencing contract": effective after-order
  `:apply → :validate → :trim-effects-on-replay → :effects` — all pure apply
  precedes all effects within one dispatch. So a single cancel-then-remove dispatch
  removes the record (apply) before the terminalize effect re-reads it → skipped.

Resolution written to design.md as "Dispatch-Sequencing Reconciliation (ψ pass 3)"
D17, with D5 step 3, D13, and D16(1) updated:

- D17 — chose option (a): cancel-then-remove is **two serialized dispatches**. The
  cancel dispatch applies the D4 `:cancelled` transition (run still present) and
  emits the D12 cancellation effects + the D13 terminalization effect together (the
  D16(2) `:cancelled` branch terminalizes the still-resolvable run with
  `:outcome :cancelled`); the subsequent serialized remove dispatch applies the
  pure `remove-run` dissoc. D4 single-writer serialization guarantees dispatch 2
  sees dispatch 1's applied state, so terminalize-before-remove holds across the
  two dispatches though it is impossible within one.
- Rejected option (b) (effect-payload self-containment): it would duplicate the
  canonical `:cancelled` run state into the effect payload (second source of truth
  vs `source_of_truth ≡ … :state*`), fork the effect into a hybrid
  reconcile-all + terminalize-this-run path diverging from its single
  reconcile-from-canonical-state contract and its payload-free `statechart_actions`
  call site. The split keeps the effect contract intact (`λ extend` compose) and
  reuses D4's existing serialized ordering.
- D16(1) reworded: terminalize-before-remove is realized by the D17 two-dispatch
  split, not an intra-effect-set ordering. D16(2)'s `:cancelled` reconcile branch
  remains required (it is what terminalizes the still-present cancelled run in the
  cancel dispatch). D5 step 3 + D13 updated to name the cancel-dispatch /
  remove-dispatch split.

Consistency check: D17 is consistent with D4 (serialized single-writer ordering
across both dispatches), D5 (cancel-then-remove sequence, now two dispatches),
D13 (single background-job terminal writer, reuse not a second writer), and D16(2)
(`:cancelled` reconcile branch). No new contradictions; no step-machine redesign.

## Ambiguity review (ψ pass 3, 2026-06-10)

Fresh ambiguity pass over design.md after D17. D5–D16 + D17 resolved the prior
contract/sequencing ambiguities; two new under-specified contracts remain that an
implementer hits when wiring D17 and the Evidence's direct sub-run cancel:

1. **D17 two-dispatch trigger/sequencing mechanism unspecified.** D17 (and D5
   step 3) say cancel-then-remove is "two serialized dispatches" — a cancel
   dispatch then "a distinct, subsequent remove dispatch" — but never state how the
   second dispatch is *issued* and ordered after the first for a single
   remove-of-live-run request. The two candidate mechanisms have different boundary
   implications: (a) the cancel dispatch emits a re-entrant dispatch effect (e.g. a
   `:runtime/dispatch`-style effect) that enqueues the remove dispatch as
   effects-as-data (fits D1/D12: no inline orchestration in the mutation), vs (b)
   the `remove` command flow synchronously issues two `dispatch` calls (orchestration
   logic in the mutation/command layer, in tension with D1's "no inline side effects /
   effects-as-data"). doc/architecture.md's dispatch sequencing contract documents
   no re-entrant dispatch-emits-dispatch effect today, so the chaining mechanism is
   an open boundary decision, not just an impl detail. An implementer cannot tell
   which to build, nor whether a new "dispatch a follow-on event" effect type is in
   scope.

2. **Direct cancellation of a nested sub-run: parent-run contract unspecified.**
   The Evidence (step 2) explicitly cancels the *nested sub-run* directly, yet the
   design only specifies *top-down* propagation ("cancelling a parent run cancels
   its in-flight nested sub-runs"). Because sub-runs are synchronous on the single
   top-level worker thread and D14 walks `:delegating-run-id` upward so the
   `future-cancel(true)` interrupt necessarily hits the *shared top-level* worker,
   directly cancelling a sub-run interrupts the parent's worker too. The design does
   not state the resulting contract: after the shared worker is woken and the pull
   check sees only the sub-run `:cancelled` (parent still `:running`), does the
   parent run (a) continue executing — and if so, how does the parent's delegate
   step interpret a directly-cancelled sub-run's delegate result (fail the step /
   propagate / continue) — or (b) does interrupting the shared worker effectively
   halt the parent as well? "delegate result-delivery paths" are nominally
   out-of-scope, but the cancelled-sub-run result handling is required for direct
   sub-run cancellation (a demonstrated Evidence case) to behave sanely. Either
   pin the contract or explicitly scope direct sub-run cancellation out.

Both are contract gaps an implementer hits immediately; neither redesigns the step
machine.
