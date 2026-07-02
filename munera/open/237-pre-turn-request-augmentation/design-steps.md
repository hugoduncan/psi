# Design follow-up steps

- [ ] ARCH: Make turn-augmentation registration/execution explicitly capability- and permission-gated for privileged extensions, rather than leaving manifest/permission declaration as an optional planning question.
- [ ] ARCH: Require augmenter input to be a bounded core-owned projection/resolver payload, not raw `ctx`, direct atom access, or hidden runtime handles.
- [ ] ARCH: Require augmentation child-session creation to route through the core/session lifecycle with canonical parent turn provenance and recursion-suppression state, not extension-local session management.
- [ ] ARCH: Specify deterministic registration/invocation/result ordering for multiple augmenters so the same state and turn event produce the same recorded operations.
- [ ] ARCH: Require accepted augmentation operations and diagnostics to be stored as canonical turn-scoped session/journal state consumed by pure request preparation, not only in runtime locals, extension diagnostics, or the bounded dispatch log.
- [ ] AMB: Define the exact turn-augmentation input contract field set and names, including which of original user message, session metadata, effective cwd, existing session context, and turn/session ids are provided.
- [ ] AMB: Define the precise turn-id creation/reuse point and lifecycle ordering around prompt submission, augmentation recording, and `prompt-prepare-request` so implementers know which event or state owns the turn id before augmentation runs.
- [ ] AMB: Define how an append-context-block operation is rendered into the prepared request, including role/layer, formatting, and ordering relative to system prompt layers, extension prompt contributions, runtime metadata, conversation history, and the submitted user message.
- [ ] AMB: Choose one unsupported-operation behavior for v1 augmentation results, replacing “rejected or ignored” with a single rule and diagnostic expectation.
- [ ] AMB: Define partial-result semantics when one augmenter fails or when one augmenter returns a mixture of valid and invalid operations: whether valid operations from the same provider or other providers are still applied, and how this is diagnosed.
- [ ] AMB: Define canceled/stale-result handling as either diagnostic recording or complete ignore, with the exact persisted state/test expectation for each case.
- [ ] AMB: Define the minimum augmentation diagnostics schema/status taxonomy and query/summary surface needed to verify success, no-op, failure, unsupported operation, stale/canceled result, and replay behavior.
- [ ] AMB: Define replay lookup behavior for augmentation records, including how the pre-turn phase detects replay mode for a turn and what happens when recorded operations are missing, malformed, or refer to a different turn id.
