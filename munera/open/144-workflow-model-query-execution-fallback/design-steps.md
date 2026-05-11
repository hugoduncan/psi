# Design follow-up steps

- [ ] Clarify whether ranked candidate metadata must be preserved in `resolve-step-session-config` output, or whether execution may instead retain the authored `:model-query` and perform one stable ranking pass later at the execution seam; update `design.md` and `plan.md` to choose one authoritative shape.
- [ ] Define the canonical failure envelope for ranked-candidate exhaustion, including where the aggregate candidate-failure details live and how that terminal failure appears in workflow attempt/result bookkeeping; update `design.md` and `plan.md` so acceptance does not leave the exposed error contract implicit.
