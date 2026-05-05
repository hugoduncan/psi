2026-05-05
- Task created as the explicit follow-on to `092-delegate-result-surface-convergence-and-rich-example-migration`.
- Rationale: `092` correctly solved the minimum canonical delegated yielded-text seam, but realistic multi-phase orchestration still lacks a canonical structured inter-workflow handoff contract.
- This task exists to solve that broader contract/dataflow problem explicitly rather than letting `gh-bug-triage-modular` remain blocked on hidden assumptions or text parsing.
- Relationship to adjacent tasks:
  - `092` established delegated `:yield :text` as the minimal downstream-consumable delegated result surface
  - `090` remains the eventual compatibility-retirement task; this task is intended to remove another substantive blocker by making realistic multi-phase target-authored delegation viable
  - `077` remains the umbrella migration context for the converged target grammar and canonical IR runtime

2026-05-05 ambiguity review
- Found one actionable ambiguity: the design strongly preferred a dual-plane model but did not require explicit first-cut decisions for declaration shape, caller-side ref shape, standard contract keys, or fallback behavior for undeclared structured exports.
- Resolved by tightening the design, plan, steps, and acceptance so the task must make one explicit decision for each of those items rather than leaving multiple plausible implementation paths.
- Found a second actionable ambiguity: the anchor proof requirement did not state the minimum proof strength for `gh-bug-triage-modular` specifically, so a migration could overclaim without proving both structured-handoff consumption and yielded-text chaining.
- Resolved by tightening the anchor requirement to require both surfaces distinctly when `gh-bug-triage-modular` is the proof target.
