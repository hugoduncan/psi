Review: partial pass.

What landed well:
- workflow-authored prompt text now composes as a layer instead of implicitly replacing the full prompt
- parent prompt mode is propagated into workflow child sessions
- provider-visible prepared request remains the proof surface

Blocking gaps before close:
- default nil-selection rebuild is still conditional on parent `:system-prompt-build-opts`, so workflow child prompt rebuilding is not yet unconditional from structured state
- workflow-path proof for explicit `:prompt-component-selection` filtering is still missing
- workflow-path proof for rendered tool/skill narrowing is still incomplete
- Allium now states stronger recomposition semantics than the runtime fully guarantees

Recommendation:
- do not close task 073 yet
- add a follow-on slice to make default workflow child prompt rebuilding unconditional and extend workflow-path tests/spec alignment accordingly
