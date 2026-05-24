# Plan

Execute the newly added inconsistency follow-up by narrowing the remaining implementation inventory so the task artifacts match the refined design split.

1. Re-read the preloaded inconsistency-review result and current task artifacts to isolate the exact wording mismatch added in `design-steps.md`.
2. Re-check the concrete prompt-contribution caller split so the artifact update stays grounded in actual surfaces:
   - extension-facing helpers/docs that already stay single-id-only
   - built-in workflow registration that supplies owner provenance internally
   - lower-level dispatch and Pathom mutation seams that still carry `ext-path`
3. Update `steps.md` so the remaining implementation inventory/work targets only lower-level seams, projections, tests/helpers, and other callers that still depend on composite `ext-path + id` identity.
4. Record the follow-up execution in `implementation.md` and mark the `design-steps.md` item done.

Approach notes:
- This pass executes only newly added unchecked `design-steps.md` items.
- Do not execute implementation items from `steps.md`.
- Keep extension-facing helper/doc surfaces explicitly out of the composite-identity implementation inventory.
