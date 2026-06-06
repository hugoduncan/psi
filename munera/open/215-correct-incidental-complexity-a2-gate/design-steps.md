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

## Ambiguities

- [ ] Define the `(ns, var, arity)` join semantics for the non-unique-key case in
      "How A2 is mechanically checked" step 3. The line-insensitive key is non-unique
      in the live data (51 `dispatch-effects/execute-effect!` defmethods share it), so
      the many-to-many join is undefined when such a unit is touched (which
      `before`/`after` value; aggregate vs. per-line vs. exclude). Either specify the
      aggregation/fallback rule or qualify the "A2's units are distinct vars/arities"
      assertion to the units A2 can actually encounter.
- [ ] Fix the phantom "`reduce-incidental-complexity` skill" reference in
      Scope / blast radius. No such skill exists (only the
      `incidental-complexity-finder` skill + the workflow EDN already being edited).
      Drop the phantom skill reference or, if a distinct skill restatement is intended,
      identify it by concrete path.
