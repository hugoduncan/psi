# 162 — Design Follow-up Steps

- [x] Narrow AC4 to reflect that only `main.clj` needs updating — `run-session` and `start-tui-runtime!` already use the target 3-arity form
  - Done: AC4 now says `main.clj` only, notes `run-session`/`start-tui-runtime!` already conform.
- [x] Reconcile scope "All production callers" list with the reality that `run-session` and `start-tui-runtime!` need zero changes (same logic as AC6 for RPC test)
  - Done: Scope section now distinguishes `main.clj` (needs change) from `run-session`/`start-tui-runtime!` (already target form).
- [x] Note in design that the `start-tui-runtime!` test mock (app_runtime_test.clj ~line 200) mocks both 3-arity and 4-arity and needs updating after refactor — the change is in the mock, not in `start-tui-runtime!` itself
  - Done: Added "start-tui-runtime! test mock needs updating" design decision section.
- [x] Clarify AC1: should the collapsed function offer a 2-arg `(ctx ai-model)` convenience arity or require `opts` always?
  - Decision: strictly one arity `(ctx ai-model opts)`, no convenience form. Zero callers use `(ctx ai-model)` today.
- [x] Specify which opts the test helper accepts (full opts map vs curated subset like `#{:cwd :persist? :session-root}`)
  - Decision: full opts map. Helper extracts `create-runtime-session-context` keys and forwards the rest.
- [x] Confirm test helper location: `components/app-runtime/test/psi/app_runtime/test_support.clj` (or equivalent)
  - Confirmed: `components/app-runtime/test/psi/app_runtime/test_support.clj` (`psi.app-runtime.test-support`). Gordian test already depends on `app-runtime`.
- [x] Clarify whether `rpc_real_delegate_command_test` needs any change (it already uses the 3-arity target form)
  - Confirmed: no change needed. Already calls `(bootstrap-runtime-session! ctx ai-model opts)`.
- [x] Confirm the opts key for session-id in the merged form is `:session-id`
  - Confirmed: `:session-id`, matching the existing binding name in the 4-arity body.
- [x] Note in design that zero callers use the `(ctx ai-model)` 2-arity path — only `(ai-model opts)` callers exist
  - Added to design under "Design decisions" section.
