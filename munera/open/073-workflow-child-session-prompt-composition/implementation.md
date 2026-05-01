Implemented in commit `d35e13c7` (`⊨ workflow: compose child session prompts structurally`).

Summary:
- workflow child sessions now compose workflow-authored prompt text as an instruction/developer layer instead of using it as implicit full prompt replacement
- workflow step config now carries parent prompt mode into child session creation
- child-session prompt derivation now rebuilds from canonical prompt build opts when available, including prompt-mode-aware rendering and capability narrowing
- focused tests cover prompt-mode inheritance, extension contribution inheritance, composed workflow prompt text, and prepared-request/provider-visible prompt coherence
- Allium spec updated in connected files:
  - `spec/session-management.allium`
  - `spec/lambda-mode.allium`

Status:
- implementation improves convergence substantially
- follow-on work remains; see `review.md`
