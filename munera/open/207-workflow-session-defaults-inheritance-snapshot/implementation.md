# Implementation notes — 207

## Architecture-fit review (ψ)

Reviewed design.md for architectural fit against AGENTS.md (VSM/S1–S5,
state-boundary, one-way), META.md (canonical run state, snapshot inheritance),
doc/architecture.md (canonical root vs runtime handles), and
doc/workflow_statechart_canonical.md (canonical surfaces).

Overall: core intent fits strongly. Capturing inherited defaults into the run's
canonical state at invoke time (deterministic/replayable) mirrors the existing
"spawned sessions inherit parent configuration snapshot, then evolve
independently" principle (META.md) and the VSM closure invariant
(∀change → event → log → replayable). AC#9 is well-aligned with the
canonical-root state boundary: the run already lives in `:state*` via
`create-run`.

Actionable architectural-fit gaps (purity/ownership boundaries):

1. **Purity boundary of snapshot capture vs `create-run`.**
   `workflow-runtime.core/create-run` is documented as the *canonical pure*
   root-state lifecycle op — it takes `state`, not `ctx`. The snapshot the
   design requires (resolved model/prompt-mode/tools/skills/thinking-level/
   speed-mode/effort-override) is derived from a *live* parent session via
   `ctx` reads (`get-session-data`, `all-skills`, `agent-tool-source-in`). The
   design fixes capture timing ("at invoke/create") but does not place the
   impure resolution outside the pure lifecycle op. Fit risk: pushing ctx reads
   into `create-run` would break its purity contract. Design should state that
   the snapshot is resolved impurely by the caller and passed *as data* into
   pure `create-run` (run state carries already-resolved snapshot).

2. **Component ownership of effective-config resolution (nested decision 3).**
   The "effective" config = run snapshot ⊕ step overrides is computed in
   `workflow-step-session-config.core/resolve-step-session-config`, a different
   canonical surface than `create-run` (`workflow-runtime.core`). Decision 3
   requires nested-run creation to capture the delegating step's *effective*
   config, but does not name which component owns producing that effective
   snapshot for the child run. Fit risk: duplicating resolution logic across
   the two components, or a layering inversion where `workflow-runtime` reaches
   into `workflow-step-session-config`. Design should assign single ownership
   of effective-snapshot derivation and the data hand-off to child `create-run`.

3. **Snapshot field-set authority / single source of truth.** Decision 1 ties
   the snapshot field set to `session-state/init.clj`'s
   `common-inherited-fields` (the canonical child-session inheritance set), but
   the design re-enumerates the fields rather than deriving from that authority.
   Fit risk (one-way / single-source-of-truth): two independent inheritance
   field lists drift. Design should make the workflow snapshot field set a
   derivation of / explicitly checked against the canonical inheritance set
   rather than a parallel hand-maintained list.

## Architecture-fit follow-up resolution (ψ, 2026-06-02)

All three architecture-fit design-steps resolved into design.md as Decisions
6–8 (design-only; no code). Grounded against real code first:

- `create-run` (`workflow-runtime/core.clj:110`) takes `state`, not `ctx` →
  pure. All three call sites already read `@(:state* ctx)` impurely
  (`psi_tool_workflow.clj:148`, `mutations/canonical_workflows.clj:96`,
  `statechart_runtime/delegate.clj:44`). → **Decision 6**: snapshot resolved by
  caller, passed as `:inherited-defaults` data into pure create-run; create-run
  records verbatim, no ctx reads.
- `resolve-step-session-config` lives in `workflow-step-session-config/core.clj`
  (live parent reads at `:163–168`); create-run lives in `workflow-runtime` —
  distinct components. → **Decision 7**: `workflow-step-session-config` is the
  single owner of snapshot derivation (new `resolve-inherited-defaults-snapshot`
  reusing the no-override resolution path); nested/effective snapshot derived
  from the same component's effective config; `workflow-runtime` never reaches
  into `workflow-step-session-config` (caller wires both → no layering
  inversion / no duplicated resolution).
- `common-inherited-fields` (`session-state/init.clj:30`, private, already
  includes `:speed-mode`/`:effort-override`) is the canonical inheritance set. →
  **Decision 8**: promote to public/accessor + test-assert the workflow snapshot
  field set matches it (modulo resolved-vs-raw `:tool-defs`/`:skills` vs
  `:tool-ids`/`:skill-ids`) → single source of truth, no drift.

No design-steps blocked; all three completable as design refinements. Code
implementation remains for the plan phase (steps.md), not this design pass.
