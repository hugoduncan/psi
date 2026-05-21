# Steps

- [x] Remove `ai-ctx` binding from `start-tui-runtime!` and `run-session`; pass `nil` directly at call sites that still need it. The binding is always `nil` and adds noise.
- [x] Extract nullable execution mode env-var handling from `start-tui-runtime!` into a named helper function.
  - Extracted to `maybe-install-nullable-execution-mode` with docstring explaining the TUI test harness purpose.
- [x] Clarify `:on-new-session!` parameter shadowing — the TUI and CLI closures intentionally ignore the `source-session-id` parameter and read their focus atom instead. Document this intent with a comment rather than changing behavior.
  - Added intent comments on both TUI and CLI `:on-new-session!` closures.
- [x] Verify all tests pass after changes.
  - 39 tests, 195 assertions, 0 failures across app-runtime, bootstrap, nrepl, navigation, and rpc-prompt-command test namespaces.

## Review follow-up

- [x] Fix implementation log inaccuracy: `start-new-session-with-startup!` is `defn-` (private), not "public/internal API".
- [x] Remove dead `:ai-ctx nil` from `session-state` atom resets in both `run-session` and `start-tui-runtime!`.
  - Confirmed no code reads `:ai-ctx` from `session-state`. Removed from both reset sites. All unit tests pass.

## Test review follow-up

- [x] Add direct unit tests for `maybe-install-nullable-execution-mode`: (1) passthrough when env var is absent/blank, (2) stub installation when `"deterministic"`, (3) stub echo-back returns correct execution-result shape.
  - Extracted `nullable-execution-mode` helper for env-var read (testable seam). Added 6 tests covering passthrough, stub installation, echo-back shape, UUID generation fallback, and empty-text fallback. 34 tests, 129 assertions, 0 failures across app-runtime test namespaces.
