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
