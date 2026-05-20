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

## Design decisions

### Strictly one arity — no convenience form

The collapsed `bootstrap-runtime-session!` has exactly one arity: `(ctx ai-model opts)`. There is no 2-arg `(ctx ai-model)` convenience form. Callers must always pass an opts map (even if `{}`). Rationale: zero callers use the `(ctx ai-model)` path today — every 2-arity call passes `(ai-model opts)` and hits the type-sniffing branch. A convenience arity adds nothing and re-introduces arity ambiguity.

### Test helper accepts full opts map

The test helper (e.g. `bootstrap-fresh-session!`) accepts `(ai-model opts)` and forwards the full opts map. It extracts keys needed by `create-runtime-session-context` (`:cwd`, `:session-config`, `:ui-type`, `:persist?`, `:session-root`, `:thinking-level-override`) and passes remaining opts through to `bootstrap-runtime-session!`. No curated subset — callers pass what they need.

### Test helper lives in `components/app-runtime/test/`

Location: `components/app-runtime/test/psi/app_runtime/test_support.clj` (namespace `psi.app-runtime.test-support`). The `gordian_launcher_manifest_runtime_boundary_test` already depends on the `app-runtime` component, so this works without adding cross-component test dependencies.

### `rpc_real_delegate_command_test` needs no change

This test already calls `(bootstrap-runtime-session! ctx ai-model opts)` — the target 3-arity form. It does not use the 2-arity "create everything" path and does not use the 4-arity session-id path. No modification required.

### Opts key for session-id is `:session-id`

The 4-arity `(ctx session-id ai-model opts)` caller in `main.clj` will become `(ctx ai-model (assoc opts :session-id session-id))`. The opts key is literally `:session-id`, matching the existing binding name in the 4-arity body.

### No `(ctx ai-model)` callers exist — no migration needed

Every 2-arity call passes an ai-model map as the first arg (hitting the `(:state* x)` → false branch). Zero callers pass a ctx as the first arg with ai-model as second. The `(:state* x)` → true branch is dead code. No migration of `(ctx ai-model)` callers is needed — only the `(ai-model opts)` callers need the test helper.

## Acceptance criteria

1. `bootstrap-runtime-session!` has exactly one public arity: `(ctx ai-model opts)` where opts may contain `:session-id` — no convenience 2-arg form
2. No `(:state* x)` type dispatch exists in `bootstrap-runtime-session!`
3. A test-tree helper in `components/app-runtime/test/psi/app_runtime/test_support.clj` wraps `create-runtime-session-context` + `bootstrap-runtime-session!` for the "create everything" use case, accepting the full opts map
4. All production callers (`run-session`, `start-tui-runtime!`, `main.clj`) updated
5. All test callers updated to use the test helper or the new single-arity form
6. `rpc_real_delegate_command_test` requires no change (already uses the target 3-arity form)
7. 301 tests, 0 failures
