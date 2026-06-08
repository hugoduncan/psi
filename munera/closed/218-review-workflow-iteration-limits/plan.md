# Plan

## Approach

Implement the review-loop limit change as a localized workflow-definition and workflow-test update. Preserve the existing runtime meaning of `:max-iterations`: it limits total entries to the target step, including the first normal entry. Do not add runtime shims unless the workflow definitions cannot express the required behavior.

Key decisions:

- Update the shared `review-step` loop from `follow-up` back to `review` to use `:max-iterations 10`, because its current known use is the implementation-review profiles delegated by `review-task-implementation` and the design explicitly allows the shared surface unless implementation discovers broader unintended impact.
- Rework `review-task-design` into repeated full passes over architecture, ambiguity, and inconsistency phases. Any phase with `ACTIONABLE_FEEDBACK` runs its follow-up, then the workflow continues through the remaining phases in the same pass. The final pass-status step decides whether to start another pass.
- Rework `review-task-plan` into repeated full passes over ambiguity and inconsistency phases with the same pass-level decision pattern.
- Encode pass-level feedback memory in deterministic workflow topology/routing, not by re-reading `design-steps.md` or `steps.md` after follow-up execution. The topology should make every phase's actionable/clean outcome explicit to the final pass-status decision.
- Put the guarded loop-back on the final pass-status decision to the first phase: `architecture-review` for design with `:max-iterations 6`, and `ambiguity-review` for plan with `:max-iterations 5`.
- Add workflow-definition checks for authored EDN topology/limits and runtime-oriented workflow execution checks for total-entry counting, full-pass ordering, pass-level feedback memory, and final-allowed-pass iteration-limit failure.
- Update user-facing workflow documentation and `CHANGELOG.md` only where the changed limits/repeated-pass behavior are described or user-visible.

## Risks

- The shared `review-step` workflow may be used outside `review-task-implementation`; if inspection finds another consumer that should not receive the 10-total-review limit, refine the design before implementing an implementation-specific loop.
- Pass-level feedback memory can become hard to understand if implemented with many route-specific status steps. Keep route names explicit and locally comprehensible.
- Existing tests may assert the old `:max-iterations 6` value or old one-follow-up-per-phase topology; update those assertions to the new documented behavior rather than weakening coverage.
- Final-allowed-pass failure tests can become brittle if they assert incidental error text. Assert the workflow status and structured iteration-limit reason/path where available.

## Slice order

1. **Inspect current workflow consumers and test seams** — confirm `review-step` consumers and identify the narrow existing tests for workflow definitions, review-step routing, and iteration-limit runtime behavior.
2. **Implementation-review limit** — update `review-step.edn` to allow 10 total `review` entries and update/add tests documenting total-entry semantics for implementation review.
3. **Design-review full-pass loop** — rework `review-task-design.edn` topology to preserve pass-level actionable feedback, finish every phase in each pass, and guard restart to `architecture-review` at 6 total entries.
4. **Plan-review full-pass loop** — rework `review-task-plan.edn` topology with pass-level actionable feedback, full phase completion, and guarded restart to `ambiguity-review` at 5 total entries.
5. **Runtime and definition coverage** — add focused tests for authored limits/topology, clean completion, actionable full-pass restart, pass-level feedback memory after follow-up, and final-allowed-pass iteration-limit failure.
6. **Docs and changelog** — update workflow documentation and `CHANGELOG.md` if they describe review-loop repetition or limits.
7. **Verification and coherence** — run focused workflow-loader/runtime tests, lint touched Clojure, repair/format if needed, and confirm task artifacts match the final implementation.
