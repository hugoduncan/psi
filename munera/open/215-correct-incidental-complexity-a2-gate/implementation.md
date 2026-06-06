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
