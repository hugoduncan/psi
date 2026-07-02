# Steps

- [x] Resolve open design follow-up steps for replay source, record well-formedness, handler wait semantics, provider generation checks, v1 session policy, and terminal close path.
- [x] Add pure turn-augmentation helpers for record well-formedness, append-context-block rendering, prepared-request summaries, and resolver summaries.
- [x] Wire request preparation to fail closed for malformed/open/missing canonical augmentation records when augmentation state is present.
- [x] Insert accepted augmentation context as a user-role turn-context message before the current user message.
- [x] Add extension registry/API/mutation support for `:register-turn-augmenter` with effective-permission gating.
- [x] Add effective permission recognition and unknown-capability fail-closed handling for extension install manifests.
- [x] Update context-manager scaffold with `project-context` augmentation and helper-session no-op recursion avoidance state.
- [x] Add focused tests for rendering, fail-closed checks, registration gating, unknown capabilities, and context-manager envelopes.
- [x] Run focused Scry tests for affected namespaces.
- [x] Run clj-kondo on affected files.
- [ ] Implement explicit `:session/pre-turn-augment` and `:session/close-pre-turn-augmentation` dispatch lifecycle and statechart-visible barrier.
- [ ] Move live augmenter invocation to dispatch effects and record provider diagnostics/results through dispatch-owned events.
- [ ] Add full validation/diagnostics for invalid/unsupported provider operations and stale/canceled late results.
- [ ] Replace compatibility no-op prompt-submit record with the explicit pre-turn barrier once lifecycle scheduling is in place.
- [ ] Add replay close-payload handling and replay missing/invalid fail-closed tests.
