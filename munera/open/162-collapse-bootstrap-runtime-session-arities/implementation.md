# 162 — Implementation Notes

## Design review — ambiguity pass (2026-05-19)

1. **AC1 "exactly one public arity" vs convenience 2-arg form**: AC1 says the single arity is `(ctx ai-model opts)` (3 args). Currently `(ctx ai-model)` (2 args, when `:state*` present) delegates to `(ctx ai-model {})`. Should the collapsed function also offer a 2-arg `(ctx ai-model)` convenience arity, or must callers always pass `{}`? If strictly one arity, the design should say so explicitly and note that `opts` is required.

2. **Test helper opts surface unspecified**: The 2-arity "create everything" path currently forwards `:session-config`, `:ui-type`, `:persist?`, `:session-root`, `:thinking-level-override` to `create-runtime-session-context`. The design says the test helper wraps `create-runtime-session-context` + `bootstrap-runtime-session!` but doesn't specify which opts the helper accepts. At minimum it needs `:cwd`, `:persist?`, `:session-root` (used by existing test callers). Should the helper accept the full opts map or a curated subset?

3. **Test helper location across components**: Tests using the 2-arity "create everything" form span `app-runtime` and `gordian-launcher` components. The suggested ns `psi.app-runtime.test-support` implies it lives in `components/app-runtime/test/`. The `gordian_launcher_manifest_runtime_boundary_test` already depends on `app-runtime` so this works, but the design should confirm the helper is in `components/app-runtime/test/` (not a shared test-utils component).

4. **`rpc_real_delegate_command_test` listed in scope but uses 3-arity**: This test already calls `(bootstrap-runtime-session! ctx ai-model opts)` — the target form. It doesn't use the 2-arity "create everything" path. The design lists it in scope but it may need no change at all (only if the opts key for `:session-id` changes the return shape). Clarify whether this caller needs any modification.

5. **4-arity `main.clj` caller: opts key name**: The 4-arity `(ctx session-id ai-model opts)` in `main.clj` will become `(ctx ai-model (assoc opts :session-id session-id))`. The design should confirm the opts key is literally `:session-id` (matching the existing `session-id` binding in the 4-arity body).

6. **No 2-arity `(ctx ai-model)` callers exist in production or tests**: The `(:state* x)` → true branch (interpreting 2-arity as ctx+ai-model) is documented but has zero callers. Every 2-arity call passes an ai-model map as first arg. The design correctly identifies this as dead code but could note that no migration of `(ctx ai-model)` callers is needed — only the `(ai-model opts)` callers need the test helper.
