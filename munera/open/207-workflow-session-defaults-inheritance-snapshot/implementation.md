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

## Ambiguity review (ψ, 2026-06-02)

Reviewed design.md against code ground truth (`session-state/init.clj`,
`workflow-step-session-config/core.clj`, `workflow-runtime/core.clj`,
`statechart_runtime/delegate.clj`, `mutations/canonical_workflows.clj`,
`workflow/{core,orchestration}.clj`). New actionable ambiguities (distinct from
resolved architecture-fit notes):

1. **Decision 8 field-set scope.** `common-inherited-fields`
   (`init.clj:30`) holds ~20 fields; snapshot enumerates 7. `:model`/
   `:thinking-level` are NOT in `common-inherited-fields` — they live in a
   separate `model-identity-fields` constant. "Derive from / validate against
   `common-inherited-fields`" (Decision 8 / note 3) is ambiguous about *which
   subset*, and the "modulo resolved-vs-raw `:tool-defs`/`:skills`" caveat
   covers only 2 of the divergences — it ignores the dozen extra authoritative/
   runtime fields and the model/thinking-level cross-constant gap.

2. **Decision 5 continue vs resume.** Two distinct mechanisms: `resume-run`
   (same run-id, reuses run state) and `continue-terminal-run-async!`
   (`orchestration.clj:208`, creates a NEW run via `create-run`). Decision 5
   only addresses "resuming a blocked run." Whether continue (a fresh
   create-run) reuses the original snapshot or captures a new one (Decision 6
   would capture fresh) is undefined.

3. **Decision 7 nested-derivation entry point.** Named resolver
   `resolve-inherited-defaults-snapshot(ctx, parent-session-id)` is the
   top-level path. The nested case derives the snapshot from the delegating
   step's *effective* config, but `delegate.clj`'s
   `delegate-step-runtime-result` does not currently resolve that effective
   config (it holds `ctx`/`parent-session-id`/`step-id`/`workflow-run` only).
   Design does not specify the function signature/entry point for the nested
   "effective config → snapshot" derivation, nor whether delegate must call
   `resolve-step-session-config` first.

4. **Decision 6 "caller / invocation site" across the mutation hop.**
   `create-run` is reached both directly (mutation `create-workflow-run`,
   `delegate.clj`, `psi_tool_workflow.clj`) and via `mutate!
   'psi.workflow/create-run` upstream callers (`workflow/core.clj:382`,
   `orchestration.clj:208`). Design names three *direct* sites but says
   resolution lives at "the caller." For the mutation path the resolver needs
   `ctx`+`parent-session-id`; the mutation holds `agent-session-ctx`+
   `session-id`. Design does not fix whether resolution happens inside the
   mutation or its upstream `mutate!` caller.


## Ambiguity follow-up resolution (ψ, 2026-06-02)

All four ambiguity design-steps resolved into design.md as Decisions 5a/5b, 6a,
7a, 8a (design-only; no code). Grounded against real code first:

- **Field set (8a).** `common-inherited-fields` (`init.clj:30`) has 19 keys and
  is a child-session-init concern; it excludes `:model`/`:thinking-level`
  (those are `model-identity-fields`, `init.clj:67`). The workflow snapshot is a
  narrower resolved-default set: 7 resolved keys
  `{:model :prompt-mode :tool-defs :skills :thinking-level :speed-mode
  :effort-override}` sourcing from `common-inherited-fields`
  (`:prompt-mode :speed-mode :effort-override :tool-ids :skill-ids`, with
  tool-ids→tool-defs, skill-ids→skills) + `model-identity-fields`
  (`:model :thinking-level`). The ~12 other `common-inherited-fields` entries
  are explicitly excluded (not per-step inherited defaults the resolver
  overrides). Validation: a named source-key constant in
  `workflow-step-session-config` + a test asserting each source key ∈ its
  authority and the resolved-key mapping, so neither list can drift.

- **continue vs resume (5a/5b).** Two distinct mechanisms confirmed in
  `orchestration.clj`: `continue-blocked-run-async!` calls `resume-run` (same
  run-id, reuses run state → reuses stored snapshot, no re-capture);
  `continue-terminal-run-async!:201` calls `mutate! 'psi.workflow/create-run`
  (a NEW run from `source-definition-id`, carrying a fresh user prompt). →
  resume reuses; continue is an ordinary fresh create-run that captures a fresh
  snapshot from the live session at continuation time (Decision 6 governs it for
  free — no special threading, which `continue-terminal-run-async!` could not do
  anyway, holding only `mutate!`/`run-id`/`session-id`).

- **nested entry point (7a).** `delegate-step-runtime-result` (`delegate.clj:36`)
  holds `ctx`/`parent-session-id`/`step-id`/`step-def`/`workflow-run` but does
  not resolve effective config. → delegate first calls
  `resolve-step-session-config` (`ctx parent-session-id workflow-run step-id`,
  which already produces effective config = run snapshot ⊕ step overrides), then
  a new pure `effective-config->snapshot` (effective-config → snapshot, no ctx
  reads), passing the result as `:inherited-defaults` into create-run at
  `delegate.clj:44`. delegate does NOT call
  `resolve-inherited-defaults-snapshot` (would re-read live parent + drop
  overrides). Two named functions: top-level `(ctx parent-session-id)` impure;
  nested `(effective-config)` pure projection — shared so paths can't drift.

- **mutation-hop site (6a).** Three direct `workflow-runtime/create-run` sites:
  `canonical_workflows.clj:96` (the `create-workflow-run` mutation, holds
  `agent-session-ctx`+`session-id`), `psi_tool_workflow.clj:148` (holds
  `ctx`+`session-id`), `delegate.clj:44` (nested, Decision 7a). The two
  `mutate! 'psi.workflow/create-run` upstream callers (`workflow/core.clj:382`,
  `orchestration.clj:208`) hold no ctx → resolution lives INSIDE the
  `create-workflow-run` mutation (and the psi-tool op site), leaving both
  `mutate!` callers untouched. Single mutation-path resolution point also makes
  Decision 5b (continue) capture-fresh automatic.

No ambiguity design-steps blocked; all four completable as design refinements.
Code implementation remains for the plan phase (steps.md), not this design pass.

## Inconsistency review (ψ, 2026-06-02)

Reviewed design.md for internal inconsistency and design↔code drift. Grounded
against `workflow-step-session-config/core.clj` (`resolve-step-session-config`
`:158`, live reads `:163–168`), `session-state/init.clj`
(`common-inherited-fields` `:30` = 19 keys; `model-identity-fields` `:67`),
`workflow-runtime/.../delegate.clj` (`delegate-step-runtime-result` `:36`,
create-run `:44`), `mutations/canonical_workflows.clj` (`create-workflow-run`,
create-run `:96`), `psi_tool_workflow.clj` (create-run `:148`),
`workflow/orchestration.clj` (`continue-terminal-run-async!` defn `:201`,
`mutate!` `:208`). Most line refs and structural claims verified accurate
(`:201` defn vs `:208` mutate! is consistent, not a contradiction).

New actionable inconsistencies:

- **I1 (design↔code + internal).** Decisions 7 and 7a state
  `resolve-inherited-defaults-snapshot` "reuses the same live-read logic
  `resolve-step-session-config` uses for the no-override path" and lists that
  path's reads as including **`speed-mode` and `effort-override`**. The actual
  `resolve-step-session-config` reads neither — it outputs only
  `:developer-prompt :prompt-mode :response-mode :tool-defs :thinking-level
  :skills :model` (+ optional temperature/model-fallback/logprob); there is no
  `:speed-mode`/`:effort-override` read or output anywhere in the resolver
  (grep-confirmed empty). This also contradicts Decision 1, which correctly
  frames `speed-mode`/`effort-override` as the *recently introduced* overrides
  added **on top of** the fields "the resolver inherits live today"
  (`model prompt-mode skills tools thinking-level`) — i.e. NOT part of the
  existing no-override logic. So the snapshot resolver must *add* speed-mode/
  effort-override ctx reads (Decision 1's intent); it cannot "reuse" a
  no-override path that already reads them. Decisions 7/7a should state these
  two reads are new (not reused from `resolve-step-session-config`'s current
  logic), aligning with Decision 1.

- **I2 (internal, minor).** Decision 8a says `common-inherited-fields` holds
  "~20 fields" and that "the dozen other entries are deliberately excluded",
  but the vector has 19 keys; with 5 included (`prompt-mode speed-mode
  effort-override tool-ids skill-ids`) exactly **14** are excluded — and 8a
  itself enumerates 14. "dozen" (12) understates its own complete list.
  Reconcile the count ("14", not "dozen"/"~20") with the enumeration.

PASS_STATUS: ACTIONABLE_FEEDBACK.

## Inconsistency follow-up resolution (ψ, 2026-06-02)

Both inconsistency design-steps (I1, I2) resolved into design.md (design-only;
no code). Grounded against real code first:

- **I1.** `resolve-step-session-config`
  (`workflow-step-session-config/core.clj:145`) reads/outputs neither
  `:speed-mode` nor `:effort-override` (grep-confirmed: only `:model`
  `:prompt-mode` `:thinking-level` `:model-fallback` appear; no speed/effort
  read anywhere in the resolver). Decisions 7 and 7a previously claimed the
  snapshot resolver "reuses" the no-override path *including* speed-mode/
  effort-override — false, and contradicted Decision 1 (which frames those two
  as recently introduced overrides layered on top of the live-inherited set).
  Fixed both: Decision 7 now states the resolver is built on
  `resolve-step-session-config`'s actual no-override reads (model/prompt-mode/
  skills/tool-defs/thinking-level) and **adds** the two new `:speed-mode`/
  `:effort-override` ctx reads (citing the resolver's real output set and the
  fact it reads neither today). Decision 7a's
  `resolve-inherited-defaults-snapshot` bullet now distinguishes the reused
  reads from the two newly-added reads. Now aligned with Decision 1.

- **I2.** `common-inherited-fields` (`init.clj:30`) vector has **19** keys, not
  "~20". Included raw keys = 5 (`:prompt-mode :speed-mode :effort-override
  :tool-ids :skill-ids`) → **14** excluded (the enumeration in 8a lists exactly
  14: 3 capability + 8 preferences + 1 ui-type + 2 telemetry). Fixed Decision
  8a: "19 fields" (not "~20") and "remaining 14 … entries (19 total minus the 5
  included raw keys)" (not "dozen"). Count now matches its own enumeration.

No inconsistency design-steps blocked; both completable as design refinements.
Code implementation remains for the plan phase (steps.md), not this design pass.
