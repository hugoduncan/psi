# Steps

- [ ] Inventory the current workflow `:model-query` resolution/execution path and record the chosen fallback seam plus fallback-worthy error predicate in `implementation.md` before code changes.
- [ ] Extend workflow model-query handling so workflow execution has access to the ordered ranked candidates from `psi.ai.model-selection/resolve-selection`.
- [ ] Preserve current single-model behaviour for explicitly authored concrete workflow models.
- [ ] Add workflow-local ranked-candidate iteration for fallback-worthy execution/setup failures.
- [ ] Stop fallback on first successful candidate and preserve existing canonical workflow step result semantics.
- [ ] Surface coherent terminal failure when all ranked candidates are exhausted.
- [ ] Add focused tests for ranked fallback success, concrete-model no-fallback behaviour, terminal non-fallback failure, and empty/no-winner candidate handling.
- [ ] Verify the motivating case: first-ranked local candidate fails with connection refused, second-ranked candidate succeeds, and the workflow step completes successfully.
