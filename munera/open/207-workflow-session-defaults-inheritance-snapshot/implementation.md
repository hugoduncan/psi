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
