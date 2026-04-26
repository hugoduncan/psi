# Workflow canonical surfaces

This note documents the authoritative workflow execution/compiler/runtime surfaces after task 058 compatibility removal convergence.

## Canonical Phase A surfaces

- `psi.agent-session.workflow-statechart/compile-hierarchical-chart`
  - Canonical execution chart compiler for deterministic workflow runs.
- `psi.agent-session.workflow-statechart`
  - Canonical run lifecycle chart plus workflow definition-order helpers such as `initial-step-id` and `next-step-id`.
- `psi.agent-session.workflow-statechart-runtime`
  - Canonical statechart-driven runtime boundary.
- `psi.agent-session.workflow-step-prep`
  - Canonical shared step input/prompt/session-config preparation helpers.
- `psi.agent-session.workflow-progression-recording`
  - Canonical record/update substrate used by the Phase A runtime.
- `psi.agent-session.workflow-runtime`
  - Canonical pure root-state lifecycle operations for definitions and runs, including run creation, resume, cancellation, input updates, and removal.

## Remaining compatibility / legacy surface

- `psi.agent-session.workflow-progression`
  - Legacy sequential progression helpers retained only for compatibility-oriented tests and migration cleanup.
  - Not used by active Phase A runtime execution paths.

## Public wrapper surface

- `psi.agent-session.workflow-execution`
  - Thin wrapper for `execute-run!` / `resume-and-execute-run!` plus shared prep helper re-exports used by tests and callers.