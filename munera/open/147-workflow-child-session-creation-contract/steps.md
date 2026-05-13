# Steps

- [ ] Audit the current workflow child-session create request surface across `create-step-attempt-session!`, judge creation, the execution adapter, and `create-workflow-child-session!`; record the authoritative supported fields.
- [ ] Add a lower workflow-runtime child-session contract namespace that defines executable request/result validation for the seam.
- [ ] Validate workflow attempt child-session create requests before they cross `execution-adapter/create-child-session!`.
- [ ] Validate judge child-session create requests before they cross `execution-adapter/create-child-session!`.
- [ ] Validate request/result shape at the higher session-owned realization edge for workflow child-session creation, explicitly proving `create-workflow-child-session!` applies the same authoritative contract for both attempt and judge caller surfaces.
- [ ] Extend adapter seam tests to prove `create-child-session!` forwards ctx, parent-session-id, and opts unchanged and preserves result passthrough.
- [ ] Extend workflow attempt-session tests as the canonical proof owner for attempt-side request forwarding and one-attempt-one-session invariants.
- [ ] Extend workflow judge tests as the canonical proof owner for judge-specific child-session defaults and projected preload messages.
- [ ] Add at least one integration test that creates a real workflow child session through the seam and asserts persisted child-session state plus runtime readiness.
- [ ] Add malformed-request and malformed-result failure tests so boundary errors are local and descriptive.
- [ ] Verify focused suites and lint; record results in `implementation.md`.
