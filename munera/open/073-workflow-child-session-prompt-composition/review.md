Review: partial pass.

What landed well:
- workflow-authored prompt text now composes as a layer instead of implicitly replacing the full prompt
- parent prompt mode is propagated into workflow child sessions
- provider-visible prepared request remains the proof surface

Blocking gaps before close:
- representative workflow lifecycle proof regressed: `workflow_lifecycle_test` now fails on the canonical execution path
- workflow execution now depends on `prompt-execution-result-in!`, but broader lifecycle proof still targets the older prompt-control seam
- workflow step config still names workflow-authored layer text as `:system-prompt` even though runtime now applies it as `:developer-prompt`, which leaves prompt semantics harder to read

Recommendation:
- do not close task 073 yet
- restore or converge the workflow lifecycle proof on the canonical execution seam
- tighten naming/docs around workflow-authored prompt text as a composed instruction/developer layer
