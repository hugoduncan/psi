# Workflow canonical surfaces

This note documents the authoritative workflow execution/compiler/runtime surfaces after task 057.

## Canonical Phase A surfaces

- `psi.agent-session.workflow-statechart/compile-hierarchical-chart`
  - Canonical execution chart compiler for deterministic workflow runs.
- `psi.agent-session.workflow-statechart-runtime`
  - Canonical statechart-driven runtime boundary.
- `psi.agent-session.workflow-step-prep`
  - Canonical shared step input/prompt/session-config preparation helpers.
- `psi.agent-session.workflow-progression-recording`
  - Canonical record/update substrate used by the Phase A runtime.

## Compatibility / legacy surfaces

- `psi.agent-session.workflow-statechart/compile-definition`
  - Compatibility Phase B metadata compiler retained for run creation and legacy sequential helpers.
- `psi.agent-session.workflow-statechart/workflow-run-chart`
  - Compatibility flat status-tracker chart.
- `psi.agent-session.workflow-progression`
  - Transitional namespace that still exposes compatibility progression/control helpers while delegating Phase A record-only helpers to `workflow-progression-recording`.

## Public wrapper surface

- `psi.agent-session.workflow-execution`
  - Thin wrapper for `execute-run!` / `resume-and-execute-run!` plus shared prep helper re-exports used by tests and callers.
