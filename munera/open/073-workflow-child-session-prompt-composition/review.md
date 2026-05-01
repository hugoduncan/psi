Review: pass with follow-up note.

What landed well:
- workflow-authored prompt text composes as a developer/instruction layer instead of implicitly replacing the full prompt
- parent prompt mode is propagated into workflow child sessions
- representative workflow lifecycle proof is now converged onto the canonical `prompt-execution-result-in!` seam
- workflow step config naming/docs now reflect the composed developer-layer semantics more clearly
- provider-visible prepared request remains the proof surface

Remaining note:
- focused workflow lifecycle + execution proof is green for this slice
- broader unit suite still reports unrelated failures outside task 073 scope

Recommendation:
- task 073 implementation slice is review-pass
- do not pull unrelated broad-suite failures into this task unless they are shown to share the same causal seam
