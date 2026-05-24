# Design follow-up steps

- [x] Clarify where the production scheduler time source is owned and how it is injected into psi-tool, dispatch handlers, timer/effect execution, lifecycle, and any runtime context creation paths.
- [x] Decide whether `:scheduler/create` must require explicit `:created-at` and `:fire-at`, or whether it may accept a mandatory time source and derive missing values; update the design so "mandatory" is one concrete boundary contract.
- [x] Specify how scheduled user-message timestamps in both `:scheduler/deliver` and `:scheduler/drain-queue` receive time, including whether delivery events carry `delivered-at` or handlers receive a time source.
- [x] Define the exact scheduler-owned wall-clock search boundary: list the namespaces/files that must be free of direct `Instant/now` and explicitly exclude any remaining test/runtime timer wall-clock use that is outside this task.
- [x] Choose the test time-source helper location and shape, and state whether it is production API, test support only, or a documented map/function contract.
