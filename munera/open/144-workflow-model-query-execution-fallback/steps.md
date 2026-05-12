# Steps

- [x] Inventory the current workflow `:model-query` resolution/execution path and record the chosen fallback seam plus fallback-worthy error predicate in `implementation.md` before code changes.
- [x] Extend workflow model-query handling so workflow execution has access to the ordered ranked candidates from `psi.ai.model-selection/resolve-selection`.
- [x] Preserve current single-model behaviour for explicitly authored concrete workflow models.
- [x] Add workflow-local ranked-candidate iteration for fallback-worthy execution/setup failures.
- [x] Stop fallback on first successful candidate and preserve existing canonical workflow step result semantics.
- [x] Surface coherent terminal failure when all ranked candidates are exhausted.
- [x] Add focused tests for ranked fallback success, concrete-model no-fallback behaviour, terminal non-fallback failure, and empty/no-winner candidate handling.
- [x] Verify the motivating case: first-ranked local candidate fails with connection refused, second-ranked candidate succeeds, and the workflow step completes successfully.
- [ ] Shape ranked fallback model mutation so the first ranked candidate reuses the child session's initial concrete model and only later fallback candidates trigger `set-execution-session-model!`.
