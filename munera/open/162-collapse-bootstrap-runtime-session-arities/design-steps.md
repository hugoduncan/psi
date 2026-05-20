# 162 — Design Follow-up Steps

- [ ] Clarify AC1: should the collapsed function offer a 2-arg `(ctx ai-model)` convenience arity or require `opts` always?
- [ ] Specify which opts the test helper accepts (full opts map vs curated subset like `#{:cwd :persist? :session-root}`)
- [ ] Confirm test helper location: `components/app-runtime/test/psi/app_runtime/test_support.clj` (or equivalent)
- [ ] Clarify whether `rpc_real_delegate_command_test` needs any change (it already uses the 3-arity target form)
- [ ] Confirm the opts key for session-id in the merged form is `:session-id`
- [ ] Note in design that zero callers use the `(ctx ai-model)` 2-arity path — only `(ai-model opts)` callers exist
