# Design review follow-up

Architectural-fit follow-up items. Address in design.md before plan stage.

- [ ] A1 — Reconcile drift-prevention with the project's structural-invariant
      ethos (`λ shape. unreachable > forbidden`, `impossible_invalid_states`,
      `enforceable(invariants)`). The design's preferred option (open question
      #1) keeps routing + description as two parallel maps and enforces
      `set(spec-names) == set(routed-names)` only *by test* (drift forbidden,
      not unreachable). Update design.md to evaluate the single-keyset option
      (one table whose entries carry both routing/handler and description, so
      name divergence is structurally impossible) as the architecturally
      preferred alternative, and state which is chosen and why on
      `unreachable > forbidden` grounds rather than blast-radius alone.
