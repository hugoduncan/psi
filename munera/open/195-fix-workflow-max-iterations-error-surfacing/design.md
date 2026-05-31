# Fix Workflow Max-Iterations Error Surfacing

## Goal

When a judged workflow step exhausts its iteration limit, surface an actionable
error message to the caller instead of silently failing with no error context.

## Context

Previously, iteration-limit exhaustion transitioned the statechart to `:failed`
but produced no `terminal-outcome` record and no error message — callers saw a
generic nil error.

## Acceptance Criteria

- Iteration exhaustion fires a dedicated `:iteration/exhausted` statechart action
- The action handler records a `terminal-outcome` with step-id, iteration count,
  max-iterations, last judge signal, and last result text
- `execute-workflow-run` and `resume-workflow-run` extract a human-readable error
  from `terminal-outcome` when no step-level error exists
- Error message includes step, counts, signal, and truncated last result
- `:judge-no-match` and unknown failure reasons also produce actionable messages
