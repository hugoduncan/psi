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

## Design review follow-up — inconsistencies (ψ, executed)

Resolved both newly-added inconsistency follow-ups in `design.md`. The two earlier
ambiguity items (non-unique-key join semantics, phantom skill reference) predate this
pass and were left untouched per scope.

- **Unit definition conflict → restated A2a/A2b over the mechanical model's terms.**
  Rewrote "Proposed corrected A2" so the **physical defunit row** `u` is A2's atomic unit
  throughout; the line-insensitive key `k = (ns, var, arity)` is used only to classify a
  row's before-side status via group `before-max(k)` — never as the unit. A2a now reads
  "every physical after-row `u` whose key is new (`before-max(k)=0`) satisfies
  `after(u) < B`"; A2b "every physical after-row `u` with `0 < before-max(k) < B` satisfies
  `after(u) < B`" (keys `>= B` exempt). Removed the key-as-unit `before(m)`/`after(m)`
  pairing notation; added a paragraph noting both sections now share one notion of "unit"
  (physical row) and why this makes A2a/A2b well-defined for the 51-row defmethod case.
  Aligned the residual prose `after(m) < B` uses (Pure-inequalities ×2, Resolved-parameters
  ×1) to `after(u) < B`. The deliberate contrast mention at "rather than a key-as-unit
  `before(m)`/`after(m)` pairing" is intentional and retained.

- **A2 form vs knowledge page → extended Scope item 3 + acceptance 4 to direct
  reconciliation.** Scope item 3 now directs the knowledge-page update to (a) mark the
  page's proposed residual anchor `after(s) < after(target)` **superseded by** the
  committed-baseline ceiling `after(u) < B` (one-line rationale: residual is a contestable
  recompute, `B` is an immutable published anchor), and (b) correct the page's **A1**
  target-reduction label to the live **A5**. Acceptance criterion 4 updated to require the
  page be reconciled with the landed form, not merely note the fix landed.

No blockers. Both follow-ups completed; `design-steps.md` inconsistency items ticked. The
two open ambiguity items remain (non-unique-key join semantics, phantom skill) — predate
this pass, out of scope.

## Plan/steps review — ambiguities (ψ)

Reviewed `plan.md` and `steps.md` for ambiguity only (statements admitting >1
interpretation by the executing emitter/test-edit agent). Did not re-review
`design.md`. Found one new actionable ambiguity, verified against the live tree.

- **Content-lock assertion enumeration undercounts — third A2-bound assertion
  unclassified (PA1).** `plan.md` (slice-1 target 2) asserts "**Two** assertions
  currently lock the net-sum A2 wording and **will break**" — naming only
  `"after total is strictly less than the before total"` (test line 299) and
  `"the set is computed from the metric, not from the diff/touched files"` (line
  301) — and directs "Leave the A5/A3/blast-radius/baseline assertions intact";
  `steps.md` slice-1 likewise only removes "the two net-sum content-lock
  assertions". But a **third** assertion in the same `reduce-incidental-complexity-test`
  "select-and-create prompt preserves … contracts" block,
  `(is (.contains select-text "identified by `(ns, var, arity, line)`"))` (line
  295), locks text emitted **only** by the net-sum A2 bullet ("…the after total is
  strictly less than the before total, with each unit `u` **identified by
  `(ns, var, arity, line)`** … then `sum after < sum before`"). Replacing that
  bullet with the line-insensitive `(ns, var, arity)` A2a/A2b removes the substring,
  so line 295 also breaks. It is neither one of the named "two" nor an
  A5/A3/blast-radius/baseline assertion, so its disposition is undefined: the agent
  cannot tell whether to remove it, re-point it at the new line-insensitive key, or
  (wrongly) preserve the line-bearing phrase against the new A2. It must be
  distinguished from the adjacent **A5** assertion at line 294,
  `"keyed by `(ns, var, arity, line)`"`, which is genuinely "leave intact" (A5 keeps
  its line-bearing key). Actionable: name the third (line-295) assertion in plan/steps,
  correct the "two assertions" count to three, and direct its disposition (remove or
  re-point to the line-insensitive `(ns, var, arity)` A2 key), explicitly contrasting
  it with the leave-intact A5 line-294 `"keyed by …"` lock.

PASS_STATUS: ACTIONABLE_FEEDBACK

## Plan/steps review follow-up — PA1 (ψ, executed)

Executed the newly-added PA1 follow-up's plan/steps-documentation deliverable. PA1 was
raised by a plan/steps **ambiguity** review; its resolvable scope is making `plan.md`
and `steps.md` unambiguous and consistent about the third net-sum-bound content-lock
assertion. The actual test-file edit it directs is slice-1 **implementation**, coupled to
the not-yet-executed emitter A2 edit (which predates this review pass) — not executed here.

- **plan.md corrected (done).** Slice-1 target 2 "**Two** assertions … will break" →
  **three**: added the line-295 lock `"identified by `(ns, var, arity, line)`"` (emitted
  only by the net-sum A2 bullet; broken by the line-insensitive A2a/A2b replacement),
  directed its disposition (remove or re-point to the line-insensitive `(ns, var, arity)`
  key; do not preserve the line-bearing phrase), and explicitly contrasted it with the
  **leave-intact A5** line-294 lock `"keyed by `(ns, var, arity, line)`"`. plan.md and
  steps.md now agree on the count, the named assertions, and the disposition — ambiguity
  resolved.
- **Verified line references** against the live test file
  (`task_209_workflow_definitions_test.clj`, `reduce-incidental-complexity-test`):
  line 294 A5 `"keyed by …"` (intact), line 295 A2 `"identified by …"` (PA1 target),
  line 299 / line 301 the two net-sum locks. Confirmed accurate.

- **Test-edit portion deferred (PA1 step left unchecked).** Removing/re-pointing the
  line-295 assertion belongs to the slice-1 emitter+content-lock implementation edit,
  which predates this review pass and is not yet executed; doing it in isolation now would
  either drop coverage prematurely (emitter still emits the net-sum text) or re-point at a
  not-yet-emitted string. The disposition is now unambiguously recorded in plan.md and
  steps.md for that slice-1 execution. PA1 step therefore left unchecked.

## Plan/steps review — inconsistencies (ψ)

Reviewed `plan.md` and `steps.md` for internal inconsistency and inconsistency
against `design.md` and the referenced artifacts (the live emitter, the test file
`task_209_workflow_definitions_test.clj`, the cited knowledge page). Did not re-review
`design.md` internals. Verified test line refs (294 A5 `keyed by`, 295 A2 `identified
by`, 299/301 net-sum), confirmed `identified by` is emitted once (A2 only) and `keyed
by` twice (A5 bullet + step-5 note), and confirmed the knowledge page's A1/`after(target)`
state. Found three **new** actionable inconsistencies, all the residue of the prior PA1
ambiguity fix correcting the count only in plan.md's "Concrete edit targets" section.
Not duplicated by the PA1 ambiguity pass (which raised the *count* and added a separate
PA1 step; it did not reconcile the surviving "two" counts and in fact asserted plan/steps
"now agree on the count", which these items disprove).

- **PI1 — plan.md contradicts itself on the broken-assertion count.** "Concrete edit
  targets" #2 (line 31) states "**Three** assertions currently lock net-sum A2 wording and
  **will break**" (299/301/295), but the Risks section "Content-lock test coupling" (lines
  61–62) still says "Editing the emitter `:text` necessarily breaks **the two** net-sum
  assertions." Same artifact, contradictory counts. Actionable: update the Risks bullet
  from "the two" to three (or "all three").

- **PI2 — steps.md primary content-lock step disagrees with plan.md's three.** steps.md
  slice-1 (line 25) says "Update **the two** net-sum content-lock assertions" and removes
  only 299/301, while plan.md (line 31) now enumerates **three** breaking assertions; the
  third (line-295 `identified by …`) is split into a separate PA1 step. The main step's
  "two" count is inconsistent with the plan's corrected three. Actionable: align the
  slice-1 primary step to the three-assertion framing — either fold the line-295 key
  assertion into it, or explicitly scope the step to "the two literal net-sum strings
  (299/301)" and cross-reference the PA1 step for the line-295 key assertion, matching how
  plan.md groups them.

- **PI3 — steps.md PA1 step carries a stale plan cross-reference.** The PA1 step (lines
  37, 44) says it handles "the *third* … assertion the plan's '**two** assertions' count
  omits" and ends "**Correct the plan's 'Two assertions … will break' to three.**" But
  plan.md's concrete-targets section already reads "Three assertions"; the phrase "Two
  assertions … will break" no longer exists there, and the only surviving "two" is the
  Risks bullet (PI1) — which this instruction does not target. The instruction references
  a plan state that no longer exists. Actionable: re-point the PA1 step's count-correction
  at plan.md's Risks bullet (the surviving "two"), or drop the now-completed
  correct-the-plan sub-instruction since the concrete-targets count is already three.

PASS_STATUS: ACTIONABLE_FEEDBACK

---

## Plan/steps inconsistency follow-ups PI1–PI3 executed (2026-06-06)

Executed the three newly-added inconsistency follow-ups (added by inconsistency review
`274211c8b`). Artifact-only — no code/tests/docs touched (none referenced; all three target
the residual two-vs-three net-sum content-lock count drift across plan.md/steps.md).

- **PI1 (done):** plan.md Risks "Content-lock test coupling" bullet now reads "breaks **all
  three** net-sum A2 content-lock assertions (lines 299/301 literal net-sum strings + the
  line-295 `(ns, var, arity, line)` key assertion)", matching "Concrete edit targets" #2's
  three-assertion enumeration. The plan is now self-consistent (three everywhere).
- **PI2 (done):** scoped the slice-1 primary content-lock step to "the **two literal
  net-sum string** content-lock assertions (lines 299/301)" and appended a cross-reference
  that the **third** (line-295 key) assertion is handled by the PA1 step + plan.md targets
  #2 (chose the explicit-scope option over folding line-295 in, keeping the PA1 split the
  plan already established).
- **PI3 (done):** replaced the PA1 step's stale "Correct the plan's 'Two assertions … will
  break' to three" instruction with a count-reconciliation note: targets #2 already reads
  "Three"; the only residual "two" is the plan Risks bullet, corrected by PI1 — no further
  count edit required in the PA1 step. The instruction no longer references a plan state
  that no longer exists.

Net effect: "three" is now the single consistent count across plan.md (targets #2 + Risks)
and steps.md (slice-1 primary step + PA1 + PI section); the PA1 step no longer carries a
dangling reference to a removed "two assertions" plan phrase.

PASS_STATUS: FOLLOW_UPS_COMPLETE

---

## Implementation executed — slices 1–4 (2026-06-06)

All four slices landed. Two commits (emitter+tests; knowledge page) plus this
artifact sync.

### Slice 1 — emitter A2 correction + content-lock tests (acceptance 1, 2)
- Replaced **only** the step-6 "Net burden (A2 — \"touched units\" defined)" bullet in
  `.psi/workflows/reduce-incidental-complexity.edn` (`select-and-create` step) with the
  per-unit **A2a/A2b** relocation-guard gate: pure inequalities `after(u) < B`,
  `B := before(target)` from the committed `before-local.json` (explicitly NOT a recomputed
  `after(target)`), no `θ`/`ε`. Inlined the full deterministic mechanical-check procedure
  (line-insensitive `(ns, var, arity)` grouping, `before-max(k)`, physical-row `after(u)`,
  order-insensitive multiset change filter for `T`, line-bearing single-row target
  exclusion, per-row A2a/A2b assertions). A5/A3/Phase-0/blast-radius/minimality criteria and
  the non-sequential numbering (A5, A2, A3) left unchanged.
- **No `clj-paren-repair` needed** — the edit replaced a contiguous span inside the EDN
  `:text` string; `bb edn/read-string` round-trips (31765-char value), so delimiters are
  intact. (Deviation from steps' literal "run clj-paren-repair": substituted an
  `edn/read-string` load check, which is the actual EDN-well-formedness gate the plan's risk
  bullet calls for.)
- Content-lock tests (`task_209_workflow_definitions_test.clj`,
  `reduce-incidental-complexity-test`): removed all **three** net-sum-coupled assertions
  (lines 299/301 literal net-sum strings + line-295 `identified by `(ns, var, arity, line)``)
  and added six new locks for the A2a/A2b wording. Verified each new lock substring is
  present and each removed substring is absent in the loaded `select-text`. A5 line-294
  `keyed by `(ns, var, arity, line)`` left intact.
- Verification: workflow-loader suite run from repo root via an ad-hoc absolute classpath
  (`-Spath` from the component, then `-Scp` from root so the test's cwd-relative
  `.psi/workflows/` reads resolve) — **3 tests, 196 assertions, 0 fail, 0 error**.
  clj-kondo clean.
  - Note: the full kaocha `--focus` run fails to LOAD unrelated namespaces
    (`psi.agent_session.tool_execution_test` → missing `psi/metrics/extension` on
    classpath) — a pre-existing environmental classpath issue, not caused by this change;
    isolated the affected suite instead.

### Slice 2 — skill alignment (acceptance 3)
- `grep -rn` over `.psi/skills/` for net-sum / A2 / touched-units restatement: **empty**.
  Confirmed absent; no edit. (Matches the design's "phantom skill" resolution — only the
  workflow emits the criterion, and it is the primary edit target.)

### Slice 3 — knowledge-page reconciliation (acceptance 4)
- `mementum/knowledge/gordian-net-sum-burden-gate-sub-additivity.md`: recorded the fix
  **LANDED (task 215)** in "Action for future sessions" item 1 + "Status / ratification";
  struck the proposed residual `after(s) < after(target)` anchor as **SUPERSEDED** by the
  committed-baseline ceiling `after(u) < B` (one-line rationale: residual is a contestable
  recompute, `B` is an immutable published anchor); corrected the **A1 → A5** target-reduction
  label in "The genuine intent" (twice) and the empirical-confirmation line. Empirical PASS
  figure re-anchored `0.8220 < B ≈ 6.0154` (was `< target after 5.5499`).

### Slice 4 — dry read-through (acceptance 5)
- **Satisfiable by genuine extraction:** task-214 Pareto seam `start-server-quietly` has
  lcc `0.8220 < B ≈ 6.0154` → A2a PASS. A genuine decomplecting split of a burden-`B`
  tangle into pieces each `< B` always satisfies A2a (the sub-additivity defect is gone:
  no sum).
- **Rejects relocated/inverted extraction:** a new seam whose `after(u) >= B` fails A2a;
  pushing the tangle into an existing below-ceiling sibling so its `after(u) >= B` fails
  A2b; a sibling already `>= B` is exempt but a genuine architectural worsening there is
  caught by A3, and the target's required improvement is A5's. The guard has no hole for
  the shared-key (51-row defmethod) case: siblings stay in `T` and are policed per-row.

### Acceptance status
1. ✅ Emitter no longer emits net-sum A2; emits per-unit A2a/A2b; EDN loads.
2. ✅ A5/A3/Phase-0/blast-radius/minimality unchanged; numbering preserved; A2 no longer
   governs the target unit.
3. ✅ Skill restatement confirmed absent.
4. ✅ Knowledge page reconciled (fix-landed + `after(target)` superseded + A1→A5).
5. ✅ Dry read-through shows satisfiable-by-genuine / rejects-relocation.

Residual out-of-scope follow-up: a dedicated `bb gordian` A2 join-and-compare subcommand
(today the emitter spells out an agent-run deterministic procedure).

## Implementation review (ψ)

Verified: EDN round-trips (`edn/read-string`); workflow-loader suite GREEN (3 tests / 196
assertions / 0 fail / 0 error); acceptance 3 skill grep empty; knowledge page reconciled
(fix-landed + `after(target)` superseded + A1→A5). Emitter diff confined to the single A2
bullet; A5/A3/blast-radius/numbering unchanged. Implementation matches design intent for
acceptance 1–5.

One actionable coherence violation (design ↔ landed artifact disagree):

- **RI1 — `B` identification key mismatch (design step 1 ≠ emitter).** `design.md` "How A2
  is mechanically checked" **step 1** (design.md:145–147) says `B := before(target)` is
  "keyed by the **line-insensitive** `(ns, var, arity)`". The landed emitter
  (`.psi/workflows/reduce-incidental-complexity.edn`, A2 step 1) instead locates `B` "by its
  **line-bearing** `(ns, var, arity, line)` identity (the same row A5 governs)". The
  emitter's choice is the *more* well-defined one — line-insensitive `B` is **undefined**
  for the 51-row `execute-effect!` defmethod case the design itself foregrounds (which of 51
  rows is "the target"?), and it agrees with the design's own **target-exclusion** rule
  (which removes the target by line-bearing identity) and with A5. But design.md step 1 was
  not reconciled to the form that landed, so the design now documents a different `B`-lookup
  than the emitter emits (a coherence violation per change_chain). Actionable: update
  design.md step 1 (and the parenthetical "A2's chosen identity") to the line-bearing
  `(ns, var, arity, line)` lookup for `B` that landed, noting why (line-insensitive `B` is
  ambiguous for shared-key defmethods; line-bearing matches target-exclusion + A5). The
  line-insensitive `(ns, var, arity)` *grouping* for `before-max(k)`/`T` (step 3) is correct
  and stays — only `B`'s own lookup key is at issue.

PASS_STATUS: ACTIONABLE_FEEDBACK

## RI1 resolved (review follow-up)

Reconciled design.md step 1 to the landed emitter. `B := before(target)` is now located by
its **line-bearing** `(ns, var, arity, line)` identity (the row A5 governs), with a one-line
rationale: `B` must name exactly one physical row, and the line-insensitive
`(ns, var, arity)` is ambiguous for the 51-row `execute-effect!` defmethod case. Removed the
misleading "A2's chosen identity — see note below" parenthetical, which conflated `B`'s
lookup with the line-insensitive *join/grouping* key. The line-insensitive grouping for
`before-max(k)`/`T` (step 3) and its dedicated note (design.md ~line 194) are unchanged and
now explicitly distinguished from `B`'s lookup. design.md ↔ emitter coherence restored.
Design-only change; no code/test/emitter edit (emitter was already correct — it was the
authoritative form RI1 reconciled toward).

## Implementation review re-pass (ψ)

Re-verified the landed change: emitter EDN round-trips (`edn/read-string`, 13 steps);
workflow-loader suite GREEN (3 tests / 196 assertions / 0 fail / 0 error); old net-sum
content-lock strings absent from the emitter (word-diff confirms the change is confined to
the single A2 bullet — A5/A3/blast-radius/minimality byte-unchanged); knowledge page
reconciled (LANDED + `after(target)` superseded + A1→A5); design.md step 1 reconciled to
the line-bearing `B` lookup (RI1 closed). Implementation matches design intent for
acceptance 1–5.

One **new** actionable coherence violation (user-facing doc ↔ landed emitter disagree),
not covered by RI1 or any prior pass:

- **RI2 — `doc/workflows.md` still documents the superseded net-sum A2.**
  `doc/workflows.md` (the `reduce-incidental-complexity` section, Phase-1 acceptance
  paragraph, ~line 709) describes the gate as "**net burden across the metric-derived
  touched set strictly decreases**" — verbatim the net-sum `sum after < sum before` A2
  that task 215 removed from the emitter. The landed A2 is the per-unit relocation guard
  (A2a/A2b ceiling `after(u) < B`, no sum, target excluded — A5 governs target reduction).
  This is a `change_chain` doc-sync violation (`update(doc,reflect(meta spec code))`) and
  breaks the project's canonical "keep user docs (`README` + `doc/`) synchronized on every
  change" rule. The task's own Scope/blast-radius listed only the emitter, the
  `incidental-complexity-finder` skill, and the knowledge page — it **omitted**
  `doc/workflows.md` (an omission, not a deliberate exclusion; out-of-scope lists only 214
  re-run / Gordian transform / 214 ratification). So a user reading the workflow docs is
  told the gate is a net-sum decrease that the workflow no longer emits. Actionable: update
  the `doc/workflows.md` Phase-1 acceptance sentence to describe the per-unit A2a/A2b
  relocation-guard ceiling (`after(u) < B`, target governed by A5), matching the landed
  emitter; verify no other `doc/` / `README.md` sentence restates the net-sum form (the
  two CHANGELOG entries describe the workflow generally and do not name the net-sum gate, so
  they are unaffected).

PASS_STATUS: ACTIONABLE_FEEDBACK
