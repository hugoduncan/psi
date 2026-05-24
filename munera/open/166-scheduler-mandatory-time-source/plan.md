# Plan

- Add a small scheduler time-source contract and wire production context ownership through `:scheduler-time-source`.
- Tighten scheduler create dispatch so `:created-at` and `:fire-at` are explicit mandatory event values.
- Replace scheduler-owned wall-clock timestamp reads in psi-tool create resolution, timer delay calculation, and deliver/drain message construction with the scheduler time source or explicit event instants.
- Add scheduler-focused test time-source helpers and update focused scheduler tests to control time deterministically for create, `delay-ms`, `at`, delivery, and drain behavior.
- Verify the focused scheduler suites listed in `design.md`.
