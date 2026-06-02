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
