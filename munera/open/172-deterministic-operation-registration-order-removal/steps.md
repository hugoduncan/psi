# Steps

- [ ] Remove `:registration-order` from `deterministic-operation-registry` state and update register/unregister logic to rely only on `:operations`.
- [ ] Update `operation-ids-in`, `all-operations-in`, and `operation-count-in` to implement the new unordered membership/count contract.
- [ ] Rewrite focused deterministic-operation registry tests to assert membership/count/coherence instead of insertion order.
- [ ] Remove or replace the dedicated registration-order preservation test with a non-order contract test.
- [ ] Audit `components/agent-session/test/psi/agent_session/extensions_test.clj` and relax any deterministic-operation ordering assumptions to unordered membership assertions while preserving cleanup/invoke-staleness proofs.
- [ ] Update task `171-deterministic-operation-registry-shared-storage-migration` so its migration target no longer assumes adapter-owned ordering state.
- [ ] Verify focused deterministic-operation and affected higher proof surfaces, then record the final simplified contract in task artifacts.
