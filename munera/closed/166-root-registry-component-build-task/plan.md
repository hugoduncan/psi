# Plan

1. Re-read task `165-root-registry-component-target-architecture` and extract the exact shared registry state model, operation semantics, and result-contract requirements.
2. Build the new standalone component in `components/root-registry/` with the primary namespace `psi.root-registry.registry`, including an explicit registry-declaration API and the shared root-state storage shape for declared registries.
3. Implement register/lookup/list/unregister/clear operations with strict shared invariant enforcement and explicit lower-layer result maps, while leaving current adopter-facing registry thrown-error/public return contracts unchanged in this task.
4. Add focused lower-component tests proving declaration idempotence, non-implicit declaration, identity, ownership, replacement, unknown-registry handling, list semantics, removal failure info, clear semantics, and unordered storage assumptions.
5. Refine naming, result shapes, and docs so the component is ready for follow-on migration/adoption tasks.
