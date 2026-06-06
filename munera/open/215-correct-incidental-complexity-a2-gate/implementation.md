# Implementation notes

## Design review — architectural fit (ψ)

Reviewed `design.md` for fit with project architecture/principles (AGENTS.md
Principles; META.md and doc/architecture.md are runtime/adapter scope and do not
govern the workflow-emitter contract). Judged fit only, not clarity/consistency.

Root-cause-at-source framing (fix the emitter, not a per-task workaround) fits
`λ fix(bug). cause(structural) → redesign` well. Anchoring on the committed
`before-local.json` fits single-source-of-truth.

Actionable architectural-fit misfits found:

- **A2/A3 enforcement asymmetry.** A3 is a *mechanically enforced* gate (concrete
  `bb gordian gate --baseline … --fail-on …` that exits non-zero). The proposed
  A2a/A2b are numeric comparisons but the design specifies no executable
  computation/command — they remain agent-judged prose. This fights the
  architecture's `enforceable(invariants)` posture and the A3 precedent. The
  values are mechanically computable from `before-local.json` + after
  `local --json`; design should specify the computation/command or justify
  leaving A2 agent-evaluated.

- **Tunable margins (θ, ε) vs. objective / one-way posture.** The design's own
  soundness goal forbids `"with margin"-style undefined buffer`, then A2b
  introduces θ (and optionally ε). Tunable thresholds reintroduce a
  configuration/drift surface that fights `λone_way` and the objective-gate
  posture. Design should justify why a non-zero margin is architecturally
  necessary vs. a pure inequality.

- **Criterion taxonomy does not match the live emitter.** The reframing rests on
  "target reduction → A1, blast radius → A5", but the emitted contract in
  `.psi/workflows/reduce-incidental-complexity.edn` labels target reduction **A5**,
  net burden **A2**, gate **A3**; there is no **A1** and no **A4**, and blast
  radius is unnumbered prose. Acceptance criterion 2 ("No other emitted criterion
  (A1, A3, A4, A5) is altered") and the A2-rescoping rationale presume a labeling
  the artifact does not have; as written the change would emit a self-inconsistent
  A1–A5 contract. The renumbering must be brought into scope or the rationale
  re-anchored to the actual labels. (Overlaps the inconsistency pass; recorded
  here because it undermines the architectural reframing's premise.)

## Design review follow-up — architectural fit (ψ, executed)

All three architectural-fit follow-up steps resolved in `design.md`:

- **Enforcement asymmetry → resolved by specifying a deterministic computation.**
  Added "How A2 is mechanically checked" — A2a/A2b are concrete numeric `<`
  comparisons over `before-local.json` (B, before(m)) and after `bb gordian local
  --json`, joined per-unit; no judgement, no threshold. Same *kind* of objective
  check as A3. Noted there is no single `bb gordian` A2 subcommand today and that
  adding one is out of scope (criterion-text fix only) → agent runs the spelled-out
  procedure; dedicated command flagged as separate follow-up.

- **Margins θ/ε → removed (pure inequalities).** Justified that a non-zero buffer is
  NOT architecturally necessary: the only motive was global-recompute jitter, and the
  ceiling form `after(m) < B` is jitter-immune by construction (jitter is small;
  crossing B is a large relocation move). Dropped the "no substantial increase" θ
  clause; its anti-relocation role is subsumed by the ceiling + A3 + A5. Eliminates
  the undefined buffer / config-drift surface that fought `λone_way`. Updated the
  former "Open design-review parameters" → "Resolved design parameters".

- **Criterion taxonomy → re-anchored to live emitter labels (no renumbering).** Live
  emitter labels: A5=target reduction, A2=net burden, A3=gate; no A1/A4; blast radius
  unnumbered. Added a "Criterion taxonomy" table; rewrote "What A2 is genuinely for"
  and acceptance criterion 2 to use A5 (not A1) for target reduction and the
  unnumbered minimality/blast-radius criteria (not A5). Renumbering left out of scope
  per the task's minimal-change constraint. Fixed stray A1→A5 (empirics) and the
  Constraints internal-consistency line.

- **Join-key reconciliation (incidental, to avoid a new internal contradiction).**
  Aligned new sections to the design's deliberate line-insensitive `(ns, var, arity)`
  A2 key (refactors move lines → line-sensitive join would collapse T). Noted A5's
  line-bearing key robustness is a separate, out-of-scope question.

No blockers. Skill files confirmed to contain no A2 restatement (grep empty) →
acceptance 3 "confirmed absent" already holds for the design phase.

## Design review — ambiguities (ψ)

Reviewed `design.md` for ambiguity only (statements admitting >1 interpretation by
the executing emitter-edit agent). Did not review plan.md/steps.md. Found two new
actionable ambiguities; both verified against the codebase, neither covered by the
prior architectural-fit pass.

- **Non-unique `(ns, var, arity)` join key — defmethod collapse (procedure
  underspecified).** "How A2 is mechanically checked" step 3 forms `T` by "joining
  the two JSONs on `(ns, var, arity)`" and the design asserts "A2's units are
  distinct vars/arities, so dropping `line` does not conflate them." That assertion
  is empirically false: `before-local.json` (task 214) contains **51**
  `psi.agent-session.dispatch-effects/execute-effect!` units all sharing the exact
  key `(ns, var, arity) = (…, execute-effect!, null-arity)`, disambiguated only by
  `line` (the emitter itself calls this out for A5's line-bearing key). When such a
  unit is touched, the line-insensitive join is many-to-many and the procedure
  leaves undefined which `before(u)`/`after(u)` value applies (per-line? aggregated?
  max?) and how A2a/A2b compare against the non-unique group. The step reads as a
  clean per-unit join but is ambiguous precisely where the live data is non-unique.
  Actionable: define the join semantics for non-unique `(ns, var, arity)` keys
  (aggregate, per-line fallback, or explicit exclusion of defmethod groups), or
  scope/qualify the "distinct vars/arities" assertion to the units A2 can actually
  encounter.

- **Phantom "`reduce-incidental-complexity` skill" reference.** Scope/blast-radius
  says: "Check for and align any A2 description in
  `.psi/skills/incidental-complexity-finder/SKILL.md` **and the
  `reduce-incidental-complexity` skill**, if either restates the net-sum form." No
  such skill exists — `.psi/skills/` contains only `incidental-complexity-finder`;
  `reduce-incidental-complexity` is a **workflow** (`.psi/workflows/…edn`), which is
  already the primary edit target. The executing agent cannot resolve which artifact
  "the reduce-incidental-complexity skill" denotes (phantom skill vs. the workflow
  already being edited). Actionable: name the actual artifact(s) — drop the phantom
  skill reference, or if a second skill restatement is meant, identify it by path.

## Design review — architectural fit re-pass (ψ)

Re-reviewed current `design.md` for architectural fit only (AGENTS.md principles;
META.md and doc/architecture.md remain runtime/adapter scope, not governing the
workflow-emitter contract). The three prior architectural-fit misfits are all
resolved in the current design:

- Enforcement asymmetry → A2a/A2b now specified as a deterministic numeric `<`
  procedure over committed `before-local.json` + after `bb gordian local --json`;
  dedicated `bb gordian` A2 command documented as out-of-scope follow-up (resolved-
  by-acceptance, recorded in prior pass).
- Tunable margins → θ/ε removed; pure inequalities against `B` fit `λone_way` and
  the objective-gate posture.
- Criterion taxonomy → re-anchored to live A5/A2/A3 labels; no renumbering; contract
  self-consistent.

No new actionable architectural-fit misfit found. Root-cause-at-source framing fits
`λ fix(bug). cause(structural) → redesign`; `B` from committed `before-local.json`
fits single-source-of-truth; out-of-scope command deferral is a documented design
decision (fits `shims_adapters` exception rule). REVIEW_COMPLETE for architectural
fit.

## Design review — ambiguities re-pass (ψ)

Re-reviewed current `design.md` for ambiguity only (statements admitting >1
interpretation by the emitter-edit agent transcribing the A2 procedure). Did not
review plan.md/steps.md. The two prior ambiguity items (non-unique-key join, phantom
skill) remain tracked/open. The revised "How A2 is mechanically checked" /
"Non-unique keys" machinery (`before-max`, per-physical-row checks) introduced two
**new** actionable ambiguities not covered by those items:

- **Step-4 T-membership uses an undefined second before-side quantity.** Step 3
  states `before-max(k)` "is the only before-side quantity A2 uses." Step 4 then
  forms `T` via "the per-key **aggregate** before-burden ≠ **aggregate** after-burden
  for `k`." "Aggregate" is undefined (sum? max? = `before-max`?) and is a *second*
  before-side quantity, directly contradicting step 3. The interpretation matters:
  if `aggregate = sum`, the change-detector reintroduces a per-key **sum** — the very
  sub-additive quantity this redesign exists to eliminate — into the gate's
  row-selection (harmless to the pass/fail inequality, but it re-imports sum
  sensitivity into *which* rows are policed, undercutting the "no sum" framing).
  Actionable: define the change-detection aggregation explicitly and reconcile it with
  the "only before-side quantity is `before-max`" claim (e.g. detect change per
  physical row, or state the aggregation function and exempt it from the no-sum
  rationale).

- **Target exclusion from `T` is unspecified in A2's keyspace.** The body removes
  "the target itself, which A5 already governs" and step 4 says "Remove the target's
  own row(s) from `T`." A5 identifies the target by its **line-bearing** key; A2
  groups **line-insensitively** by `(ns, var, arity)`. The procedure never says how
  the target is identified for this exclusion. When the target's line-insensitive key
  is shared (the design's own 51-row defmethod case), "the target's own row(s)" is
  ambiguous — the target's single physical row, or every row under its
  `(ns, var, arity)` key? Removing the whole key group would exempt a relocation that
  pushes the tangle into a sibling sharing the target's key, opening a hole in the
  very relocation guard A2 is for. Actionable: specify that the target is identified
  by its line-bearing identity and only its physical row is removed from `T` (and
  state the behaviour when the target shares its line-insensitive key with siblings),
  and clarify why "(s)" is plural for a single defunit target.

Both verified against `design.md` lines 85, 138–148 and the live 214 data the design
cites. Not duplicated by the architectural-fit passes or the two prior ambiguity
items.

## Design review follow-up — ambiguities re-pass (ψ, executed)

Resolved both newly-added ambiguity follow-ups from the re-pass in `design.md`
("How A2 is mechanically checked"). The two earlier ambiguity items (non-unique-key
join semantics, phantom skill reference) predate this pass and were left untouched
per scope.

- **Step-4 change-detection aggregation → defined as an order-insensitive multiset
  comparison (no sum), and reconciled with step 3.** Replaced the undefined "per-key
  aggregate before-burden ≠ aggregate after-burden" with: `k ∈` changed iff `k` is new
  *or* the **multiset of `lcc-total` over `k`'s before-rows differs from the multiset
  over its after-rows** — explicitly never a sum. Added the soundness observation that
  `T`-membership is *non-load-bearing*: an untouched row has
  `after(u) = before(u) ≤ before-max(k)`, so it auto-passes A2a/A2b when
  `before-max(k) < B` and is exempt when `before-max(k) ≥ B`; `T` is therefore a
  reporting/efficiency filter. Reconciled with step 3 by scoping its "only before-side
  quantity" claim to the **A2a/A2b pass/fail inequalities** (which consume only
  `before-max`), distinct from the multiset row-selection filter — so the sub-additive
  sum never re-enters even row selection.

- **Target exclusion from `T` → specified line-bearing identity, single physical row,
  shared-key behaviour, singular "row".** Step 4 now removes **only the target's own
  physical row**, identified by its **line-bearing** `(ns, var, arity, line)` key (the
  same identity A5 uses), not its line-insensitive A2 key. Stated that the target is a
  single physical defunit (hence singular "row", never the whole key group), and that
  in the 51-row defmethod shared-key case the siblings stay in `T` and remain policed by
  A2a/A2b — so relocating into a key-sharing sibling still trips the ceiling (or surfaces
  via A3/A5); the group is never blanket-exempted and the relocation guard has no hole.

No blockers. Both follow-ups completed; `design-steps.md` ambiguity items 3–4 ticked.

## Design review — inconsistencies (ψ)

Reviewed `design.md` for internal inconsistency and inconsistency against referenced
artifacts (the live emitter `.psi/workflows/reduce-incidental-complexity.edn` `select-and-create`
step, the cited knowledge page, and the skill files). Did not review plan.md/steps.md.
Confirmed the skill grep is empty (acceptance 3 "absent" holds). Found two **new**
actionable inconsistencies; neither is duplicated by the prior architectural-fit,
taxonomy, or ambiguity passes (those covered the design's *internal* A1→A5 re-anchoring,
θ/ε, join semantics, change-detection aggregation, and target exclusion).

- **Adopted A2 form contradicts the cited knowledge page's proposed corrected A2, and
  Scope item 3's knowledge-update is under-specified.** `design.md` adopts
  `after(n/m) < B` with `B := before(target)` from committed `before-local.json`, and
  explicitly rejects the recomputed residual ("NOT a recomputed `after(target)`"). The
  cited `active` knowledge page (`gordian-net-sum-burden-gate-sub-additivity.md`,
  "The genuine intent, correctly expressed") proposes the *opposite* anchor — "each
  extracted seam is strictly simpler than the **residual target**:
  `∀ s ∈ (after-units \ before-units): after(s) < after(target)`" — and labels target
  reduction **A1**. `design.md` Scope item 3 only directs recording that "the fix has
  landed" (the "Action for future sessions" item 1 / "Status / ratification" un-filed
  notes); it does not direct reconciling the knowledge page's proposed `after(target)`
  formula or its A1 labeling. As written, after the scoped update the knowledge page
  would still document a *different gate* (`after(target)`, A1) than the one that landed
  (`B`, A5). Actionable: extend Scope item 3 to reconcile the knowledge page's proposed
  corrected-A2 formula and its A1 labeling with the adopted `after < B` / A5 form (or
  explicitly state the knowledge page's `after(target)` form is superseded by `B`), so
  the post-update knowledge page is consistent with the landed emitter.

- **"Unit" is defined two incompatible ways across sections.** "Proposed corrected A2"
  states "Units are identified by the line-insensitive key `(ns, var, arity)`" and
  expresses A2a/A2b in per-unit terms (`before(n)`, `before(m)`, `after(m)`). "How A2 is
  mechanically checked" states "A2's atomic unit is the **physical defunit row** … the
  key `k` is used only to *pair* before/after rows … never to merge", replacing the
  before side with the group-max `before-max(k)` and the after side with per-physical-row
  `after(u)`. The two framings contradict (key-as-unit vs physical-row-as-unit);
  consequently A2b's `before(m)`/`after(m)` is undefined for the non-unique-key (51-row
  `execute-effect!` defmethod) case the design itself foregrounds. This is distinct from
  the open join-semantics ambiguity item (which concerns *how* to join), being a
  notational contradiction in the formal A2a/A2b statement itself. Actionable: restate
  A2a/A2b over the mechanical model's terms — physical-row `after(u)` and group
  `before-max(k)` — so both sections use one consistent notion of "unit".

Both verified against `design.md` ("Proposed corrected A2", "How A2 is mechanically
checked", Scope) and the cited knowledge page. Not duplicated by prior passes.
