# Design follow-up steps

- [ ] ARCH1: Strengthen the pre-simplification gate with a workflow-level baseline/diff check before routing to simplification: record the target/source baseline before the characterization phase, classify coverage-phase changes, and proceed only when changes are tests/task artifacts/docs or explicitly justified minimal testability seams; otherwise stop/revert/split before simplification.
