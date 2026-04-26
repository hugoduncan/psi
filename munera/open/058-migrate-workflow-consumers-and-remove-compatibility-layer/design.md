# 058 — Migrate workflow consumers and remove compatibility layer

## Intent

Retire the remaining workflow compatibility surfaces introduced during the Phase A migration once all consumers can use the canonical statechart-driven workflow model directly.

## Problem statement

Task 057 established the canonical workflow execution architecture:
- hierarchical chart compilation
- statechart-driven runtime execution
- shared step preparation
- record-only Phase A progression substrate

To preserve stability during migration, 057 left an explicit compatibility layer in place:
- `workflow_statechart_compat.clj`
- compatibility/legacy control helpers in `workflow_progression.clj`
- small sequential compatibility helpers still referenced by some callers/tests

That compatibility layer is useful only while consumers still depend on Phase B sequential/status-tracker semantics. Keeping it indefinitely has costs:
- two workflow mental models remain live
- compatibility and canonical APIs can drift
- future workflow changes must preserve legacy surfaces unnecessarily
- code locality and architectural clarity are reduced

This task exists to migrate the remaining consumers, prove the canonical surfaces are sufficient, and remove the compatibility layer safely.

## Scope

In scope:
- identify all remaining consumers of workflow compatibility surfaces
- migrate them to canonical Phase A workflow surfaces where appropriate
- remove `workflow_statechart_compat.clj` if no longer needed
- remove legacy compatibility helpers from `workflow_progression.clj` if no longer needed
- update tests to validate canonical behavior directly
- document the final authoritative workflow surfaces after compatibility removal

Out of scope:
- changing workflow behavior semantics
- redesigning workflow authoring format
- changing `psi-tool` workflow user-facing behavior unless required by canonicalization
- broader workflow feature work beyond compatibility removal
- extension workflow runtime redesign

## Acceptance criteria

- [ ] All remaining consumers of workflow compatibility surfaces are identified
- [ ] Each remaining consumer is either migrated or explicitly justified as a retained compatibility boundary
- [ ] `workflow_statechart_compat.clj` is removed, or its retained necessity is made explicit in task output
- [ ] Legacy compatibility control helpers in `workflow_progression.clj` are removed, or their retained necessity is made explicit in task output
- [ ] Canonical workflow surfaces are the only surfaces used by active runtime execution paths
- [ ] Tests no longer depend on compatibility compiler/status-tracker surfaces unless those surfaces are intentionally retained
- [ ] Full unit suite is green after compatibility migration/removal
- [ ] Full `bb test` suite is green after compatibility migration/removal
- [ ] Final authoritative workflow surface documentation is updated

## Minimum concepts

- **Canonical execution surface**: the hierarchical compiler + statechart runtime + record-only progression substrate
- **Compatibility surface**: any API that exists only to preserve Phase B sequential/status-tracker semantics
- **Consumer migration**: moving callers/tests from compatibility APIs to canonical APIs without changing behavior
- **Removal gate**: compatibility code can be deleted only when no important consumer still requires it

## Implementation approaches / shapes

### Approach A — Full removal
- migrate all callers/tests
- delete compatibility namespaces and helpers
- simplest final architecture
- best if remaining compatibility consumers are shallow

### Approach B — Boundary minimization
- migrate most callers/tests
- retain one tiny documented compatibility seam if truly needed
- acceptable if one boundary remains structurally useful
- must be explicit and justified

### Approach C — Deferred retention
- keep compatibility layer but add more documentation
- lowest risk short-term
- does not satisfy the intent well and should be rejected unless migration exposes unexpected coupling

Preferred shape: **Approach A**, with **Approach B** acceptable only if a very small, explicit compatibility seam remains justified.

## Architectural constraints

Follow the architecture established by 057:
- `workflow_statechart.clj` should remain the canonical Phase A compiler home
- `workflow_statechart_runtime.clj` should remain the canonical execution runtime
- `workflow_step_prep.clj` should remain the shared preparation surface
- `workflow_progression_recording.clj` should remain the record-only Phase A substrate
- compatibility code should shrink, not grow
- avoid introducing a third workflow model or new transitional layer

## Notes

This task should be treated as architecture cleanup and canonicalization after a successful migration, not as new workflow feature work.
