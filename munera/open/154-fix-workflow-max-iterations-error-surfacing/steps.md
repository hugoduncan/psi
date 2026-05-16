# Steps

- [x] Add `:iteration/exhausted` dispatch action to failed-transition in `judged-routing-transition`
- [x] Handle `[:failed]` vector form of target in `judged-routing-transition`
- [x] Add `:iteration/exhausted` action handler in `statechart_runtime.clj`
- [x] Record `terminal-outcome` with iteration context in workflow run state
- [x] Record final judge result before terminal outcome
- [x] Add `terminal-outcome-error-message` in `canonical_workflows.clj`
- [x] Add `run-failure-error` fallback chain (step errors → terminal-outcome)
- [x] Wire `run-failure-error` into `execute-workflow-run`
- [x] Wire `run-failure-error` into `resume-workflow-run`
- [x] Add statechart-level tests for iteration exhaustion action firing
- [x] Add unit tests for `terminal-outcome-error-message` and `run-failure-error`

## Review follow-ups (2026-05-15)
- [x] Guard `terminal-outcome-error-message` fallback against nil `:reason` — use `(some-> (:reason terminal-outcome) name)` or equivalent to avoid NPE
  - `some->` in generic fallback case; 62 assertions pass
- [x] Guard `last-result-text` with `not-empty` to prevent dangling "Last result:" header on empty string
  - `not-empty` guard on `when-let` binding
- [x] Add test case for `terminal-outcome-error-message` with nil `:reason` to document defensive contract
  - Added test: asserts string result, contains step-id, no "null" leakage; 62 assertions (was 59)
- [x] Extract shared working-memory cleanup helper used by both `:judge/record` and `:iteration/exhausted` handlers (optional, minor duplication)
  - `clear-pending-judge-state!` private fn in statechart_runtime.clj; both handlers now call it; 113 statechart assertions pass

## Test review follow-ups (2026-05-15)
- [ ] Add test for empty-string `last-result-text` in `terminal-outcome-error-message` — verify no "Last result:" header appears for `""` input
- [ ] Add test for >2000-char `last-result-text` truncation — verify output contains `[truncated]` marker and is bounded
- [ ] Add test for `[:failed]` vector target form in `judged-routing-transition` — anchor the defensive guard with a regression test
- [ ] Add test (or document decision to skip) for `:judge/no-match` statechart event → error message end-to-end — currently this path produces no `terminal-outcome`, so `run-failure-error` returns nil. Either add a dispatch action to record `terminal-outcome` on `:judge/no-match`, or add a test documenting the current nil-error behaviour as intentional
