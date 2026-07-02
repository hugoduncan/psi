# Plan

Implement task 237 in vertical slices, preserving the core-owned/replayable boundary.

1. Resolve remaining design follow-ups before widening implementation.
2. Add pure turn-augmentation record/rendering helpers and wire request preparation to consume a canonical turn-scoped record.
3. Add extension registry/API registration support for turn augmenters with effective-permission gating.
4. Add manifest/effective permission recognition for `:psi.capability/turn-augmentation` and fail unknown capabilities closed.
5. Update the context-manager scaffold to register `project-context` and return the minimal working-directory context block/no-op envelopes.
6. Add resolver/summary exposure for augmentation records.
7. Verify focused request, extension API, extension install, context-manager, and prompt lifecycle tests.

This pass implements a compatibility first slice: `prompt-submit` creates a canonical no-op augmentation record so existing prompt flows keep working while the explicit pre-turn invocation/effect/statechart barrier is added in later slices.
