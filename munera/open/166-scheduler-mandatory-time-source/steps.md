# Execution steps

- [x] Add scheduler time-source production contract and context wiring.
- [x] Require explicit `:created-at` and `:fire-at` for scheduler create dispatch.
- [x] Thread scheduler time source through psi-tool create resolution and timer delay calculation.
- [x] Thread scheduler time source or explicit `:delivered-at` through deliver and drain message construction.
- [x] Add deterministic scheduler time-source test helpers.
- [x] Update focused scheduler tests for deterministic create, `delay-ms`, `at`, delivery, and drain behavior.
- [x] Run the focused scheduler verification suite from `design.md`.
- [x] Add focused tests proving missing/invalid `:scheduler-time-source` fails early at scheduler runtime boundaries (for example psi-tool create and timer/deliver/drain paths) instead of falling back to wall-clock/default time.
- [x] Move `:scheduler/deliver` delivered-at resolution until after the target schedule has been found and the path actually needs to construct a scheduled user message, preserving schedule-not-found/non-deliverable error precedence.
