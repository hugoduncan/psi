# Plan

Align the execution artifacts with the current task design so future design review and implementation can operate from an accurate task surface.

1. Rewrite this plan to match the actual design scope rather than the earlier scaffolding-only follow-up.
2. Rewrite `steps.md` so it tracks the remaining design/implementation work implied by `design.md`.
3. Make the design unambiguous about:
   - canonical prompt contribution `id` normalization
   - nil/blank `id` handling
   - same-owner duplicate registration behavior
   - cross-owner duplicate registration behavior
   - lookup/update/unregister targeting after removing owner-qualified identity
   - ownership/provenance retention
   - deterministic ordering after the identity change
   - any narrow compatibility handling for callers that currently pass `ext-path + id`
4. Keep the resulting artifacts aligned with the stated acceptance criteria and with eventual root-registry-style adoption.

Approach notes:
- This pass is design-artifact follow-up only; do not execute implementation items from `steps.md`.
- Prefer one obvious contract over adapter-hidden compatibility.
- Record any unresolved blocker tersely in `implementation.md`; otherwise complete the design-step items and mark them done.
