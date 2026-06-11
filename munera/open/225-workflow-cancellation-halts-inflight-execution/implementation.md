# Implementation notes

## Implementation pass — cooperative workflow stop checkpoints (ψ, 2026-06-11)

Implemented Slice 4's cooperative stop path in the workflow statechart runtime.
`statechart-runtime.state` now exposes the canonical read-path predicate:
missing run record ⇒ `:removed`, `:status :cancelled` ⇒ `:cancelled`. The lifecycle
`send-and-drain!`/`drain-events!` path calls a stop checkpoint before event
processing, during queue drain, and after event processing; a stopped run clears
ordinary queued events and marks the working-memory chart cancelled without
resurrecting removed run records. Step entry now checks before ordinary work,
before delegate/session execution, after delegate/session/turn return, and the
session-step helper accepts a stop predicate so a late child-turn result is not
recorded after the cancel checkpoint. Top-level execute/resume catches
`InterruptedException`, clears interrupt state, and reports the current canonical
run.

Added state-based controlled-harness tests covering: cancelled top-level execution
does not start the next attempt and does not record a late returned actor result;
invoke results returned after cancellation are likewise not recorded and do not
advance to the following session step; a cancelled parent does not create a delegate
sub-run; run absence is a pull stop signal that discards queued ordinary events.
Focused scry suites and focused clj-kondo are green. Remaining concrete work:
parked-worker `future-cancel(true)` wake-up test plus nested direct cancel/remove
and background job terminalization assertions.

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

## Ambiguity follow-up resolution (ψ pass 3, 2026-06-10)

Executed both pass-3 ambiguity follow-up design-steps. Both were design-decision
steps (pin a contract in design.md); both completable now — no blockers. Code
premises verified before deciding:

- `:runtime/dispatch-event` (`dispatch_effects.clj:186`) **already exists** as a
  re-entrant dispatch-emits-dispatch effect: its `execute-effect!` calls
  `dispatch/dispatch!` from the `:effects` interceptor. `kernel/dispatch!`
  (`state-kernel/dispatch.clj:387`) runs the interceptor chain synchronously
  in-thread with no global lock → reentry-safe (pattern already used by
  scheduler/post-tool flows). So item-1 option (a) needs **no new effect type**;
  the follow-up's "no re-entrant effect today" premise is true only of the *doc*
  (the effect exists in code, just undocumented in the sequencing contract).
- `delegate-step-runtime-result` (`statechart_runtime/delegate.clj`) **already has
  a `:cancelled` case** in its status `case`: returns `{:pending-kind :failure
  :payload {:message "Delegated workflow cancelled" …}}`. So a directly-cancelled
  sub-run already maps to a failed delegate step via the existing result-delivery
  path — item-2 needs no new mechanism (stays within the out-of-scope boundary).
- `cancel-run`/`remove-run` are the `reset!`-after-guard Pathom mutations
  (`mutations/canonical_workflows.clj:220,244`); `remove-run` pure dissoc
  (`workflow-runtime/core.clj`).

Resolutions written to design.md as "Ambiguity Reconciliations (ψ pass 3)" D18–D19,
with D17, D5 step 3, D14, Scope, and the out-of-scope note updated:

- D18 — D17's two-dispatch chaining is option (a): the cancel dispatch emits the
  remove dispatch as **effects-as-data** via the **existing** `:runtime/dispatch-event`
  effect (no new follow-on-dispatch type in scope), ordered after the D13
  terminalize + D12 cancellation effects in the cancel dispatch's effect set →
  terminalize-before-remove holds (effects run in declared order; the re-entrant
  remove dispatch is itself D4-serialized). Option (b) (synchronous two `dispatch`
  calls in the `remove` mutation/command layer) rejected — command-layer
  orchestration in tension with D1. Noted residual artifact gap: document
  `:runtime/dispatch-event` re-entrancy in doc/architecture.md's "Dispatch
  sequencing contract" (change-chain doc step at implementation time).

- D19 — direct nested sub-run cancellation is **in scope**. Cascade runs **downward
  only** from the cancelled run (signal + per-attempt child abort to it and its
  in-flight descendants). The worker `future-cancel(true)` is emitted **iff the
  directly-cancelled run is the top-level run** (owns the `inflight-runs` entry);
  for a direct sub-run cancel it is **not** emitted (would disrupt the
  still-`:running` parent + siblings on the shared thread). The downward
  child-session abort terminates the in-flight turn, the sub-run reaches
  `:cancelled` terminal, `send-and-drain` returns, and
  `delegate-step-runtime-result`'s **existing** `:cancelled` case maps it to a
  failed delegate step; the parent run continues per normal step-failure handling
  and is **not halted** (a child cancel must not kill a running parent). D14's
  future-cancel emission rule refined (walk-up targets the single interrupt during
  a top-level cascade; not a license to interrupt the worker on a direct sub-run
  cancel); Scope adds the direct-sub-run-cancel bullet; the out-of-scope
  delegate-result-delivery note now points to D19 (reuse, not redesign).

Consistency check: D18 is consistent with D1/D12 (effects-as-data at the dispatch
boundary), D4 (re-entrant remove dispatch is serialized), D13/D16/D17 (terminalize
before the record drop). D19 is consistent with D3/D14/D15 (downward cascade,
single top-level future, per-attempt abort target) and D6 ("no new side effects
after the checkpoint" for the cancelled sub-run while the parent legitimately
continues its own work). No step-machine redesign; no new contradictions introduced.

## Inconsistency review (ψ pass 3, 2026-06-10)

Fresh internal-consistency pass over design.md after D17–D19, checking the
D4 race-safety mechanism against the now-explicit dispatch concurrency model (D18)
and the referenced `state-kernel/dispatch.clj` + doc/architecture.md. The
D10/D11/D16/D17/D18/D19 reconciliations stand. One **new** actionable contradiction:

1. **D4 "serialized single-writer dispatch" race-safety contradicts D18's "no
   global lock" + the dispatch code.** D4 attributes the design's idempotent /
   no-double-terminal / no-resurrection / "two concurrent cancels cannot both apply
   a terminal transition" guarantees to "the single serialized writer (dispatch)"
   and "atomicity-from-dispatch-serialization," and explicitly disavows the
   mutation guard ("the authoritative atomicity comes from dispatch serialization,
   not the mutation's outer `when` guard"). But D18 (pass 3) states `dispatch!`
   "runs the interceptor chain synchronously on the calling thread with **no global
   lock**," and the referenced code confirms it: `kernel/dispatch!`
   (`state-kernel/dispatch.clj:387`) runs the chain on the caller's thread with no
   lock; worker futures dispatch from `clojure-agent-send-off-pool` threads
   (Evidence). doc/architecture.md's "Dispatch sequencing contract" describes only
   phase ordering (`:apply → :validate → :trim → :effects`), **not** single-writer
   serialization. So dispatch is **not** a serialized single-writer against
   concurrent threads — two cancels on two threads run two unsynchronized
   `dispatch!` calls. The only atomicity is the per-`swap!` CAS on `:state*` in the
   `:apply` phase (`apply-root-state-update!` = `(swap! (:state* env)
   root-update-fn)`), and the terminal-status guard is computed in the `:handler`
   `:before` (reading `:state*`) **separately** from that `swap!` — so the
   read-guard-and-commit is **not** atomic unless the guard is re-evaluated inside
   the `:root-state-update` fn passed to `swap!`. D4's stated mechanism
   ("dispatch serialization") does not exist as described; the guarantee, if it
   holds, must come from the atom CAS with the guard inside the update fn — which D4
   disavows. This contradiction propagates: D13/D16/D17 ("D4 single-writer",
   "serialized dispatch (D4 single-writer) guarantees dispatch 2 observes dispatch
   1's applied state") all lean on the same "serialization" framing. (For the D17
   re-entrant remove dispatch the cross-dispatch ordering actually holds via
   single-thread in-thread sequencing per D18, not serialization — same mislabel.)

This is an internal D4⟷D18 contradiction and a design-vs-code/doc inconsistency;
it does not redesign the step machine (the fix is to restate the real atomicity
basis, not change the cancellation mechanism).

## Inconsistency follow-up resolution (ψ pass 3, 2026-06-10)

Executed the single pass-3 inconsistency follow-up design-step (a design-decision
step: reconcile D4's race-safety mechanism with the now-explicit dispatch
concurrency model). Completable now — no blocker. Code basis re-confirmed before
deciding (read `state-kernel/dispatch.clj`):

- `kernel/dispatch!` runs the interceptor chain synchronously on the **calling
  thread with no global lock** (no `locking`/monitor; worker futures dispatch from
  pool threads) — so dispatch is **not** a serialized single-writer against
  concurrent threads.
- `handler-interceptor` `:before` computes the `:root-state-update` fn (reading
  `:state*` then); `apply-interceptor` `:after` applies it via
  `apply-root-state-update!` = `(swap! (:state* env) root-update-fn)`. The only
  atomicity primitive is that per-`swap!` CAS. A guard read in the handler separate
  from the update fn is TOCTOU; the read-guard-and-commit is atomic **only if** the
  terminal-status guard is evaluated **inside** the `:root-state-update` fn (so it
  rides the CAS retry).
- doc/architecture.md "Dispatch sequencing contract" documents only phase order,
  not cross-thread serialization.

Resolution written to design.md as "Atomicity-Basis Reconciliation (ψ pass 3)" D20,
with D4/D13/D16/D17 wording aligned:

- D20 — chose option (a): race-safety atomicity = the apply-phase atom CAS with the
  terminal guard inside the `:root-state-update` fn; option (b) (name a real
  serialization point) rejected — none exists (no lock). Two concurrent cancels
  converge to one terminal commit because the second CAS re-runs its update fn
  against the already-`:cancelled` state and the in-fn guard makes it a no-op
  (idempotent, no double-terminal, no resurrection). Added an explicit builder
  constraint: express the guard inside the pure update fn, never as a
  handler-level pre-read + unconditional update.
- D4 heading + body restated: the safety is the atom CAS with the in-fn guard, not
  "dispatch serialization"; "serialized single-writer" phrases flagged as
  superseded by D20.
- D13/D16/D17 dependent phrasings reconciled: run-`:status` "single writer" is a
  *logical* statement (one transition fn owns status), not thread serialization;
  the D17 two-dispatch cross-ordering holds via **in-thread sequencing** of the
  re-entrant `:runtime/dispatch-event` effect (D18), not serialization. Edited the
  concrete "serialized dispatch (D4 single-writer) guarantees dispatch 2 observes
  dispatch 1" claims (lines in D17/D18) to name in-thread sequencing.

Consistency check: D20 is consistent with D18 (no global lock, re-entrant in-thread
`:runtime/dispatch-event`), D4 (atom CAS replaces the TOCTOU `reset!`-after-`when`),
and D13/D16/D17 (single logical run-`:status` writer; terminalize-before-remove via
in-thread sequencing). No new contradictions; no step-machine redesign; cancellation
effect set unchanged.

## Architecture-fit review (ψ pass 4, 2026-06-10)

Fresh architecture-fit pass over design.md against AGENTS.md VSM (S1 effects /
S3 dispatch, `λ parity`, `λ extend`, `λ(state)`, `λ shims_adapters`), META.md, and
doc/architecture.md (State boundary, Dispatch sequencing contract, replay-trim,
dispatch trace). D1–D20 already commit cancellation to effects-as-data canonical
`:runtime/*` effects through the dispatch `:effects` interceptor (D1/D12), the
signal/handle split (D2/D10/D14/D15), the agent-session session-dispatch authority
for the cascade (D3/D9), terminalization reuse + two-dispatch ordering under
apply-before-effects (D13/D16/D17/D18), and the apply-phase atom-CAS atomicity
basis (D4/D20). Each fits the project boundaries.

Verified the one candidate **new** concern not explicitly stated in the design —
the D18 re-entrant `:runtime/dispatch-event` remove dispatch vs the S5 replay
closure D12 invokes. Code-confirmed (`dispatch_effects.clj:186`): its
`execute-effect!` calls `dispatch/dispatch!`, so the remove dispatch logs its own
event-log entry. On replay the triggering effect is trimmed while the
independently-logged remove event re-applies its pure `remove-run` dissoc — replay
fidelity is preserved. This matches the existing reentry-safe pattern
(scheduler-drain / post-tool). Architecturally sound, not a misfit.

No new actionable architectural-fit misfit found. The design fits the architecture
and principles; the only adjacent residual is the already-noted (D18) doc gap to
document `:runtime/dispatch-event` re-entrancy in the sequencing contract, owned by
the change-chain doc step at implementation time — not a new design misfit.

## Ambiguity review (ψ pass 4, 2026-06-10)

Fresh ambiguity pass over design.md after D17–D20 resolved the prior
contract/sequencing/atomicity ambiguities. The happy paths (top-level cancel,
direct sub-run cancel, cancel-then-remove of a top-level run) are well-pinned. Two
boundary contracts remain under-specified — multiple-interpretation gaps an
implementer would have to guess:

1. **Direct `remove` of a live *nested sub-run* (D5 × D19 intersection).** D5
   states cancel-then-remove as the general semantics for "remove of a live
   (non-terminal) run" without restricting to top-level; D19 pins the
   parent-observes-failed-delegate-step contract only for direct *cancel* of a
   sub-run, where the sub-run reaches a readable `:cancelled` status and
   `delegate-step-runtime-result` keys on `(:status delegate-run) = :cancelled`.
   But under D5/D17 a *remove* of a live sub-run drops the run record (D17 dispatch
   2) — so when the shared parent worker returns from `send-and-drain`,
   `(workflow-run-in state sub-run-id)` is `nil`, and the existing `case` on
   `(:status nil)` falls to the **default** branch ("Delegated workflow did not
   reach terminal or blocked status"), not the `:cancelled` branch D19 assumes
   (code-confirmed `delegate.clj:76` `case`, default at lines ~108–112). The
   design does not state whether direct remove-of-a-live-sub-run is in scope and,
   if so, which delegate-result contract the parent observes after run-absence
   (vs the `:cancelled` failure). Ambiguous: implementer can't tell if the generic
   "did not reach terminal" failure is acceptable or a defect.

2. **Effect emission when the D20 terminal guard makes the transition a no-op.**
   D20 says a second/racing terminal request "commits a no-op" via the in-`swap!`
   guard — but that covers only the `:state*` CAS. In the pure-result shape the
   handler computes its `:effects` in the `:handler` `:before` (pre-CAS), so the
   cancellation effect set (worker `future-cancel`, per-run `:runtime/agent-abort`,
   `:runtime/mark-workflow-jobs-terminal`, and — for remove — the re-entrant
   `:runtime/dispatch-event`) is queued **before** the CAS decides no-op. The
   design does not state whether effects are **suppressed when the guard no-ops**
   (run already terminal / lost the CAS race to natural completion). This matters
   for (a) the stated idempotency guarantee — "a second terminal request … is a
   no-op" is only true of state, not effects, as written; and (b) correctness of
   the `:runtime/agent-abort` target — a no-op'd cancel against an
   already-`:completed` run would still emit an abort for that run's
   `:execution-session-id`, whose turn already finished (benign no-op only if the
   session-id is never reused). Ambiguous: implementer can't tell if cancellation
   effects are gated on the guard actually applying `:cancelled`.

No other new actionable ambiguity found; D1–D20 cover the remaining contracts.

## Pass-4 ambiguity follow-up resolutions (ψ, 2026-06-10)

Both pass-4 ambiguity follow-ups resolved in design.md; no blocking reasons.

1. **Direct `remove` of a live nested sub-run → D21.** In scope (D5 cancel-then-
   remove applies to any live run). The cancel/remove timing race means the parent
   worker, returning from `send-and-drain`, may read either `:cancelled` (record
   present → D19 branch) or run-absence (record dropped by D17 dispatch 2 →
   `(:status nil)` → existing `case` default branch). Decision: **run-absence
   specifically** is treated identically to `:cancelled` at the delegate result
   (maps to the `:cancelled` failed-step result, "Delegated workflow cancelled or
   removed"), via an explicit `nil`/absent-run guard before the status `case` — so
   D19's parent-continues-not-halted contract is race-independent. Non-`nil`
   non-terminal statuses still fall through to the existing default, so real "did
   not reach terminal" anomalies are not masked. Reuses the existing `:cancelled`
   failure mapping (`λ extend`), inside the no-new-result-delivery-path boundary.
   Scope updated.

2. **Effect gating on a no-op'd terminal guard → D22.** Code-confirmed
   (`state-kernel/dispatch.clj`): `:effects` are computed in handler `:before`
   (pre-CAS) and `apply-pure-result` sets `:applied-effects` verbatim regardless of
   whether the D20 `swap!` changed state — so the in-`swap!` no-op does NOT suppress
   effects. Decision: gate in two layers — (1) **handler-before terminal-precondition
   gate**: a request whose run is already terminal/absent at the handler-before read
   returns `{:root-state-update identity :effects []}` (no effects), covering all
   sequentially-later terminal requests (dominant idempotency case); (2)
   **effect-level idempotency** for the residual true-concurrent CAS race where both
   threads pass the before-gate — `:runtime/agent-abort` re-checks the D15
   live-attempt predicate at execute time and no-ops a non-live attempt (cannot abort
   an already-completed/reused `:execution-session-id`), and future-cancel /
   mark-workflow-jobs-terminal / re-entrant remove are inherently idempotent. Aligns
   D4/D20's "second terminal request is a no-op" with the pre-CAS pure-result shape:
   state no-op = D20 in-`swap!` guard; effect no-op = D22.1 gate + D22.2 idempotency.

## Inconsistency review (ψ pass 4, 2026-06-10)

Fresh internal-consistency pass over design.md focused on the newest decisions
(D21, D22) against the established cancel-then-remove body (D5/D17/D18) and the
acceptance criteria. D1–D20 internal reconciliations stand. One **new** actionable
contradiction:

1. **D22.1's "already-terminal ⇒ identity update + empty effects" gate contradicts
   D5 (remove-of-terminal = plain record removal) and D17/D18 (the remove dispatch
   always runs against a just-terminalized run).** D22.1 states "the cancel/remove
   handler … if the target run is already terminal (or absent) at that read …
   returns a no-op pure-result with empty `:effects` (`{:root-state-update identity
   :effects []}`)," and explicitly lists "a **remove** after the run is already
   terminal/removed" as one of the no-op'd cases. But:
   - **vs D5:** D5 says "`remove` of an already-terminal run is unchanged (**plain
     record removal**)" — the record must be dropped. Under D22.1 the remove
     handler returns `identity` for an already-terminal run, so `remove-run` never
     dissocs the record; the terminal record lingers forever.
   - **vs D17/D18 (the core cancel-then-remove flow):** D17 splits cancel-then-
     remove into a cancel dispatch (applies `:cancelled`) and a **subsequent**
     remove dispatch that "applies the pure `remove-run` dissoc." By construction
     the remove dispatch (D18 effect #3, the re-entrant `:runtime/dispatch-event`)
     runs *after* the cancel dispatch already set `:cancelled`, so the remove
     handler **always** reads the run as terminal. If that remove handler is the
     "cancel/remove handler" carrying the D22.1 gate, it no-ops via `identity` and
     the record is **never removed** — defeating D5/D17 cancel-then-remove and
     re-orphaning exactly the scenario this task fixes.

   Root cause: D22.1 conflates two concerns in one gate — (a) the
   *cancellation/terminal-transition + cancellation-effects* part (correctly
   suppressed when the run is already terminal, to keep idempotency) and (b) the
   *record-removal* `remove-run` dissoc (which by design operates **on** an
   already-terminal run and must still apply). The gate as written suppresses (b)
   along with (a). The design must state that the handler-before terminal gate
   suppresses only the cancellation/terminal-transition effects, while the
   `remove-run` record-drop still applies to an already-terminal run (the cancel-
   then-remove sequenced case and the plain remove-of-terminal case). Reconcile
   D22.1 with D5/D17/D18.

(Secondary, non-blocking observation — not filed as a separate step: Acceptance
criterion #2 phrases "`remove` of a live run … future is cancelled" generically,
but D14/D19/D21 establish a nested-sub-run remove emits **no** worker
`future-cancel` — the sub-run owns no future. The criterion is correct only for a
top-level run; it is consistent for its intended top-level scenario, so it is a
coverage/phrasing nuance rather than a contradiction.)

## Inconsistency follow-up resolution (ψ pass 4, 2026-06-10)

Executed the single pass-4 inconsistency follow-up design-step (a design-decision
step: reconcile D22.1's terminal-precondition gate with D5/D17/D18 so it does not
suppress the `remove-run` record drop). Completable now — no blocker. Code premise
re-confirmed: `remove-run` is the pure `:state*` dissoc
(`workflow-runtime/core.clj`), distinct from the `cancel`/terminal-transition path.

Resolution written to design.md, rewording D22.1 and adding a cross-reference:

- D22.1 split into two explicit concerns. The **terminal-precondition gate is
  scoped to the cancellation/terminal-transition only** — the `:cancelled` `:state*`
  commit + the cancellation effect set (worker `future-cancel`,
  `:runtime/agent-abort`, `:runtime/mark-workflow-jobs-terminal`, the re-entrant
  `:runtime/dispatch-event` remove-trigger). When the run is already terminal/absent
  at the handler-`:before` read, that portion contributes `identity` + empty effects.
- The **`remove-run` record-drop dissoc is NOT gated** by the terminal precondition.
  It is an unconditional, status-independent dissoc (itself idempotent on an absent
  record — D22.2). Required because (a) plain remove-of-terminal (D5) must drop the
  record, and (b) the cancel-then-remove remove dispatch (D17/D18 dispatch 2) always
  runs after `:cancelled` is applied, so its handler always reads the run as terminal
  — gating the record drop would no-op it and re-orphan the record.
- Added "Cross-reference (D5/D17/D18) — scope of the D22.1 gate" paragraph at the end
  of D22 stating only cancellation effects are suppressed for an already-terminal
  run; the record drop always applies, so the gate never re-orphans a terminal run.

Consistency check: the reworded D22.1 is consistent with D5 (cancel-then-remove +
plain remove-of-terminal both drop the record), D17/D18 (the remove dispatch reads a
terminal run yet still applies `remove-run`), and D22.2 (record drop idempotent on an
absent record). The conflated "cancel/remove handler … no-op for already-terminal"
wording was the rejected option; the cancellation-transition vs record-drop split is
chosen. No new contradictions; no step-machine redesign.

## Architecture-fit review (ψ pass 5, 2026-06-10)

Fresh architecture-fit pass over design.md against AGENTS.md VSM (S1 effects /
S3 dispatch, `λ parity`, `λ extend`, `λ(state)`, `λ shims_adapters`), META.md, and
doc/architecture.md (State boundary, Dispatch sequencing contract, replay-trim,
dispatch trace). D1–D22 commit the directly-cancelled run's own terminal
transition + cancellation effects, the cancel-then-**remove** re-dispatch (D17/D18,
pinned to the re-entrant `:runtime/dispatch-event` effects-as-data mechanism), the
signal/handle split (D2/D10/D14/D15), terminalization reuse (D13/D16), and the
apply-phase atom-CAS atomicity basis (D4/D20). Those all fit.

One **new** actionable misfit:

1. **Transitive cascade re-dispatch mechanism not committed to the effects-as-data
   dispatch boundary (vs D18) and not reconciled with the D4/D20 single-run
   atomicity basis.** D3 specifies the cascade as "enumerates in-flight nested
   sub-runs from canonical run-tree state … and **dispatches a cancel for each
   (recursively)**, reusing the same cancel mutation path"; D14 reframes each
   sub-run cancel as emitting a per-sub-run `:cancelled` terminal transition (D2/D4)
   + per-in-flight child-abort effect. But unlike the cancel-then-remove second
   dispatch — which D18 explicitly pins to a re-entrant `:runtime/dispatch-event`
   follow-on effect *because* D1 forbids command-layer / inline orchestration of a
   re-dispatch — the design never states *how* the per-sub-run cascade terminal
   transitions are issued: (a) one multi-run apply-phase `:root-state-update` over
   the enumerated descendant set within the single parent-cancel dispatch, or (b) N
   re-entrant `:runtime/dispatch-event` cancel dispatches (one per descendant). The
   same re-dispatch-boundary question D18 answered for `remove` is left open for the
   cascade, so an implementer could place the recursion in a command-layer loop /
   inline cross-handle reach-in (violating D1/D3/D18). Compounding it, D4/D20's
   race-safety is framed for a **single** run (terminal guard inside the
   single-run `:root-state-update` fn riding one CAS); a subtree cancel under (a)
   is a multi-run transition whose per-run guard/idempotency is not stated, and
   under (b) is N separate dispatches/CASes whose ordering vs the parent's own
   terminal transition + D14 single top-level `future-cancel` is not stated.
   Architectural fit gap: the cascade's per-sub-run terminal-transition +
   re-dispatch is under-committed to the canonical dispatch `:effects` pathway the
   design otherwise mandates (D1/D12/D18), and unreconciled with the D4/D20
   atomicity shape. Actionable — file as a pass-5 architecture-fit follow-up.

No other new actionable architectural-fit misfit found; D1–D22 cover the remaining
boundary commitments.

## Architecture-fit follow-up resolution (ψ pass 5, 2026-06-10)

Executed the single pass-5 architecture-fit follow-up design-step (a
design-decision step: pin the transitive cascade's per-descendant
terminal-transition issue mechanism to the canonical dispatch boundary and
reconcile it with the D4/D20 atom-CAS atomicity basis). Completable now — no
blocker. Premises re-confirmed from the established decisions before deciding:
the cascade enumeration is a pure canonical-`:state*` read (D3/D14); each
per-descendant `:cancelled` signal is a pure `:state*` transition (D2/D4); D18's
re-entrant `:runtime/dispatch-event` exists only because the record-drop is a pure
transition forced *after* an effect (D17 apply-before-effects); D20's atomicity is
the apply-phase atom CAS with the terminal guard inside the `:root-state-update` fn.

Resolution written to design.md as "Transitive-Cascade Re-Dispatch Reconciliation
(ψ pass 5)" D23, with D3 and D14 updated:

- D23 — chose option (a): the cascade is a **single multi-run apply-phase
  `:root-state-update`** over the cascade set (cancelled run ∪ non-terminal
  `:delegating-run-id` descendants, D14) within the **one parent-cancel dispatch**;
  each run's terminal-status guard lives inside the one `:root-state-update` fn, so
  the entire subtree terminalization rides **one atom CAS** — D20's single-run
  atomicity generalised, strictly stronger than option (b)'s N independent CASes.
  Cancellation effects (single top-level `future-cancel` iff top-level cancel —
  D14/D19; one `:runtime/agent-abort` per in-flight descendant attempt — D15; D13
  terminalize) are emitted as the cancel dispatch's effect set through the
  `:effects` interceptor (D1/D12). No per-descendant re-dispatch; no command-layer
  loop / cross-handle reach-in.
- Rejected option (b) (N re-entrant `:runtime/dispatch-event` cancel dispatches):
  the cascade's per-descendant signals are **pure `:state*` transitions with no
  after-effect ordering constraint**, so they compose into one apply-phase update
  fn — re-dispatch buys nothing and only multiplies dispatches/event-log
  entries/CASes while complicating ordering vs the parent's own terminal transition
  + the D14 single top-level `future-cancel`. The D18 re-entrant dispatch is needed
  *only* for the record-drop, which (unlike the cascade signals) must be sequenced
  *after* the terminalize effect (D17) — different ordering constraint, different
  answer. Stated as the D18 cross-ref.
- Enumeration-race bound stated: a descendant terminal-by-apply is a per-run in-fn
  guard no-op + its already-computed `:runtime/agent-abort` is no-op'd at execute
  time by the D22.2 live-attempt re-check; a descendant spawned after enumeration is
  bounded by D6/D2/D10 (once the parent is `:cancelled` the cooperative checkpoint
  refuses to advance/spawn), so the handler-before enumeration snapshot is
  sufficient — no re-enumeration loop.

Consistency check: D23 is consistent with D1/D12 (canonical `:runtime/*` effects
through the `:effects` interceptor), D3/D14/D15 (downward cascade, single top-level
future target, per-attempt abort), D4/D20 (atom-CAS atomicity, guard inside the
update fn — now multi-run), D18 (re-entrant dispatch reserved for the
ordering-forced record-drop), and D22.2 (execute-time idempotency for the
concurrent-CAS race). No step-machine redesign; cancellation effect set unchanged;
no new contradictions introduced.

## Ambiguity review (ψ pass 5, 2026-06-10)

Fresh ambiguity pass after D21–D23. The cancellation contracts are thoroughly
pinned: directly-cancelled run, top-down propagation, cancel-then-remove, direct
sub-run cancel/remove, the multi-run cascade, and idempotency are all
single-interpretation (D1–D23). One **new** actionable ambiguity: the **Acceptance
Criteria** section is stale relative to D14/D19/D21/D22 — it predates the
direct-sub-run + idempotency decisions, so the test / definition-of-done surface no
longer matches the pinned contracts.

1. **Criterion #2 asserts a universal post-condition that holds only for a
   top-level run.** "`remove` of a live run … future is cancelled" reads as the
   general contract for any live run, but D14/D19/D21 establish that a live
   **nested sub-run** remove emits **no** worker `future-cancel` (a sub-run owns no
   future); its guarantee is instead child-turn abort + the parent observing
   run-absence ≡ `:cancelled` and continuing (D21). The criterion does not qualify
   run-type, so an implementer deriving the test cannot tell whether "a live run"
   includes a sub-run — and "future is cancelled" is false there. (Noted but
   explicitly not filed by the pass-4 inconsistency review; it is a genuine
   acceptance-contract ambiguity.)

2. **No criterion covers the Evidence-step-2 motivating cases.** The acceptance
   criteria omit: direct cancel of a nested sub-run → parent observes a failed
   delegate step and **continues, not halted** (D19); direct remove of a live
   sub-run → run-absence treated identically to `:cancelled` (D21); and
   repeated/concurrent terminal-request **idempotency** → a no-op'd terminal request
   emits no cancellation effects (D22.1) with execute-time-idempotent effects on the
   concurrent-CAS race (D22.2). Evidence step 2 was precisely a *direct sub-run*
   cancel, yet the acceptance/test surface that derives the tests does not require
   that behaviour — so "done" is under-specified for the exact cases the heaviest
   refinement pinned.

These are acceptance-contract (definition-of-done / test-surface) ambiguities, not
step-machine redesigns. Prior passes treated acceptance reconciliation as in-scope
for ambiguity review (D6 updated acceptance #3/#4), so this fits the profile.

## Ambiguity follow-up resolution (ψ pass 5, 2026-06-10)

Executed the single pass-5 ambiguity follow-up design-step (reconcile the
Acceptance Criteria with D14/D19/D21/D22). A design-decision step (rewrite the
acceptance/test surface in design.md); completable now — no blocker. All referenced
decisions already exist (D14, D19, D21, D22), so this was pure reconciliation, no
new contract pinned.

Rewrote the Acceptance Criteria section into 10 numbered criteria, grouped
(top-level / transitive nested / Evidence-step-2 direct / idempotency / build), each
explicitly tagged **[guaranteed]** (definition-of-done, derive a test) or
**[out-of-test-scope]** (true-concurrency race, harmlessness by construction):

- (a) **Criterion #2 qualified to a top-level run** — the worker `future-cancel`
  target is the single top-level run only (D14); added **#5** as the nested sub-run
  remove variant whose guarantee is child-turn abort + parent observing run-absence
  ≡ `:cancelled` + continuing, **no** worker future-cancel (D14/D19/D21).
- (b) Added Evidence-step-2 direct cases: **#6** direct cancel of a nested sub-run →
  parent observes a failed delegate step and **continues, not halted** (D19); **#7**
  direct `remove` of a live sub-run → run-absence ≡ `:cancelled`, race-independent
  (D21); **#8** sequential terminal idempotency → no cancellation effects emitted +
  record-drop still applies [guaranteed] (D22.1); **#9** concurrent-CAS execute-time
  idempotency [out-of-test-scope] (D22.2, narrow non-reproducible race).
- #4 now also states the no-per-sub-run-future-cancel + single multi-run
  apply-phase transition (D14/D23); #10 keeps the build gates.

Consistency check: the rewritten criteria are consistent with D14 (single top-level
future), D19/D21 (parent continues on failed delegate step / run-absence), D22
(sequential gate vs concurrent execute-time idempotency), and D23 (multi-run
apply-phase cascade). No new contract introduced; only the test/definition-of-done
surface made unambiguous. No step-machine redesign.

## Inconsistency review (ψ pass 6, 2026-06-10)

Fresh internal-consistency pass over design.md after D21–D23, checking the
cancel-then-remove record-drop wording against the actual `remove-run` /
`inflight-runs` code. D1–D23 reconciliations stand. One **new** actionable
contradiction around the `inflight-runs` runtime-handle entry drop:

1. **D17 step 2 (+ Acceptance #2) attribute the `inflight-runs` entry drop to the
   pure `remove-run` dissoc, contradicting D1/D2 + code.** D17 step 2 says the
   remove dispatch "applies the pure `remove-run` dissoc, dropping the canonical
   run record **and its `inflight-runs` entry**"; Acceptance #2 likewise requires
   "its `inflight-runs` entry is cleared … (cancel-then-remove, D5/D17)." But
   code-confirmed: pure `remove-run` (`workflow-runtime/core.clj:217`) dissocs
   **only** canonical `:state*` (`runs-path` + `run-order-path`) — it never touches
   `inflight-runs`. `inflight-runs` is a separate `defonce` runtime-handle atom
   (`runtime_state.clj:11`) whose entry is dropped by a **distinct command-layer
   side effect** `(swap! inflight-runs dissoc run-id)` (`workflow/core.clj:493`),
   after the mutation. This contradicts (a) D2 ("`inflight-runs` and the worker
   future stay a **pure runtime handle**" — not the canonical `:state*` a pure
   transition mutates) and (b) D1 (pure transitions perform **no** side effects;
   runtime-handle mutations flow as effects-as-data executed at the boundary). A
   pure apply-phase `remove-run` cannot drop a runtime-handle entry, yet the
   design's cancellation effect set (D12/D23: worker `future-cancel`,
   `:runtime/agent-abort`, `:runtime/mark-workflow-jobs-terminal`, the re-entrant
   `:runtime/dispatch-event`) defines **no** effect to clear the `inflight-runs`
   entry. So the remove flow's required `inflight-runs` entry-drop is an unmodeled
   runtime-handle side effect: either it needs its own canonical `:runtime/*`
   cleanup effect in the remove dispatch's effect set (parity with the
   `future-cancel` effect that already reaches `inflight-runs` via `ctx`), or
   D17/Acceptance #2 must stop folding it into the pure `remove-run` `:state*`
   dissoc. As written the design both (i) misstates what `remove-run` does and
   (ii) leaves the handle entry-drop without an effects-as-data mechanism — an
   implementer cannot tell where/how the `inflight-runs` entry is cleared.

This is a design-vs-artifact + internal (D17/Acceptance-#2 ⟷ D1/D2) inconsistency;
the fix is to assign the `inflight-runs` entry-drop an explicit
effects-as-data mechanism (or correct the wording), not a step-machine redesign.

## Inconsistency follow-up resolution (ψ pass 6, 2026-06-10)

Executed the single pass-6 inconsistency follow-up design-step (a design-decision
step: assign the remove-flow `inflight-runs` entry-drop an effects-as-data
mechanism, or correct the wording). Completable now — no blocker. Code premises
re-confirmed before deciding:

- Pure `remove-run` (`components/workflow-runtime/.../core.clj:217`,
  `state → [state', run]`) dissocs **only** canonical `:state*` (`runs-path` +
  `run-order-path`); it never touches `inflight-runs`.
- `inflight-runs` is a separate `defonce` runtime-handle atom
  (`agent-session/workflow/runtime_state.clj:11`); its remove-flow entry is dropped
  by a command-layer `(swap! inflight-runs dissoc run-id)`
  (`agent-session/workflow/core.clj:493`, `delegate-remove`).
- No existing dispatch effect drops `inflight-runs` entries
  (`dispatch_effects.clj` has no such `:runtime/*`); the natural-completion handle
  cleanups live in `orchestration.clj` (lines 153/191/248/272) as worker-thread
  `swap!`s — the handle owner's own bookkeeping.

Resolution written to design.md as "Runtime-Handle Cleanup Reconciliation (ψ pass
6)" D24, with D17 step 2, D5 step 3, Acceptance #2, and the Scope effects bullet
updated:

- D24 — chose **option (a)**: the `inflight-runs` entry-drop is its own canonical
  `:runtime/drop-inflight-run` cleanup effect (parity: `effect-schema` +
  `execute-effect!`, dissoc via the `ctx` handle), emitted in the remove dispatch's
  (D17 dispatch 2) effect set and run by the `:effects` interceptor — parity with
  the D12 worker `future-cancel` effect that already reaches `inflight-runs` via
  `ctx`. Within the remove dispatch the pure `remove-run` dissoc (apply phase)
  drops the **canonical record** and the cleanup effect (effects phase) drops the
  **handle entry** — two distinct stores per the D2 signal/handle split. Cross-
  dispatch ordering: the handle-drop (dispatch 2) runs strictly after the cancel
  dispatch's (dispatch 1) `future-cancel` (which reads the future from
  `inflight-runs` via `ctx`) → drop-after-cancel, never drop-then-orphan (the exact
  Evidence-step-3 orphaning). Idempotent dissoc (D22.2) tolerates a prior worker
  natural-completion cleanup.
- Option (b) (re-label D17/Acceptance to the command-layer `swap!`) rejected: it
  perpetuates an off-dispatch handle side effect (un-trimmed on replay,
  trace-invisible) — the very boundary the task moves cancellation/cleanup away
  from (D1/D12/D23).
- Scope precision: only the **remove-flow** entry-drop is in scope; the existing
  natural-completion `orchestration.clj` handle cleanups are out of scope (not part
  of the cancellation effect set).

Consistency check: D24 is consistent with D1 (effects-as-data, no inline handle
side effects), D2 (`inflight-runs` ∈ runtime handle, distinct store from canonical
`:state*`), D12 (canonical `:runtime/*` effects, parity, executed by the `:effects`
interceptor, replay-trimmed/traced), and D17/D18 (two-dispatch ordering;
future-cancel in dispatch 1, handle-drop in dispatch 2). No new contradictions; no
step-machine redesign; the cancellation effect set gains one cleanup effect on the
existing dispatch-effect pathway.

## Architecture-fit review (ψ pass 7, 2026-06-10)

Fresh architecture-fit pass over design.md against AGENTS.md VSM (S1 effects /
S3 dispatch, `λ parity`, `λ(state)`, `λ shims_adapters`), META.md (managed services
keyed on ctx, ¬extension-local hidden state), doc/architecture.md (State boundary,
dispatch sequencing, replay-trim, dispatch trace). D1–D24 commit the effects-as-data
boundary, signal/handle split, multi-run cascade, two-dispatch cancel-then-remove,
atom-CAS atomicity, and the `:runtime/drop-inflight-run` cleanup effect (D24). Those
all fit.

One **new** actionable misfit (the D12/D24 "via ctx" handle-reachability premise is
code-false and uncommitted):

1. **Cancellation/cleanup effect handles reached "via ctx" — but `inflight-runs` is
   a process-global `defonce` atom, not on ctx.** D12 and D24 both assert the new
   worker `future-cancel` and `:runtime/drop-inflight-run` `execute-effect!` methods
   reach "the `inflight-runs` handle reached **via `ctx`**" ("which already reaches
   `inflight-runs` via `ctx`"). Code-confirmed this premise is false:
   `inflight-runs` is a free-standing `(defonce inflight-runs (atom {}))` in
   `runtime_state.clj:11`, aliased in `workflow/core.clj:31`, and never placed on the
   dispatch `ctx` (absent from `context.clj`; it is only passed as a plain arg in
   local option maps to orchestration fns). Every existing `:runtime/*` handler that
   touches workflow runtime state reaches it through a **ctx-injected fn/handle**
   wired in `context.clj` — e.g. `:runtime/mark-workflow-jobs-terminal` →
   `((:mark-workflow-jobs-terminal-fn ctx) ctx)` (`dispatch_effects.clj:191`,
   `context.clj:248`); `:runtime/agent-abort` keys off `(effect-session-id ctx …)`.
   So the project's `:runtime/*` effect pattern is dependency-injection-through-ctx,
   not direct namespace-global access. The design therefore leaves the new
   cancellation/cleanup effects on an unstated, code-contradicted wiring: it neither
   (a) commits to threading `inflight-runs` onto the dispatch `ctx` (a `context.clj`
   injection change, in scope for the new effect handlers to honor the asserted "via
   ctx" parity), nor (b) explicitly justifies the new handlers reaching the `defonce`
   global directly. Direct-global access (b) diverges from the ctx-injection parity
   of every other `:runtime/*` handler and is exactly the **extension-local hidden
   state** META.md cautions against ("managed services keyed by logical identity …
   reused within ctx rather than extension-local hidden state"), coupling the new
   effects to a process-global atom (replay/test-isolation hazard) — undercutting the
   D12/D24 parity + replay-closure rationale. Architectural-fit gap: the
   handle-reachability mechanism for the D12/D24 cancellation/cleanup effects is
   under-committed and rests on a false "via ctx" premise. Actionable — file as a
   pass-7 architecture-fit follow-up. (AGENTS.md S1 effects / `λ parity` /
   `λ(state)`; META.md managed-services-on-ctx; design.md D1/D2/D12/D24)

No other new actionable architectural-fit misfit found; D1–D24 cover the remaining
boundary commitments.

## Architecture-fit follow-up resolution (ψ pass 7, 2026-06-10)

Executed the single pass-7 architecture-fit follow-up design-step (a
design-decision step: commit a handle-reachability mechanism for the D12 worker
`future-cancel` and D24 `:runtime/drop-inflight-run` effects, reconciling the
code-false "via ctx" premise). Completable now — no blocker. Code premises
re-confirmed before deciding:

- `inflight-runs` is a free-standing `(defonce inflight-runs (atom {}))`
  (`agent-session/workflow/runtime_state.clj:11`, aliased `workflow/core.clj:31`)
  and is **absent from `context.clj`** — never placed on the dispatch `ctx`.
- Every existing `:runtime/*` handler reaches workflow runtime state through a
  **ctx-injected fn/handle** wired in the `context.clj` ctx map — e.g.
  `:mark-workflow-jobs-terminal-fn bg-rt/maybe-mark-workflow-jobs-terminal!`
  (`context.clj:248`), invoked as `((:mark-workflow-jobs-terminal-fn ctx) ctx)`
  (`dispatch_effects.clj:191`); `:runtime/agent-abort` keys off
  `(effect-session-id ctx effect)`. The pattern is dependency-injection-through-`ctx`,
  not direct namespace-global access.

Resolution written to design.md as "Handle-Reachability Reconciliation (ψ pass 7)"
D25, with D12 and D24 annotated:

- D25 — chose **option (a)**: thread `inflight-runs` onto the dispatch `ctx` via a
  `context.clj` injection (e.g. `:workflow-inflight-runs-handle
  runtime-state/inflight-runs`); the D12 worker `future-cancel` and D24
  `:runtime/drop-inflight-run` `execute-effect!` methods read
  `(:workflow-inflight-runs-handle ctx)` with parity to every other `:runtime/*`
  handler, making the asserted "via ctx" premise true. The `context.clj` injection
  is in scope for the new effect handlers.
- Rejected **option (b)** (direct `defonce` global reach-in as a documented
  exception): diverges from the ctx-injection parity of every other `:runtime/*`
  handler (`λ parity`/`λ(state)`), is the extension-local-hidden-state pattern
  META.md cautions against (managed services keyed on `ctx`, ¬extension-local hidden
  state), and couples the effects to a process-global atom — a replay/test-isolation
  hazard that undercuts the D12/D24 parity + replay-closure rationale. Option (a) is
  also the one-line, simpler fit (`λ build` simple > complex) and gives tests/replay
  a `ctx` seam to substitute an isolated handle.
- Scope note in D25: the injected handle is still backed by the same
  `runtime-state/inflight-runs` atom in production (the out-of-scope
  natural-completion `orchestration.clj` cleanups keep mutating that atom directly);
  `ctx` injection only adds the dispatch-side reach-path + the test/replay seam.
- D12/D24 "reached via `ctx`" / "supplies the handle through `ctx`" wording
  annotated with the D25 pointer so the premise is now true.

Consistency check: D25 is consistent with D1/D12 (effects-as-data, canonical
`:runtime/*` effects via the `:effects` interceptor), D2 (`inflight-runs` ∈ runtime
handle), D24 (the `:runtime/drop-inflight-run` cleanup effect), and `λ parity`
(parity with every other ctx-injected `:runtime/*` handler). No new contradictions;
no step-machine redesign; the cancellation/cleanup effect set is unchanged (only its
handle reach-path is committed).

## Ambiguity review (ψ pass 8, 2026-06-10)

Fresh ambiguity pass over design.md (D1–D25 + Scope/Desired/Acceptance). The
design is mature; three new actionable ambiguities found, none covered by the
existing pass-1..7 follow-ups.

1. **D17 "same worker thread" conflicts with D20/D21 on which thread runs the
   cancel/remove dispatches.** D17 says the two-dispatch ordering holds because the
   re-entrant remove dispatch is "executed synchronously on the **same worker
   thread**, in the cancel dispatch's `:effects` phase." But D20 says "the same
   thread" (neutral) and D21 says "the remove dispatch runs on the
   **operator/command thread**, concurrently with the parent worker thread parked
   in the sub-run's `send-and-drain` deref." Code-confirmed: the operator-initiated
   cancel/remove path runs on the command/tool-execution thread —
   `delegate-remove` (`workflow/core.clj:474`) and `cancel-run`
   (`psi_tool_workflow.clj:227` / `canonical_workflows.clj:220`) are invoked from
   the agent tool dispatcher, **not** the workflow worker (the
   `clojure-agent-send-off-pool` thread parked on `send-and-drain`). So D17's
   "worker thread" qualifier is wrong/ambiguous: the in-thread sequencing holds on
   the **dispatch-invoking (command/operator) thread**, which is generally **not**
   the workflow worker. An implementer reading D17 literally could try to run the
   remove dispatch on the worker thread (which is parked/being interrupted —
   impossible). Actionable: correct D17 to say "the same dispatch-invoking
   (command/operator) thread" and align with D20/D21.

2. **Entry-event taxonomy for cancel vs cancel-then-remove vs plain-remove is
   unspecified.** The design pins the *effect set* precisely (cancel dispatch emits
   effect (3) the re-entrant remove only for a remove; plain cancel omits it —
   D18) but never states the *event/handler entry structure*: code-confirmed two
   distinct existing mutations, `psi.workflow/cancel-run`
   (`canonical_workflows.clj:220`) and `psi.workflow/remove-run` (via
   `delegate-remove`). For a `remove` of a **live** run (D5 cancel-then-remove), the
   design does not say whether (a) the `remove-run` handler itself produces the
   `:cancelled` transition + cancellation effects + the chained re-entrant
   `remove-run` dispatch (liveness branch in the `remove-run` handler-before, the
   bare dissoc on the re-entrant/terminal pass), or (b) the `remove` command
   dispatches the existing `cancel-run` event first and chains a `remove-run`
   dispatch — and where the live-vs-terminal branch lives (handler-before vs
   command layer, the latter in tension with D18's rejection of command-layer
   orchestration). This determines dispatch count, event-log shape, and whether the
   cancel-transition+cancellation-effect logic is shared (one helper) or duplicated
   across the `cancel-run` and `remove-run` handlers. Actionable: pin the
   entry-event taxonomy and the owner of the shared cancel-transition logic.

3. **D23 enumeration-race bound is argued only for top-level cancel; the
   no-worker-interrupt direct sub-run cancel case is unaddressed.** D23's
   enumeration-race bound ("a descendant spawned after enumeration is bounded by
   D6/D2/D10: once the parent run is `:cancelled`, the cooperative pull checkpoint
   refuses to advance/spawn") relies on the **cancelled run's own** checkpoint
   stopping further spawns, and for a top-level cancel the single
   `future-cancel(true)` (D14) additionally interrupts the whole synchronous stack.
   For a **direct sub-run cancel** (D19) **no** worker `future-cancel` is emitted.
   So a deeper descendant turn/child-session spawned in the window between the D23
   handler-before enumeration and the worker reaching its next checkpoint is
   neither in the cascade set (so not D15-aborted) **nor** interrupted (no worker
   future-cancel) — the worker parks on its `send-and-drain` and the just-spawned
   child turn runs to natural completion. D6's stated **guarantee** "no further
   child session spawns after the cancel checkpoint" is stronger than D6's "physics"
   exception (which is scoped to a single in-syscall-flight tool call, not a whole
   spawned child session). The design does not say whether this direct-sub-run-cancel
   spawn race upholds the D6 no-new-child-session guarantee or is an accepted
   true-concurrency exception (like D22.2/criterion #9). Actionable: state whether
   the D23 enumeration-race bound holds for the direct sub-run cancel (no worker
   interrupt) case — and if it is an accepted true-concurrency race, classify it
   explicitly (analogous to D22.2/#9) rather than implying the D6 guarantee.

No other new actionable ambiguity found; D1–D25 + Scope/Acceptance otherwise pin a
single contract per behaviour.

## Ambiguity follow-up resolution (ψ pass 8, 2026-06-10)

Executed all three pass-8 ambiguity follow-up design-steps. All were
design-decision/correction steps (correct a thread-attribution misnomer; pin the
entry-event taxonomy; reconcile the direct-sub-run-cancel spawn race); all
completable now — no blockers. Code premises re-verified before deciding:

- Both cancel/remove mutations are operator-initiated Pathom mutations that
  `reset!` `:state*` after a guard (`canonical_workflows.clj:217`
  `cancel-workflow-run`, `:244` `remove-workflow-run`), and `delegate-remove`
  (`workflow/core.clj:474`) additionally does the command-layer
  `(swap! inflight-runs dissoc run-id)` (`:493`). These run on the agent
  tool-dispatch (operator/command) thread, distinct from the workflow worker
  (`clojure-agent-send-off-pool`) thread parked on `send-and-drain`.

Resolutions:

- **Item 1 (D17 thread misnomer)** — fixed inline in D17's in-thread-sequencing
  paragraph: the re-entrant `:runtime/dispatch-event` remove dispatch runs on the
  **dispatch-invoking (operator/command) thread**, not the workflow worker thread
  (the worker is the *target* of the `future-cancel(true)` interrupt, never the
  *runner* of the cancel/remove dispatches). Aligned with D20 ("the same thread")
  and D21 ("the operator/command thread"). Prevents an implementer running the
  remove dispatch on the parked/interrupted worker.

- **Item 2 (entry-event taxonomy)** — new D26. Option (a): the **`remove-run`
  handler itself** owns cancel-then-remove (option (b) — command dispatches
  `cancel-run` first / a cancel-run "then-remove" flag — rejected as command-layer
  orchestration / flag both contradicting D18). Two entry events: `cancel-run` =
  shared cancel-transition helper (no re-entrant remove); `remove-run` live
  first-pass = same helper + re-entrant `:runtime/dispatch-event` (no dissoc),
  re-entrant/terminal second-pass = bare unconditional dissoc +
  `:runtime/drop-inflight-run` (no cancellation effects). (c) live-vs-terminal
  branch = the `remove-run` handler-`:before` D22.1 terminal-precondition gate
  (not command layer). (d) **one shared** cancel-transition+effect helper across
  both handlers (not duplicated). D5/D17/D18 annotated with D26 pointers.

- **Item 3 (direct-sub-run-cancel spawn race)** — new D27. The D6 "no new child
  session after the checkpoint" guarantee **holds for the cascade set** via per-run
  cooperative checkpoints (D2/D10) + per-attempt aborts (D15) — child-abort, not a
  worker interrupt, is the sub-run wake mechanism (D19); emitting `future-cancel`
  would violate the D14/D19 parent-survival invariant. The residual
  post-enumeration spawn is **one bounded, accepted true-concurrency exception** of
  the same class as D22.2 / criterion #9 (the spawn's own checkpoint reads the
  pre-effect-committed `:cancelled` signal under apply-before-effects and refuses;
  window closes when the abort returns control; momentary turn self-terminates —
  never an unbounded runaway). D6 restated for the sub-run case; D14's
  `future-cancel` reframed as the top-level promptness mechanism (not a subtree
  prerequisite); Acceptance #9a [out-of-test-scope] added.

Consistency check: D26 is consistent with D5/D17/D18 (cancel-then-remove two
dispatches, re-entrant `:runtime/dispatch-event`, no command-layer orchestration),
D22.1 (handler-before gate reused as the live-vs-terminal selector; record-drop
unconditional), and D23 (shared multi-run cancel transition). D27 is consistent
with D2/D6/D10/D14/D15/D19/D23 and reuses the D22.2 accepted-race classification.
No new contradictions; no step-machine redesign; the cancellation effect set is
unchanged.

## Inconsistency review (ψ pass 9, 2026-06-10)

Fresh internal-consistency pass over design.md (full read, D1–D27 + Scope/Desired/
Acceptance) targeting design-vs-design contradictions surviving the prior passes.
D1–D27 reconciliations hold. One **new** actionable contradiction — a residual
"serialized" qualifier surviving D20:

1. **Residual "serialized dispatch transition" wording (Desired Behaviour + D13)
   contradicts D20's "dispatch is not serialized."** D20 establishes
   `dispatch!` "does **not** serialize against concurrent threads (no global
   lock)"; the run-`:status` terminal transition's atomicity/identity is the
   apply-phase atom CAS with the guard inside the `:root-state-update` fn, and D20's
   directive reinterprets "serialized (single-writer) dispatch" everywhere. But
   three writer-identity sentences still literally call the transition *serialized*:
   - Desired Behaviour (line 82): "the run's own `:status` is written by the **D4
     serialized dispatch transition**";
   - D13 "Two distinct writers" (line 522): "written by the **D4 serialized
     dispatch terminal transition**";
   - D13 "Concretely" (line 529): "the cancel dispatch terminal transition (**D4,
     serialized single-writer**)".
   D20's pass-3 resolution claimed to align D13's "serialized"/"single-writer"
   phrasing, yet the literal qualifiers persist, and Desired Behaviour (82) was
   never in D20's stated alignment scope (D20 listed D4/D13/D16/D17 only). An
   implementer reading Desired Behaviour or D13 without back-referencing D20 reads a
   direct contradiction (transition described as "serialized" against a no-lock
   dispatch). The fix is to strip/correct the "serialized" qualifier in these three
   spots to D20's atom-CAS basis (the transition routes through dispatch and is the
   single *logical* writer of run `:status`, but is **not** serialized) — not a
   step-machine redesign or a change to the cancellation mechanism.

No other new actionable internal contradiction found; D1–D27 + the code premises
(re-confirmed accurate in prior passes) otherwise hold a single consistent contract.

## Inconsistency follow-up execution (ψ pass 9 step, 2026-06-10)

Executed the single unchecked design-step from the ψ pass 9 inconsistency review:
stripped the residual "serialized" qualifier from the three run-`:status`
writer-identity sentences, replacing each with D20's atom-CAS basis (single
*logical* writer of run `:status`; atomicity from the apply-phase atom CAS with the
guard inside the `:root-state-update` fn — not dispatch serialization).

- Desired Behaviour (~line 82): "D4 serialized dispatch transition" → D20 phrasing.
- D13 "Two distinct writers" (~line 522): "D4 serialized dispatch terminal
  transition (the single writer of *run* status)" → D20 phrasing.
- D13 "Concretely" (~line 529): "(D4, serialized single-writer)" → D20 phrasing.

Verified remaining "serialized" occurrences are out of scope (and not
writer-identity contradictions): line 269 already marks the phrase superseded by
D20; D17's "two serialized dispatches" (686/711/727/809) names in-thread
*sequencing/ordering* (the cancel-then-remove split), reconciled by D20's
in-thread-sequencing note, not the race-safety single-writer claim; 917/928/960/985
are D20's own reconciliation prose. No step-machine or mechanism change. No blocker.

## Architecture-fit review (ψ pass 10, 2026-06-10)

Fresh architecture-fit pass over design.md after D26/D27, consulting AGENTS.md VSM
(S1 effects / S3 dispatch, effects-as-data, replay, `λ parity`, `λ extend`,
`λ shims_adapters`), META.md (managed services on ctx, no hidden process-global
reach-in), and doc/architecture.md (State boundary, dispatch sequencing,
replay-trim, dispatch trace).

No new actionable architectural-fit misfit found.

The current design fits the project architecture:

- cancellation/cleanup side effects are canonical dispatch `:runtime/*` effects with
  schema/executor parity, replay trimming, and trace visibility (D1/D12/D24);
- the cancellation signal stays in canonical `:state*`, while futures and
  `inflight-runs` remain runtime handles reached through ctx injection (D2/D25);
- the cascade is owned by the agent-session dispatch boundary, avoids command-layer
  loops/reach-in shims, and uses one multi-run apply-phase update with D20 atom-CAS
  guards (D3/D20/D23);
- cancel-then-remove uses effects-as-data re-entrant dispatch only where the
  apply-before-effects ordering requires it, with terminalization before record drop
  and handle cleanup after future cancel (D17/D18/D24/D26);
- direct nested sub-run cancel/remove preserves parent authority and reuses existing
  delegate failure semantics rather than introducing a result-delivery shim
  (D19/D21/D26);
- D27's direct-sub-run spawn-race treatment is explicitly bounded and classified as
  the same true-concurrency construction-review class as D22.2, without changing the
  effects boundary or introducing hidden state.

No new design-steps.md follow-up item added.

## Ambiguity review (ψ pass 11, 2026-06-11)

Fresh ambiguity pass over design.md (D1–D27 + Scope/Acceptance), consulting the
relevant dispatch/effect code and doc/architecture.md. Four new actionable
ambiguities found; none duplicate existing design-steps.

1. **Workflow-cancellation `:runtime/agent-abort` liveness recheck is underspecified.**
   D15 emits the existing `:runtime/agent-abort` keyed by `:session-id` (the
   in-flight attempt's `:execution-session-id`), while D22.2 requires
   `:runtime/agent-abort` to re-read the D15 live-attempt predicate from canonical
   run state at execute time. The effect payload/read rule is not pinned: with only
   `:session-id`, an implementer must guess whether to carry guard metadata
   (`run-id`/`step-id`/attempt id/expected execution-session-id), scan canonical
   runs by `:execution-session-id`, or apply the guard to every existing
   `:runtime/agent-abort` use. Existing non-workflow abort emissions (e.g.
   statechart `:on-abort`) also lack workflow run context, so the design must state
   how workflow-specific execute-time idempotency composes with the reused generic
   abort effect.

2. **Idempotent terminal/absent API result semantics are unclear.** D4/D20/D22 say
   repeated terminal requests are no-op/idempotent and D26 routes terminal/absent
   `remove-run` to bare record-drop + cleanup, but the public mutation outputs have
   `:error` / `:removed?` fields and current code errors on terminal cancel. The
   design does not state what `cancel-run` returns for already-terminal/absent runs,
   or what `remove-run` returns for absent/terminal runs, while still emitting no
   cancellation effects. Implementers/test authors need the public success/error
   contract, not only the state/effect contract.

3. **"No new side effects / journal writes" conflicts with required cancellation
   bookkeeping unless scoped.** D6 and Acceptance #3 forbid new side effects after
   the cancel checkpoint, explicitly including journal writes. But the chosen design
   requires cancellation-control effects and writes: `:cancelled` state, background
   job terminalization, `:runtime/agent-abort` / session-abort consequences, possible
   interruption/tool-result records, and `inflight-runs` cleanup. The design needs a
   crisp distinction between forbidden child-work side effects (new tool calls,
   commits, ordinary child-turn journal writes) and allowed/required cancellation
   bookkeeping, otherwise tests can read Acceptance #3 as forbidding the abort path
   the design mandates.

4. **"Cancel checkpoint" is not a single testable boundary.** Acceptance #1/#3 and
   D6/D7/D27 assert behaviour "after the cancel checkpoint", but the design does
   not define whether that checkpoint is the cancel request, the apply-phase CAS
   that writes `:cancelled`, interrupt delivery, or the worker's cooperative read
   that observes `:cancelled`/run-absence. This matters for attempts or child
   sessions started in the request→CAS→interrupt→read window, especially in the
   direct sub-run case. The term should be defined once and applied consistently to
   top-level and nested-run acceptance tests.

No other new actionable ambiguity found; the remaining D1–D27 contracts otherwise
choose single behaviours.

## Ambiguity follow-up resolution (ψ pass 11, 2026-06-11)

Executed all four newly-added unchecked pass-11 design follow-ups. Each was a
completable design-clarification item; no blockers.

- D28 pins workflow-cancellation `:runtime/agent-abort` execute-time idempotency:
  workflow-cancel abort effects carry guard metadata (`run-id`, `step-id`,
  `attempt-id`, expected `execution-session-id`) in addition to `:session-id`; the
  executor re-reads canonical run state and aborts only if the guarded latest
  attempt is still live. Existing non-workflow abort emissions omit the guard and
  keep session-id-only behaviour. D12/D15/D22.2 updated.
- D29 defines public result semantics for terminal/absent `cancel-run` and
  `remove-run`: terminal/absent idempotency is reported as success/no-op rather
  than `already terminal` / `not found` errors; live remove reports successful
  cancel-then-remove; terminal remove still performs bare record drop without
  cancellation effects. Acceptance criteria updated.
- D30 scopes "no new side effects" to forbidden ordinary workflow/child-turn
  advancement (new step attempts, sub-runs, child sessions, tool calls, commits,
  ordinary child-turn journal writes), while allowing required cancellation-control
  writes/effects (`:cancelled`, job terminalization, abort/interruption records,
  dispatch trace, re-entrant remove, handle cleanup). D6 and Acceptance #3 updated.
- D31 defines "cancel checkpoint" as the apply-phase CAS that commits
  `:status :cancelled` (the D23 multi-run CAS for cascades), not request arrival,
  interrupt delivery, or the worker's later read. Acceptance #1/#3/#4/#6 updated to
  use this testable boundary; D27's bounded direct-sub-run spawn race remains the
  explicit out-of-test-scope exception.

## Inconsistency review (ψ pass 12, 2026-06-11)

Fresh internal-consistency pass over design.md after D28–D31, checking D28's
`:runtime/agent-abort` schema/executor contract against the dispatch validation
order and existing abort emitters. One new actionable contradiction found:

1. **D28 makes `:session-id` required for `:runtime/agent-abort`, but existing
   unguarded abort effects omit it and validation runs before effect-time session-id
   injection.** D28 says the `:runtime/agent-abort` effect schema keeps
   `:session-id` required while adding optional workflow guard keys, and also says
   existing non-workflow abort emissions omit the workflow guard and keep current
   session-id-only behaviour. Code-confirmed: `:on-abort` emits
   `{:effect/type :runtime/agent-abort}` with no `:session-id`
   (`dispatch_handlers/statechart_actions.clj`), and the state-kernel
   `effect-interceptor` injects the dispatching `:session-id` into effects only
   when effects execute. But dispatch validation runs before the effect interceptor
   (`:apply → :validate → :trim-effects-on-replay → :effects`), so an
   `effect-schema` that requires `:session-id` would reject the existing unguarded
   abort effect before injection. This contradicts D12's validate-interceptor parity
   and D28's "existing non-workflow aborts keep current behaviour." The design must
   choose either (a) keep `:session-id` optional in the schema for unguarded aborts
   that rely on effect-interceptor injection, while requiring it only for guarded
   workflow-cancel abort payloads or documenting the injected-session path, or (b)
   require all abort emitters (including `:on-abort`) to include `:session-id`
   before validation. As written, the D28 schema requirement is unbuildable without
   breaking existing abort effects.

No other new actionable inconsistency found in D28–D31; D29–D31 align with the
acceptance criteria and D22/D30 boundaries.

## Inconsistency follow-up resolution (ψ pass 12, 2026-06-11)

Executed the single newly-added pass-12 design follow-up. The item was a
completable schema/validation-order design clarification; no blockers.

- D32 resolves D28's `:runtime/agent-abort` schema contradiction by choosing option
  (a): unguarded/non-workflow abort payloads keep `:session-id` optional at schema
  validation time because the `:effects` interceptor injects the dispatching
  session id after validation; guarded workflow-cancel aborts require explicit
  `:session-id`, `:expected-session-id`, and complete workflow guard metadata before
  validation. The executor keeps current session-id-only behaviour when no guard is
  present and applies the D28 liveness re-check only for guarded workflow-cancel
  payloads.
- D15 and D28 were updated so the explicit `:session-id` requirement applies only
  to workflow-cancellation abort emissions; existing `:on-abort`-style emissions
  remain valid before effect-time session-id injection.

## Architecture-fit review (ψ pass 13, 2026-06-11)

Fresh architecture-fit pass over design.md after D28–D32, consulting AGENTS.md VSM
(S1 effects / S3 dispatch, effects-as-data, replay, `λ parity`, `λ extend`,
`λ(state)`, `λ shims_adapters`), META.md (ctx-managed services / no hidden
process-global reach-in), and doc/architecture.md (State boundary, dispatch
sequencing, validation-before-effects, replay-trim, dispatch trace).

No new actionable architectural-fit misfit found.

The D28–D32 refinements fit the established project boundaries: guarded workflow
`agent-abort` payloads keep the reused canonical `:runtime/agent-abort` effect
rather than introducing a parallel abort path; the liveness re-check is scoped to
workflow-cancellation metadata while unguarded aborts preserve existing
session-dispatch-local behaviour; D32 reconciles schema parity with the actual
validation-before-effects order by keeping unguarded `:session-id` optional and
requiring it only for guarded workflow-cancel aborts; D29/D30/D31 clarify public
results, cancellation-control effects, and the apply-phase CAS checkpoint without
moving state/side effects outside dispatch.

No new design-steps.md follow-up item added.

## Ambiguity review (ψ pass 14, 2026-06-11)

Fresh ambiguity pass over design.md after D32, consulting the dispatch effect schema/order and existing `:runtime/dispatch-event` / `:runtime/agent-abort` shapes. Two new actionable ambiguities found:

1. **Workflow-cancel `:runtime/agent-abort` guard payload shape is reopened.** D28 defines a concrete flat payload (`:workflow-run-id`, `:workflow-step-id`, `:workflow-attempt-id`, `:expected-session-id`) and D15 cross-references that flat shape, but D32 says the schema may instead use a required nested `:workflow-abort-guard` map. Emitters, schema, executor, and tests need one canonical payload shape; otherwise flat and nested guarded aborts are both plausible interpretations.

2. **Absent `remove-run`: public no-op vs cleanup effect is not explicit.** D26 groups terminal/absent `remove-run` into the bare-dissoc + `:runtime/drop-inflight-run` cleanup branch, while D29/Acceptance #10 describe absent remove as success/idempotent no-op (`:removed? false`, `:found? false`, `:noop? true`). The design should state whether absent remove still emits `:runtime/drop-inflight-run` to clean a possible orphaned handle, and whether `:noop?` means no canonical record removed vs no effects at all.

No other new actionable ambiguity found; the remaining D1–D32 contracts otherwise choose single behaviours.

## Ambiguity follow-up resolution (ψ pass 14, 2026-06-11)

Executed both newly added pass-14 ambiguity follow-up design-steps. Both were
contract-clarification steps in design.md; both completable now — no blockers.
Resolutions:

- D33 — pinned workflow-cancellation `:runtime/agent-abort` guard payload to one
  canonical flat top-level key shape: `:session-id`, `:workflow-run-id`,
  `:workflow-step-id`, `:workflow-attempt-id`, and `:expected-session-id`.
  Rejected nested `:workflow-abort-guard`; D28/D32 schema/executor implications now
  require all-or-none flat guard keys for guarded workflow-cancel aborts while
  preserving optional `:session-id` for unguarded aborts.
- D34 — clarified absent `remove-run`: it returns the D29 success/no-op public
  shape and applies no canonical record removal / no cancel transition, but still
  emits only the idempotent D24 `:runtime/drop-inflight-run` cleanup to clear a
  possible orphaned handle. `:noop? true` means no canonical record was found or
  removed, not literal absence of effects. Acceptance #10 updated.

## Inconsistency review (ψ pass 15, 2026-06-11)

Fresh internal-consistency pass over design.md after D33/D34, focused on stale early
contract wording against the later nested-run and guarded-abort decisions. Two new
actionable contradictions found; neither duplicates existing design-steps:

1. **Generic live-remove wording still promises worker/future stop for nested
   sub-run removes.** D5 says "remove of a live run" and then "the
   `future-cancel` interrupt guarantees the worker stops," while Scope's test
   bullet says "`remove` of a live run does not leave a running future." But
   D19/D21 and Acceptance #5/#7 explicitly put direct live nested-sub-run remove in
   scope, emit **no** worker `future-cancel`, and require the shared parent worker
   to continue. The generic D5/Scope wording is only true for top-level runs and
   contradicts the nested-run contract unless qualified/split.

2. **D15's abort emission example is bare, contradicting D28/D33 guarded payload
   requirement.** D15 still says to emit `{:effect/type :runtime/agent-abort
   :session-id sid}` for workflow-cancellation aborts. Later D28/D33 require every
   workflow-cancellation abort to carry the complete flat guard payload
   (`:workflow-run-id`, `:workflow-step-id`, `:workflow-attempt-id`,
   `:expected-session-id`) in addition to `:session-id`; only non-workflow aborts
   may remain unguarded/session-id-only. The D15 emit rule must show or reference
   the guarded shape, not a bare abort effect.

## Design follow-up resolution (ψ pass 15, 2026-06-11)

Executed the two newly added inconsistency follow-up design-steps from
`design-steps.md` pass 15. Both were design wording/contract reconciliations and
were completable now; no blockers.

- Qualified the live-`remove` worker/future-stop guarantee to **top-level** runs in
  Desired Behaviour, Scope, the Scope test bullet, and D5. Direct live nested-sub-run
  remove remains in scope under D19/D21: no worker `future-cancel`, child abort wakes
  the shared parent worker, run-absence maps to the `:cancelled` failed delegate-step
  result, and the parent continues.
- Aligned D15's workflow-cancellation `:runtime/agent-abort` emit rule with D28/D33:
  D15 now reads `attempt-id` and shows the complete flat guarded payload
  (`:session-id`, `:workflow-run-id`, `:workflow-step-id`, `:workflow-attempt-id`,
  `:expected-session-id`). The stale bare `{:effect/type :runtime/agent-abort
  :session-id sid}` workflow-cancel example is removed; session-id-only aborts are
  explicitly reserved for unguarded non-workflow emissions.

## Architecture-fit review (ψ pass 16, 2026-06-11)

Reviewed `design.md` for architectural fit only, against AGENTS.md VSM/principles,
META.md managed-service/ctx guidance, and doc/architecture.md state-boundary,
dispatch sequencing, replay-trim, and dispatch-trace contracts. Did not review
`plan.md` or `steps.md`.

No new actionable architectural-fit misfit found.

The current D1–D34 design remains aligned: cancellation and cleanup side effects
are canonical dispatch `:runtime/*` effects with schema/executor parity and replay
trimming; cancellation signal vs runtime handles stay split across `:state*` and
ctx-reached handles; the cascade is a dispatch-owned multi-run root-state update
rather than command-layer recursion/reach-in; cancel-then-remove uses the existing
effects-as-data/re-entrant-dispatch boundary only for the apply-before-effects
ordering case; guarded workflow aborts reuse `:runtime/agent-abort` without
breaking unguarded aborts; direct nested sub-run cancel/remove preserves parent
continuation via existing delegate failure semantics. No new `design-steps.md`
follow-up item added.

## Ambiguity review (ψ pass 17, 2026-06-11)

Reviewed `design.md` for ambiguity only after the pass-15 wording fixes, consulting
current dispatch effect schema/executor code (`dispatch_schema.clj`,
`dispatch_effects.clj`) and the workflow `inflight-runs` handle path. Did not review
`plan.md` or `steps.md`.

One new actionable ambiguity found:

1. **Worker future-cancel effect type/payload is not pinned.** D12/D14/D18/D23 require
   a canonical dispatch `:runtime/*` effect that `future-cancel(true)`s the top-level
   worker future via `inflight-runs`, with effect-schema + `execute-effect!` parity.
   Unlike D24 (`:runtime/drop-inflight-run`) and D18 (`:runtime/dispatch-event`), the
   design never names the exact `:effect/type` keyword or payload shape for this
   worker-cancel effect. Builders/tests could choose different representations
   (`:runtime/cancel-inflight-run`, `:runtime/workflow-future-cancel`, etc.) while
   still satisfying the prose. Pin one keyword and required keys.

No other new actionable ambiguity found in the current D1–D34 design.


## Ambiguity follow-up resolution (ψ pass 17, 2026-06-11)

Executed the newly added pass-17 ambiguity follow-up design-step. It was a
representation-pinning design step, completable now — no blocker.

Resolution written to `design.md` as D35 and threaded through D12/D14/D18/D23 plus
Acceptance #2/#4/#5/#6:

- canonical worker future-cancel effect is
  `{:effect/type :runtime/cancel-inflight-run :run-id top-level-run-id}`;
- required keys are exactly `:effect/type` and `:run-id`; `:run-id` is the
  top-level run id owning the `inflight-runs` entry;
- emitters emit it only for top-level cancel / live top-level remove; direct nested
  sub-run cancel/remove emits no worker cancel effect;
- executor reads the D25 ctx-injected `:workflow-inflight-runs-handle`, looks up
  exactly `:run-id`, calls `future-cancel`, does no run-tree traversal, and no-ops
  idempotently on missing handle/future;
- schema/executor/test implications now name `:runtime/cancel-inflight-run` as the
  single accepted shape.

This removes the previous representational ambiguity (`:runtime/*` prose without a
keyword/payload), while preserving D14/D19 parent-survival for direct nested
sub-run cancellation and D25 ctx-based handle reachability.

## Inconsistency review (ψ pass 18, 2026-06-11)

Reviewed `design.md` for internal/design-vs-artifact inconsistency after D35,
consulting the current dispatch abort/effect code (`dispatch_effects.clj`,
`dispatch_schema.clj`, `turn.clj`) and workflow runtime handle state. Did not
review `plan.md` or `steps.md`.

Two new actionable contradictions found; neither duplicates existing
`design-steps.md` items:

1. **D9/D3 child abort path contradicts the existing `:runtime/agent-abort`
   executor.** The design says the workflow-cancellation abort effect handler
   invokes the agent-session `:session/abort` dispatch authority, and D9 says the
   existing `:runtime/agent-abort` effect already drives that path. Code shows the
   opposite layering: `:session/abort` statechart action emits
   `{:effect/type :runtime/agent-abort}`, and `execute-effect! :runtime/agent-abort`
   directly performs abort side effects (`turn` stream cancellation / turn error /
   `agent/abort-in!`) without dispatching `:session/abort`. Implementing D9
   literally would either recurse/double-abort or require a different effect path;
   keeping the existing effect means D3/D9's `:session/abort` wording is false.

2. **Absent `remove-run` cleanup can drop a live orphaned handle without cancelling
   it.** D24 promises drop-after-cancel / never drop-then-orphan for
   `inflight-runs`; D34 says absent `remove-run` still emits
   `:runtime/drop-inflight-run` to clear a possible orphaned handle; D29/D34 also
   say absent remove emits no cancellation effects. For an absent canonical run that
   still has a live `inflight-runs` future (the exact orphan class D34 wants to
   clean), the design currently drops the only handle without first emitting D35
   `:runtime/cancel-inflight-run`, recreating the Evidence-step-3 orphaning failure.


## Inconsistency follow-up resolution (ψ pass 18, 2026-06-11)

Executed both newly added pass-18 design follow-ups; no blockers.

- Resolved child-session abort ownership as D36: workflow cancellation reuses the
  existing `:runtime/agent-abort` effect as the **direct** abort-side-effect
  executor with the D28/D33 guarded payload. It does not dispatch a follow-on
  `:session/abort`; `:session/abort` remains the public/statechart event that emits
  the same effect. Updated D3/D9/D12/D15/D28/D33 wording and Q2 resolution.
- Resolved absent `remove-run` stale-handle cleanup as D36b: absent remove emits an
  ordered handle-cleanup pair (`:runtime/cancel-inflight-run` before
  `:runtime/drop-inflight-run`) for the requested run id, so a stale live
  `inflight-runs` future is interrupted before the handle is dropped. Public D29
  no-op semantics remain canonical-record semantics (`:noop?` ≠ no effects).

Acceptance #10 and related D24/D26/D29/D34/D35 wording now reflect the no-orphan
cleanup ordering.

## Architecture-fit review (ψ pass 19, 2026-06-11)

Reviewed current design.md for architectural fit only after the pass-18
reconciliations (D36/D36b), consulting AGENTS.md VSM/principles, META.md, and
`doc/architecture.md` state-boundary / dispatch sequencing / replay-trace surfaces.
No new actionable architectural-fit feedback found.

The design now fits the project boundaries: canonical cancellation signal stays in
`:state*`; `inflight-runs` remains a runtime handle reached by dispatch effects via
ctx; cancellation/cleanup effects are canonical `:runtime/*` effects with schema /
executor parity, replay trimming, and dispatch trace; child abort reuses the existing
`:runtime/agent-abort` side-effect executor with workflow guard metadata; cascade is
a pure multi-run apply-phase update; cancel-then-remove uses the existing re-entrant
`:runtime/dispatch-event` only for the apply-before-effects ordering case; and
absent-remove stale-handle cleanup cancels before dropping the handle, preserving the
no-orphan invariant. Existing D18 doc-gap note for documenting `:runtime/dispatch-event`
re-entrancy remains an implementation/doc follow-through item, not a new design
architecture misfit.

## Ambiguity review (ψ pass 20, 2026-06-11)

Reviewed current `design.md` for ambiguity only after D36/D36b, consulting the
state-kernel dispatch schema and existing Pathom workflow mutation names. Did not
review `plan.md` or `steps.md`.

One new actionable ambiguity found:

1. **Canonical dispatch event types for workflow cancel/remove are not pinned.**
   D18 requires a re-entrant `:runtime/dispatch-event` for the remove second pass,
   whose schema requires a keyword `:event-type`, and D26 defines `cancel-run` /
   `remove-run` as the two dispatch entry events. But the current public operations
   are Pathom mutation symbols (`'psi.workflow/cancel-run`, `'psi.workflow/remove-run`),
   while D18 still uses a placeholder (`<remove-run dispatch>`) and the design never
   names the actual state-kernel event keywords. Builders/tests could choose
   different event types (`:psi.workflow/remove-run`, `:workflow/remove-run`,
   `:session/workflow-remove-run`, etc.) or try to feed the Pathom symbol through a
   keyword-only effect. Pin the canonical dispatch event keywords and how the public
   Pathom mutations/delegate tool route into them.

No other new actionable ambiguity found in the current D1–D36b design.

## Ambiguity follow-up resolution (ψ pass 20, 2026-06-11)

Executed the newly added pass-20 design follow-up; no blockers.

- Added D37 to pin canonical state-kernel workflow terminal events:
  `:psi.workflow/cancel-run` and `:psi.workflow/remove-run`.
- Defined event-data shape: required `:run-id`, optional `:reason`, optional
  dispatch-context `:session-id`; no `:then-remove?` / `:reentrant?` flag.
- Replaced D18's `<remove-run dispatch>` placeholder with the concrete
  `:runtime/dispatch-event` payload targeting `:psi.workflow/remove-run`.
- Stated routing: Pathom mutation symbols remain public adapters, while Pathom,
  psi-tool cancel, and delegate remove route into the keyword dispatch events and
  must not directly call workflow-runtime pure functions or mutate `inflight-runs`.

## Inconsistency review (ψ pass 21, 2026-06-11)

Reviewed current `design.md` for internal/design-vs-artifact inconsistency after
D37. Did not review `plan.md` or `steps.md`.

One new actionable inconsistency found:

1. **D1 still assigns the canonical transition/effects to the Pathom mutation,
   contradicting D26/D37's state-kernel event ownership.** D1 says the
   "agent-session cancel/remove mutation" commits the pure canonical-state
   transition and emits cancellation effects, and that the mutation's only
   canonical-state write is the pure status transition. But D26/D37 now say the
   public Pathom mutation symbols are thin adapters: the registered state-kernel
   keyword handlers (`:psi.workflow/cancel-run`, `:psi.workflow/remove-run`) own the
   shared cancel-transition helper, effect set, and remove branch; Pathom/psi-tool/
   delegate surfaces route into those events and must not call workflow-runtime pure
   functions or mutate handles directly. Leaving D1's mutation-owned wording gives
   two different owners for the same transition/effect boundary and could lead an
   implementer to keep direct Pathom mutation writes that D37 explicitly forbids.

## Inconsistency follow-up resolution (ψ pass 21, 2026-06-11)

Executed the newly added pass-21 design follow-up; no blockers.

- Reworded D1 so the canonical transition/effect owner is the registered
  state-kernel keyword handlers `:psi.workflow/cancel-run` and
  `:psi.workflow/remove-run`, aligning D1 with D26/D37.
- Made Pathom mutations, psi-tool cancel, and `delegate remove` explicit public
  adapters only: they route into the keyword events and do not call
  workflow-runtime pure functions directly, `reset!` canonical state, or mutate
  `inflight-runs` / background-job handles inline.
- D1 now names the shared cancel-transition helper, live-remove first pass,
  terminal/absent record-drop branch, and dispatch `:effects` interceptor as the
  executor boundary.

## Architecture-fit review (ψ pass 22, 2026-06-11)

Reviewed current `design.md` for architectural fit only, consulting AGENTS.md VSM /
principles, META.md, and `doc/architecture.md` state-boundary, dispatch sequencing,
replay-trim, dispatch-trace, and ctx-managed-service guidance. Did not review
`plan.md` or `steps.md`.

No new actionable architectural-fit misfit found. The D1–D37 design continues to
fit the project boundaries: public surfaces are adapters over canonical
state-kernel events; cancellation/cleanup remains effects-as-data through dispatch
with schema/executor parity, replay trimming, and trace visibility; canonical
`:state*` signals stay separate from ctx-reached runtime handles; the cascade is a
pure multi-run apply-phase update; cancel-then-remove uses re-entrant dispatch only
for the apply-before-effects ordering case; and guarded abort / stale-handle cleanup
reuse existing runtime-effect mechanisms without command-layer reach-in. No new
`design-steps.md` follow-up item added.

## Ambiguity review (ψ pass 23, 2026-06-11)

Reviewed current `design.md` for ambiguities after D37 and the pass-21 D1 owner
alignment. Consulted the relevant dispatch/effect code paths (`dispatch_schema.clj`,
`dispatch_effects.clj`, `state-kernel/dispatch.clj`, current workflow
cancel/remove mutations, and `delegate remove`) to check whether the design leaves
multiple implementation interpretations. Did not review `plan.md` or `steps.md`.

No new actionable ambiguity found. D1–D37 now pin the entry events, public routing,
effect payloads, ctx handle reachability, cascade semantics, cancel/remove result
contracts, idempotency gates, and acceptance-test surface sufficiently for planning
and implementation. No new `design-steps.md` follow-up item added.

## Inconsistency review (ψ pass 24, 2026-06-11)

Reviewed current `design.md` for internal/design-vs-artifact inconsistency after
D37 and the pass-23 ambiguity review. Consulted the relevant cancellation-contract
sections in `design.md` (D6, D27, D31, Acceptance #3/#9a). Did not review
`plan.md` or `steps.md`.

One new actionable inconsistency found:

1. **D6 / Acceptance #3 still state an absolute guaranteed no-new-child-session
   contract, while D27 / Acceptance #9a accept a post-checkpoint direct-sub-run
   spawn exception.** D6 says that after the D31 cancel checkpoint, no further
   delegate sub-run is created by the cancelled subtree and no further ordinary
   child agent session spawns; Acceptance #3 repeats that no new ordinary child
   sessions are initiated after the checkpoint as a guaranteed criterion. But D27
   and Acceptance #9a explicitly accept a direct-sub-run-cancel descendant spawned
   after the D23 enumeration / D31 CAS checkpoint as a bounded, out-of-test-scope
   exception. These cannot both be absolute. The design should qualify D6 and
   Acceptance #3 with the D27 exception (or restate the guarantee as applying only
   to the enumerated cascade set), so the guaranteed contract and accepted exception
   have one scope.

## Inconsistency follow-up resolution (ψ pass 24, 2026-06-11)

Executed the newly added pass-24 design follow-up from `design-steps.md`; no blocker.

- Reconciled D6 / Acceptance #3 with D27 / Acceptance #9a by scoping the guaranteed
  "no new ordinary workflow/child-turn side effects" contract to runs in the D23
  enumerated cascade set after their D31 cancel checkpoint.
- D6 now explicitly points at the D27 bounded true-concurrency exception for direct
  sub-run cancellation: a descendant spawned after handler-before enumeration but
  before the abort-driven checkpoint refuses it is out-of-test-scope and not a
  violation of the cascade-set guarantee.
- Acceptance #3 now asserts the deterministic guarantee only for cascade-set runs,
  and leaves the post-enumeration spawn race solely under criterion #9a / D27.

## Architecture-fit review (ψ pass 25, 2026-06-11)

Reviewed current `design.md` for architectural fit only after the pass-24
inconsistency resolution. Consulted AGENTS.md VSM/principles, META.md, and
`doc/architecture.md` state-boundary, dispatch sequencing, replay-trim,
dispatch-trace, and ctx-managed-service guidance. Did not review `plan.md` or
`steps.md`.

No new actionable architectural-fit feedback found. The design remains aligned
with project boundaries: public surfaces are adapters over canonical
`:psi.workflow/*` state-kernel events; cancellation state changes happen via
canonical `:state*` updates; runtime handles stay on ctx and are reached only by
canonical `:runtime/*` effects with schema/executor parity; replay trimming and
dispatch trace cover the side effects; abort/cleanup reuse existing runtime-effect
mechanisms; and the cascade/exception scopes are now explicit enough to preserve
the no-orphan/no-runaway invariant without introducing adapter shims or hidden
mutation paths. No new `design-steps.md` follow-up item added.

## Ambiguity review (ψ pass 26, 2026-06-11)

Reviewed current `design.md` for ambiguity only after the pass-24 inconsistency
resolution. Consulted the in-design contracts D1–D37, Scope, Design Questions, and
Acceptance Criteria; did not review task `plan.md` or `steps.md`.

No new actionable ambiguity found. The design now gives single-interpretation
contracts for cancel/remove entry events, effect payloads, nested-sub-run direct
cancel/remove behaviour, absent/terminal public results, idempotency, runtime-handle
cleanup, and the D27 bounded exception vs D6/Acceptance #3 cascade-set guarantee.
No new `design-steps.md` follow-up item added.

## Inconsistency review (ψ pass 27, 2026-06-11)

Reviewed current `design.md` for internal/design-vs-artifact inconsistency after
pass-26 ambiguity review. Consulted D6/D27/D30/D31 and Acceptance #3/#9a; did not
review `plan.md` or `steps.md`.

One new actionable inconsistency found:

1. **Intent still states the pre-D27 absolute no-new-child-session/no-new-side-effect
   contract.** The Intent says that after a cancel "no further child sessions spawn"
   and "no new side effects are initiated (commits, journal writes) — transitively
   across nested sub-runs." Later D6/Acceptance #3 scope the deterministic guarantee
   to runs in the D23 enumerated cascade set after the D31 cancel checkpoint; D27 /
   Acceptance #9a explicitly accept a bounded direct-sub-run post-enumeration spawn
   exception; and D30 allows required cancellation-control writes/effects while only
   forbidding ordinary workflow/child-turn advancement. Read literally, the Intent
   contradicts those later decisions by making the no-new-child-session/no-side-effect
   claim absolute again. Qualify Intent to the cascade-set/D31/D30 contract and point
   at the D27 exception.

## Inconsistency follow-up resolution (ψ pass 27, 2026-06-11)

Executed the single newly added pass-27 design follow-up. Updated `design.md`
Intent to match the refined cancellation contract instead of the older absolute
wording:

- deterministic guarantee is scoped to runs in the D23 enumerated cascade set;
- the guarantee begins at the D31 cancel checkpoint (apply-phase CAS committing
  `:cancelled`);
- forbidden ordinary workflow/child-turn advancement is distinguished from allowed
  cancellation-control writes/effects (D30);
- the bounded direct-sub-run post-enumeration spawn exception is explicitly named
  as D27.

Marked the follow-up complete in `design-steps.md`. No blockers.

## Architecture-fit review (ψ pass 28, 2026-06-11)

Reviewed current `design.md` for architectural fit only after the pass-27 Intent
reconciliation. Consulted AGENTS.md VSM/principles, META.md, and
`doc/architecture.md` state-boundary, dispatch sequencing, replay-trim,
dispatch-trace, and ctx-managed-service guidance. Did not review `plan.md` or
`steps.md`.

No new actionable architectural-fit feedback found. The design still fits the
project architecture: canonical workflow cancellation/removal is owned by
state-kernel events, public surfaces are adapters, cancellation state lives in
`:state*`, runtime handles remain ctx-reached effect dependencies,
cancellation/cleanup are canonical `:runtime/*` effects with schema/executor
parity, replay/trace coverage is preserved, child abort reuses the existing guarded
`:runtime/agent-abort` executor, and the D23 cascade-set/D31 checkpoint/D30
control-effect boundary now aligns the top-level Intent with the refined
cancellation contract. No new `design-steps.md` follow-up item added.

## Ambiguity review (ψ pass 29, 2026-06-11)

Reviewed current `design.md` for ambiguity only after the pass-27 Intent reconciliation and pass-28 architecture-fit review. Consulted referenced dispatch/effect/runtime code and `doc/architecture.md` where needed; did not review task `plan.md` or `steps.md`.

No new actionable ambiguity found. D1–D37 plus Scope/Intent/Acceptance now pin a single contract for cancel/remove entry events, effect payloads and ordering, nested sub-run direct cancel/remove behaviour, absent/terminal public results, idempotency, stale-handle cleanup, cancellation-control side effects, and the D23/D31 cascade-set boundary with the D27 bounded exception. No new `design-steps.md` follow-up item added.

## Inconsistency review (ψ pass 30, 2026-06-11)

Reviewed current `design.md` for internal/design-vs-artifact inconsistency after
pass-29 ambiguity review. Consulted D3/D6/D15/D23, D24/D26/D29/D36b, Acceptance
#3/#10, and referenced dispatch/effect/runtime-handle code. Did not review
`plan.md` or `steps.md`.

Two new actionable inconsistencies found:

1. **D23 omits the directly-cancelled run from the child-abort effect set.** D6
   guarantees the directly-cancelled run's in-flight child turn is interrupted, D15
   says aborted sessions are the directly-cancelled run plus each in-flight
   descendant, and D3 defines the cascade set as cancelled run ∪ descendants. But
   D23's effect list says to emit one guarded `:runtime/agent-abort` per
   "in-flight descendant attempt", omitting the directly-cancelled run itself.
   For a top-level run with an active step and no descendant, that wording would
   emit no abort, contradicting D6/D15. D23 should say the abort effect is emitted
   for each cascade-set run (directly-cancelled run included) that has a live
   attempt.

2. **Terminal `remove-run` drop-only cleanup can orphan a still-live terminal
   handle, contradicting the no-orphan/drop-before-cancel invariant.** D26/D29 say
   already-terminal remove performs bare record-drop plus D24
   `:runtime/drop-inflight-run` cleanup only; D36b adds cancel-before-drop only for
   absent remove. But a top-level run may already be terminal in canonical state
   (e.g. cancelled by a prior cancel request) while its `inflight-runs` future is
   still unwinding or stale. Dropping that handle without first emitting D35
   `:runtime/cancel-inflight-run` recreates the Evidence-step-3 failure D36b was
   written to avoid. The design should either prove terminal canonical records
   cannot have live/stale handles, or require terminal-remove cleanup to cancel a
   possible top-level handle before dropping it (likely the same ordered cleanup
   pair as absent remove, scoped so nested sub-run removes still do not infer/kill
   a parent worker).

## Design follow-up resolution (ψ pass 30 follow-up, 2026-06-11)

Executed the two newly added unchecked design-steps from `design-steps.md` pass 30.
Both were design-contract reconciliation items and were completable now; no blockers.

- **D23 child-abort effect set:** corrected the effect-set wording so guarded
  `:runtime/agent-abort` emits for each **cascade-set** run with a live attempt,
  including the directly-cancelled run itself and in-flight descendants. Acceptance
  #4 now asserts abort coverage over the cascade-set live attempts, preserving the
  D15/D28/D33 guarded payload/read rule.
- **Terminal remove handle cleanup:** added D38. Chose cancel-before-drop cleanup for
  already-terminal **top-level** remove: emit `:runtime/cancel-inflight-run` before
  `:runtime/drop-inflight-run` so a still-unwinding/stale worker is not orphaned.
  Terminal **nested** remove remains no-worker-cancel (no parent/top-level inference)
  and may only exact-key drop. D24/D26/D29/D34/D36b and Acceptance #8/#10 were
  aligned to distinguish canonical cancellation/cascade effects from idempotent
  runtime-handle cleanup.

## Architecture-fit review (ψ pass 31, 2026-06-11)

Reviewed current `design.md` for architectural fit only after the pass-30 D23/D38
reconciliations. Consulted AGENTS.md VSM/principles, META.md, and
`doc/architecture.md` state-boundary, dispatch sequencing, replay-trim,
dispatch-trace, ctx-managed-service, and adapter/public-surface guidance. Did not
review `plan.md` or `steps.md`.

No new actionable architectural-fit feedback found. The D38 terminal-top-level
cancel-before-drop cleanup preserves the no-orphan runtime-handle invariant while
staying inside canonical `:runtime/*` effects, and the nested/absent cleanup cases
avoid parent-worker inference or command-layer reach-in. D23's cascade-set abort
wording now includes the directly-cancelled run while retaining the pure multi-run
apply-phase cascade plus guarded effect execution. No new `design-steps.md`
follow-up item added.

## Ambiguity review (ψ pass 32, 2026-06-11)

Reviewed current `design.md` for ambiguity only after the pass-30 D23/D38
reconciliations and pass-31 architecture-fit review. Consulted the current design
contracts (D1–D38), Scope, Design Questions, Acceptance Criteria, and referenced
runtime/effect code where needed (`dispatch_schema.clj`, `dispatch_effects.clj`,
workflow runtime handle paths). Did not review task `plan.md` or `steps.md`.

No new actionable ambiguity found. The design now gives single-interpretation
contracts for canonical cancel/remove events, public routing, effect payloads and
ordering, cascade-set abort coverage, terminal/absent runtime-handle cleanup,
top-level vs nested remove behaviour, public result semantics, idempotency, and the
D23/D31/D27 cancellation guarantee/exception boundary. No new `design-steps.md`
follow-up item added.

## Inconsistency review (ψ pass 33, 2026-06-11)

Reviewed current `design.md` for internal/design-vs-artifact inconsistency after
pass-32 ambiguity review. Consulted D24/D26/D35/D36b/D38 and Acceptance #10; did
not review `plan.md` or `steps.md`.

One new actionable inconsistency found:

1. **D35's worker-cancel emitter rule omits/appears to exclude D38 terminal
   top-level remove cleanup.** D38 requires already-terminal **top-level**
   `remove-run` cleanup to emit the ordered runtime-handle pair
   `:runtime/cancel-inflight-run` before `:runtime/drop-inflight-run`, so a
   still-unwinding/stale worker is not orphaned. But D35's emitter-responsibility
   bullet still says emitters produce `:runtime/cancel-inflight-run` only for
   top-level cancel / **live** top-level remove, never for direct nested sub-run
   cancel/remove, and that the "only exception" is D36b absent-remove stale-handle
   cleanup. Read literally, D35 leaves terminal top-level remove outside the allowed
   emitter cases (and its test implication says tests assert the effect only for
   top-level cancel / live top-level remove), contradicting D38 and Acceptance #10.
   D35 should distinguish canonical cancellation emissions from runtime-handle
   cleanup emissions and include D38 terminal top-level remove as an allowed cleanup
   emitter, while preserving the nested-sub-run no-parent-inference rule.

## Design follow-up resolution (ψ pass 33 follow-up, 2026-06-11)

Executed the newly added unchecked pass-33 design-step from `design-steps.md`. The
step was a design-contract reconciliation item and was completable now; no blocker.

Updated D35 so `:runtime/cancel-inflight-run` emitter responsibility now separates
canonical cancellation/cascade emissions from runtime-handle cleanup emissions:

- canonical cancellation emits worker cancel only for top-level cancel / live
top-level remove, never for direct nested sub-run cancel/remove;
- runtime-handle cleanup may also emit worker cancel for D38 already-terminal
top-level remove and D36b absent remove before `:runtime/drop-inflight-run`;
- terminal nested-sub-run remove still emits no worker cancel and does not infer or
interrupt a parent/top-level worker (exact-key drop only).

Also updated D35's test implication to include terminal top-level remove and absent
remove cleanup and to assert absence for terminal nested-sub-run remove.

## Architecture-fit review (ψ pass 34, 2026-06-11)

Reviewed current `design.md` for architectural fit only after the pass-33 D35
runtime-handle cleanup reconciliation. Consulted AGENTS.md VSM/principles,
META.md, and `doc/architecture.md` state-boundary, dispatch sequencing,
replay-trim, dispatch-trace, ctx-managed-service, and adapter/public-surface
contracts. Did not review `plan.md` or `steps.md`.

No new actionable architectural-fit feedback found. The design remains aligned:
canonical workflow cancel/remove is owned by state-kernel events with public
surfaces as adapters; cancellation state changes stay in canonical `:state*`;
runtime handles are reached through ctx-injected canonical `:runtime/*` effects;
effect schema/executor parity, replay trimming, and dispatch trace cover the real
side effects; top-level/terminal/absent handle cleanup preserves the no-orphan
invariant without inferring parent workers for nested sub-runs; and the cascade is
a pure multi-run apply-phase transition with guarded abort effects. No new
`design-steps.md` follow-up item added.

## Ambiguity review (ψ pass 35, 2026-06-11)

Reviewed current `design.md` for ambiguity only after the pass-33 D35 runtime-handle
cleanup reconciliation and pass-34 architecture-fit review. Consulted Scope,
D1–D38, Acceptance Criteria, referenced dispatch/effect/runtime code, and
`doc/architecture.md` state-boundary / dispatch sequencing where needed. Did not
review task `plan.md` or `steps.md`.

No new actionable ambiguity found. The D35 cleanup-emitter split introduced by the
latest follow-up is single-interpretation: canonical cancellation emissions remain
limited to top-level cancel/live top-level remove; runtime-handle cleanup may emit
`cancel-inflight-run` only for terminal top-level remove and absent stale-handle
cleanup before drop; terminal nested-sub-run cleanup remains no-worker-cancel / no
parent inference. Existing Scope, D24/D26/D29/D36b/D38, and Acceptance #8/#10 are
aligned with that split. No new `design-steps.md` follow-up item added.

## Inconsistency review (ψ pass 36, 2026-06-11)

Reviewed current `design.md` for internal/design-vs-referenced-artifact inconsistency after the pass-35 ambiguity review. Consulted Scope, Intent, D1–D38, Acceptance Criteria, and referenced dispatch/effect/runtime code where needed. Did not review task `plan.md` or `steps.md`.

No new actionable inconsistency found. The current design is internally aligned on state-kernel event ownership, effect payloads and ordering, top-level vs nested run cleanup, guarded abort semantics, terminal/absent result semantics, idempotency, and the D23/D31 cascade-set guarantee with the D27 bounded exception. No new `design-steps.md` follow-up item added.

## Plan/steps ambiguity review (ψ, 2026-06-11)

Reviewed `plan.md` and `steps.md` against the current D1–D38 design plus referenced
workflow dispatch/effect/runtime code and `doc/architecture.md`. Two new actionable
plan/steps ambiguities found:

1. `steps.md` says to emit `:runtime/cancel-inflight-run` only when the directly
   cancelled live run is top-level, while later terminal/absent remove steps require
   the same effect for D38/D36b runtime-handle cleanup. The step needs the D35 split:
   canonical cancellation emissions vs cleanup emissions.
2. `plan.md` says Pathom/psi-tool/`delegate remove` are adapters only, but the steps
   only call out removing direct `inflight-runs` cleanup. Current `delegate-remove`
   also performs command-layer background-job terminalization before remove; steps
   should explicitly remove/reroute or justify that side effect so the adapter-only
   boundary is unambiguous.

## Plan/steps ambiguity follow-up execution (ψ, 2026-06-11)

Executed both newly added unchecked plan/steps follow-up items from `steps.md`; both were task-artifact reconciliation items and were completable now.

- Qualified the Slice 3 `:runtime/cancel-inflight-run` step with the D35 split: canonical cancellation/cascade worker cancel is limited to top-level cancel / live top-level remove, while runtime-handle cleanup may emit cancel-before-drop for D38 terminal top-level remove and D36b absent stale-handle cleanup; direct/terminal nested remove remains no-worker-cancel/no-parent-inference.
- Reconciled Slice 6 and `plan.md` with the adapter-only cancel/remove boundary by explicitly including `delegate remove` active-background-job cleanup (`cleanup-active-delegate-background-jobs-before-remove!` / `terminalize-active-delegate-background-jobs!`) alongside direct `inflight-runs` cleanup as a command-layer side effect to remove/reroute through canonical `:psi.workflow/remove-run` dispatch/effects, unless a retained pre-remove cleanup is explicitly documented as non-cancellation/remove.

No production code/tests/docs were changed in this follow-up pass; the review items only required clarifying implementation steps and plan intent before execution.

## Plan/steps inconsistency review (ψ, 2026-06-11)

Reviewed `plan.md` and `steps.md` against the current D1–D38 design, prior review notes, Acceptance Criteria, and referenced cancel/remove dispatch/effect/runtime code. One new actionable inconsistency found:

1. `plan.md` says the `remove-run` "terminal/absent pass drops the canonical record", but `design.md` D29/D34/D36b and `steps.md` distinguish terminal and absent remove: terminal remove drops an existing canonical record, while absent remove is a success/no-op canonical result with no record found/removed and only the ordered stale-handle cleanup pair. The plan wording conflates terminal and absent semantics and contradicts the step-level absent branch.

## Plan/steps inconsistency follow-up resolution (ψ, 2026-06-11)

Executed the newly added plan/steps inconsistency follow-up. Updated `plan.md` key decisions so `remove-run` terminal vs absent branches are no longer collapsed:

- Terminal remove drops the existing canonical record and performs runtime-handle cleanup.
- Absent remove returns success/no-op with no canonical record found/removed and emits only the ordered stale-handle cleanup pair (`:runtime/cancel-inflight-run` then `:runtime/drop-inflight-run`) per D29/D34/D36b.

Marked the follow-up complete in `steps.md`. No code/tests/docs changes were required because the item was a planning-artifact reconciliation.

## Plan/steps ambiguity review (ψ pass 2, 2026-06-11)

Reviewed current `plan.md` and `steps.md` against `design.md` D1–D38, prior
plan/steps review notes, acceptance criteria, and referenced workflow dispatch /
effect / runtime-handle code. No new actionable plan/steps ambiguity found.

The current plan/steps now give single-interpretation implementation guidance for
canonical cancel/remove event routing, effect payloads and ordering, top-level vs
nested runtime-handle cleanup, guarded child aborts, cooperative stop checkpoints,
public result contracts, and acceptance-test coverage. No new unchecked follow-up
items were added to `steps.md`.

## Plan/steps inconsistency review (ψ pass 2, 2026-06-11)

Reviewed current `plan.md` and `steps.md` against `design.md` D1–D38,
Acceptance Criteria, prior plan/steps review notes, and the referenced
cancel/remove dispatch/effect/runtime code (`canonical_workflows.clj`,
`workflow/core.clj`, `background_job_runtime.clj`, and delegate result handling).

No new actionable plan/steps inconsistency found. The plan and steps are aligned on
canonical `:psi.workflow/cancel-run` / `:psi.workflow/remove-run` ownership,
terminal vs absent remove semantics, top-level vs nested worker-cancel rules,
ordered runtime-handle cleanup, guarded child aborts, cooperative checkpoints,
public result fields, background-job terminalization, docs/changelog updates, and
test/gate coverage. No new unchecked follow-up items were added to `steps.md`.

## Implementation pass — dispatch-owned cancel/remove foundation (ψ, 2026-06-11)

Implemented the first concrete cancellation slice across dispatch routing and runtime cleanup effects:

- Added canonical state-kernel handlers `:psi.workflow/cancel-run` and `:psi.workflow/remove-run` in `dispatch_handlers/workflows.clj` and registered them in the agent-session handler surface.
- Routed Pathom `psi.workflow/cancel-run` / `psi.workflow/remove-run`, `psi-tool workflow op=cancel-run`, and `delegate remove` through those canonical events instead of direct workflow-runtime mutation / handle cleanup. `delegate remove` no longer performs command-layer background-job terminalization or `inflight-runs` dissoc.
- Added ctx injection for `:workflow-inflight-runs-handle` and canonical effects `:runtime/cancel-inflight-run` / `:runtime/drop-inflight-run` with schema + executors.
- Extended `:runtime/agent-abort` to accept the guarded flat workflow-cancellation payload while preserving unguarded abort behaviour.
- Implemented D29 idempotent result shapes and D36b/D38 ordered handle cleanup for absent and terminal top-level remove.
- Extended background-job terminal reconciliation so canonical `:cancelled` workflow runs terminalize jobs as `:cancelled`.
- Documented `:runtime/dispatch-event` re-entrant sequencing in `doc/architecture.md`; added CHANGELOG entry.

Deviation / scope note: this pass implements direct-run cancel/remove semantics and guarded abort for the directly-cancelled run's live attempt. The full D23 descendant cascade, cooperative execution checkpoints, interrupt-aware waits, and delegate-result run-absence mapping remain unchecked follow-up work in `steps.md`.

Verification:

- `bb clojure:test:scry --namespace psi.agent-session.mutations.canonical-workflows-test --namespace psi.agent-session.workflow-tools-test --namespace psi.agent-session.workflow-delegate-list-test --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.agent-session.dispatch-test --namespace psi.agent-session.statechart-actions-test` → 60 tests / 479 assertions green.
- Focused `clj-kondo --lint` over changed source/test files → clean.

## Implementation pass — cascade cancel + removed delegate result (ψ, 2026-06-11)

Implemented the next cancellation slice:

- Added transitive cascade enumeration in the canonical workflow cancel/remove dispatch handlers: live descendants are discovered by `:delegating-run-id` from canonical run state, and the directly-cancelled run plus live descendants are cancelled in one multi-run `:root-state-update`.
- Cancellation effects now use the cascade set: guarded `:runtime/agent-abort` is emitted for each cascade-set run with a live current attempt, while worker `:runtime/cancel-inflight-run` remains limited to the directly-cancelled top-level run.
- Updated delegate result handling so a removed/missing delegated run maps to the cancellation/removal failed-step result instead of the generic non-terminal anomaly. Present non-terminal delegate runs still use the existing anomaly/default branch.
- Added focused tests for top-down cascade cancellation/abort effect targeting and removed delegate-run semantics.

Verification:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 5 tests / 32 assertions green.
- Focused affected suite: `bb clojure:test:scry --namespace psi.agent-session.mutations.canonical-workflows-test --namespace psi.agent-session.workflow-tools-test --namespace psi.agent-session.workflow-delegate-list-test --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.agent-session.dispatch-test --namespace psi.agent-session.statechart-actions-test --namespace psi.agent-session.workflow-execution-terminal-contract-test` → 62 tests / 492 assertions green.
- `clj-kondo --lint components/agent-session/src/psi/agent_session/dispatch_handlers/workflows.clj components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime/delegate.clj components/agent-session/test/psi/agent_session/workflow_cancellation_dispatch_test.clj` → clean.

Remaining concrete work: cooperative execution checkpoints / interrupt-aware waits and full direct nested cancel/remove parent-continuation acceptance coverage remain unchecked in `steps.md`.

## Implementation pass — cancellation acceptance coverage closure (ψ, 2026-06-11)

Closed the remaining cancellation acceptance-test gaps without changing production
code. Added state-based, mock-free coverage for: direct nested sub-run cancel
(no worker cancel, guarded child abort, child `:cancelled`, parent remains
`:running`, delegate result maps to failed-step semantics); direct live nested
sub-run remove (guarded child abort, re-entrant record drop, no worker cancel,
parent remains `:running`, run-absence already maps to cancelled/removed failure);
cancel-without-remove and live remove background-job terminalization as
`:cancelled`; and a real-future top-level worker wake-up check showing
`:runtime/cancel-inflight-run` interrupts a parked worker future before cancelled
job terminalization.

Out-of-test-scope criteria 9/9a remain intentionally covered by construction notes:
D22.2's true-concurrent duplicate-effect race relies on execute-time idempotency
(guarded abort liveness re-check, idempotent future cancel / terminalize / cleanup),
and D27's direct-sub-run post-enumeration spawn window is the explicitly bounded
true-concurrency exception. No deterministic test was added for those races.

Verification this pass:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.agent-session.workflow-async-path-test --namespace psi.agent-session.workflow-execution-terminal-contract-test` → 14 tests / 82 assertions green.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_cancellation_dispatch_test.clj components/agent-session/test/psi/agent_session/workflow_async_path_test.clj` → clean.
- `clj-kondo --lint components` → errors 0 / warnings 0; existing info-level findings outside this pass remain.
- `bb test` → green.

All checklist items in `steps.md` are now complete. Implementation appears ready for review; no further concrete implementation work is known from the task artifacts.

## Implementation review (ψ, 2026-06-11)

Reviewed implementation against `task-implementation-review` skill, task design/plan/steps, changed workflow cancellation code/tests/docs, and focused verification. The dispatch/effect boundary, guarded abort payloads, top-level vs nested cleanup split, delegate removed-run mapping, and background-job terminalization generally match D1–D38.

New actionable issue: ordinary workflow advancement still has unguarded stale-state writes that can race a D31 cancel checkpoint. In `statechart_runtime.clj`, `:step/enter` checks `workflow-stopped?` before attempt setup, but then unconditionally appends/starts the attempt in a later `swap!`; if cancel commits between the pre-check and that `swap!`, `start-latest-attempt` can rewrite a `:cancelled` run back to `:running` and record a post-checkpoint attempt. In `delegate.clj`, delegated sub-run creation has the same shape: parent stop pre-check, `create-run` on a stale `@state*`, then `reset!`, which can overwrite a concurrent parent cancel and create a sub-run after the checkpoint. These are implementation-level no-resurrection / no-post-checkpoint-advancement gaps; focused scry and lint run during review stayed green, so follow-up is required rather than immediate test failure.

Verification during review:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.agent-session.workflow-execution-test --namespace psi.agent-session.workflow-async-path-test` → 29 tests / 155 assertions green.
- Focused `clj-kondo --lint` over changed workflow cancellation source files → clean.

## Review follow-up implementation — cancellation-safe advancement writes (ψ, 2026-06-11)

Executed both newly-added implementation-review follow-ups.

- Made `:step/enter` attempt append/start cancellation-safe by routing the canonical attempt-start write through a CAS helper that re-reads canonical run presence / `:cancelled` status at the compare-and-set boundary. If cancellation wins after the pre-check and before the attempt-start write, the helper returns false, records no attempt, does not rewrite the run to `:running`, and enqueues workflow cancellation instead. The exception fallback path now uses the same guarded helper and only records a synthetic execution-failure attempt if that guarded start succeeds.
- Made delegate sub-run creation cancellation-safe by replacing stale-state `create-run` + `reset!` with a CAS loop that re-checks parent run presence / `:cancelled` before committing the child run. If the parent is cancelled/removed before the CAS, no delegated child run is created and the existing cancelled/removed delegate failure result is returned.
- Added regression coverage in `workflow_statechart_runtime_test.clj`: one test cancels between child-session creation/pre-check and attempt-start write and asserts the parent remains `:cancelled` with no appended attempt or turn execution; one test cancels during delegate child-run creation and asserts no delegated run is added and the parent remains `:cancelled`.

Verification:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-test` → 17 tests / 72 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 7 tests / 49 assertions green.
- `bb clojure:test:scry --namespace psi.workflow-runtime.statechart-runtime.state-test` → 2 tests / 7 assertions green.
- Focused `clj-kondo --lint` over changed workflow runtime source and test files → clean.

## Implementation review (ψ pass 2, 2026-06-11)

Reviewed the post-follow-up implementation against `task-implementation-review`, the D1–D38 design, current steps, and changed workflow cancellation code/tests/docs. The prior advancement-race follow-ups for step-entry attempt start and delegate sub-run creation are implemented and covered.

New actionable issues remain in later statechart actions. Cancellation safety is not yet applied to ordinary result/judge writes after an event has entered `process-event!`: `:step/record-result`, `:step/record-failure`, `:judge/record`, and `:iteration/exhausted` can still mutate a run after a D31 cancel checkpoint if cancel races after the lifecycle stop-checkpoint but before their `swap!`; `:judge/record`/`:iteration/exhausted` can also overwrite `:cancelled` with `:running`, `:completed`, or `:failed`. Separately, judge execution is not part of the cooperative stop/abort path: `:judge/enter` has no stop check before/after `execute-judge!`, and the guarded cancel abort targets `:execution-session-id` but not an in-flight judge session, so a judge child turn can continue and write ordinary journal/session state after cancellation.

Verification during review:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-test --namespace psi.agent-session.workflow-execution-test --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 40 tests / 198 assertions green.
- Focused `clj-kondo --lint` over changed workflow-runtime cancellation source files → clean.

## Review follow-up implementation — post-entry cancellation-safe writes and judge stop (ψ, 2026-06-11)

Executed both newly-added implementation-review pass-2 follow-ups.

- Added a shared CAS guard in `statechart_runtime.clj` for ordinary canonical root-state writes after event admission. `:step/record-result`, `:step/record-failure`, `:judge/record`, and `:iteration/exhausted` now re-check canonical run presence / `:cancelled` inside the compare-and-set loop before committing results, failures, judge routing, or terminal failure/completion updates. If cancellation wins the race, the ordinary write is skipped and workflow cancellation is queued instead of resurrecting/advancing the run.
- Added judge stop checks before judge-session creation and after judge turn/retry execution. Judge child sessions now carry workflow run/step/attempt linkage and attach their `judge-session-id` to the live attempt with a guarded CAS before the turn runs, making an in-flight judge turn addressable by cancellation while still avoiding ordinary judge output/routing writes after the D31 checkpoint.
- Extended guarded `:runtime/agent-abort` with `:workflow-session-kind :attempt|:judge`, preserving backward-compatible attempt targeting by default. Cancellation now emits guarded aborts for both live actor attempt sessions and live judge sessions recorded on the current attempt.
- Added regression coverage for result write, failure write, judge record, iteration-exhausted terminal write, cancellation during a judge turn, and guarded judge abort execution.

Verification:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-test` → 22 tests / 89 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 8 tests / 51 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-judge-test` → 15 tests / 83 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-execution-test` → 16 tests / 77 assertions green.
- Focused `clj-kondo --lint` over changed source/test files → clean.

Additional gate verification after the follow-up:

- `bb test` → green.
- `clj-kondo --lint components` → errors 0 / warnings 0; existing info-level suggestions outside this pass remain.

## Implementation review (ψ pass 3, 2026-06-11)

Reviewed the post-pass-2 implementation against `task-implementation-review`, the
D31 no-post-checkpoint ordinary-advancement contract, changed cancellation runtime
code/tests/docs, and focused verification. The prior guarded writes for step-entry,
delegate creation, result/failure/judge/iteration actions, and judge abortability
are implemented and covered.

New actionable issue: invoke-step attempt-data recording still has a stale-check
race. In `statechart_runtime.clj` the invoke branch checks `workflow-stopped?`
after `invoke-step-runtime-result`, then performs an unguarded
`swap!`/`merge-latest-attempt-data` to write `:effective-args` before queuing the
actor result. If cancel commits after that post-invoke stop check but before the
`swap!`, the cancelled run can still receive ordinary invoke attempt metadata after
the D31 checkpoint. This does not resurrect `:status`, but it violates the same
cancellation-safe write discipline applied to the other post-entry ordinary action
writes; follow-up should guard or fold the attempt-data write into
`update-state-if-live!` and add a race regression.

Verification during review:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.agent-session.workflow-execution-test --namespace psi.agent-session.workflow-judge-test --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 47 tests / 236 assertions green.
- Focused `clj-kondo --lint` over changed workflow cancellation source files → clean.

## Review follow-up implementation — invoke attempt-data cancellation-safe write (ψ, 2026-06-11)

Executed the newly-added implementation-review pass-3 follow-up.

- Routed the invoke branch's post-`invoke-step-runtime-result` `merge-latest-attempt-data` write through the existing `update-state-if-live!` CAS helper. The canonical write now re-checks run presence / `:cancelled` inside the compare-and-set loop; if cancellation wins after invoke returns but before attempt metadata commits, the write is skipped and workflow cancellation is queued instead of recording ordinary `:effective-args` metadata or downstream actor output.
- Added regression coverage in `workflow_execution_test.clj` that forces cancellation during the attempt-data write window and asserts `:effective-args` / accepted result are absent, the run remains `:cancelled`, and no downstream session is spawned.

Verification:

- `bb clojure:test:scry --var psi.agent-session.workflow-execution-test/invoke-step-attempt-data-write-is-cancellation-safe-test` → 1 test / 6 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-execution-test` → 17 tests / 83 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.agent-session.workflow-execution-test --namespace psi.agent-session.workflow-judge-test --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 48 tests / 242 assertions green.
- `clj-kondo --lint components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime.clj components/agent-session/test/psi/agent_session/workflow_execution_test.clj` → clean.

## Implementation review (ψ pass 4, 2026-06-11)

Reviewed the post-pass-3 implementation against `task-implementation-review`, the
D31/D30 no-post-checkpoint ordinary-child-session contract, current steps, and the
changed workflow cancellation code/tests. The prior invoke attempt-data race is fixed
and covered; focused cancellation/execution suites and lint stayed green.

New actionable issue: child-session creation itself is still outside the
cancellation-safe commit/abort discipline. For normal session steps,
`:step/enter` creates the workflow-owned child session before the guarded
attempt-start CAS. If cancellation wins during/after `create-step-attempt-session!`
but before the attempt is attached to the run, the attempt-start write is skipped
and the turn does not run, but production can still leave a newly-created ordinary
workflow child session after the D31 checkpoint with no recorded attempt for the
D28 guarded abort to target. Judge execution has the same shape: `execute-judge!`
creates the judge session before `attach-judge-session-if-live!`; if cancellation
wins before attach, the judge output is suppressed but the just-created judge session
is not recorded/aborted. Follow-up should make actor/judge child-session creation
cancellation-safe by either moving creation behind an attach/commit protocol that
can be guarded atomically, or immediately aborting/cleaning the just-created session
when the live-run attach CAS fails; add regressions for cancellation between child
session creation and run attempt/judge attachment.

Verification during review:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-execution-cancellation-test --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 21 tests / 96 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-execution-test --namespace psi.agent-session.workflow-execution-cancellation-test` → 17 tests / 83 assertions green.
- `clj-kondo --lint components/agent-session/test/psi/agent_session/workflow_execution_cancellation_test.clj components/agent-session/test/psi/agent_session/workflow_execution_test.clj` → clean.

## Review follow-up implementation — abort unattached actor/judge child sessions (ψ, 2026-06-11)

Executed both newly-added implementation-review pass-4 follow-ups.

- Added an explicit `:abort-session!` operation to the workflow execution adapter so lower workflow-runtime code can request session-owned cancellation cleanup without depending directly on agent-session internals. The production adapter aborts any active turn and drives the agent-core abort state for the target session.
- Made actor child-session creation cancellation-safe: when `:step/enter` creates an ordinary workflow child session but the guarded attempt-start CAS loses to cancellation/removal, the just-created execution session is immediately aborted before workflow cancellation is queued. The existing step-entry race regression now also asserts the unattached child session is aborted.
- Made judge child-session creation cancellation-safe: when `execute-judge!` creates a judge child session but `attach-judge-session-if-live!` loses to cancellation/removal, the just-created judge session is immediately aborted before the workflow-stopped exception is thrown. Added a regression covering cancellation between judge child-session creation and judge-session attachment.

Verification:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.agent-session.workflow-judge-test --namespace psi.workflow-runtime.execution-adapter-test` → 32 tests / 143 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-test --namespace psi.agent-session.workflow-execution-cancellation-test --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 27 tests / 135 assertions green.
- `clj-kondo --lint components/workflow-runtime/src/psi/workflow_runtime/execution_adapter.clj components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime.clj components/agent-session/src/psi/agent_session/context.clj components/agent-session/src/psi/agent_session/workflow_judge.clj components/workflow-runtime/test/psi/workflow_runtime/execution_adapter_test.clj components/agent-session/test/psi/agent_session/workflow_judge_test.clj components/agent-session/test/psi/agent_session/workflow_statechart_runtime_cancellation_test.clj` → clean.

Additional gate verification after pass-4 follow-ups:

- `bb test` → green.
- `clj-kondo --lint components` → errors 0 / warnings 0; existing info-level suggestions outside this pass remain.

## Implementation review (ψ pass 5, 2026-06-11)

Reviewed the post-pass-4 implementation against `task-implementation-review`, the
D30/D31 no-post-checkpoint ordinary child-turn contract, current task artifacts,
and changed workflow runtime/judge code. The unattached actor/judge child-session
follow-ups are implemented and covered.

New actionable issue: retry/fallback loops can still start additional ordinary
child turns after a D31 cancel checkpoint because the cancellation predicate is
only checked outside the loop or after the retry turn returns. In
`step_execution.clj`, `execute-with-ranked-fallback!` can proceed from one
fallback-worthy model failure to the next `execute-actor-turn!` without rechecking
workflow stop state between candidate attempts. In `workflow_judge.clj`, both
structured-output judge retries and no-match judge retries can call
`execute-judge-turn!` again after routing decides to retry but before a fresh stop
check. These paths can initiate a new ordinary actor/judge turn after cancellation;
follow-up should thread/check the stop predicate immediately before every fallback
or judge retry turn and add regressions.

## Implementation review follow-up pass 5 (ψ, 2026-06-11)

Executed the two new pass-5 follow-ups. Ranked actor fallback now carries the workflow stop predicate into `execute-with-ranked-fallback!` and checks it before every non-initial candidate turn, so cancellation after a fallback-worthy failure returns control to `execute-session-step!`'s existing stopped path instead of installing the next candidate model or starting another actor turn. Judge execution now funnels initial and retry judge turns through a live-check helper; structured-output retry and no-match retry branches re-check immediately before invoking the retry turn and throw the existing `:workflow-stopped` exception on cancellation.

Regression coverage added for cancellation between ranked fallback candidates, no-match judge retry attempts, and structured-output judge retry attempts. Focused Scry runs over the changed workflow cancellation/judge/statechart namespaces pass (27 tests / 122 assertions and 24 tests / 127 assertions), the three new regression vars pass (3 tests / 9 assertions), and focused clj-kondo over changed source/tests is clean.

## Implementation review (ψ pass 6, 2026-06-11)

Reviewed the post-pass-5 implementation against `task-implementation-review`, the
D30/D31 no-post-checkpoint ordinary-work contract, current task artifacts, and the
changed actor/judge retry cancellation code. The retry/fallback follow-ups are
implemented and covered.

New actionable issue: initial ordinary execution starts still have a cancellation
race after the last stop check. For normal actor session steps,
`execute-session-step!` checks `stopped?` before entering the execution branch, but
then calls `execute-actor-turn!`; if the D31 cancel CAS lands after that check and
before the turn call, the guarded cancel abort can hit an idle child session and
then the actor turn may still start after cancellation. Judge execution has the
same shape in `execute-judge-turn-if-live!`: the pre-turn `assert-workflow-live!`
can pass, cancel can commit/abort an idle judge session, and the subsequent
`execute-judge-turn!` can start. Invoke steps likewise check before
`invoke-step-runtime-result`, but a cancel racing after that check can still start
the deterministic operation. Follow-up should make initial actor turn, initial
judge turn, and invoke-operation start gates cancellation-safe (or make the abort
leave a durable per-session stop marker consumed by turn start) and add regressions
for cancellation between the final pre-start stop check and each ordinary execution
start.

## Implementation review (ψ pass 7, 2026-06-11)

Reviewed the current implementation against `task-implementation-review`, task
artifacts, changed cancellation code/tests, `CHANGELOG.md`, `doc/architecture.md`,
and `doc/workflows.md`. The retry/fallback follow-ups are implemented and focused
cancellation/judge suites remain green.

New actionable issue: user-facing workflow docs are stale for the changed
`delegate remove` semantics. `doc/workflows.md` still describes `delegate remove` as
pre-cleaning/terminalizing an active delegate/background job and failing if that
cleanup cannot complete; the implemented task contract is canonical
`:psi.workflow/remove-run` cancel-then-remove with dispatch-owned terminalization,
worker cancel-before-drop cleanup, nested-run parent continuation, and idempotent
terminal/absent remove semantics. Update the workflow docs so users are not guided
by the old command-layer cleanup/failure model.

Verification during review:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.agent-session.workflow-judge-test --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.workflow-runtime.statechart-runtime.step-execution-test` → 45 tests / 236 assertions green.

## Implementation follow-up pass 6 (ψ, 2026-06-11)

Executed the newly added pass-6 cancellation follow-up. Initial ordinary start
windows are now guarded at the lower execution boundaries in addition to the
workflow lifecycle pre-checks:

- ordinary actor/judge turn execution checks canonical workflow-owned session
  linkage immediately before calling the prompt adapter; cancelled/removed runs
  return a stopped turn result and do not invoke the prompt path;
- deterministic operation runtime checks `:workflow-run-id` in the invocation's
  ctx immediately before invoking the operation handler; cancelled/removed runs
  return a tagged `:workflow-stopped` operation error and do not call the handler;
- the statechart ordinary-session path aborts a just-attached child session if a
  final pre-turn cancellation check trips.

Added regressions for cancelled actor turn start, cancelled judge turn start, and
cancelled deterministic operation start. Focused lint and focused suites passed:
`psi.deterministic-operation-runtime.core-test`,
`psi.workflow-runtime.turn-execution-contract-test`,
`psi.agent-session.workflow-statechart-runtime-cancellation-test`,
`psi.agent-session.workflow-judge-test`,
`psi.agent-session.workflow-execution-cancellation-test`, plus workflow execution
smoke suites.

## Implementation review (ψ pass 8, 2026-06-11)

Reviewed the post-pass-6 implementation against `task-implementation-review`, the
D30/D31 no-post-checkpoint ordinary-work contract, changed cancellation code/tests,
`CHANGELOG.md`, `doc/architecture.md`, and `doc/workflows.md`. The actor/judge
turn-start and deterministic-operation start gates are now guarded at the lower
execution boundary and focused cancellation suites remain green.

No new code/test implementation issue found in this pass. The existing unchecked
pass-7 follow-up remains actionable: `doc/workflows.md` still documents the old
`delegate remove` command-layer cleanup/fail-if-cleanup-fails semantics instead of
the implemented canonical cancel-then-remove / dispatch-owned cleanup contract. No
new follow-up item was added to `steps.md` to avoid duplicating that existing step.

Verification during review:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.agent-session.workflow-judge-test --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.workflow-runtime.statechart-runtime.step-execution-test --namespace psi.workflow-runtime.turn-execution-contract-test --namespace psi.deterministic-operation-runtime.core-test` → 49 tests / 255 assertions green.

## Implementation follow-up pass 7 (ψ, 2026-06-11)

Executed the remaining pass-7 documentation follow-up. `doc/workflows.md` no longer describes `delegate remove` as command-layer active-background-job pre-cleanup that can fail before canonical removal. It now states the implemented workflow removal contract:

- live top-level remove is canonical dispatch cancel-then-remove, with cancelled background-job terminalization and worker interrupt before runtime-handle drop;
- live nested delegate sub-run remove aborts the child turn, removes the sub-run record, and lets the parent continue via cancelled/removed failed-step semantics;
- terminal remove is idempotent canonical-record cleanup;
- absent remove is canonical success/no-op with stale runtime-handle cleanup when applicable;
- job terminalization and worker-handle cleanup are dispatch-owned runtime effects.

Validation: `git diff --check` passed. No code/test changes were required for this doc-only follow-up.

## Implementation review (ψ pass 9, 2026-06-11)

Reviewed the post-doc-follow-up implementation against `task-implementation-review`, task artifacts, cancellation dispatch/effect code, workflow execution/judge/runtime guards, docs, and focused tests. `doc/workflows.md` now reflects the canonical remove contract and focused cancellation suites remain green.

New actionable issue: guarded judge abort idempotency is still weaker than D22.2. `workflow-abort-guard-matches?` treats a judge attempt with `:status :succeeded` as live so cancellation can abort an already-completed judge session after judge output was recorded, because judged actor attempts also remain `:succeeded` while judging. The guard needs a judge-specific in-flight/completed distinction (or an active-turn/session liveness check) so duplicate/stale guarded judge abort effects no-op after the judge turn/result is complete, while still aborting a genuinely in-flight judge turn.

Verification during review:

- `git diff --check` passed.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.workflow-runtime.turn-execution-contract-test --namespace psi.deterministic-operation-runtime.core-test` → 21 tests / 100 assertions green.

## Implementation follow-up pass 8 (ψ, 2026-06-11)

Executed the pass-8 guarded judge abort follow-up. Guarded workflow `:runtime/agent-abort` now treats judge sessions as abortable only until the judge result has been recorded. The executor no longer uses `:status :succeeded` alone as judge liveness proof; for judge abort guards it also requires absence of the durable `:judge-output` completion marker. This preserves in-flight judge aborts on judged actor attempts (which can be `:succeeded` while judging) while making stale/duplicate guarded abort effects no-op after judge output/result recording.

Added regression coverage in `workflow-cancellation-dispatch-test`: an in-flight judge session on a `:succeeded` actor attempt remains abortable, stale expected-session guards no-op, and the same guarded judge abort effect no-ops after `:judge-output`/`:judge-event` are recorded.

Validation:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test` → 8 tests / 52 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.workflow-runtime.turn-execution-contract-test --namespace psi.deterministic-operation-runtime.core-test` → 21 tests / 101 assertions green.

## Implementation review (ψ pass 10, 2026-06-11)

Reviewed the post-pass-8 implementation against `task-implementation-review`, the
D30/D31 no-post-checkpoint ordinary-work contract, current task artifacts, changed
cancellation dispatch/effect/runtime code, docs, and focused cancellation tests. The
completed judge-abort guard now no-ops after judge output is recorded, and the
focused suites remain green.

New actionable issue: the pass-6 “initial ordinary start” fix is still a
check-then-call guard, not a cancellation-safe start protocol. Actor and judge turn
start (`turn_execution_contract.clj` → `prompt-execution-result`) read canonical
workflow state, then immediately call the prompt adapter; deterministic operation
start (`deterministic_operation_runtime/core.clj`) reads canonical workflow state,
then calls the operation handler. If the D31 cancel CAS lands after that final read
but before the prompt adapter / operation handler call, a new ordinary turn or
operation can still be initiated after the cancel checkpoint. Guarded abort may later
stop a prompt turn, but it does not prevent the forbidden post-checkpoint start, and
there is no equivalent abort for deterministic operation handlers. Follow-up should
replace these check-then-call gates with a cancellation-safe start protocol (for
example a CAS/reservation that makes “started before D31” explicit, or a durable
per-session/per-invocation stop marker consumed at the actual start boundary) and add
race regressions for cancel landing after the final read but before actor, judge, and
invoke starts.

Verification during review:

- `bb clojure:test:scry --namespace psi.agent-session.workflow-cancellation-dispatch-test --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.workflow-runtime.turn-execution-contract-test --namespace psi.deterministic-operation-runtime.core-test --namespace psi.agent-session.workflow-judge-test` → 39 tests / 193 assertions green.

## Implementation follow-up pass 10 (ψ, 2026-06-11)

Executed the pass-10 final read→call cancellation follow-up. The remaining
check-then-call gates now use a cancellation-safe start reservation immediately
before ordinary work starts:

- workflow-owned actor and judge turn starts CAS-mark the latest live attempt with
  `:turn-started-at` / `:turn-start-count` after the review-injected final race
  window and before calling the prompt adapter; if cancellation/removal wins that
  CAS, the prompt adapter is not invoked and the caller receives a workflow-stopped
  result;
- deterministic-operation starts CAS-mark the latest live invoke attempt with
  `:operation-started-at` / `:operation-start-count` after the final race window
  and before invoking the operation handler; if cancellation/removal wins, the
  handler is not invoked and a `:workflow-stopped` operation result is returned;
- invoke-step and invoke-judge callers also re-check after the operation returns so
  a cancellation racing during operation execution does not record ordinary invoke
  outputs.

Added regressions for cancellation in the forced final read→call window for actor
turn start, judge turn start, and deterministic operation invocation.

Validation:

- `bb clojure:test:scry --namespace psi.workflow-runtime.turn-execution-contract-test --namespace psi.deterministic-operation-runtime.core-test --namespace psi.agent-session.workflow-judge-test --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test` → 34 tests / 151 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-execution-test --namespace psi.agent-session.workflow-execution-cancellation-test --namespace psi.agent-session.workflow-invoke-runtime-test --namespace psi.workflow-runtime.statechart-runtime.step-execution-test --namespace psi.workflow-runtime.terminal-contract-execution-test` → 31 tests / 166 assertions green.
- Focused `clj-kondo --lint` over changed cancellation source/test files → clean.

## Implementation review (ψ pass 11, 2026-06-11)

Reviewed the pass-10 start-reservation implementation against `task-implementation-review`, D30/D31, the changed actor/judge/invoke start paths, and the new regressions. The previous check-then-call window is narrowed but not closed.

New actionable issue: `:turn-started-at` / `:operation-started-at` reservation is still not atomic with the ordinary side-effecting call. Actor/judge turn execution and deterministic operation invocation CAS-mark the latest attempt, then call the prompt adapter / operation handler. A D31 cancel CAS can still land after the reservation CAS but before that call, so the prompt adapter or operation handler can initiate ordinary work after the cancel checkpoint. The current regressions force cancellation before reservation, not in the post-reservation→call window. Follow-up should make reservation and crossing into ordinary work mutually ordered with cancellation (or otherwise make cancel observe/close the reservation as an already-started in-flight unit) and add actor, judge, and invoke regressions for cancellation after a successful start reservation but before the adapter/handler call.

No tests run (review-only pass).

## Implementation follow-up pass 11 (ψ, 2026-06-11)

Executed the pass-11 post-reservation cancellation follow-up. Actor/judge turn
starts and deterministic-operation starts now split "reserved" from "started":

- the first CAS marks the latest live attempt `:turn-start-state :reserved` or
  `:operation-start-state :reserved` with a reservation timestamp;
- the review/test hook can force cancellation in the post-reservation window;
- a second CAS is the actual ordinary-start linearization point. It re-reads the
  canonical run and attempt, refuses `:cancelled`/removed/mismatched attempts, and
  only then marks `:turn-start-state :started` / `:operation-start-state :started`
  plus the started timestamp/count before crossing into the prompt adapter or
  operation handler.

If cancellation wins after reservation but before this start-commit CAS, the
adapter/handler is not called and callers receive the existing workflow-stopped
result/exception path. Judge coverage uses the real turn-execution boundary (not a
redefined `execute-judge-turn!`) so it exercises the same actor/judge reservation
and start-commit protocol.

Added regressions for actor, judge, and invoke cancellation after a successful
start reservation but before adapter/handler call.

Validation:

- `bb clojure:test:scry --namespace psi.workflow-runtime.turn-execution-contract-test --namespace psi.deterministic-operation-runtime.core-test --namespace psi.agent-session.workflow-statechart-runtime-cancellation-test --namespace psi.agent-session.workflow-judge-cancellation-test --namespace psi.agent-session.workflow-judge-test` → 37 tests / 165 assertions green.
- `bb clojure:test:scry --namespace psi.agent-session.workflow-execution-test --namespace psi.agent-session.workflow-execution-cancellation-test --namespace psi.agent-session.workflow-invoke-runtime-test --namespace psi.workflow-runtime.statechart-runtime.step-execution-test --namespace psi.workflow-runtime.terminal-contract-execution-test` → 31 tests / 166 assertions green.
- Focused `clj-kondo --lint` over changed cancellation source/test files → clean.

## Implementation review (ψ pass 12, 2026-06-11)

Reviewed the pass-11 start protocol against `task-implementation-review`, D6/D30/D31,
`turn_execution_contract.clj`, `deterministic_operation_runtime/core.clj`, and the
workflow-cancellation regressions. The reservation→start CAS split prevents a cancel
that lands before the second CAS from starting ordinary work, but one race remains.

New actionable issue: the second CAS marks `:turn-start-state` / `:operation-start-state`
`:started`, then the code calls the prompt adapter / operation handler outside that CAS.
If the D31 cancel CAS lands after the start-commit CAS but before the adapter/handler
call, the ordinary work can still be initiated after cancellation. For actor/judge
turns the guarded abort effect may execute while the child session is still idle, so it
leaves no durable stop marker; the later `prompt-dispatch!` call can still start the
turn. For deterministic operations there is no abort path at all, so the handler can
run after the cancel checkpoint. Follow-up should close the start-commit→ordinary-call
window by making cancellation observe and durably block/abort a committed-but-not-yet-
called start, or by moving the actual call under a cancellation-safe runtime boundary
that re-checks after any concurrent cancel effects. Add regressions for cancel after a
successful `:started` commit but before actor, judge, and invoke calls.

No tests run (review-only pass).

## Implementation follow-up pass 12 (ψ, 2026-06-11)

Closed the start-commit → ordinary-call race for workflow-owned actor/judge turns
and deterministic operations. The previous start protocol reserved and then
committed `:turn-start-state` / `:operation-start-state` `:started`, but a D31
cancel CAS could still land before `prompt-execution-result!` / operation handler
entry. Added a second guarded call-begin CAS (`:turn-call-state` /
`:operation-call-state`) after the start commit and before the ordinary adapter /
handler call, plus a final stop-signal read immediately before crossing the
boundary. Cancellation in the reviewed start-commit window now returns the
standard workflow-stopped result with no ordinary work; a committed call-begin is
now the durable marker for cancellation to treat the unit as already in flight.

Added regressions for actor turn, judge turn, and deterministic operation
cancellation after the start commit and before call-begin. Focused Scry suites passed:
`psi.deterministic-operation-runtime.core-test`,
`psi.agent-session.workflow-statechart-runtime-cancellation-test`, and
`psi.agent-session.workflow-judge-cancellation-test` (21 tests / 82 assertions).
Focused clj-kondo over touched code/tests passed with 0 warnings.

## Implementation review (ψ pass 13, 2026-06-11)

Reviewed the pass-12 call-begin implementation against `task-implementation-review`,
D30/D31, `turn_execution_contract.clj`, `deterministic_operation_runtime/core.clj`,
and the actor/judge/invoke cancellation regressions. The new call-begin CAS plus
final stop read closes cancellation that lands before the final read, but it still
leaves one ordinary-start race.

New actionable issue: `:turn-call-state` / `:operation-call-state` is marked
`:started`, then the code performs a final stop read and calls the prompt adapter /
operation handler outside any cancellation-safe boundary. If the D31 cancel CAS lands
after that final read but before `prompt-execution-result!` or the deterministic
operation handler call, ordinary work can still be initiated after cancellation. The
guarded abort effect can also run while the child session is still idle and leave no
durable stop marker; deterministic operations have no abort path. Follow-up should
make call-begin itself the start linearization point that cancellation observes and
blocks/aborts durably, or move the final ordinary call under a boundary that consumes
a durable cancel marker after concurrent cancel effects. Add actor, judge, and invoke
regressions for cancellation after successful call-begin/final stop read but before
the adapter/handler call.

No tests run (review-only pass).

## Follow-up pass 13 — close call-begin to ordinary-call race (ψ, 2026-06-11)

Closed the pass-13 cancellation race by splitting workflow ordinary-call startup into a call-begin marker and a second live-run call-commit CAS immediately before crossing into ordinary work. Actor and judge turns now record `:turn-call-state :begun` at call-begin, then re-check run presence/`:cancelled` in `commit-workflow-turn-call!` before recording `:turn-call-state :committed` and invoking the prompt adapter. Deterministic operations do the analogous `:operation-call-state :begun` → `:committed` transition before invoking the operation handler. Workflow-owned `prompt-dispatch!` now also consumes the canonical cancellation/removal signal at the agent-session turn boundary, so a concurrent cancel that lands before the adapter call returns a stopped execution result instead of submitting a prompt.

Added regressions for actor, judge, and invoke cancellation after successful call-begin and before the adapter/handler call. Focused Scry namespaces `psi.agent-session.workflow-statechart-runtime-cancellation-test` and `psi.agent-session.workflow-judge-cancellation-test` pass (22 tests / 88 assertions). Focused clj-kondo on changed source/test files is clean.
