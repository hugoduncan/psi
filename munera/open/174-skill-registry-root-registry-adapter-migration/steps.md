# Steps

- [ ] Audit current `skill-registry` implementation and task `173` proof surfaces before changing code.
- [ ] Implement a local root-registry adapter inside `skill-registry` for vector → root-registry state → canonical vector projection.
- [ ] Use `root-registry/insert` for lower duplicate detection and translate duplicate-id results into public duplicate-ignore/no-change skill results.
- [ ] Preserve first-write-wins behavior when the incoming existing skill vector already contains duplicate names.
- [ ] Update or add focused `skill-registry` tests for add, duplicate/no-change, unsorted existing vectors, duplicate names in existing vectors, exact lookup, name listing, and count.
- [ ] Re-run or update session dispatch tests proving prompt refresh remains gated by semantic `:changed?`, while canonicalized duplicate/no-change vectors are still persisted when needed.
- [ ] Re-run representative task `173` higher-surface tests for prompt/display/TUI/command/workflow canonical ordering.
- [ ] Update `munera/closed/164-registry-semantics-unification-audit/` to record the new `skill-registry` classification and migration lesson.
- [ ] Run full `bb test` before close.
