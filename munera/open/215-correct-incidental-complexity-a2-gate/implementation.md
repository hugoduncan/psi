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
