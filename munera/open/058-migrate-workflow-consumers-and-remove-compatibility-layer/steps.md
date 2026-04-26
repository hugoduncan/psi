# 058 — Steps

- [ ] Inventory every remaining compatibility surface and record each caller in `implementation.md`
- [ ] Classify each caller as canonical runtime, public mutation/tool path, test-only, docs-only, or justified retained boundary
- [ ] Remove compatibility compilation from run creation in `workflow_runtime.clj`
- [ ] Prove run creation still derives initial step selection canonically from workflow definition order
- [ ] Identify which remaining `workflow_progression.clj` functions are true canonical run-lifecycle operations vs legacy sequential progression helpers
- [ ] Migrate canonical lifecycle operations to their final home and update all callers
- [ ] Remove test-only dependence on `workflow_statechart_compat`
- [ ] Collapse progression tests onto final canonical or intentionally retained surfaces
- [ ] Remove dead compatibility code from `workflow_statechart_compat.clj` and/or delete the namespace entirely
- [ ] Remove dead legacy compatibility helpers from `workflow_progression.clj` or explicitly document any retained seam
- [ ] Update `workflow_statechart_canonical.md` to match the final authoritative workflow surfaces
- [ ] Record final retained-vs-removed surface summary in `implementation.md`
- [ ] Verify focused workflow suites green
- [ ] Verify isolated workflow suite green if still applicable
- [ ] Verify full unit suite green
- [ ] Verify full `bb test` suite green