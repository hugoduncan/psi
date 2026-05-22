# Steps

- [ ] Inventory the current deterministic-operation write, read, invoke, and cleanup seams named in `design.md`.
- [ ] Refactor `deterministic-operation-registry` so canonical operation storage is hosted in shared `root-registry` state with no parallel adapter-local canonical operation store.
- [ ] Preserve adapter-owned duplicate-throw and invoke-miss behaviour while translating lower shared-storage results deliberately.
- [ ] Update focused deterministic-operation registry tests to prove shared-storage ownership, unordered listing behaviour, duplicate handling, cleanup, and invoke semantics.
- [ ] Update at least one extension runtime registration seam proof and one extension reload/unregister cleanup proof so higher seams prove migration coherence.
- [ ] Add focused extension-introspection projection proofs for `operation-ids-in`, `extension-detail-in`, `extension-details-in`, and `summary-in` so higher seams prove projection coherence after the runtime canonical-owner migration.
- [ ] Run focused verification and record results in `implementation.md`.
- [ ] Update the task artifacts to record the final authoritative-vs-derived registry-object state split after migration.
