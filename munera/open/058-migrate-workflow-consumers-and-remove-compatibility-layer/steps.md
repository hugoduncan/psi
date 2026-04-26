# 058 — Steps

Completed migration work:
- [x] Inventory every remaining compatibility surface and record each caller in `implementation.md`
- [x] Classify each caller as canonical runtime, public mutation/tool path, test-only, docs-only, or justified retained boundary
- [x] Remove compatibility compilation from run creation in `workflow_runtime.clj`
- [x] Prove run creation still derives initial step selection canonically from workflow definition order
- [x] Identify which remaining `workflow_progression.clj` functions are true canonical run-lifecycle operations vs legacy sequential progression helpers
- [x] Migrate canonical lifecycle operations to their final home and update all callers
- [x] Remove test-only dependence on `workflow_statechart_compat`
- [x] Collapse progression tests onto final canonical or intentionally retained surfaces
- [x] Remove dead compatibility code from active runtime paths and isolate any remaining compatibility support outside production execution paths
- [x] Remove dead legacy compatibility helpers from production workflow namespaces or explicitly document any retained seam
- [x] Update `workflow_statechart_canonical.md` to match the final authoritative workflow surfaces
- [x] Record interim retained-vs-removed surface summary in `implementation.md`
- [x] Verify focused workflow suites green
- [x] Verify isolated workflow suite green if still applicable
- [x] Verify full unit suite green
- [x] Verify full `bb test` suite green

Review follow-up:
- [x] Decide final fate of `workflow_statechart_compat.clj`: delete entirely or convert to a safe-load removed-boundary trap instead of throwing at require time
- [x] Decide final fate of `workflow_progression.clj`: delete entirely or explicitly retain it as a documented removed-boundary shim
- [x] Make removed-boundary behavior consistent across any retained compatibility tombstone namespaces
- [x] Record final closure summary in `implementation.md`, including whether tombstone namespaces are intentional and why
- [x] Re-run verification after any final tombstone/deletion cleanup