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
