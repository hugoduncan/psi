2026-05-05
- Task created as the explicit follow-on to `092-delegate-result-surface-convergence-and-rich-example-migration`.
- Rationale: `092` correctly solved the minimum canonical delegated yielded-text seam, but realistic multi-phase orchestration still lacks a canonical structured inter-workflow handoff contract.
- This task exists to solve that broader contract/dataflow problem explicitly rather than letting `gh-bug-triage-modular` remain blocked on hidden assumptions or text parsing.
- Relationship to adjacent tasks:
  - `092` established delegated `:yield :text` as the minimal downstream-consumable delegated result surface
  - `090` remains the eventual compatibility-retirement task; this task is intended to remove another substantive blocker by making realistic multi-phase target-authored delegation viable
  - `077` remains the umbrella migration context for the converged target grammar and canonical IR runtime
