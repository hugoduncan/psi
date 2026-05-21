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

2026-05-21 inconsistency review:

- Actionable inconsistencies: `design.md` and `plan.md` require an explicit list-entries operation with unknown-registry failure semantics, but `steps.md` has no step to implement or test list semantics; `design.md` also makes registry declaration idempotence and non-implicit declaration part of the authoritative contract, but `steps.md` does not yet carry explicit follow-up for proving those declaration semantics in tests.

2026-05-21 inconsistency follow-up:

- Updated `steps.md` to add an explicit implementation step for list-entries semantics, including the contract that listing an unknown registry fails explicitly.
- Updated `steps.md` to make focused lower-component tests explicitly cover declaration idempotence and the rule that register/unregister/clear/list operations do not implicitly declare missing registries.
- Updated `plan.md` test scope to include list semantics and declaration/non-implicit declaration proof obligations.
- These newly added design follow-up items are completed at the task-artifact level; no blocker remains for them.

2026-05-21 implementation pass:

- Added new standalone component `components/root-registry/` with primary namespace `psi.root-registry.registry` and focused tests in `components/root-registry/test/psi/root_registry/registry_test.clj`.
- Implemented shared root-state host `:root-registries`, explicit idempotent declaration, canonical declared-registry state `{:entries-by-id {} :ids-by-extension {}}`, and uniform lower operation surfaces for `lookup`, `list-entries`, `register`, `unregister`, `clear-by-extension`, and `clear-registry`.
- Chose a consistent lower return contract of `{:root-state ... :result ...}` for all operations so reads and mutations expose explicit result maps without changing current higher-level adopter registry contracts in this task.
- Shared validation is intentionally narrow: known registry for non-lookup operations, required `:id`, required `:extension-id`, and required presence of `:value`; registry-specific entry validation remains above this layer.
- Built-ins are represented directly through `:extension-id :built-in` as designed.
- Verification for this pass: `clojure -M:test --focus psi.root-registry.registry-test`, `clj-kondo --lint components/root-registry/src components/root-registry/test`, and `clojure -M:fmt -m cljfmt.main check components/root-registry/src/psi/root_registry/registry.clj components/root-registry/test/psi/root_registry/registry_test.clj deps.edn tests.edn` all passed.

2026-05-21 implementation review:

- Reviewed `components/root-registry/src/psi/root_registry/registry.clj`, `components/root-registry/test/psi/root_registry/registry_test.clj`, and task artifacts against the `task-implementation-review` skill and task `166` design.
- No new actionable implementation feedback found: delivered lower-layer semantics, result contracts, focused tests, and current boundary with existing adopter registries are consistent with the task scope.
- Re-ran focused verification: `clojure -M:test --focus psi.root-registry.registry-test`, `clj-kondo --lint components/root-registry/src components/root-registry/test`, and `clojure -M:fmt -m cljfmt.main check components/root-registry/src/psi/root_registry/registry.clj components/root-registry/test/psi/root_registry/registry_test.clj deps.edn tests.edn` all passed.

2026-05-21 follow-up execution pass:

- Re-read task artifacts and preloaded review result to identify newly added actionable items from the preceding review pass.
- No newly added unchecked follow-up steps remained in `steps.md`; all recorded review follow-ups were already completed at the task-artifact level before this pass.
- Verified the delivered component and focused proof obligations still hold: `clojure -M:test --focus psi.root-registry.registry-test`, `clj-kondo --lint components/root-registry/src components/root-registry/test`, and `clojure -M:fmt -m cljfmt.main check components/root-registry/src/psi/root_registry/registry.clj components/root-registry/test/psi/root_registry/registry_test.clj deps.edn tests.edn` all passed.
- No blocker to record: there was no remaining newly added actionable work to execute in this task.

2026-05-21 test review:

- Reviewed `components/root-registry/test/psi/root_registry/registry_test.clj`, `components/root-registry/src/psi/root_registry/registry.clj`, and task `166` artifacts against `.psi/skills/task-test-review/SKILL.md`.
- No new actionable test feedback found: focused tests cover the task-defined shared behaviors, exercise unknown-registry and declaration semantics explicitly, and use no mocks or stubs.
- Re-ran focused verification: `clojure -M:test --focus psi.root-registry.registry-test`, `clj-kondo --lint components/root-registry/src components/root-registry/test`, and `clojure -M:fmt -m cljfmt.main check components/root-registry/src/psi/root_registry/registry.clj components/root-registry/test/psi/root_registry/registry_test.clj deps.edn tests.edn` all passed.
