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
