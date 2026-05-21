# Implementation

Created task `166-root-registry-component-build-task` as the implementation follow-on to closed design task `165-root-registry-component-target-architecture`.

Starting point:

- task `165` is now the closed source-of-truth design for the target shared registry architecture
- this task will build the standalone lower component defined there
- migration of existing registries is intentionally deferred to later tasks

Initial next step:

- extract the exact shared state model, operation set, and result contracts from task `165` and turn them into a focused lower-component implementation with tests

2026-05-21 ambiguity review:

- Actionable ambiguities: the task still does not say whether the first minimal integration scaffolding may break current direct-adopter thrown-error/public API contracts in order to adopt explicit result-map semantics; it does not record the authoritative component path/namespace for the new shared component; and it does not identify which lower API owns explicit registry declaration/initialization in root state before mutation/list operations can succeed.

2026-05-21 ambiguity follow-up:

- Clarified in `design.md` that this build task does not change current adopter-facing `workflow-registry`, `tool-registry`, or `command-registry` public thrown-error / return-shape contracts during minimal scaffolding. The new lower shared component may use explicit result maps, but adapter or migration work that changes higher-level contracts is deferred to later tasks.
- Chose and recorded the authoritative implementation home as a new `components/root-registry/` component with primary namespace `psi.root-registry.registry` and focused tests in `components/root-registry/test/psi/root_registry/registry_test.clj`.
- Specified the lower declaration boundary: the shared component must expose an explicit registry-declaration API, and the authoritative empty declared-registry state shape is `{:entries-by-id {} :ids-by-extension {}}` under the shared root-state registry area. Register/list/remove/clear operations must not implicitly declare registries.
- These ambiguity follow-up items are design-resolved; no remaining ambiguity blocker is recorded from this pass.
