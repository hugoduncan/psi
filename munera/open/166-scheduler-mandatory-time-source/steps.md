# Execution steps

- [ ] Add scheduler time-source production contract and context wiring.
- [ ] Require explicit `:created-at` and `:fire-at` for scheduler create dispatch.
- [ ] Thread scheduler time source through psi-tool create resolution and timer delay calculation.
- [ ] Thread scheduler time source or explicit `:delivered-at` through deliver and drain message construction.
- [ ] Add deterministic scheduler time-source test helpers.
- [ ] Update focused scheduler tests for deterministic create, `delay-ms`, `at`, delivery, and drain behavior.
- [ ] Run the focused scheduler verification suite from `design.md`.
