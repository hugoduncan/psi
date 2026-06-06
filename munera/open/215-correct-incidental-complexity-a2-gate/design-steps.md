# Design review follow-up steps

## Architectural fit

- [x] Resolve A2/A3 enforcement asymmetry: specify how A2a/A2b are mechanically
      computed (concrete command/check over `before-local.json` + after
      `local --json`) so A2 is an enforceable gate like A3, or explicitly justify
      leaving A2 as an agent-evaluated prose criterion.
- [x] Justify (or remove) the tunable margins θ (and optional ε): explain why a
      non-zero buffer is architecturally necessary vs. a pure inequality, given the
      design's own "no undefined buffer" / one-way objective-gate posture.
- [x] Reconcile the criterion taxonomy with the live emitter: the emitted contract
      labels target reduction **A5**, net burden **A2**, gate **A3** (no **A1**, no
      **A4**, blast radius unnumbered). Either bring the renumbering into scope or
      re-anchor the A2-rescoping rationale and acceptance criterion 2 to the actual
      labels so the emitted A1–A5 contract is self-consistent.
