# Execution steps

- [x] Add scheduler time-source production contract and context wiring.
- [x] Require explicit `:created-at` and `:fire-at` for scheduler create dispatch.
- [x] Thread scheduler time source through psi-tool create resolution and timer delay calculation.
- [x] Thread scheduler time source or explicit `:delivered-at` through deliver and drain message construction.
- [x] Add deterministic scheduler time-source test helpers.
- [x] Update focused scheduler tests for deterministic create, `delay-ms`, `at`, delivery, and drain behavior.
- [x] Run the focused scheduler verification suite from `design.md`.
