# Steps

- [x] Remove `ai-ctx` binding from `start-tui-runtime!` and `run-session`; pass `nil` directly at call sites that still need it. The binding is always `nil` and adds noise.
- [x] Extract nullable execution mode env-var handling from `start-tui-runtime!` into a named helper function.
  - Extracted to `maybe-install-nullable-execution-mode` with docstring explaining the TUI test harness purpose.
- [x] Clarify `:on-new-session!` parameter shadowing — the TUI and CLI closures intentionally ignore the `source-session-id` parameter and read their focus atom instead. Document this intent with a comment rather than changing behavior.
  - Added intent comments on both TUI and CLI `:on-new-session!` closures.
- [x] Verify all tests pass after changes.
  - 39 tests, 195 assertions, 0 failures across app-runtime, bootstrap, nrepl, navigation, and rpc-prompt-command test namespaces.
