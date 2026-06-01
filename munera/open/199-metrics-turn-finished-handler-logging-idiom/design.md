# 199 Replace raw println logging in metrics turn-finished handler with timbre

## Intent

`psi/metrics` extension's `make-turn-finished-handler` swallows exceptions from
token-usage tracking and logs them via a raw `println` with a hand-written
`"DEBUG [psi/metrics]"` prefix, rather than the project-standard `taoensso.timbre`
logging. This is a `consistent(idioms)` drift: the rest of the codebase logs via
timbre with structured levels, and a raw `println` bypasses log-level control,
output routing, and structured context.

This was surfaced (and deferred as out-of-scope) by the task-198
implementation review.

## Context

- File: `extensions/metrics/src/psi/metrics/extension.clj`
- Function: `make-turn-finished-handler` (the `catch` branch, ~line 99)
- Current code:
  ```clojure
  (catch Exception e
    (println (str "DEBUG [psi/metrics] skipping token tracking for session "
                  session-id ": " (ex-message e))))
  ```
- Introduced in `2a04436fb` (#94), not by task 198.

## Scope

- Replace the `println` call with a `timbre` logging call at an appropriate
  level (a swallowed, non-fatal "skip token tracking" condition is `debug` or
  `warn` — choose the level that matches how similar swallowed-exception paths
  are logged elsewhere in the codebase).
- Add the `taoensso.timbre` require if not already present in the namespace.
- No behavioural change beyond logging mechanism: the exception remains
  swallowed and the handler still returns `nil`.

## Acceptance Criteria

- The turn-finished handler logs the skipped-token-tracking condition via
  `timbre` (not `println`), at a level consistent with sibling swallowed-error
  log sites.
- The `"DEBUG"`-prefixed raw `println` is removed.
- `clj-kondo` clean on the changed file.
- No change to token-tracking success-path behaviour or to the handler's
  `nil` return contract.

## Out of Scope

- The separate `dispatch-tool-result-in` / `wrap-tool-executor` verbose-predicate
  / dead-code cleanup (tracked in task 200).
- Any change to what conditions are caught or whether the exception is swallowed.
