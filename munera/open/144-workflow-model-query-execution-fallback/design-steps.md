# Design follow-up steps

- [x] Clarify whether ranked candidate metadata must be preserved in `resolve-step-session-config` output, or whether execution may instead retain the authored `:model-query` and perform one stable ranking pass later at the execution seam; update `design.md` and `plan.md` to choose one authoritative shape.
- [x] Define the canonical failure envelope for ranked-candidate exhaustion, including where the aggregate candidate-failure details live and how that terminal failure appears in workflow attempt/result bookkeeping; update `design.md` and `plan.md` so acceptance does not leave the exposed error contract implicit.
- [ ] Record the canonical fallback-worthy execution failure predicate in `implementation.md` and identify the runtime seam where it is applied, so `implementation.md` no longer contradicts `design.md`, `plan.md`, and `steps.md` about that decision being made before code changes.
