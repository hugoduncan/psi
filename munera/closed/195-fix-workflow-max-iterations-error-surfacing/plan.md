# Plan

## Approach

Three-layer change:

1. **Statechart compiler** (`statechart.clj`): When iteration-limited routing
   transitions to `:failed`, attach `:iteration/exhausted` dispatch action.
2. **Statechart runtime** (`statechart_runtime.clj`): Handle `:iteration/exhausted`
   action — record `terminal-outcome` with full context into the workflow run.
3. **Mutation layer** (`canonical_workflows.clj`): Add `terminal-outcome-error-message`
   and `run-failure-error` to extract human-readable errors, used by both
   `execute-workflow-run` and `resume-workflow-run`.
