# Steps

- [ ] Remove adapter-owned registration-order state from `deterministic-operation-registry` implementation and align listing/count surfaces with unordered registered-operation semantics.
- [ ] Update focused deterministic-operation registry tests to prove unordered membership/count/coherence semantics and remove registration-order preservation proofs.
- [ ] Update `components/agent-session/test/psi/agent_session/extensions_test.clj` so its deterministic-operation cleanup proof uses unordered membership assertions rather than ordered listing expectations.
- [ ] Update any higher proof surfaces that still assume deterministic-operation insertion order.
- [ ] Update task `171-deterministic-operation-registry-shared-storage-migration` so its target shape no longer includes adapter-owned ordering metadata.
- [ ] Verify the affected focused test suites and record the result.
