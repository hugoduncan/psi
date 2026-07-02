# Plan

Implement task 237 in vertical slices, preserving the core-owned/replayable boundary.

1. Resolve remaining design follow-ups before widening implementation.
2. Add pure turn-augmentation record/rendering helpers and wire request preparation to consume a canonical turn-scoped record.
3. Add extension registry/API registration support for turn augmenters with effective-permission gating.
4. Add manifest/effective permission recognition for `:psi.capability/turn-augmentation` and fail unknown capabilities closed.
5. Update the context-manager scaffold to register `project-context` and return the minimal working-directory context block/no-op envelopes.
6. Add resolver/summary exposure for augmentation records.
7. Verify focused request, extension API, extension install, context-manager, and prompt lifecycle tests.

Current slice status: the compatibility prompt-submit no-op seeding has been replaced by an explicit dispatch-visible pre-turn barrier. `prompt-submit` records `:turn/submitted`; `:session/pre-turn-augment` opens a turn-scoped augmentation phase; live authorized provider invocation now runs via `:runtime/turn-augmentation-invoke`; `:session/close-pre-turn-augmentation` records terminal provider diagnostics/results and schedules `prompt-prepare-request` for non-canceled closes. Provider envelopes are normalized/validated, sessions are seeded with extension-scoped available capabilities from live effective permissions, replaced registration-token results are diagnosed as stale, session-unavailable registrations are diagnosed as unauthorized, and parent workflow cancellation closes open augmentation as `:canceled` without preparing a request. Remaining implementation focus is replay close-payload handling.
