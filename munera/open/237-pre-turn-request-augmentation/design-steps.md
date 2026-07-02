# Design follow-up steps

- [ ] ARCH: Make turn-augmentation registration/execution explicitly capability- and permission-gated for privileged extensions, rather than leaving manifest/permission declaration as an optional planning question.
- [ ] ARCH: Require augmenter input to be a bounded core-owned projection/resolver payload, not raw `ctx`, direct atom access, or hidden runtime handles.
- [ ] ARCH: Require augmentation child-session creation to route through the core/session lifecycle with canonical parent turn provenance and recursion-suppression state, not extension-local session management.
- [ ] ARCH: Specify deterministic registration/invocation/result ordering for multiple augmenters so the same state and turn event produce the same recorded operations.
- [ ] ARCH: Require accepted augmentation operations and diagnostics to be stored as canonical turn-scoped session/journal state consumed by pure request preparation, not only in runtime locals, extension diagnostics, or the bounded dispatch log.
