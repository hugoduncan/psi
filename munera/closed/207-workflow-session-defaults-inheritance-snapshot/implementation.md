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

PASS_STATUS: ACTIONABLE_FEEDBACK

## R5/R6/R7 follow-up executed (ψ, 2026-06-02)

R4's `:inherited-snapshot?` refinement committed and made coherent end-to-end.

- **Re-gate from `workflow-owned?` to `:inherited-snapshot?`.** The committed R4
  fix gated child-state snapshot isolation on `workflow-owned?'`, which would
  regress the workflow judge: `workflow_judge.clj:107` creates a workflow-owned
  child WITHOUT `:model`/`:prompt-mode` and relies on live-parent inheritance.
  Under a `workflow-owned?` gate the judge would have received the
  initial-session default (nil model) instead of the parent model. The fix gates
  on a new `:inherited-snapshot?` flag instead — true only on the
  resolver/step-attempt path where the inherited fields are snapshot-governed.
  `child-session-base-state*` now uses
  `(if inherited-snapshot?' (or supplied default) (or supplied parent-value))`.
- **Producer.** `create-step-attempt-session!` (`workflow-runtime/attempts.clj`)
  sets `:inherited-snapshot? true` in the child-session request (the resolver
  output is snapshot-governed there).
- **Threading.** `:inherited-snapshot?` flows
  `create-workflow-child-session!` (`agent-session/context.clj`) →
  `:session/create-child` (`dispatch_handlers/session_lifecycle.clj`) →
  `child-session-base-state*`. Declared on
  `child_session_contract/request-schema` (committed HEAD already had the schema
  field; this work adds the missing producer + consumer, closing the dangling
  field flagged by R5).
- **Why `workflow-owned?` was insufficient.** Workflow-owned ≠ snapshot-governed.
  The judge (and any future workflow-owned child created outside the resolver
  path) is workflow-owned but supplies no inherited defaults and must keep live
  parent inheritance. Only the resolver/attempt path carries snapshot values, so
  only it sets `:inherited-snapshot?`.
- **Tests.**
  `child-session-base-state-workflow-owned-isolates-snapshot-fields-test`
  re-gated onto `:inherited-snapshot? true`, strengthened to use a non-`:lambda`
  live `:prompt-mode` so the default-vs-live-leak distinction is observable, and
  extended with a "workflow-owned but NOT snapshot-governed (judge)" block
  asserting live-parent inheritance is preserved.
  `attempts-test/create-step-attempt-session-forwards-supported-request-surface-test`
  now asserts `:inherited-snapshot? true` on the forwarded request.
  Suites green: child-session-state (9 tests, 62 assertions), inheritance-snapshot
  + workflow-judge (24/130), workflow-execution + attempts + child-session-context
  (18/113), workflow-judge + statechart-runtime (28/128), workflow-runtime
  attempts + child-session-contract (12/45). clj-kondo clean on all touched files.

PASS_STATUS: RESOLVED.

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

## Plan ambiguity review (ψ, 2026-06-02)

Reviewed plan.md + steps.md against code ground truth
(`workflow-step-session-config/core.clj` `resolve-step-session-config` `:145`,
output set + `parent-session-model` uses `:164/173/175/183`, model-query
`model-query->selection-request` `:104` reading `:provider`/`:id`;
`workflow-runtime/core.clj` `create-run` `:108`; `model.clj`
`workflow-run-schema` `:179`; `delegate.clj` `delegate-step-runtime-result`
`:36`, child create-run `:44`, ns deps `:1`; `canonical_workflows.clj`
`create-workflow-run` `:86`; `psi_tool_workflow.clj` create-run `:148`). Most
plan mechanics/line refs verified accurate. New actionable plan/steps
ambiguities (distinct from the resolved design notes):

- **P1 — S6 dependency direction is asserted safe but is actually a cycle.**
  `workflow-step-session-config` ALREADY requires `workflow-runtime`
  (`core.clj:16` `execution-adapter`, `:17` `statechart`). So S6 having
  `delegate.clj` (in `workflow-runtime`) call
  `resolve-step-session-config`/`effective-config->snapshot` (in
  `workflow-step-session-config`) is a **require cycle**, not the "caller→both,
  no inversion" the plan Risks + Decision-7 framing assert. Only the buried S6
  third bullet admits this conditionally ("If it introduces a cycle … inject
  the resolver as a ctx op / passed fn"). The plan does not DECIDE the
  mechanism: the cycle is certain (not conditional), so S6 must commit up front
  to passing the resolver in as a fn/ctx-op (mirroring delegate's existing
  `create-workflow-context-fn`/`send-and-drain-fn` injected-fn params) rather
  than leaving "direct require vs inject" open. Resolve the contradiction
  between the Risks "must NOT depend" claim and the as-written
  `resolve-step-session-config`/`effective-config->snapshot` direct calls.

- **P2 — `effective-config->snapshot` cannot recover `:speed-mode`/
  `:effort-override` by projection.** Plan S2 + steps describe it as a pure
  `select-keys`/projection of a `resolve-step-session-config` result into the 7
  snapshot keys. But (per resolved I1) `resolve-step-session-config` outputs
  NEITHER `:speed-mode` NOR `:effort-override` — its result set is
  `{:developer-prompt :prompt-mode :response-mode :tool-defs :thinking-level
  :skills :model :prompt-component-selection}` (+ optional
  temperature/model-fallback/logprob). So `select-keys` over an effective
  config yields only 5 of the 7 snapshot keys; the nested path silently drops
  speed-mode/effort-override (violating AC3/AC4 for those two fields under
  delegation). The plan must specify where the nested path obtains those two
  fields (e.g. delegate also threads the parent run's snapshot
  speed-mode/effort-override, or `resolve-step-session-config` is extended to
  emit them) — projection alone is insufficient.

- **P3 — snapshot `:model` shape vs `resolved-model-query` selection context
  unspecified.** Plan AC7/S5 say "feed the snapshot's `:model` into
  `resolved-model-query` selection context". But `model-query->selection-request`
  (`core.clj:104`) reads `(:provider parent-session-model)` and
  `(:id parent-session-model)` — i.e. it expects a `{:provider :id}`-shaped
  map (the live `(:model parent-session)`). The plan does not state whether the
  snapshot stores `:model` in that same `{:provider :id}` shape (so it drops in
  directly) or as a bare resolved id string (which would break the
  provider/id destructure). Pin the snapshot `:model` shape against what
  `model-query->selection-request` consumes.

- **P4 — S5 replaces only ONE of four `parent-session-model` consumers.** In
  `resolve-step-session-config`, `parent-session-model` (`:164`, live
  `(:model parent-session)`) feeds FOUR sites: `resolved-step-model-config` for
  the step override (`:173`), for the base-meta override (`:175`), the bare
  no-override `:model` fallback (`:183/184`), AND (transitively, via those)
  `resolved-model-query`. Plan S5 only names "feed the snapshot's `:model` into
  `resolved-model-query`/`resolved-step-model-config`" and "source the
  no-override `:model` from the snapshot", but does not state that ALL
  `parent-session-model` uses (including the two override-resolution calls at
  `:173/:175`) must switch to the snapshot model, or AC1/AC2 leak: a step whose
  model-query/override resolution still consults the live parent model would
  observe a mid-run model switch. Specify that snapshot `:model` replaces
  `parent-session-model` wholesale (single binding), not just the
  no-override/model-query subset.

- **P5 — S5 snapshot-vs-live is per-field-set, not whole-path; merge with
  always-live fields unspecified.** The snapshot holds 7 inherited-default
  fields, but `resolve-step-session-config` also produces fields that are NEVER
  inherited from the parent and always come from step-def/base-meta
  (`:developer-prompt`, `:response-mode`, `:prompt-component-selection`,
  temperature, logprob). Plan S5 says "source the no-override inherited fields
  from the snapshot … with live-read fallback" but does not state that the
  snapshot substitution is scoped to ONLY the 7 inherited keys while the
  always-step-derived fields stay on their current code path. As written it
  reads as a binary snapshot-vs-live fork; clarify it is a per-field source
  swap for the seven inherited defaults, leaving non-inherited outputs
  untouched (and AC6's "no-mutation behaviour unchanged" still holds for
  those).

PASS_STATUS: ACTIONABLE_FEEDBACK.

## Plan ambiguity follow-up resolution (ψ, 2026-06-02)

All five plan-ambiguity follow-ups (P1–P5) resolved into plan.md + steps.md
(plan-phase only; S1–S7 code not yet started). Grounded against real code first:

- **P1 (dependency cycle — certain, not conditional).** Confirmed
  `workflow-step-session-config/deps.edn` already declares
  `psi/workflow-runtime {:local/root "../workflow-runtime"}` (and `core.clj:16/17`
  require `execution-adapter`/`statechart`). So a reverse `delegate.clj` →
  `workflow-step-session-config` require is a genuine cycle. Resolved by
  committing S6 to **injected-fn**: add `resolve-inherited-defaults-fn` to
  `delegate-step-runtime-result` (already takes injected
  `create-workflow-context-fn`/`send-and-drain-fn` at `delegate.clj:36`); caller
  (depends on both) binds the closure. Rewrote plan Risks "Layering" bullet, plan
  slice-order S6, and steps S6 to make this a decided mechanism, not an "if it
  introduces a cycle" branch.

- **P2 (speed/effort not projectable).** Confirmed `resolve-step-session-config`
  (`core.clj:145–211`) output set = `:developer-prompt :prompt-mode
  :response-mode :tool-defs :thinking-level :skills :model
  :prompt-component-selection` (+ optional `:temperature`/`:model-fallback`/
  logprob) — emits NEITHER `:speed-mode` NOR `:effort-override`. A `select-keys`
  projection yields only 5/7 snapshot keys. Resolved by changing the signature to
  `effective-config->snapshot (effective-config parent-snapshot)`: the 5
  resolver-emitted inherited keys come from the effective config; speed/effort
  come from the parent run's snapshot (`(:inherited-defaults workflow-run)`),
  which is correct since neither is per-step overridable today. Updated plan S2
  approach + slice-order S6, steps S2 + S6.

- **P3 (snapshot `:model` shape).** Confirmed live `(:model parent-session)` is a
  `{:provider :id}` map: `model-query->selection-request` (`core.clj:104`) reads
  `(:provider …)`/`(:id …)`, `candidate->session-model` (`:114`) emits
  `{:provider (name …) :id …}`. Resolved: snapshot stores `:model` as that same
  `{:provider :id}` map (verbatim copy), drops directly into
  `parent-session-model` with no reshaping; `inherited-defaults-schema` encodes
  it as a map. Added a Risks bullet + steps S3/S5 notes.

- **P4 (`parent-session-model` wholesale replacement).** Confirmed the single
  `parent-session-model` binding (`core.clj:164`) feeds four sites:
  `resolved-step-model-config` step override (`:173`), base-meta override
  (`:175`), bare no-override fallback (`:183`), and transitively
  `resolved-model-query`. Resolved: set the binding itself to the snapshot
  `:model` (single binding, all four consumers see it). AC5 preserved — the
  override path's OUTPUT still wins; only its selection CONTEXT is snapshot-
  sourced. Added a Consumption sub-bullet + rewrote steps S5 model item.

- **P5 (per-field source swap, not whole-path fork).** Confirmed the resolver
  always derives `:developer-prompt`/`:response-mode`/
  `:prompt-component-selection`/`:temperature`/logprob/`:model-fallback` from
  step-def/base-meta (never from the parent). Resolved: snapshot substitution is
  scoped to ONLY the 7 inherited keys; non-inherited outputs stay on their
  current code path regardless of snapshot presence. Rewrote the Consumption
  paragraph + steps S5 source-fields item.

No plan-ambiguity follow-ups blocked; all five resolved as plan/steps
refinements. Code implementation remains for the build phase (S1–S7), not this
plan-review pass.

## Plan inconsistency review (ψ, 2026-06-02)

Reviewed plan.md ↔ steps.md ↔ design.md ↔ implementation.md for cross-file
inconsistency, grounded against code (`workflow-step-session-config/core.clj`
`resolve-step-session-config` `:145`, `effective-config->snapshot` consumer
shape; `delegate.clj` `delegate-step-runtime-result` `:36-37` injected params +
child create-run `:44`; `create-run` `:110`; `model.clj` schema `:179`). Plan
and steps are internally consistent with each other (S1–S7 align; AC→slice map
covers AC1–9; line refs accurate). The P1/P2 plan-phase resolutions, however,
updated plan.md + steps.md but left design.md's stable Decisions 7/7a stating
the now-superseded mechanisms — two new design↔plan/steps inconsistencies, not
captured by any prior note or step:

- **PI1 — `effective-config->snapshot` signature/behaviour: design.md vs
  plan/steps.** design.md Decision 7a (`:209`) still defines
  `effective-config->snapshot` as `(effective-config) → snapshot-map`, "pure
  projection of an already-resolved effective step-config into the snapshot
  field set", and the nested-derivation prose (`:220-221`) says delegate "calls
  `effective-config->snapshot` on that result" (single arg, no parent-snapshot).
  But the P2 resolution changed plan.md (`:26`, `:36`) and steps.md (`:37`,
  `:136`) to `effective-config->snapshot (effective-config parent-snapshot) →
  snapshot`, where `:speed-mode`/`:effort-override` come from the parent run's
  snapshot because the resolver emits neither (resolved I1/P2) — so a single-arg
  pure projection yields only 5/7 keys. design.md Decision 7a is the exact
  framing P2 proved insufficient. The P2 follow-up resolution note explicitly
  lists only "plan S2 + slice-order S6, steps S2 + S6" as updated — design.md
  was not. Reconcile: update design.md Decision 7a (signature + the "pure
  projection of effective config into the snapshot field set" description + the
  `:220-221` nested-flow prose) to the two-arg form sourcing speed/effort from
  the parent snapshot, matching plan/steps.

- **PI2 — S6 dependency mechanism: design.md vs plan/steps.** design.md
  Decision 7 (`:188`) asserts "the dependency direction stays caller → both
  components, avoiding a layering inversion" and the nested-flow prose
  (`:215-221`) has `delegate.clj` directly calling `resolve-step-session-config`
  then `effective-config->snapshot`. But P1 established (and plan Risks +
  steps S6 now commit) that this is a CERTAIN require cycle
  (`workflow-step-session-config` already requires `workflow-runtime` per
  `deps.edn`/`core.clj:16-17`), resolved by injecting `resolve-inherited-
  defaults-fn` into `delegate-step-runtime-result` (mirroring its existing
  `create-workflow-context-fn`/`send-and-drain-fn`). design.md still describes
  the direct-call/no-inversion mechanism the plan determined is impossible, and
  never mentions the injected-fn resolution. The P1 follow-up note lists only
  plan Risks/slice-order S6 + steps S6 as updated — design.md Decision 7 was
  not. Reconcile: update design.md Decision 7's "caller → both" / direct-call
  framing to reflect the injected-fn mechanism (or explicitly note delegate
  reaches the resolver via an injected fn, not a direct require), matching
  plan/steps.

Both are design↔plan/steps drift created by resolving plan ambiguities without
back-propagating to the still-"stable" design.md. Build (S1–S7) not started.

PASS_STATUS: ACTIONABLE_FEEDBACK.

## Plan-inconsistency follow-up resolution (ψ, 2026-06-02)

Both plan-inconsistency follow-ups (PI1, PI2) from the preceding review pass
resolved into design.md (design-only; no code; build S1–S7 not started). These
back-propagate the P1/P2 plan/steps resolutions into the previously-"stable"
design.md so design no longer contradicts plan/steps:

- **PI1.** Updated Decision 7a's `effective-config->snapshot` signature from
  single-arg `(effective-config) → snapshot-map` "pure projection of an
  already-resolved effective step-config into the snapshot field set" to the
  two-arg `(effective-config parent-snapshot) → snapshot-map`: the five
  resolver-emitted inherited keys (`:model :prompt-mode :tool-defs :skills
  :thinking-level`) come from the effective config, while
  `:speed-mode`/`:effort-override` come from `parent-snapshot`
  (`(:inherited-defaults workflow-run)`), because `resolve-step-session-config`
  emits neither (resolved I1) — a single-arg projection would yield only 5/7
  keys and drop speed/effort under delegation. Also updated the nested-flow
  prose to call `effective-config->snapshot` on the effective config **plus the
  parent snapshot**. Now matches plan.md (`:26/:36`) + steps.md (`:37/:136`).

- **PI2.** Updated Decision 7's "dependency direction stays caller → both
  components, avoiding a layering inversion" + direct-call framing to the
  P1-resolved injected-fn mechanism: `workflow-step-session-config` already
  requires `workflow-runtime` (deps.edn + core.clj:16/17), so a reverse require
  from `delegate.clj` is a certain cycle. Decision 7 now states the nested path
  reaches the resolver via an injected `resolve-inherited-defaults-fn` passed
  into `delegate-step-runtime-result` (mirroring its existing
  `create-workflow-context-fn`/`send-and-drain-fn`), bound by the caller (which
  depends on both components); `delegate.clj` does not require
  `workflow-step-session-config`. Updated the nested-flow prose accordingly.
  Now matches plan Risks + steps S6.

No plan-inconsistency follow-ups blocked; both resolved as design refinements.
PI1/PI2 checked in steps.md. Code implementation remains for the build phase.

## S1 build — field-set authority surface (ψ, 2026-06-02)

Promoted `common-inherited-fields` and `model-identity-fields` to public
(dropped `^:private`) in `session-state/init.clj`. Added two named constants to
`workflow-step-session-config/core.clj`: `inherited-defaults-source-keys`
(`:from-common`/`:from-model` authority split) and
`inherited-defaults-snapshot-keys` (the 7 resolved keys). Added
`inherited-defaults-field-set-authority-test` proving each source key ∈ its
authority and the resolved key set = source keys with `:tool-ids`→`:tool-defs`/
`:skill-ids`→`:skills` substituted, so the two field lists cannot drift.

- Added `psi/session-state` as a direct dep of `workflow-step-session-config`
  (was transitive via workflow-runtime) for the test's `session-init` require.
- Kept `session-init` require only in the test (not core.clj) — the snapshot
  source-key constants are declared explicitly and validated against the
  authority by the test, so core.clj does not reference init directly (avoids an
  unused-require lint warning).

Verification: `psi.workflow-step-session-config.core-test` (19 tests, 49
assertions) and `psi.session-state.init-test` (4 tests, 46 assertions) green;
clj-kondo clean on touched files.

## S2 build — snapshot derivation functions (ψ, 2026-06-02)

Added two functions to `workflow-step-session-config/core.clj`:

- `resolve-inherited-defaults-snapshot (ctx parent-session-id)` — impure
  top-level resolver. Mirrors the resolver's no-override reads
  (`get-session-data` → model/prompt-mode/thinking-level, `all-skills`,
  `agent-tool-source-in` + `:tool-ids` → tool-defs) and **adds** the two new
  `:speed-mode`/`:effort-override` parent-session reads (Decision 1 / I1).
  Returns exactly `inherited-defaults-snapshot-keys`; `:model` copied verbatim
  as the parent's `{:provider :id}` map.
- `effective-config->snapshot (effective-config parent-snapshot)` — pure
  projection (no ctx reads). Five inherited keys from the effective config;
  `:speed-mode`/`:effort-override` from the parent snapshot (the effective
  config / resolver emits neither — P2).

Tests: `resolve-inherited-defaults-snapshot-test` (speed/effort captured, exact
key set, thinking-level `:off` default) and `effective-config->snapshot-test`
(only snapshot keys projected, overridden model preserved, speed/effort sourced
from parent snapshot even when the effective config carries a stray speed key).
Used `update-in [:agent-session :sessions sid :data] merge` to seed parent
session fields for the live-read resolver test (no mocks — real ctx/state).

Verification: `psi.workflow-step-session-config.core-test` (21 tests, 69
assertions) green; clj-kondo clean.

## S3 build — persist snapshot on the run (ψ, 2026-06-02)

Added `inherited-defaults-schema` to `workflow-runtime/model.clj` (7 optional/
nilable fields; `:model` a `{:provider :id}` map per P3) and wired
`[:inherited-defaults {:optional true} [:maybe inherited-defaults-schema]]` into
`workflow-run-schema`. Threaded an optional `:inherited-defaults` opt through
pure `create-run` via a `cond-> (contains? opts :inherited-defaults) (assoc …)`
branch mirroring `:parent-session-id` — recorded verbatim, no ctx reads, purity
preserved.

Tests: `create-run-persists-inherited-defaults-snapshot-test` (verbatim persist
+ schema-valid) and `create-run-without-inherited-defaults-omits-key-test`
(back-compat: key absent, still schema-valid).

Verification: `psi.workflow-runtime.core-test` (9 tests, 34 assertions) green;
clj-kondo clean.

## S4 build — top-level capture sites (ψ, 2026-06-02)

Resolved+passed the inherited-defaults snapshot at the two direct top-level
`create-run` sites:

- `mutations/canonical_workflows.clj` `create-workflow-run` mutation: when
  `session-id` present, `resolve-inherited-defaults-snapshot agent-session-ctx
  session-id` → `:inherited-defaults` in create-run opts. Added the
  `workflow-step-session-config.core` require (agent-session already depends on
  the component; no cycle).
- `psi_tool_workflow.clj` `create-run` op: `session-id` is already required
  there, so always resolve and pass `:inherited-defaults`.

The two upstream `mutate!` callers (`workflow/core.clj`,
`orchestration.clj` `continue-terminal-run-async!`) are unchanged. Decision 5b
(continue captures fresh) holds for free: `continue-terminal-run-async!` routes
through `mutate! 'psi.workflow/create-run` → the same `create-workflow-run`
mutation, which resolves a fresh snapshot from the active/continuing session at
continuation time. No special snapshot threading needed.

Tests: mutation-level capture (model/prompt-mode/speed/effort from a real
invoking session) + no-session-id omission in `canonical-workflows-test`;
psi-tool-op-level capture in `workflow-tools-test`. Existing tests using a
non-existent `:session-id "delegating-session"` still pass — the resolver reads
empty session data and produces a nilable snapshot (schema-valid).

Verification: `workflow-tools-test` + `canonical-workflows-test` (13 tests, 177
assertions) green; clj-kondo clean.

## S5 build — consume snapshot in step config resolution (ψ, 2026-06-02)

`resolve-step-session-config` now does a per-field source swap (P5): when
`(:inherited-defaults workflow-run)` is present the 7 inherited defaults come
from the snapshot, else the live-read path is retained (AC6). Key points:

- `parent-session-model` is set WHOLESALE to the snapshot `:model` (P4) — all
  four consumers (step override, base-meta override, no-override fallback,
  model-query selection context) see it, so AC1/AC2 cannot leak through override
  resolution, and AC7's model-query selection context is snapshot-sourced.
- `:tool-defs`/`:skills` snapshots replace the resolved name-resolution pools;
  `:prompt-mode` and the `:thinking-level` inherited fallback come from the
  snapshot.
- `:speed-mode`/`:effort-override` are emitted into the resolved config via
  cond-> when the snapshot supplies them (the resolver emits neither today —
  I1/P2).

### Deviation / discovery — end-to-end speed/effort propagation

AC3 requires the invariant for speed-mode/effort-override too. The resolved
config carrying them is necessary but not sufficient: workflow child sessions
build their state via `child-session-state/child-session-base-state*`, which
(unlike init.clj's lifecycle paths using `common-inherited-fields`) did NOT
inherit or apply speed/effort. So a mid-run parent change would still leak into
later steps' children. Threaded them through the full child-creation path:

- `child-session-contract/request-schema` — added optional
  `:speed-mode`/`:effort-override` (closed schema).
- `attempts/create-step-attempt-session!` — destructure + forward.
- `context/create-workflow-child-session!` — destructure + dispatch keys.
- `dispatch_handlers/session_lifecycle.clj` `:session/create-child` — forward to
  `initialize-child-session-state`.
- `child-session-state/child-session-base-state*` — apply override (else parent
  fallback) via cond->.

Not in the original steps list; recorded here as a design deviation. It is a
clean threading of two already-modelled session fields and is required for AC3.

Tests added/updated:
- `workflow-step-session-config.core-test`: AC1/2/3 isolation, AC5 override,
  AC6 no-snapshot fallback, AC7 model-query selection context (25 tests total).
- `workflow-runtime.core-test`: AC8 resume reuse.
- `workflow-runtime.attempts-test`: request-surface forwards speed/effort.
- `agent-session.child-session-state-test`: override-wins / parent-fallback /
  nil-default for speed/effort.

Verification: focused suites (54 tests, 207 assertions) green; clj-kondo clean.

## S6 build — nested/delegated capture (ψ, 2026-06-02)

Added the injected `resolve-inherited-defaults-fn` param to
`delegate-step-runtime-result` (alongside `create-workflow-context-fn`/
`send-and-drain-fn`). When present, the delegate site derives the child run's
inherited-defaults from the delegating step's EFFECTIVE config and passes it as
`:inherited-defaults` to the child `create-run` (cond->).

- `statechart_runtime.clj` passes `(:resolve-inherited-defaults-fn ctx)` at the
  delegate-step call site.
- `context.clj` binds the closure (depends on both components):
  `resolve-step-session-config` → effective config, then
  `effective-config->snapshot effective (:inherited-defaults workflow-run)`
  (speed/effort from the parent run snapshot, P2).
- `delegate.clj` does NOT require `workflow-step-session-config` (the reverse
  require is a certain cycle — wssc deps.edn already pulls workflow-runtime, P1),
  so the resolver is reached only via the injected fn.

This makes overrides propagate down the delegation tree as the new inherited
default (Decision 3): a step that overrides the model then delegates → the
sub-delegation sees the overridden model, captured at sub-delegation creation,
not the (since-mutated) invoking session.

Tests: `nested-delegation-effective-snapshot-propagates-overridden-model-test`
(AC4 — overridden model + parent-snapshot speed/effort, exact key set). Real
delegation execution/result-boundary/list tests still green.

Verification: `workflow-step-session-config.core-test` (26 tests, 85
assertions), delegate execution/boundary/list/statechart suites green; clj-kondo
clean.

## S7 build — coherence + docs (ψ, 2026-06-02)

- Re-read touched source; confirmed resolver per-field source swap, the delegate
  injected-fn signature, and create-run purity are coherent.
- `doc/workflows.md`: added "Inherited session defaults are snapshotted at invoke
  time" section (invoke-time capture; nested effective-config inheritance;
  explicit-override precedence; resume-reuse vs continue-fresh).
- `CHANGELOG.md` `[Unreleased]` → Fixed: documents the snapshot behaviour and
  mid-run-leakage fix for all seven inherited fields, nested delegation, and the
  replayable canonical-state property.
- Lint clean across all touched component src files.
- Tests: 84-test focused cross-component pass (490 assertions) +
  workflow-execution/statechart-runtime (12/61) + child-session
  mutation/judge (24/121) all green.
- Final review: create-run stays pure (verbatim record, no ctx reads); no
  workflow-runtime → workflow-step-session-config layering inversion (the nested
  path is reached only through the injected `resolve-inherited-defaults-fn`).

All slices S1–S7 implemented and checked. Implementation complete pending
review/closure.

## Implementation review (ψ, 2026-06-02)

Reviewed code ↔ design/plan/steps with task-implementation-review skill.
Grounded against source + ran focused suites (all green): wssc core (18/46) +
inheritance-snapshot (8/39), workflow-runtime core (9/35), attempts (7/32),
agent-session child-session-state (8/46), canonical-workflows (12/123),
workflow-tools (1/54). clj-kondo clean on touched src.

Strong fit: create-run stays pure (takes `state`, records `:inherited-defaults`
verbatim via cond->); no require cycle (`delegate.clj` does not require
`workflow-step-session-config`; nested path reached via injected
`resolve-inherited-defaults-fn`); snapshot tests are real (no mocks, real
ctx/state); schema/capture-sites/consumption all match Decisions 6/6a/7/7a/8a/5.
The S5 speed/effort end-to-end threading deviation is clean and AC3-necessary.

New actionable findings:

- **R1 (simple/locally-comprehensible — minor).**
  `resolve-step-session-config` (`core.clj:195`) unconditionally binds
  `parent-session (execution-adapter/get-session-data ctx
  authoritative-parent-session-id)`, but when `snapshot?` is true that value is
  used in NONE of the three `(if snapshot? …)` forms (model, prompt-mode,
  skills, tool-defs all take the snapshot branch; `:tool-ids parent-session` /
  `all-skills … parent-session` only appear in the live-read else-branches). So
  with a snapshot present the live `get-session-data` read is performed and
  discarded — a dead read that partially defeats the snapshot's "no live parent
  re-read" intent (AC1/AC2 still hold because the value is unused, but the read
  still happens). Make the live `parent-session` read lazy / snapshot-gated
  (e.g. `(when-not snapshot? (get-session-data …))` or a `delay`) so the
  snapshot path performs no live parent read, matching the design intent that
  resolution is isolated from the live parent.

- **R2 (test coverage — AC4 end-to-end gap).** AC4 is asserted only at the
  function-composition level
  (`nested-delegation-effective-snapshot-propagates-overridden-model-test`
  calls `resolve-step-session-config` + `effective-config->snapshot` directly,
  mirroring the closure). No test exercises `delegate-step-runtime-result` with
  the injected `resolve-inherited-defaults-fn` to assert the *child run's
  persisted* `:inherited-defaults` (the `when resolve-inherited-defaults-fn`
  branch + `cond-> … (assoc :inherited-defaults …)` into the child `create-run`
  at `delegate.clj:54-60`). The existing delegate-execution tests run through
  the bound closure but assert none of the child run's snapshot. Add a test that
  drives `delegate-step-runtime-result` (or a full delegation) and asserts the
  child run's stored `:inherited-defaults` equals the delegating step's
  effective snapshot (overridden model + parent-snapshot speed/effort) — so the
  delegate wiring, not just the two helper fns, is covered.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Implementation-review follow-ups R1/R2 executed (ψ, 2026-06-02)

Both newly-added implementation-review follow-ups completed.

- **R1 — snapshot-gate the live parent read.** In `resolve-step-session-config`
  (`workflow-step-session-config/core.clj`) the `parent-session` binding was an
  unconditional `(execution-adapter/get-session-data ctx …)`, but on the
  snapshot path the value is consumed nowhere (model/prompt-mode/skills/tool-ids
  all take the `(if snapshot? <snapshot> <else-using-parent-session>)`
  snapshot branch). Changed the binding to
  `(when-not snapshot? (execution-adapter/get-session-data …))` so the snapshot
  path performs zero live parent reads, matching the "resolution isolated from
  the live parent" design intent. All three `parent-session` consumers
  (`:model`/`:prompt-mode` at the two `if` else-arms, `all-skills … parent-session`,
  `:tool-ids parent-session`) are strictly in else-branches → non-nil when
  reached. No new test: existing AC1/AC2 isolation tests
  (`snapshot-isolates-resolution-from-live-parent-mutation-test`,
  `snapshot-model-feeds-model-query-selection-context-test`) and the
  back-compat `no-snapshot-falls-back-to-live-parent-test` cover both branches;
  all green.

- **R2 — AC4 end-to-end delegate-wiring test.** Added
  `delegate-step-runtime-result-persists-child-inherited-defaults-test` to
  `inheritance_snapshot_test.clj`, requiring
  `psi.workflow-runtime.statechart-runtime.delegate`
  (`workflow-step-session-config` already depends on `workflow-runtime`). Drives
  `delegate/delegate-step-runtime-result` with the REAL injected
  `resolve-inherited-defaults-fn` closure (identical composition to the one
  bound in `agent-session/context.clj`:
  `effective-config->snapshot` ∘ `resolve-step-session-config`), plus stub
  no-op `send-and-drain-fn` (leaves the child run at created status) and
  `create-workflow-context-fn`. Asserts the child run's persisted
  `:inherited-defaults` (read back from canonical state) — exercising the
  `delegate.clj:54-60` `when resolve-inherited-defaults-fn → cond-> assoc
  :inherited-defaults` wiring into the child `create-run`, which the prior
  function-level AC4 test did not cover.

  Discovery / correction to the review's parenthetical "overridden model": a
  delegate step's COMPILED effective definition drops per-step `:session`
  overrides (the canonical-IR compiler keeps only delegate-relevant keys:
  `:type :delegate :delegate :outputs :yields …`). So the delegating step's
  effective model is INHERITED from the parent run snapshot, not a step
  override. The e2e test therefore asserts the real behaviour (child model =
  parent-snapshot `claude-PARENT`); the overridden-model composition is already
  covered directly by
  `nested-delegation-effective-snapshot-propagates-overridden-model-test`.
  Also confirmed `:effective-definition :steps` is a NAME-keyed MAP, not a
  vector (lookup by `"delegate-step"`).

Verification: `psi.workflow-step-session-config.inheritance-snapshot-test`
9 tests / 45 assertions (was 39) green; wssc core-test + workflow-runtime
core-test + terminal-contract-execution-test 28 tests / 86 assertions green;
clj-kondo clean on both touched files. Both R1 and R2 checked in steps.md; all
207 steps now checked.

## Implementation review pass 2 (ψ, 2026-06-02)

Re-reviewed code ↔ design/plan/steps with task-implementation-review skill
after R1/R2 follow-ups. Grounded against source: `core.clj`
`resolve-step-session-config` (snapshot-gated `parent-session` :194,
wholesale `parent-session-model` :199, per-field swaps), `resolve-inherited-
defaults-snapshot`, `effective-config->snapshot`; `model.clj`
`inherited-defaults-schema` :179; `delegate.clj` injected-fn :128; `context.clj`
`:resolve-inherited-defaults-fn` :233; `child_session_state.clj`
`child-session-base-state*` :158-169; `child_session_contract.clj` closed
request-schema :8-18; capture sites (`canonical_workflows.clj` :96,
`psi_tool_workflow.clj` :144). Lint clean (0/0). 207-specific suites green
(inheritance-snapshot, wssc core, workflow-runtime core/attempts, child-session-
state, canonical-workflows, workflow-tools). One unit failure
(`psi.tui.app-projection-test/autocomplete-selection-movement-updates-rendered-
highlight-test`) is UNRELATED and pre-existing — task 207 touched no TUI/app-
projection files (`git diff --name-only a683959c8~1 HEAD` excludes them).

Strong fit confirmed: create-run pure (records verbatim, no ctx reads); R1
dead-read gate present and correct; no require cycle (delegate reaches resolver
only via injected fn); schema/capture/consumption match Decisions 5/6/6a/7/7a/8a;
explicit-override precedence preserved (AC5); resume reuse (AC8).

New actionable finding (not covered by any prior note/step):

- **R3 (doc↔code coherence drift — `child_session_state.clj` classification
  comment).** S5's necessary end-to-end deviation added speed/effort inheritance
  to `child-session-base-state*` via `(or speed-mode (:speed-mode parent-sd))`
  / `(or effort-override (:effort-override parent-sd))` (`:166-169`). This is
  the general child-session path (not workflow-only) and is the path's first
  inheritance of these two fields — a legitimate alignment with
  `common-inherited-fields` (which lists both). But the file's header
  classification comment (`:14-50`), which hand-mirrors `common-inherited-fields`
  into "Inherited (N of M)" / "Not inherited (N of M)" buckets, was NOT updated:
  (a) it still says "common-inherited-fields (17 keys)" / "7 of 17" / "10 of 17"
  while the constant now holds 19 keys (init.clj docstring + this task's own
  Decision 8a both say 19); (b) `:speed-mode`/`:effort-override` appear in
  NEITHER the Inherited nor the Not-inherited enumeration — they are simply
  absent, despite the code now inheriting them. This is exactly the
  "two independent inheritance field lists drift" failure Decision 8 set out to
  prevent; the workflow-snapshot side got a drift-guard test
  (`inherited-defaults-field-set-authority-test`), but this hand-maintained
  classification mirror did not, and has now drifted. Fix: update the
  classification comment — correct the count (17→19), and add
  `:speed-mode`/`:effort-override` to the "Inherited" bucket with their
  `(or … (:…  parent-sd))` derivation note. (Optionally consider a lighter-weight
  guard than a prose comment, but at minimum the comment must stop contradicting
  the code and the authority.)

PASS_STATUS: ACTIONABLE_FEEDBACK

## Follow-up execution — R3 resolution (2026-06-02)

- **R3 DONE — `child_session_state.clj` classification comment reconciled with
  `common-inherited-fields`.** Comment-only edit to the header block
  (`:24-58`): (a) count corrected `17 → 19` in the section header
  ("common-inherited-fields (19 keys)"); (b) the Inherited bucket relabelled
  "Inherited from parent (9 of 19)" and now enumerates `:speed-mode` /
  `:effort-override` with their `(or … (:… parent-sd)) — workflow snapshot
  (task 207)` derivation notes, mirroring `child-session-base-state*:166-169`;
  (c) the Not-inherited bucket relabelled "Not inherited — intentional defaults
  (10 of 19)" with its existing 10-key enumeration unchanged
  (`:nucleus-prelude-override` stays classified as consumed-not-carried). Sum
  reconciles: 9 inherited + 10 not-inherited = 19 = constant length.
  No behaviour/code-path change (pure comment), so no test or doc delta;
  `clj-kondo --lint child_session_state.clj` → 0 errors / 0 warnings.
  No drift-guard test added (R3 stated "at minimum reconcile the comment"); a
  programmatic guard for the hand-maintained classification mirror remains a
  possible future hardening but is out of scope for this follow-up.

PASS_STATUS: COMPLETE

## Implementation review pass 3 (ψ, 2026-06-02)

Independent re-review with task-implementation-review skill after R1/R2/R3
resolved. Re-verified code ↔ design/plan against source + ran focused suites
(all green): inheritance-snapshot (9/45), wssc core-test + workflow-runtime
core-test + child-session-state (35/127). clj-kondo clean (0/0) on the four
touched src files. `common-inherited-fields` confirmed 19 keys; the
`child_session_state.clj` classification comment (9 inherited + 10 not = 19)
reconciles with the constant and with `child-session-base-state*:167-171`
(`(or speed-mode (:speed-mode parent-sd))` / effort-override).

Verified fit (no regressions vs prior passes):
- R1 present: `parent-session` is `(when-not snapshot? (get-session-data …))`
  (`core.clj`), so the snapshot path performs no live parent read.
- R2 present: `delegate-step-runtime-result-persists-child-inherited-defaults-test`
  exercises the `delegate.clj` `cond-> … assoc :inherited-defaults` wiring.
- R3 present: comment count/enumeration reconciled with the authority.
- create-run pure (records `:inherited-defaults` verbatim via cond->, no ctx
  reads); no require cycle (delegate reaches resolver only via injected
  `resolve-inherited-defaults-fn`, bound in `context.clj`); schema
  (`inherited-defaults-schema`, `:model` as `{:provider :id}`) + capture sites
  (`canonical_workflows.clj:96`, `psi_tool_workflow.clj:144`) + per-field
  consumption all match Decisions 5/6/6a/7/7a/8a. `parent-session-model`
  replaced wholesale (P4); explicit-override precedence preserved (AC5).
- docs (`doc/workflows.md` snapshot section) + `CHANGELOG.md [Unreleased]`
  Fixed entry present and accurate (all seven inherited fields, nested
  delegation, replayable canonical state).

No new actionable findings. All seven slices + R1/R2/R3 follow-ups complete and
coherent. Implementation ready for closure.

PASS_STATUS: REVIEW_COMPLETE

## Implementation review pass 3 (ψ, 2026-06-02)

Re-reviewed code ↔ design/AC with task-implementation-review skill after the
R1/R2/R3 follow-ups. Grounded against source and ran focused suites green:
inheritance-snapshot (9/45), wssc core + workflow-runtime core + child-session-
state (35/127). clj-kondo clean on `core.clj`/`delegate.clj`/`model.clj`/
`context.clj`. Confirmed: create-run pure; no require cycle (delegate→resolver
only via injected fn at `context.clj:233`, wired `statechart_runtime.clj:170`);
schema/capture/consumption match Decisions 5/6/6a/7/7a/8a; R1 dead-read gate
present (`core.clj` `parent-session` = `(when-not snapshot? …)`); R2 e2e
delegate-wiring test present; R3 comment reconciled.

New actionable finding (not covered by any prior note/step):

- **R4 (correctness — live-parent leak past the resolver in
  `child-session-base-state*`; AC1/AC2/AC3 for nil-at-invoke fields).** The
  snapshot correctly isolates `resolve-step-session-config`'s OUTPUT from the
  live parent (R1), but the final workflow child-state assembly re-reads the
  LIVE parent for any snapshot-governed field whose snapshot value was nil at
  invoke. `child-session-base-state*`
  (`agent-session/child_session_state.clj:144-169`) builds the child via
  `(or <arg> (:<field> parent-sd))`, where `parent-sd =
  (session/get-session-data-in ctx session-id)` — the live parent session
  resolved mid-run at child-creation time (`dispatch_handlers/
  session_lifecycle.clj:115`, `:session/create-child`). For the
  snapshot-governed inherited defaults this is a live re-read whenever the
  resolver did not supply a non-nil value:
  - `:speed-mode`/`:effort-override` — emitted by the resolver ONLY via
    `cond-> (some? (:speed-mode snapshot)) …` (`core.clj:243-249`), so when the
    parent had none at invoke the arg is nil and
    `(or speed-mode (:speed-mode parent-sd))` (`child_session_state.clj:166-169`)
    picks up a LIVE speed/effort set AFTER invoke — the exact AC3 leak for the
    two most-transient fields. The unit test
    `child-session-base-state-applies-speed-effort-override-test`
    (`child_session_state_test.clj:121-125`) pins this parent-sd fallback as
    intended behaviour, but at workflow runtime parent-sd is live, not the
    snapshot.
  - `:model`/`:prompt-mode` — same `(or model (:model parent-sd))` /
    `(or prompt-mode (:prompt-mode parent-sd))` fallback; reachable when the
    snapshot value is nil (e.g. parent had no model/prompt-mode at invoke).
  Existing isolation coverage
  (`snapshot-isolates-resolution-from-live-parent-mutation-test`) stops at the
  resolver's output map and never drives `child-session-base-state*`/
  `:session/create-child`, so this leak past the resolver is untested. This
  contradicts Decision 2 ("resolved snapshot … robust against later parent
  mutation") and AC3 ("invariant holds for EVERY inherited default … not just
  model"). Fix: for workflow-owned children the snapshot-governed inherited
  fields must NOT fall back to live `parent-sd` — either thread the full
  snapshot through `:session/create-child` and make the workflow child path use
  snapshot values (with explicit-override precedence preserved) rather than
  `(or … (:… parent-sd))`, or have the resolver always emit the snapshot's
  value (including nil) so the child path cannot reach the live fallback. Add a
  test driving child-state assembly (resolver → `:session/create-child` →
  `child-session-base-state*`) that mutates the live parent's
  speed-mode/effort-override/model AFTER invoke and asserts the workflow child
  state is unchanged.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test review (ψ, 2026-06-02)

Reviewed implementation tests with task-test-review skill
(well-formed ∧ behaviour-coverage ∧ no-mock/no-stub). Grounded against the test
files (`inheritance_snapshot_test.clj`, `workflow-runtime/core_test.clj`,
`attempts_test.clj`, `child_session_state_test.clj`, `canonical_workflows_test.clj`,
`workflow_tools_test.clj`) and the resolver code
(`workflow-step-session-config/core.clj` `resolve-step-session-config`
:170-265, `resolved-model-query`/`model-query->selection-request` :130-155).

Strong: tests use real ctx/state (`support/create-session-context`, real
`create-run`/`resolve-step-session-config`/`delegate-step-runtime-result`) — no
mocks, no interaction assertions, all state/output assertions. AC1/2/3 isolation,
AC4 (function-level + e2e delegate-wiring), AC5 override, AC6 no-snapshot
fallback, AC8 resume-reuse, S3 persist, S4 capture (mutation + psi-tool op),
field-set authority drift-guard all genuinely covered.

New actionable finding (distinct from open R4 and the P3/P4 implementation
notes — those discuss the code; this is the test's assertion strength):

- **T1 (AC7 assertion too weak to prove the behaviour).**
  `snapshot-model-feeds-model-query-selection-context-test`
  (`inheritance_snapshot_test.clj`) is the only AC7 test. It mutates the live
  session to `claude-LIVE-CHANGED` after invoke, then asserts ONLY
  `(some? (:model-fallback config))` and
  `(= :ranked-model-candidates (get-in config [:model-fallback :type]))`. Both
  hold regardless of WHICH model fed the selection context: the selection
  request's `:context {:session-model {:provider … :id …}}`
  (`model-query->selection-request` :130-137) is built from
  `parent-session-model`, but the test asserts nothing about the resulting
  `:session-model`, candidate ranking, or any value that differs between the
  snapshot model (`claude-snapshot`) and the mutated live model
  (`claude-LIVE-CHANGED`). The test would pass identically if the resolver read
  the LIVE model into the selection context — i.e. it does not actually prove
  AC7's invariant ("selection context comes from the snapshot's effective
  model", isolated from the live parent). Fix: strengthen the assertion to
  observe the snapshot-vs-live distinction — e.g. assert the selection request's
  `:session-model`/`:context` (or a snapshot-model-dependent candidate/outcome
  in `:model-fallback`) reflects `claude-snapshot` and NOT `claude-LIVE-CHANGED`.
  If `:model-fallback` does not surface a model-dependent value the test can
  observe, expose the selection request's session-model context (or assert the
  ranked-candidate set differs for the two models) so the isolation is provable,
  not just the fallback's shape.

PASS_STATUS: ACTIONABLE_FEEDBACK

## R4 follow-up executed (2026-06-02)

R4 (implementation-review pass 3): closed the live-parent leak past the resolver
in `child-session-base-state*` (`agent-session/child_session_state.clj`).

Problem: the snapshot isolates `resolve-step-session-config`'s OUTPUT (R1), but
the resolver `cond->`-omits `:speed-mode`/`:effort-override` when the snapshot
value is nil-at-invoke, and `child-session-base-state*` then did
`(or <supplied> (:<field> parent-sd))` where `parent-sd` is the LIVE parent read
mid-run inside `:session/create-child` (`session_lifecycle.clj`). For
snapshot-governed fields with a nil-at-invoke value this re-read the live parent,
leaking a post-invoke mutation — violating Decision 2 / AC3 for those fields
(`:speed-mode`/`:effort-override`, and the `(or model (:model parent-sd))` /
`(or prompt-mode (:prompt-mode parent-sd))` fallbacks).

Fix: introduced `workflow-owned?'` + an `inherited-default` helper. For
workflow-owned children the four snapshot-governed inherited fields
(`:model :prompt-mode :speed-mode :effort-override`) resolve to the supplied
(snapshot) value, else the FRESH `initial-session` default — never the live
`parent-sd`. Non-workflow children keep the existing live `parent-sd` fallback
(the general fork/spawn path, still covered by
`child-session-base-state-applies-speed-effort-override-test`). Explicit-override
precedence is preserved because the resolver feeds the override as the supplied
value.

Why `initial-session` default rather than bare nil: `:prompt-mode` is
`{:optional true}` non-nilable in `agent-session-schema` — a nil would fail
`valid-session?` (the defensive throw in `create-step-attempt-session!`). Falling
back to the initial-session default (`:lambda` for prompt-mode, nil for
model/speed/effort) keeps the child schema-valid while still never reading the
live parent.

Real-flow note: the resolver ALWAYS supplies `:model`/`:prompt-mode` (from the
snapshot) for workflow children, so the default fallback only ever applies to
malformed/partial unit inputs; the practical leak vector closed here is the
resolver's conditional omission of `:speed-mode`/`:effort-override`.

Test: added `child-session-base-state-workflow-owned-isolates-snapshot-fields-test`
to `child_session_state_test.clj` driving `child-session-base-state` with a LIVE
parent carrying speed/effort/prompt-mode/model and asserting (a) nil-at-invoke
snapshot fields resolve to the initial-session default (NOT the live parent),
(b) supplied snapshot values are authoritative, (c) non-workflow children still
fall back to the live parent. Updated the pre-existing
`child-session-base-state-normalizes-and-inherits-test` to supply `:model`
explicitly (it asserts `:workflow-owned? true` and previously relied on the now-
removed live-parent model fallback — the real resolver always supplies it).
Reconciled the `child_session_state.clj` classification comment (model-identity +
common-inherited buckets) with the new workflow-owned behaviour.

Verification: child-session-state + attempts (workflow-runtime + agent-session) +
child-session-mutation 28 tests/149 assertions green; workflow delegate-example /
execution / statechart-runtime / inheritance-snapshot / child-session-context
30 tests/148 assertions green; clj-kondo clean. R4 checked.

## T1 follow-up executed (2026-06-02)

T1 (test review): strengthened the AC7 test
(`snapshot-model-feeds-model-query-selection-context-test`,
`workflow-step-session-config/inheritance_snapshot_test.clj`) so it actually
proves the model-query selection context comes from the SNAPSHOT model, not the
live parent. The prior assertion was shape-only (`(some? :model-fallback)` +
`:type :ranked-model-candidates`), which held regardless of which model fed
`model-query->selection-request`'s `:session-model` context.

New form: the step's `:model {:type :model-query …}` now carries a
`:prefer [{:criterion :same-model-as-session :prefer :context-match}]`
preference; the snapshot model (`claude-opus-4-5`) and the post-invoke live model
(`claude-haiku-4-5`) are two DISTINCT real registered anthropic models (same
provider, so only the exact-model context can disambiguate). The
`:same-model-as-session` criterion ranks the candidate matching the selection
context's `:session-model` first, so the ranking winner distinguishes
snapshot-vs-live. Asserts `(= snapshot-model (:model config))`,
`(= snapshot-model (first ranked))`, and `(not= live-model (:model config))` —
the test would FLIP/fail if the resolver leaked the live model into the
selection context. AC7's isolation invariant is now provable, not just the
fallback's shape.

Verification: `psi.workflow-step-session-config.inheritance-snapshot-test`
9 tests / 47 assertions green (was 45 — T1 added 2 assertions); clj-kondo clean.
T1 checked.

## Implementation-review pass 4 follow-ups (review 2026-06-02)

ψ review of code against design/plan/steps. AC coverage and the snapshot purity
boundary remain sound; tests in the working tree are green
(child-session-state 9/62, inheritance-snapshot 24/130 incl. judge). One
HIGH-severity coherence finding plus two related drifts:

- **R5 (HIGH — R4 fix is uncommitted; HEAD is internally incoherent).** The
  genuine R4 fix (re-gating child-state snapshot isolation on a dedicated
  `:inherited-snapshot?` flag instead of `:workflow-owned?`) exists ONLY as
  uncommitted working-tree changes across 5 files:
  `agent_session/child_session_state.clj` (gate `inherited-default` on
  `inherited-snapshot?'`), `agent_session/context.clj` +
  `dispatch_handlers/session_lifecycle.clj` (thread `:inherited-snapshot?`
  through `:session/create-child`), `workflow_runtime/attempts.clj` (set
  `:inherited-snapshot? true` on step-attempt children), and
  `agent_session/child_session_state_test.clj` (the judge-keeps-live-parent
  test). Meanwhile committed HEAD (`9d3c52649`) has `child-session-contract`'s
  `:inherited-snapshot?` schema entry + the contract comment naming the judge
  case, but NOTHING in HEAD produces or consumes that flag — committed
  `child_session_state.clj` still gates on `workflow-owned?'`. So HEAD ships a
  dangling schema field and a contract comment whose behaviour is not
  implemented, and the working tree's real fix is unsaved. Severity: the task is
  presented as fully implemented/reviewed (steps S1–S7 + R1–R4 + T1 all `[x]`),
  but a closing commit would either ship the incoherent HEAD (judge regression:
  workflow-owned-but-not-snapshot-governed children lose live-parent
  inheritance) or silently include unreviewed uncommitted work. Fix: commit the
  5-file working-tree change with a `⚒ 207` message, OR revert it and re-derive
  R4 — do not close with a dirty tree. Verify post-commit that
  `git status --short` is clean and HEAD is self-consistent (schema field has a
  producer + consumer).

- **R6 (MED — steps.md R4 describes the superseded approach).** steps.md R4
  `[x] DONE` note documents the `workflow-owned?'`-gated `inherited-default`
  helper as the fix, but the working tree (and the committed
  `child_session_contract` comment) moved to an `:inherited-snapshot?` gate to
  spare the judge / non-snapshot workflow children. The DONE note contradicts
  the actual fix. Reconcile R4's steps.md note with the `:inherited-snapshot?`
  mechanism once R5 is committed.

- **R7 (MED — implementation.md R4 note omits the `:inherited-snapshot?`
  refinement).** The "R4 follow-up executed" section documents only the
  `workflow-owned?` gate + `initial-session` default rationale; it does not
  mention the subsequent re-gating onto `:inherited-snapshot?` (the judge
  carve-out), nor the contract-schema field, nor the threading through
  `context`/`session_lifecycle`/`attempts`. After R5 is committed, append a note
  recording the `:inherited-snapshot?` gate and why `workflow-owned?` was
  insufficient (workflow-owned judge children supply no model/prompt-mode and
  must keep live-parent inheritance).

PASS_STATUS: ACTIONABLE_FEEDBACK

## Implementation review pass 5 (2026-06-02)

Fresh full-implementation review after the R5/R6/R7 follow-up (commit
`3e9c17a43` / code `d886e1963`). Verified the prior pass-4 coherence hazard
(uncommitted R4 fix + dangling HEAD schema field) is resolved.

Confirmed HEAD self-consistency of the `:inherited-snapshot?` mechanism:
- **producer** `attempts.clj:88` sets `:inherited-snapshot? true` on the
  step-attempt child request only (correctly NOT on the workflow judge path —
  judge children keep live-parent inheritance per the R5 carve-out);
- **schema** `child_session_contract.clj:39`
  `[:inherited-snapshot? {:optional true} [:maybe :boolean]]`;
- **threading** `context.clj:124/159` destructures + `(some? …) assoc`s it into
  the `:session/create-child` dispatch; `session_lifecycle.clj:115/141` accepts
  + forwards it to `child-session-base-state*`;
- **consumer** `child_session_state.clj:135/153` `(boolean inherited-snapshot?)`
  → `inherited-default` switches the fallback from live `parent-sd` to the fresh
  `initial-session` default for `:model`/`:prompt-mode`/`:speed-mode`/
  `:effort-override`.

Resolver consumption (`workflow-step-session-config/core.clj`) re-verified:
live parent read gated on `(when-not snapshot? …)` (R1); `parent-session-model`
replaced wholesale by snapshot `:model` (P4); all 7 inherited fields
snapshot-sourced; `:thinking-level` child uses `(or thinking-level :off)` (no
live leak); speed/effort `cond->`-assoc'd from snapshot.

Architecture fit: purity boundary preserved (`create-run` records
`:inherited-defaults` verbatim, no ctx reads); no `workflow-runtime →
workflow-step-session-config` layering cycle (nested path via injected
`resolve-inherited-defaults-fn`); one-way state boundary intact (ctx reads →
resolved data → pure transform). No unnecessary abstractions, no reusable
pattern bypassed, no structural performance issue.

Verification: focused suites green
(`inheritance-snapshot-test` + `child-session-state-test` + `attempts-test` =
25 tests, 141 assertions, 0 failures); `clj-kondo` 0 errors / 0 warnings on
touched src; `git status --short` clean; CHANGELOG `[Unreleased]` entry +
`doc/workflows.md` "Inherited session defaults are snapshotted at invoke time"
section present and accurate.

No new actionable findings. All design decisions (1–8a) and acceptance criteria
(1–9) traced to code + tests. Implementation review complete.

PASS_STATUS: REVIEW_COMPLETE

## Test review pass 2 (ψ, 2026-06-02)

Re-reviewed implementation tests with task-test-review skill
(well-formed ∧ behaviour-coverage ∧ no-mock/no-stub) after the R4–R7 / T1
follow-ups landed. Grounded against the test files and resolver code
(`workflow-step-session-config/core.clj:195-265`).

Well-formed ∧ no-mock: confirmed. Tests use real ctx/state
(`support/create-session-context`, real `create-run`,
`resolve-step-session-config`, `delegate-step-runtime-result`,
`child-session-base-state`) and real `execution-adapter/create` with a
captured-opts atom as a nullable (data-shape assertions, not interaction
assertions). `attempts-test`'s `with-redefs valid-session?` stubs only a
defensive validation guard, not domain logic — acceptable. T1's AC7
strengthening verified: the `:same-model-as-session` preference makes the
snapshot-vs-live distinction observable, so AC7 isolation is now provable.

Behaviour-coverage gap (new, actionable; distinct from T1/R-series):

- **T2 (AC3 tools/skills isolation behaviourally uncovered).** AC3 requires the
  no-live-leak invariant to hold for **every** inherited default and explicitly
  names `tools` and `skills`. The sole AC1/AC2/AC3 isolation test
  (`snapshot-isolates-resolution-from-live-parent-mutation-test`,
  `inheritance_snapshot_test.clj`) sets `:tool-defs`/`:skills` in the snapshot
  but (a) never asserts the resolved config's `:tool-defs`/`:skills` come from
  the snapshot, and (b) never mutates the live parent's tools/skills after
  invoke to prove they do NOT leak. The resolver DOES source both from the
  snapshot pool (`core.clj:206-212`: `session-skills`/`session-tool-defs` are
  `(if snapshot? (:skills/:tool-defs snapshot) <live read>)`, with the live
  read gated `(when-not snapshot? …)` per R1), so the behaviour exists and is
  structurally leak-free — but it is asserted only by the field-derivation unit
  tests (`resolve-inherited-defaults-snapshot-test`/`effective-config->snapshot-test`),
  NOT by the AC3 isolation test that proves independence from a post-invoke
  live-parent mutation. The isolation test would pass identically if a future
  change reintroduced a live tools/skills read on the snapshot path. Fix:
  extend the AC3 isolation test (or add a sibling) to assert resolved
  `:tool-defs`/`:skills` equal the snapshot pool AND remain unchanged after
  mutating the live parent session's tool source / tool-ids / skills after
  invoke — closing the AC3-named tools/skills isolation coverage to match the
  model/prompt-mode/speed/effort coverage already present in the same test.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Test review pass 3 (ψ, 2026-06-02)

Re-reviewed with task-test-review (well-formed ∧ behaviour-coverage ∧
no-mock/no-stub) against committed HEAD and the working tree. AC→test map is
otherwise complete and green (focused suites: inheritance-snapshot 9/47,
child-session-state 9/62, attempts 7/32, core 9/35, canonical-workflows 12/123).
Real collaborators throughout (`support/create-session-context`, real
`create-run`/`resolve-step-session-config`/`delegate-step-runtime-result`/
`child-session-base-state`); delegate e2e asserts persisted child-run state, not
interactions; the only `with-redefs`/captured-opts usages are pre-existing
infra-guard stubs in `attempts-test` (interaction-asserting `forwards-supported`
predates 207 / #90 — 207 only appended the `:inherited-snapshot? true`
assertion). T1 (AC7) verified strong.

Actionable (coherence, same class as R5):

- **T3 (T2 fix uncommitted; HEAD still has the AC3 tools/skills gap).** Test
  review pass 2 raised T2 (AC3 tools/skills isolation behaviourally uncovered).
  The fix — `snapshot-isolates-tools-skills-from-live-parent-mutation-test`
  (`inheritance_snapshot_test.clj`, +71 lines) — exists ONLY in the working
  tree (`git diff --stat`: 1 file, +71); committed HEAD
  (`git show HEAD:…inheritance_snapshot_test.clj | grep -c …` = 0) does NOT
  contain it, and steps.md T2 is still `[ ]` unchecked. So HEAD ships the exact
  AC3 tools/skills behavioural-coverage gap T2 identified, while the closing
  test dangles uncommitted — the same dirty-tree / incoherent-HEAD failure
  mode as R5. The working-tree test is well-formed (real ctx/state, references
  tools/skills by name, mutates the live parent post-invoke with a
  distinguishing `from-live`/`from-snapshot` value, asserts resolved config
  sources from the snapshot pool = output assertion, lint-clean, passes within
  the 9/47 run). Fix: commit the working-tree test with a `⚒ 207` message and
  check off T2; verify `git status --short` clean and HEAD self-consistent
  (AC3 tools/skills isolation now behaviourally covered in HEAD, not just the
  working tree). Do NOT close the task with a dirty tree.

## T3 follow-up executed (ψ, 2026-06-02)

T3 asked to commit the uncommitted T2 fix so HEAD carries the AC3 tools/skills
isolation coverage and the tree is clean.

State on entry: HEAD `91d65298d` ("⊨ 207: test review pass 3 — commit
uncommitted T2 AC3 tools/skills isolation test (T3)") ALREADY contained
`snapshot-isolates-tools-skills-from-live-parent-mutation-test`
(`git show HEAD:…inheritance_snapshot_test.clj | grep` matched at line 193), so
the original T2-coverage gap was already closed in HEAD. What remained was a
small uncommitted REFINEMENT to that same test (`git diff --stat`: 1 file,
+6/-6):
- Moved the step's tool/skill references from inside `:session` to the top-level
  `:tools`/`:skills` step keys, and added `"read"` alongside `"shared-tool"`
  (more representative of a real multi-tool step config).
- Made the resolved-def lookup robust: find the named def in the pool via
  `(some #(when (= "shared-tool" (:name %)) %) …)` instead of assuming `first`,
  and assert `(some? resolved-tool/skill)` before the `from-snapshot`
  description check. This survives ordering/extra-entry changes in the resolved
  pool (the pool now also carries `read`).

Verified before commit: focused suite green
(`psi.workflow-step-session-config.inheritance-snapshot-test` — 10 tests, 51
assertions, 0 failures) and `clj-kondo` clean (0/0) on the touched test file.
Committed the refinement with a `⚒ 207` message; `git status --short` clean
afterwards. HEAD self-consistent: AC3 tools/skills isolation behaviourally
covered, resolved-pool lookup order-independent.

## Test-review pass 4 (review 2026-06-02)

Applied `task-test-review` skill (well-formedness, ∀-behaviour-coverage,
injectable/nullable infra deps). Existing tests are well-formed (real ctx/state,
output assertions, no logic mocks; `execution-adapter/create` nullable-adapter
injection at the infra boundary is acceptable). The 9 numbered ACs map to
focused tests and the focused suite is green (10 tests, 51 assertions).

One behavioural-coverage gap found (criterion: ∀ design behaviour ∃ covering
test):

- **T4 — Decision 5b (`continue-terminal-run-async!` fresh snapshot capture)
  has no behavioural test, and the production `mutate!` session-id injection it
  relies on is untested.** AC8 + `resume-run-test` cover Decision 5a (resume
  REUSES the snapshot). Decision 5b — a *terminal*-run continuation creates a
  NEW run that must capture a FRESH snapshot from the continuing session — is
  asserted by steps.md S4 only "structurally" and has zero test coverage. The
  structural argument is in fact subtle: both upstream `mutate!
  'psi.workflow/create-run` callers (`workflow/core.clj:382`,
  `orchestration.clj:208` `continue-terminal-run-async!`) pass NO `:session-id`
  in their payload; capture works only because the bootstrap `mutate-fn` wrapper
  (`workflow/bootstrap.clj:80`) auto-injects `:session-id sid` from
  `*active-workflow-session-id*` (bound at `:128`) into every mutation. The
  S4 capture tests (`canonical-workflows-test`, `workflow-tools-test`) BYPASS
  this by calling the mutation DIRECTLY with an explicit `:session-id`, so they
  prove only "mutation captures when session-id is supplied" — not that the
  real invoke/continue path supplies it. A regression dropping the
  `(assoc params :session-id sid)` injection, or 5b reusing the original
  terminal run's snapshot instead of re-capturing, would pass all current
  tests. Add a behavioural test driving `continue-terminal-run-async!` (or the
  bootstrap mutate-fn wrapper with `*active-workflow-session-id*` bound) that
  asserts the NEW continuation run's `:inherited-defaults` is a fresh snapshot
  resolved from the continuing session — distinguishable from the original
  terminal run's snapshot (e.g. mutate the session model between the original
  invoke and the continue, then assert the continuation run captured the changed
  model). This closes the 5b coverage hole and pins the session-id auto-injection
  contract that ALL top-level capture depends on.

## Test-review pass 5 (review 2026-06-02)

Re-applied `task-test-review` (well-formedness, ∀-behaviour-coverage,
injectable/nullable infra). Ran the 207 suites green:
inheritance-snapshot (10/51), workflow-runtime core + attempts + child-state
(25/129). 207-introduced tests are well-formed — real ctx/state, output
assertions, no logic mocks; `execution-adapter/create` nullable injection at the
infra boundary is acceptable. Pre-existing `with-redefs` on `valid-session?`
(`attempts-test:88`) and `set-session-model!` (`:204`) predate 207 and stub
validation/effect infra rather than logic — out of scope, not introduced here.

Confirmed prior T4 (Decision 5b continue-terminal fresh-capture + session-id
auto-injection) is STILL OPEN (`steps.md` `[ ] T4`) and STILL UNCOVERED — no
test drives `continue-terminal-run-async!` nor the bootstrap mutate-fn
session-id injection. T4 stands.

One NEW behaviour-coverage gap (distinct from T4):

- **T5 — `:inherited-snapshot?` contract seam (`context.clj` →
  `:session/create-child` → `session_lifecycle.clj` → `child-session-base-state*`)
  is untested end-to-end.** The R4/R5 fix gates child-state snapshot isolation
  on a `:inherited-snapshot?` request flag. Its PRODUCER (`create-step-attempt-session!`
  emitting `:inherited-snapshot? true`) is asserted at `attempts-test:140`, and
  its CONSUMER (`child-session-base-state*` suppressing the live parent-sd
  fallback when the flag is set) at `child-session-state-test:141`. But the
  MIDDLE threading hop — `context.clj:159` `(some? inherited-snapshot?) (assoc
  :inherited-snapshot? …)` into `create-workflow-child-session!` →
  `:session/create-child` handler (`session_lifecycle.clj`) → the child-state
  builder — has no test. Both endpoints pass in isolation while the flag could
  be dropped on the wire between them and every current test stays green. This
  is precisely the incoherence class R5 caught (a `child-session-contract`
  schema field with no producer/consumer wiring in HEAD); the producer/consumer
  unit tests do not guard the seam. Add a test driving the attempt path through
  `:session/create-child` (real ctx/state, nullable adapter) that mutates the
  live parent's model/speed-mode/effort-override AFTER invoke and asserts the
  created child session's state reflects the snapshot/initial-session default,
  NOT the live parent — proving the `:inherited-snapshot?` flag survives the
  full `context`/`session_lifecycle` threading, not just the two endpoints.

## Test-review T4/T5 follow-ups executed (ψ, 2026-06-02)

Both newly-added test-review follow-ups (T4 from pass 4, T5 from pass 5)
completed in one pass. No code/doc changes required — both are pure
behavioural-coverage additions closing seam/contract gaps that the existing
endpoint tests left unguarded.

- **T4 — Decision 5b continue-terminal fresh-snapshot + session-id
  auto-injection (production-grounded; reconciled with concurrent work).** This
  worktree was being worked concurrently; a concurrent session landed the
  stronger, real-path T4 resolution in the working tree, which ψ adopted in
  preference to an initially-drafted test-only mirror. The genuine fix makes the
  session-id auto-injection T4 depends on a REAL production behaviour:
  `psi.workflow/create-run` is added to
  `runtime-eql/session-scoped-extension-mutation-ops`
  (`extensions/runtime_eql.clj`), so `run-extension-mutation-in!` injects the
  invoking `:session-id` when the caller passes none. That is precisely the path
  `orchestration/continue-terminal-run-async!` and the top-level invoke
  (`workflow/core.clj`) rely on — both call `mutate! 'psi.workflow/create-run`
  with no explicit `:session-id`. Behavioural coverage:
  `continue-terminal-run-captures-fresh-snapshot-test` in
  `canonical_workflows_test.clj` drives the REAL `continue-terminal-run-async!`
  with a `production-like-mutate!` reproducing the session-scoped injection
  contract (inject from `*active-workflow-session-id*` when absent) and routing
  to the real `create-workflow-run`. Original invoke captures `claude-ORIGINAL`;
  the continuing session switches to `claude-CHANGED` AFTER invoke; the
  continuation NEW run's persisted `:inherited-defaults :model` =
  `claude-CHANGED` (FRESH) and `≠` the original terminal run's snapshot —
  proving 5b captures fresh, never reuses, and pinning the session-id injection
  contract every top-level capture relies on (the S4 tests bypass it with an
  explicit `:session-id`). canonical-workflows + workflow-async-path suites
  green; clj-kondo clean.

  Reconciliation note: ψ initially drafted a separate test-only ns
  (`orchestration_continue_snapshot_test.clj`) mirroring the injection in a test
  closure. On detecting the concurrent working-tree changes
  (`runtime_eql.clj` production fix + the canonical-workflows T4 test, both
  uncommitted, HEAD advanced to `3cea68aeb` mid-session), ψ removed the
  redundant draft and kept the concurrent session's stronger production-grounded
  resolution. This mirrors the prior T3 concurrent-commit reconciliation
  recorded in state.md.

- **T5 — `:inherited-snapshot?` contract threading seam.** New test
  `create-workflow-child-session-inherited-snapshot-flag-survives-threading-test`
  in `workflow_child_session_context_test.clj`, driving the REAL private
  `create-workflow-child-session!` (`context.clj`) → `:session/create-child`
  dispatch (`session_lifecycle.clj`) → `child-session-base-state*`
  (`child_session_state.clj`) with real ctx/state. The R4/R5 fix gates child
  snapshot isolation on `:inherited-snapshot?`; the producer (`attempts-test`)
  and consumer (`child-session-state-test`) each had a unit test, but the middle
  `context.clj:159` `(assoc :inherited-snapshot? …)` → handler → builder hop was
  untested — both endpoints would stay green if the flag were dropped on the
  wire (the same incoherence class R5 caught with the dangling contract field).
  Block 1 (`:inherited-snapshot? true`, nil snapshot-governed fields, parent
  carrying non-default `live-model`/`:prose`/`:flex`/`:low`): the persisted
  child uses the initial-session defaults (`:model` nil, `:prompt-mode :lambda`,
  `:speed-mode`/`:effort-override` nil), NOT the live parent — proving the flag
  survives threading. Block 2 (control, no flag): the same nil-supplied fields
  fall back to the live parent through the identical chain — proving the
  distinction is carried by the flag, not lost on the wire. 2 blocks / 39
  assertions in the (4-test) suite green; clj-kondo clean.

Both T4 and T5 checked in steps.md; all 207 follow-up steps now checked. No new
actionable findings; no follow-up items added.

## Test-review pass 6 (review 2026-06-02)

Re-reviewed the full task test surface against task-test-review criteria
(well-formed ∧ ∀behaviour∃test ∧ infra-deps injectable/nullable/¬mock). All
AC→test mappings present and green (inheritance-snapshot 10/51, child-session +
context + core 22/136, canonical-workflows + canonical-workflows-snapshot +
attempts 20/159). Tree clean at HEAD; `make-test-ctx`/`sample-definition` public
and shared by the split `canonical_workflows_snapshot_test.clj` (compiles +
passes). `with-redefs` usages (`valid-session?`, nullable
`create-child-session!`/`get-session-data` adapters) are infra-boundary
nullables, not mocks of the unit under test — methodology-conformant.

One actionable gap (T6): the **capture-side** tools/skills value is asserted
only by shape. `resolve-inherited-defaults-snapshot-test`
(`inheritance_snapshot_test.clj`) sets model/prompt-mode/thinking/speed/effort
on the fixture parent and asserts each exactly, but for the two pool fields it
asserts only `(vector? (:tool-defs snapshot))` / `(sequential? (:skills
snapshot))` — never that the captured pools reflect the parent's actual
`tool-source`+`:tool-ids` / `all-skills`. `resolve-inherited-defaults-snapshot`
(`core.clj:282-285`) reads both from the live parent; a regression that dropped
`:tool-ids`, read the wrong session, or returned an empty pool would still pass
(empty `[]` is `vector?`/`sequential?`). The isolation test
(`snapshot-isolates-tools-skills-…`) uses a HAND-BUILT snapshot, not one
produced by `resolve-inherited-defaults-snapshot`, so it does not cover the
capture path's tools/skills value either. AC3 names tools/skills as inherited
defaults; the capture half of that invariant is value-unasserted. See T6.

## Test-review pass 6 (ψ, 2026-06-02)

Full test-review re-pass after T1–T5 + the file-length split (`ee5dc140b`).
Applied task-test-review skill: well-formed ∧ AC-coverage ∧ no-mock infra deps.

- **AC→test coverage complete (all 9).** AC1/2 + model/prompt/thinking/speed/
  effort AC3 → `snapshot-isolates-resolution-from-live-parent-mutation-test`;
  tools/skills AC3 → `snapshot-isolates-tools-skills-from-live-parent-mutation-test`;
  AC4 → `nested-delegation-…-overridden-model-test` +
  `delegate-step-runtime-result-persists-child-inherited-defaults-test`;
  AC5 → `snapshot-preserves-explicit-step-override-test`;
  AC6 → `no-snapshot-falls-back-to-live-parent-test`;
  AC7 → `snapshot-model-feeds-model-query-selection-context-test` (T1-strengthened,
  snapshot-vs-live distinguishing winner);
  AC8 → `resume-run-test` "AC8" block (`workflow-runtime/core_test.clj` — note
  steps.md S5 misattributed the ns to agent-session `workflow_runtime_test`;
  coverage is correct, only the pointer was wrong; non-actionable);
  AC9 → `create-run-persists-inherited-defaults-snapshot-test` (canonical-state
  residence + `workflow-run-schema` validation = the testable replayability claim).
- **No-mock invariant holds.** 207 tests use real ctx/state + nullable adapter
  (`support/create-session-context {:persist? false}`); zero `with-redefs`/mock/
  stub of infra deps in the inheritance-snapshot, child-session-context, or
  canonical-workflows-snapshot suites. The lone `with-redefs` in
  `child_session_state_test.clj:239` is pre-existing non-207 prompt-rebuild
  scaffolding.
- **Nested-snapshot schema conformance transitively covered.** `create-run`
  throws on `valid-workflow-run?` failure, so the e2e child-persist test
  validates the `effective-config->snapshot` output against the schema (a
  malformed `:model` shape would error the test), closing the apparent
  "nested path only asserts key-set, not schema" concern.
- **Tree/HEAD coherent.** The transiently-dirty working tree first observed
  (T4 split mid-commit by a concurrent session) resolved to clean; HEAD
  `ee5dc140b` self-consistent — T4 test lives solely in tracked
  `canonical_workflows_snapshot_test.clj`, no duplication.
- **Suites green (bounded heap):** workflow-runtime core (9/35),
  inheritance-snapshot (10/51), canonical-workflows-snapshot +
  workflow-child-session-context (5/43). 0 failures.

No new actionable test issues. Prior passes closed every gap (weak assertions
T1/T2, dirty-tree commits T3/R5, Decision 5b T4, `:inherited-snapshot?` seam T5).
Test review complete.

## Test-shaper review (ψ, 2026-06-02)

Applied `test-shaper` skill (simple ∧ consistent ∧ robust ∧ economical;
meaningful_failures ∧ behavior_focused ∧ no-mock infra). Grounded against the
207 test surface (`inheritance_snapshot_test.clj`, `core_test.clj`,
`attempts_test.clj`, `child_session_state_test.clj`,
`canonical_workflows_snapshot_test.clj`, `workflow_child_session_context_test.clj`)
and the resolver code (`workflow-step-session-config/core.clj`
`resolve-inherited-defaults-snapshot` :266-285, `effective-config->snapshot`).

Shape strong: real ctx/state throughout (`support/create-session-context`,
real `create-run`/`resolve-step-session-config`/`delegate-step-runtime-result`/
`child-session-base-state`); output/state assertions, no interaction
assertions; nullable adapters at the infra boundary only. AC1–9 each map to a
focused test (AC7 T1-strengthened to a snapshot-vs-live distinguishing winner;
AC3 tools/skills *consumption* isolation covered by
`snapshot-isolates-tools-skills-from-live-parent-mutation-test`; AC4 covered at
both function-composition and e2e delegate-wiring level).

One open `meaningful_failures` / `economical` gap, ALREADY tracked as T6
(steps.md, unchecked) — not re-raised here to avoid duplication:
`resolve-inherited-defaults-snapshot-test` asserts the captured tools/skills
pools only by SHAPE (`vector?`/`sequential?`), never by value, so a regression
dropping `:tool-ids`, reading the wrong session, or returning an empty pool
(empty `[]` is `vector?`/`sequential?`) would still pass — the CAPTURE half of
AC3's tools/skills invariant is value-unasserted (the isolation test uses a
hand-built snapshot, not a captured one, so it does not cover capture either).
T6 stands as written.

Reconciliation note (ψ): a CONCURRENT, UNCOMMITTED working-tree change to
`inheritance_snapshot_test.clj` already implements T6 — it seeds a known tool
(`agent-core/set-tools-in!` + `:tool-ids`) and registers a known skill
(`:session/register-skill`) on the parent, then asserts the captured
`:tool-defs`/`:skills` pools contain those defs BY VALUE (description), replacing
the shape-only `vector?`/`sequential?` assertions. That is precisely the T6 fix.
It is NOT committed (surfaced by the pre-commit stash) and was NOT verified by
this review pass; left to its author to verify + commit. This test-shaper commit
deliberately carries only the implementation.md note (the concurrent test change
is excluded). Once that change is committed and green, T6 can be checked.

No NEW actionable test-shaper findings beyond the open T6. The other
candidate-smells reviewed are non-actionable: the AC8 ns-pointer
misattribution in steps.md S5 (pass-6 noted; coverage correct, only the
pointer wrong) and the dual-shape `(or (get-in config [:model :id]) (:model
config))` override read are defensive, not signal-eroding.

PASS_STATUS: ACTIONABLE_FEEDBACK

## T6 follow-up executed (test-review pass 6)

The concurrent uncommitted T6 working-tree change noted in the reconciliation
above was NOT present at this session's start (tree was clean, HEAD
`850698f5e`), so T6's capture-path value assertions did not exist in HEAD. ψ
authored the T6 fix fresh in this pass.

Implemented in `resolve-inherited-defaults-snapshot-test`'s first `testing`
block (`inheritance_snapshot_test.clj`): seed the fixture parent's REAL capture
inputs — agent tool-source via `agent-core/set-tools-in! (ss/agent-ctx-in …)`
(`known-tool`) selected by `:tool-ids`, and a `known-skill` registered through
the canonical `:session/register-skill` dispatch (root-state def + `:skill-ids`
tracking). Then assert the captured snapshot's `:tool-defs`/`:skills` CONTAIN
the named def AND carry its value (`:description`), replacing the shape-only
`vector?`/`sequential?` checks. The capture half of AC3's tools/skills invariant
is now value-asserted with the same rigor as the other five captured fields.

Key subtlety: the capture path resolves `:tool-defs` from
`ss/agent-tool-source-in` (the agent data-atom `:tools`), NOT session-data
`:tool-source` — so the data-atom must be seeded, not session-data
`:tool-source`. (The isolation test's session-data `:tool-source` mutation
targets the consumption path with a hand-built snapshot, a deliberate distractor
that does not exercise capture.) New requires: `psi.agent-core.core`,
`psi.agent-session.core`, `psi.session-state.state` — all on the main base test
classpath. Test-only change (no behaviour/code/doc/changelog delta).
inheritance-snapshot suite green (10 tests, 53 assertions); lint clean.

## Test-review pass 7 (ψ, 2026-06-02)

Re-applied `task-test-review` (well-formed ∧ ∀-AC-coverage ∧ no-mock infra
deps) across the full 207 test surface. All 9 ACs map to tests; suites green
(inheritance-snapshot 10/53; canonical-workflows-snapshot + child-session-state
+ child-session-context + workflow-runtime core 23/140). Infra deps are real /
nullable (`support/create-session-context {:persist? false}`, real
ctx/state/dispatch); the delegate test's two no-op injected fns are legitimate
dependency injection (the design mandates injected fns), not mocks. No mocks /
`with-redefs` / stubs of logic.

One actionable behaviour-coverage gap (T7):

- **T7 — nested/delegated path isolation from a post-invoke live-parent
  mutation is untested directly.** AC4 has two halves: (i) the nested run
  inherits the delegating step's EFFECTIVE config (overridden model
  propagates), and (ii) it is captured "**not the (possibly-since-mutated)
  invoking session**" — the anti-leak invariant for the nested path. Both AC4
  tests cover (i): `nested-delegation-effective-snapshot-propagates-overridden-
  model-test` (function-composition level) and
  `delegate-step-runtime-result-persists-child-inherited-defaults-test`
  (delegate.clj wiring). Neither mutates the LIVE parent session AFTER invoke
  before delegating, so half (ii) — the nested child snapshot's isolation from
  a since-mutated invoking session — is proven only TRANSITIVELY (via the
  AC1/AC2/AC3 `resolve-step-session-config` isolation tests, since the nested
  path reads the parent RUN snapshot through `effective-config->snapshot` and
  the resolver is snapshot-gated, R1). A future change reintroducing a live
  read on the nested derivation path (e.g. the injected closure re-reading the
  live session instead of `(:inherited-defaults workflow-run)`) would NOT be
  caught by any current test. The top-level path got exactly this direct
  isolation assertion in T2 (tools/skills) — the nested path should have the
  parallel one. See T7.

## Test-shaper review (ψ, 2026-06-02 — post-T6)

Re-applied `test-shaper` after T6 committed (`f338e5f4b`). Tree clean, HEAD
`a30abd809`. Re-read the full 207 test surface (`inheritance_snapshot_test.clj`,
`workflow_runtime/core_test.clj`, `canonical_workflows_snapshot_test.clj`,
`workflow_child_session_context_test.clj`) + resolver/schema source
(`workflow-step-session-config/core.clj`, `workflow-runtime/model.clj`).
Focused suites green: inheritance-snapshot + workflow-child-session-context
(14 tests, 92 assertions, 0 failures).

Quality: HIGH. simple ∧ consistent ∧ robust ∧ economical all hold. Real
ctx/state throughout; nullable adapters at infra boundary only (no-mock).
Behavior/state assertions, no interaction assertions. Every `is` carries a
meaningful failure message. Distinguishing-value assertions (snapshot-vs-live
model/prompt/speed/effort/tools/skills) prove the actual isolation contract,
not just shape. Positive+negative control blocks for the `:inherited-snapshot?`
flag. AC1–9 each map to a focused test; T6 closed the last capture-side value
gap. No case-explosion.

One candidate smell considered and judged NON-actionable: the
`inherited-defaults-schema` (model.clj:179) is asserted only on the positive
path (`valid-workflow-run?` true) — no negative test proves it REJECTS a
malformed snapshot (e.g. bare-string `:model`, non-keyword `:prompt-mode`). I
deliberately do NOT raise this as a follow-up: (1) the schema is consumed only
inside pure `create-run` via `valid-workflow-run?`, and (2) every production
producer (`resolve-inherited-defaults-snapshot`, `effective-config->snapshot`)
is already value-tested to emit exactly the typed key set, so a malformed
snapshot can only originate from a producer bug the producer tests already
guard. A negative schema test would assert malli's own constraint enforcement
— framework-level, low-signal, redundant — which `economical(tests)` /
`behavior_focused(tests)` steer away from. The behavioral contract (capture →
persist → consume → isolate) is fully covered.

No new actionable test-shaper findings. Review complete.

## T7 follow-up executed (ψ, 2026-06-02 — test-review pass 7)

Closed the last AC4 coverage gap: the nested/delegated child snapshot's
isolation from a since-mutated invoking session had no DIRECT test (only
transitive via the AC1/AC2/AC3 resolver isolation tests + the snapshot-gated R1
read). A future change reintroducing a live read on the nested derivation path
(the injected closure re-reading the live session instead of
`(:inherited-defaults workflow-run)`) would have passed every prior test.

Added `nested-delegation-isolates-child-snapshot-from-live-parent-mutation-test`
to `inheritance_snapshot_test.clj`, the nested-path parallel of the direct
top-level T2 tools/skills isolation test. It:

- creates the delegating run (`delegating-e2e` → `child-wf`) with a
  `claude-PARENT` parent-run snapshot (speed `:fast`/effort `:xhigh`);
- mutates the LIVE parent session to `claude-LIVE-CHANGED`
  (speed `:flex`/effort `:low`) AFTER invoke, BEFORE delegating;
- drives the REAL `delegate/delegate-step-runtime-result` with the real injected
  `resolve-inherited-defaults-fn` closure (mirrors `context.clj`:
  `effective-config->snapshot` ∘ `resolve-step-session-config`) plus stub no-op
  `send-and-drain-fn`/`create-workflow-context-fn`;
- asserts the CHILD run's persisted `:inherited-defaults` carries the parent-run
  snapshot model (`claude-PARENT`, and explicitly `≠ claude-LIVE-CHANGED`) and
  the parent-snapshot speed/effort (`:fast`/`:xhigh`, not the mutated
  `:flex`/`:low`), with the exact snapshot key set.

Test-only addition (no behaviour/code/doc change). inheritance-snapshot suite
green (11 tests, 59 assertions, 0 failures via `clojure -M:test --focus`);
`clj-kondo` clean (0 errors / 0 warnings) on the touched file. No new actionable
follow-up items — the T7 gap is the only item this review pass added, and it is
now closed.

## Test-shaper review (ψ, 2026-06-02 — post-T7, pass 8)

Re-applied `test-shaper` after T7 committed (`69fe49ec0`). Tree clean. Re-read
the full 207 test surface (`inheritance_snapshot_test.clj`,
`workflow_runtime/core_test.clj`, `canonical_workflows_snapshot_test.clj`,
`workflow_child_session_context_test.clj`). Focused suites green: 16 tests,
102 assertions, 0 failures.

Quality: HIGH. simple ∧ consistent ∧ robust ∧ economical ∧ deterministic all
hold. Real ctx/state throughout; nullable adapters at infra boundary only
(no-mock). State/behavior assertions only, no interaction assertions. Every
`is` carries a meaningful failure message. Distinguishing-value assertions
(snapshot-vs-live for model/prompt/speed/effort/tools/skills, and model-query
selection winner) prove the actual isolation contract, not shape. Positive +
negative controls for both the `:inherited-snapshot?` flag (child-session) and
the no-snapshot live-parent fallback. AC1–9 each map to a focused test, now
including the direct nested-path isolation test T7 added.

AC coverage confirmed: AC1/AC2 (snapshot-isolates-resolution-…), AC3 model
(same test) + tools/skills (snapshot-isolates-tools-skills-…), AC4 propagation
(nested-delegation-effective-…, delegate-step-runtime-result-persists-…) +
isolation (nested-delegation-isolates-child-snapshot-…, T7), AC5
(snapshot-preserves-explicit-step-override-…), AC6 (no-snapshot-falls-back-…),
AC7 (snapshot-model-feeds-model-query-…), AC8 (core_test resume-run-test
"AC8" block — reuses original snapshot verbatim), AC9 (create-run-persists-…
+ schema validation). Field-set authority drift guarded by
inherited-defaults-field-set-authority-test against common-inherited-fields /
model-identity-fields. Decision 5b fresh-snapshot capture covered by
continue-terminal-run-captures-fresh-snapshot-test with a production-like
session-id-injecting mutate!.

One candidate smell considered and judged NON-actionable: the injected
`resolve-inherited-defaults-fn` closure (the `effective-config->snapshot ∘
resolve-step-session-config` mirror of `context.clj`) is defined verbatim in
two nested e2e tests (delegate-…-persists-… and nested-delegation-isolates-…).
Extracting it to a shared helper would WEAKEN the isolation test — that test's
proof is precisely that this closure structure reads
`(:inherited-defaults workflow-run*)` and not the live session, so the closure
should remain visible at the test site, not hidden behind a helper
(`¬helpers_that_hide(intent)`). The duplication is local-comprehensibility-
preserving, not incidental ceremony. No follow-up.

No new actionable test-shaper findings. Review complete.

## Docs review (review-task-docs, 2026-06-02)

Scope: README.md, doc/, CHANGELOG.md per review-task-docs checklist.

- doc/workflows.md "Inherited session defaults are snapshotted at invoke time"
  (`:206-229`): accurate ∧ complete ∧ consistent. Covers invoke-time capture,
  all 7 inherited fields (model/prompt-mode/tools/skills/thinking-level/
  speed-mode/effort-override = AC3), no-retroactive-effect (AC1/AC2), nested
  effective config (AC4), explicit-override precedence (AC5), resume-reuse vs
  continue-fresh (AC8 / Decision 5a+5b). Field names + resume/continue
  distinction match design exactly. Prose-only (no drift-prone examples).
- CHANGELOG.md [Unreleased] → Fixed: present, accurate; user-visible workflow
  behaviour change correctly logged with the full field list. Matches
  workflows.md wording. No footer hand-edit.
- README.md: no inherited-session-default surface to update (no stale refs).
- doc/workflow-grammar-concepts.md / workflow_statechart_canonical.md: describe
  authoring grammar / canonical shaping, NOT runtime default-value sourcing
  (live vs snapshot), so the live→snapshot change introduces no stale refs.
  Correctly localized to workflows.md (runtime resolution concern).
- No removed user-facing behaviour (live-read was internal).

No actionable docs findings. Review complete.

## Code-shaper review (ψ, 2026-06-02)

Lens: simplicity ∧ consistency ∧ robustness on the production code
(`workflow-step-session-config/core.clj` `resolve-step-session-config`,
the snapshot consumption seam). Prior reviews covered tests/docs/architecture
but not the resolver's per-field shape. Two consistency findings (CS1, CS2);
one candidate dismissed.

CS1 — three different idioms express the same "source field from
snapshot-or-live" operation across the seven inherited defaults:
  - `(if snapshot? (:X snapshot) (:X parent-session))` — model, prompt-mode,
    skills, tool-defs (`core.clj:202-212`)
  - `(when snapshot? (:thinking-level snapshot))` buried inside an `or` chain
    (`core.clj:243-246`)
  - `(and snapshot? (some? (:X snapshot))) → assoc` cond-> branches — speed-mode,
    effort-override (`core.clj:255-261`)
The snapshot field set is now an explicit named authority
(`inherited-defaults-snapshot-keys`), yet its consumption is scattered across
three shapes, so the "seven fields sourced from the snapshot" unit is not
locally comprehensible as a unit. Consistency smell (¬consistent(idioms)).

CS2 — `:thinking-level` snapshot/inherited precedence is inverted relative to
`:model`. For `:model` the inherited default (`parent-session-model`) ranks
ABOVE the base-meta `:model` override (`core.clj:220-235`: step → inherited →
base-meta). For `:thinking-level` the snapshot/inherited value ranks BELOW the
base-meta `:thinking-level` (`core.clj:243-246`: step → base-meta → snapshot →
:off). So a `:workflow-file-meta` thinking-level masks the inherited parent
value, but a `:workflow-file-meta` model does NOT mask the inherited parent
model. The two inherited fields disagree on whether base-meta or the inherited
default wins. Robustness/consistency concern: AC1–3 frame the inherited-default
invariant uniformly across all seven fields, but base-meta interacts with the
inherited layer differently per field. Decide the intended ordering and make it
uniform (or document the per-field difference explicitly with rationale).

Dismissed (NON-actionable): `:prompt-mode` has no step/base-meta override layer
(`:prompt-mode parent-session-prompt-mode` direct, `core.clj:240`), unlike
model/thinking-level/tools/skills. This is correct — the workflow grammar
exposes no `:prompt-mode` override surface in `:session`/`:workflow-file-meta`
(grep: prompt-mode absent from workflow-step-materialization /
workflow-registry schemas), so the resolver faithfully mirrors the grammar (no
override surface → no override layer). Not a finding.

CS1/CS2 are shape/consistency findings, not correctness regressions (no AC test
fails today); follow-ups added.

## Code-shaper follow-ups executed (ψ, 2026-06-02)

CS1 (idiom unification) and CS2 (thinking-level precedence) both executed in
`workflow-step-session-config/core.clj` `resolve-step-session-config`.

CS1 — collapsed the three snapshot-vs-live consumption shapes into a single
`inherited` map bound once. Snapshot path:
`(select-keys snapshot inherited-defaults-snapshot-keys)`. Live path: only the
four fields the pre-task resolver inherited live (`:model :prompt-mode :skills
:tool-defs`), with `:thinking-level :speed-mode :effort-override` deliberately
omitted so the live (snapshot-less, AC6) path keeps emitting no speed/effort and
falls thinking-level back to base-meta/:off — i.e. no behaviour change for
snapshot-less runs. All seven downstream reads now source from `inherited`, so
the `inherited-defaults-snapshot-keys` set is the single edit point and the
"seven fields from the snapshot" reads as one unit (locally comprehensible).
`parent-session-model` is now `(:model inherited)`.

CS2 — chose uniformity over per-field documentation. The established `:model`
convention ranks the inherited default ABOVE base-meta (`resolved-model` cond:
step → `parent-session-model` → base-meta); `:thinking-level` was the lone
inversion (step → base-meta → snapshot → :off). Reordered thinking-level to
`(or (:thinking-level session-spec) (:thinking-level inherited)
(:thinking-level base-meta) :off)` so a `:workflow-file-meta` thinking-level no
longer masks an inherited snapshot value, matching the inherited model. This is
a behaviour change ONLY on the snapshot path with a base-meta thinking-level
present (no existing snapshot test combined the two, so none regressed); the
snapshot-less path is unchanged (inherited thinking-level absent ⇒ base-meta
still wins there, preserving AC6).

Coherence: design.md Decision 1a records the uniform precedence + the AC6
carve-out; CHANGELOG Fixed entry notes the user-visible thinking-level
precedence change; new test
`snapshot-thinking-level-precedence-matches-model-test` pins inherited>base-meta
for thinking-level (alongside model) and step-override>inherited>base-meta.
Full `bb clojure:test:unit` green; clj-kondo clean on both touched files; both
files under the 800-line commit-check limit.

## Test-review pass 8 (review 2026-06-02)

ψ test-review (task-test-review skill: well-formed ∧ AC-coverage ∧
no-mocks/nullable-infra). Ran the touched suites:
inheritance-snapshot (12 tests/62 assertions),
workflow-runtime core-test + child-session-state + workflow-child-session-context
+ canonical-workflows-snapshot + attempts (30 tests/172 assertions) — all green.

Findings — NONE actionable. Assessment:

- **Well-formed / no-mocks**: task-207 tests use real ctx/state, output+state
  assertions, and injected seams (`resolve-inherited-defaults-fn`,
  `send-and-drain-fn`, `create-workflow-context-fn`, `production-like-mutate!`)
  that are real closures / no-op boundary stubs at the same injection points
  production uses — not `with-redefs`/mocks of logic. The one `with-redefs`
  (`child_session_state_test.clj:239`, `system-prompt/build-system-prompt`) is
  pre-existing and outside task scope.
- **AC coverage** (1–9) is complete and *discriminating*, not shape-only:
  - AC7 uses two REAL registered models (`claude-opus-4-5`/`claude-haiku-4-5`)
    + the REAL `:same-model-as-session`/`:context-match` criterion, so a leaked
    live model would flip the ranking winner (closes the original T1 weakness).
  - AC3 covers model/prompt-mode/thinking/speed/effort isolation AND the
    tools/skills isolation (T2) by name against distinct snapshot-vs-live defs.
  - AC4 covers function-level propagation, e2e `delegate-step-runtime-result`
    child-run persistence (R2), AND since-mutated-live-parent isolation (T7).
  - AC8 (resume reuse) + Decision 5b (continue-terminal fresh capture via the
    real `continue-terminal-run-async!` + session-id auto-injection contract, T4).
  - Field-set drift is guarded by `inherited-defaults-field-set-authority-test`.
- **create-run purity** is structurally enforced by signature (`state`-in, no
  `ctx` param); the persistence test asserts "verbatim, no resolution". A
  dedicated purity test would be redundant (`unreachable > forbidden`).
- **Decision 5a residual asymmetry (considered, NOT flagged)**: the AC8 "no
  re-capture on resume" is proven at the pure `resume-run` level, not at the
  `continue-blocked-run-async!` async level. Unlike the terminal path (which
  calls `create-run` and needed T4), the blocked-resume path routes ONLY through
  `mutate! 'psi.workflow/resume-run` and reaches NO capture site, so "no
  re-capture" is architecturally guaranteed; an async-level test would be a
  near-duplicate of `resume-run-test` with no discriminating power. No follow-up.

Coherence note (non-test, NOT a test follow-up): steps.md ends with a stray
DUPLICATE CS2 item left UNCHECKED (`- [ ] CS2`) below the completed `[x] CS2`;
the work is done (committed `6cad7a672`). A leftover-checklist artifact, not a
test gap — left for a steps/plan-hygiene pass, not raised as a test follow-up.

Conclusion: test suite is well-formed, mock-free, and provides discriminating
coverage of every acceptance criterion and resolved decision. Review complete;
no new actionable test follow-up items added.

## Test-shaper review pass 9 (review 2026-06-02)

Re-ran the inheritance-snapshot suite (12 tests, 62 assertions, green) and
audited the new continue-terminal T4 test against test-shaper. Coverage and
discrimination remain strong (state-based, mock-free, distinguishing values).
Two NEW `meaningful_failures`/clarity defects in the T4 test seam (not
previously recorded; prior passes' DUPLICATE-CS2 note is a separate
steps-hygiene item):

- **T8 — contradictory contract comment.**
  `canonical_workflows_snapshot_test.clj:44` calls `psi.workflow/create-run`
  "non-session-scoped", directly contradicting the SAME file's docstring
  (`:19-20`: "`psi.workflow/create-run` is in that session-scoped set") and
  production (`runtime_eql.clj:17-49`: it IS a member of
  `session-scoped-extension-mutation-ops`). The whole point of this test is to
  pin the session-id auto-injection contract; a comment that misstates the
  contract direction misleads a future reader debugging the seam and weakens
  the test's self-documenting `meaningful_failures` value. Actionable: a
  test-comment correctness fix.

- **T9 — steps.md T4 note cites the wrong file.** The T4 follow-up's DONE note
  says the test was added "in `canonical_workflows_test.clj`", but it actually
  lives in the sibling `canonical_workflows_snapshot_test.clj` (split out for
  the 800-line file-length limit). Minor doc-vs-artifact drift in the task
  record; correct the cited filename so the test trail is accurate.

Everything else (AC1–9 discriminating coverage, field-set authority guard,
purity-by-signature, R1 snapshot-gated read, R4/R5 `:inherited-snapshot?`
threading with judge carve-out) remains well-shaped. No other new test gaps.

## Test-review pass 9 follow-up execution (2026-06-02)

Executed the two newly added pass-9 follow-ups (T8, T9); both are
documentation/comment-only with no behaviour change.

- **T8 (done).** Rewrote the `continue-terminal-run-captures-fresh-snapshot-test`
  deftest comment in `canonical_workflows_snapshot_test.clj` (the `:44`
  citation): "matching the runtime-fns wrapper for the **non**-session-scoped
  `psi.workflow/create-run`" → "matching the runtime-fns wrapper that injects
  the active session for the **session-scoped** `psi.workflow/create-run`". Now
  consistent with the file docstring (`:17-22`) and production
  (`runtime_eql.clj` `session-scoped-extension-mutation-ops`). `clj-kondo` clean
  (0 errors / 0 warnings) on the touched file; comment-only, so existing test
  behaviour is unaffected.

- **T9 (done).** Corrected steps.md T4's DONE note (`:496`) to cite
  `canonical_workflows_snapshot_test.clj` (split out of
  `canonical_workflows_test.clj` for the 800-line file-length limit) instead of
  `canonical_workflows_test.clj`, matching where
  `continue-terminal-run-captures-fresh-snapshot-test` actually lives. Docs-only.

Out of scope (predates pass 9): the unchecked duplicate `CS2` entry between the
code-shaper section and the pass-9 header was introduced by the earlier
code-shaper review (`65af82156`), not pass 9; CS2's work is already implemented
and recorded under the checked CS2 above. Left untouched per the "do not execute
items that predate the preceding review pass" constraint.

## Test-review pass 10 (test-shaper, ψ, 2026-06-02)

Applied test-shaper (clarity ∧ signal ∧ robustness ∧ economical) across all
touched test files: `inheritance_snapshot_test.clj` (12 tests / 62 assertions),
`workflow_runtime/core_test.clj` snapshot+resume blocks, `canonical_workflows_test.clj`
S4 capture, `canonical_workflows_snapshot_test.clj` (Decision 5b), and
`workflow_tools_test.clj`. Focused runs green and deterministic: snapshot ns
12/62, the four agent-session/workflow-runtime ns 23 tests / 216 assertions, 0
failures.

Assessment — no new actionable test-quality findings:
- Mock-free (zero `with-redefs`); seams are real (dispatch register-skill,
  agent tool-source, real registered models for AC7) — testing-without-mocks
  satisfied.
- Behavior-focused: assertions read resolved config outputs (`:model`,
  `:prompt-mode`, `:tool-defs`, `:skills`, `:thinking-level`, `:speed-mode`,
  `:effort-override`, model-fallback ranking), not internals.
- Discriminating signal: snapshot-vs-live use DISTINCT values for the same key
  (claude-snapshot vs claude-LIVE-CHANGED; from-snapshot vs from-live tool/skill
  descriptions; two distinct real models for AC7), so a regression re-reading
  the live parent flips the assertion rather than passing silently.
- Meaningful failures: every `is` carries a contract-stating message.
- Economical AC coverage: AC1/2/3 (iso + tools/skills iso), AC4 (projection +
  e2e delegate + nested live-mutation isolation), AC5, AC6, AC7, AC8 (resume),
  Decision 5b (fresh continue), field-set authority drift guard.
- Determinism: no time/random/IO; fixed run-ids and seeded session data.

Known inconsistency, NOT re-flagged: the unchecked duplicate `CS2` item in
steps.md (between the code-shaper section and the pass-9 header) is a verbatim
copy of the already-`[x]` CS2; its requested test
(`snapshot-thinking-level-precedence-matches-model-test`) exists and passes.
Pass 9 already documented this as a pre-pass-9 leftover (`65af82156`) left
untouched per the "do not execute items predating the preceding pass"
constraint. Re-removing or re-flagging it would contradict that recorded
decision and duplicate an existing note, so no new step is added.

Conclusion: test suite is well-shaped; review complete, no actionable feedback.

## Docs review pass 2 (review-task-docs, ψ, 2026-06-02)

Independent re-review after the CS2 commit (`6cad7a672`) landed *after* the
original docs review (`66a26a176`). Re-checked README.md, doc/, CHANGELOG.md.

- CHANGELOG [Unreleased] → Fixed: accurate ∧ complete. Field list
  (model/prompt-mode/tools/skills/thinking-level/speed-mode/effort-override)
  matches the shipped snapshot keys; nested-effective-config, explicit-override,
  replayable-canonical-state claims all match implementation. The CS2
  thinking-level precedence change (inherited ranks above the workflow-file
  default; step → inherited → file → off) is correctly recorded here as a
  user-visible behaviour change.
- doc/workflows.md "Inherited session defaults are snapshotted at invoke time"
  (`:206-229`): still accurate ∧ consistent for the snapshot/inheritance model
  (invoke-time capture, all 7 fields, no-retroactive-effect, nested effective
  config, explicit-override precedence, resume-reuse vs continue-fresh). The
  CS2 precedence nuance (inherited thinking-level vs a *workflow-file* default)
  is not reflected here — judged NON-actionable: workflows.md is a conceptual
  guide that does not document a workflow-file-level `:model`/`:thinking-level`
  default surface, so the inherited-vs-file precedence has no natural home at
  this abstraction level, and the user-visible record (CHANGELOG) already
  captures it accurately.
- README.md: no inherited-session-default surface; no stale refs.
- No stale references to the old live-read behaviour anywhere in doc/ or
  README.md (grep clean; the lone `rpc-edn-op-mapping-contract.md` hit is
  unrelated).
- No removed user-facing behaviour to clean up.

No actionable docs findings. Review complete.

## Code-shaper review pass 2 (post-CS1/CS2) (ψ, 2026-06-02)

Independent re-shape after CS1/CS2 landed (`6cad7a672`). Re-read the shipped
production surface: `workflow-step-session-config/core.clj`
(`resolve-step-session-config`, `resolve-inherited-defaults-snapshot`,
`effective-config->snapshot`, field-set constants),
`workflow-runtime/model.clj` (`inherited-defaults-schema`),
`workflow-runtime/core.clj` (`create-run`), the two top-level capture sites
(`mutations/canonical_workflows.clj`, `psi_tool_workflow.clj`), and the nested
path (`delegate.clj` + `context.clj` injected `resolve-inherited-defaults-fn`).

Assessment — no new actionable code-shaping findings:
- **Simple**: post-CS1 the seven inherited defaults flow through one `inherited`
  map; consumers read `inherited` rather than re-expressing snapshot-vs-live per
  field. Helpers are single-responsibility; the two snapshot producers each have
  one purpose.
- **Consistent**: field set is one named authority
  (`inherited-defaults-snapshot-keys`/`-source-keys`); naming and arg order are
  uniform; the new injected `resolve-inherited-defaults-fn` matches the
  established `create-workflow-context-fn`/`send-and-drain-fn` injected-fn idiom.
- **Robust**: field set validated against `common-inherited-fields`/
  `model-identity-fields` by a drift test; both producers assert their output
  keys equal the constant; schema-validated on the run; live-read fallback
  preserves AC6 back-compat.

Considered but NON-actionable (consistency-with-local-idiom):
- The two top-level capture sites differ — `canonical_workflows.clj` guards
  resolution with `(when session-id …)` and conditionally assocs, while
  `psi_tool_workflow.clj` resolves unconditionally and assocs in the base map.
  Justified by a real precondition delta: psi-tool calls
  `(require-session-id! session-id op)` (throws when absent), so its session-id
  is always present; the mutation tolerates a nil session-id. Divergence is
  warranted, not drift.
- `delegate-step-runtime-result` now takes 8 positional params (three injected
  fns). The design (Decision 7a) deliberately added `resolve-inherited-defaults-fn`
  *alongside* the two pre-existing injected fns to match the local idiom;
  converting to an options map would make it inconsistent with the surrounding
  two params and is out of scope. Matching the established convention is the
  shaped choice.

Verification: clj-kondo clean (0/0) on the touched namespaces; focused
`inheritance-snapshot-test` 12 tests / 62 assertions, 0 failures.

Conclusion: production code is well-shaped; review complete, no actionable
feedback.
