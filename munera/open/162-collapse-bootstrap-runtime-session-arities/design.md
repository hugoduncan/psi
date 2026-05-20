# 162 — Collapse bootstrap-runtime-session! arities

## Intent

`bootstrap-runtime-session!` has 3 arities (2, 3, 4) that conflate two distinct responsibilities:

1. **Bootstrap an existing ctx** (optionally with an existing session-id) — the real function
2. **Create a fresh ctx + bootstrap** — a test/convenience shortcut that sniffs its first argument via `(:state* x)`

The type-sniffing 2-arity is confusing and couples test convenience into production code. The 3-arity and 4-arity are near-identical (differ only in whether session-id is provided or created).

## Goal

- Merge 3-arity `(ctx ai-model opts)` and 4-arity `(ctx session-id ai-model opts)` into a single form that accepts an optional `:session-id` in opts (defaulting to creating a new session)
- Extract the 2-arity "create everything from scratch" form into a test helper in the test tree
- Remove the `(:state* x)` type-sniffing dispatch

## Scope

### In scope

- `bootstrap-runtime-session!` in `psi.app-runtime`
- All production callers: `run-session`, `start-tui-runtime!`, `main.clj` RPC bootstrap-fn
- All test callers across `app_runtime_test`, `app_runtime_bootstrap_test`, `extension_install_startup_test`, `gordian_launcher_manifest_runtime_boundary_test`, `rpc_real_delegate_command_test`
- New test helper (e.g. `psi.app-runtime.test-support/bootstrap-fresh-session!`) in the test tree

### Out of scope

- Restructuring `adopt-startup-plan-into-session!`
- Changing `create-runtime-session-context`
- Extracting `start-tui-runtime!`

## Constraints

- No behavioural change — all existing callers produce identical results
- Test helper must be in a test-only source path (not shipped in production)
- 301 tests, 0 failures after

## Acceptance criteria

1. `bootstrap-runtime-session!` has exactly one public arity: `(ctx ai-model opts)` where opts may contain `:session-id`
2. No `(:state* x)` type dispatch exists in `bootstrap-runtime-session!`
3. A test-tree helper wraps `create-runtime-session-context` + `bootstrap-runtime-session!` for the "create everything" use case
4. All production callers (`run-session`, `start-tui-runtime!`, `main.clj`) updated
5. All test callers updated to use the test helper or the new single-arity form
6. 301 tests, 0 failures
