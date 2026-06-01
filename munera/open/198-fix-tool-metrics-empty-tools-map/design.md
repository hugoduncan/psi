# 198 Fix: tool metrics :tools map always empty

## Intent

The `psi/metrics` extension persists usage counters to `.psi/metrics.edn`.
The `:tools` map was always `{}` despite tools being called, because the
`"tool_call"` and `"tool_result"` extension bus events were never fired
during interactive tool execution.

## Root Cause

The interactive tool execution path is:

```
tool-runtime-adapter/emit-tool-lifecycle!
  → dispatch :session/tool-lifecycle-event   (telemetry ring buffer)
```

The extension handler dispatch path is:

```
extensions/dispatch-in "tool_call" / "tool_result"
  → fires handlers registered via (:on api)
```

`dispatch-tool-call-in` / `dispatch-tool-result-in` — the only callers of
`dispatch-in` for `"tool_call"` / `"tool_result"` — were only invoked from
`tool_plan.clj` (data-driven tool plans) and `wrap-tool-executor` (unused
in the interactive path). The metrics extension's `on-tool-call` and
`on-tool-result` handlers were therefore never called.

## Fix

Bridge the lifecycle event path to the extension handler dispatch in
`emit-tool-lifecycle!`:

- `:tool-start` lifecycle event → `dispatch-in "tool_call"`
- `:tool-result` lifecycle event → `dispatch-in "tool_result"`

This is the single correct injection point for the interactive/batch path:
all interactive and batch tool executions pass through `emit-tool-lifecycle!`.
(The data-driven plan path is disjoint and retains its own direct dispatch.)

## Scope

- One function change: `psi.agent-session.tool-runtime-adapter/emit-tool-lifecycle!`
- Add `[psi.agent-session.extensions :as ext]` require
- No schema changes, no new files, no other components touched

## Clarifications

### Double-dispatch on tool-plan path

`run-tool-plan-step-in!` in `tool_plan.clj` calls `ext/dispatch-tool-call-in` /
`ext/dispatch-tool-result-in` directly — it does **not** route through
`emit-tool-lifecycle!`. The two paths are disjoint:

- Interactive/batch path: `emit-tool-lifecycle!` → new `ext/dispatch-in` bridge
- Data-driven plan path: `run-tool-plan-step-in!` → `dispatch-tool-call-in` / `dispatch-tool-result-in`

No double-dispatch occurs. `emit-tool-lifecycle!` is the single correct
injection point for the interactive/batch path only; the plan path retains
its own direct dispatch.

### `wrap-tool-executor` status

`wrap-tool-executor` is dead code in production. It is defined in
`extensions.clj` and exercised only in `extensions_test.clj`. No production
caller exists. The new `emit-tool-lifecycle!` bridge creates no double-dispatch
through this function.

### `extension-registry` nil guard

`context.clj` always sets `:extension-registry (ext/create-registry)` in
production ctx construction. `test_support.clj` also always sets it. Absence
of `:extension-registry` in ctx is **not a valid production state** — it can
only arise in minimal unit-test contexts that bypass `make-session-ctx`. The
`when-let` guard is defensive/test-safe; it is acceptable to keep it as-is
rather than asserting, since asserting would break low-level unit tests that
legitimately omit the registry.

## Acceptance Criteria

- After the fix, tool invocations appear in `:tools` in `.psi/metrics.edn`
- Tool errors (`:is-error true`) increment `:errors` counters
- Existing extension tests pass (no regressions on tool blocking/override on the
  plan path; blocking is intentionally not enforced on the interactive/batch path
  because the bridge calls `dispatch-in` directly and `{:block true}` returns are
  silently ignored there)
- `clj-kondo` clean on changed file
