# Plan

Implement the next concrete task-176 slice: move prompt-registry and its lower-level seams from composite `ext-path + id` identity to canonical single-id identity, then prove the new contract with focused tests.

1. Change `components/prompt-registry` pure contribution ownership so canonical identity is string-coerced `id` alone, cross-owner duplicate registration becomes the required thrown ownership-conflict contract at registry/dispatch/mutation seams, same-owner duplicate registration replaces, and canonical ordering becomes `[priority id]`.
2. Update affected lower-level session seams to treat `ext-path` only as owner/provenance metadata and ownership assertion while preserving that thrown ownership-conflict behavior rather than normalizing it into a structured non-throwing result:
   - dispatch handlers in `dispatch_handlers/prompt_handlers.clj`
   - Pathom prompt mutations in `mutations/prompts.clj`
   - prompt contribution projection ordering in resolvers/session surfaces that still sort locally
3. Update nullable/test-helper infrastructure and focused lower-level tests so they model the new single-id contract rather than composite coexistence.
4. Run focused verification for the affected prompt-registry and agent-session areas; broaden verification if focused results expose wider coupling.
5. Synchronize task artifacts (`steps.md`, `implementation.md`) with the implementation outcomes and commit this pass.

Approach notes:
- Preserve nil/blank `id` acceptance via existing string coercion in this pass.
- Keep extension-facing helper APIs single-id-only; do not expand them to owner-qualified targeting.
- Allow lower-level seams to continue accepting `ext-path` only as ownership metadata/check input, not as a second identity coordinate.
- Prefer narrow, state-based tests without mocks; exercise real pure helpers and real dispatch/mutation seams.