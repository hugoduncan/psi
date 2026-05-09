# Workflow canonical surfaces

This note documents the authoritative workflow execution/compiler/runtime surfaces after task 058 compatibility removal convergence.

## Canonical Phase A surfaces

- `psi.workflow-runtime.statechart/compile-hierarchical-chart`
  - Canonical execution chart compiler for deterministic workflow runs.
- `psi.workflow-runtime.statechart`
  - Canonical run lifecycle chart plus workflow definition-order helpers such as `initial-step-id` and `next-step-id`.
- `psi.workflow-runtime.statechart-runtime`
  - Canonical statechart-driven runtime boundary.
- `psi.workflow-step-materialization.core`
  - Canonical shared step input materialization, child-session conversation materialization, and prompt derivation helpers.
- `psi.workflow-step-materialization.source-resolution`
  - Canonical shared source binding/source-spec resolution, template rendering, and delegate/invoke materialization helpers.
- `psi.workflow-step-session-config.core`
  - Canonical child-session config shaping helpers for workflow steps.
- `psi.workflow-runtime.progression-recording`
  - Canonical record/update substrate used by the Phase A runtime.
- `psi.workflow-runtime.core`
  - Canonical pure root-state lifecycle operations for definitions and runs, including run creation, resume, cancellation, input updates, and removal.

## Remaining compatibility / legacy surface

- `psi.agent-session.workflow-sequential-compat-test-support`
  - Test-only sequential progression helpers retained for compatibility-oriented proofs during cleanup.
  - Not used by production/runtime execution paths.

## Public wrapper surface

- `psi.agent-session.workflow-execution`
  - Thin wrapper for `execute-run!` / `resume-and-execute-run!` plus shared prep helper re-exports used by tests and callers.