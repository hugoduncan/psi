# Implementation Notes

## 2026-06-01 — fix implemented (d16e90286)

Root cause confirmed by tracing the call graph:

- `(:on api) "tool_call"` → `register-handler-in!` → stored in `:extensions ext-path :handlers "tool_call"`
- `dispatch-in "tool_call"` → reads from `:extensions % :handlers "tool_call"` → fires handlers
- `dispatch-tool-call-in` calls `dispatch-in "tool_call"` — but only called from `tool_plan.clj` and `wrap-tool-executor` (dead in interactive path)
- Interactive path: `tool-runtime/core` emits `:tool-start` / `:tool-result` via `on-event` → `emit-tool-lifecycle!` → only dispatches `:session/tool-lifecycle-event` (telemetry ring buffer), never calls `dispatch-in`

Fix: added a `case` branch in `emit-tool-lifecycle!` that calls `ext/dispatch-in` for `:tool-start` → `"tool_call"` and `:tool-result` → `"tool_result"`.

The payload shapes match what the metrics `on-tool-call` / `on-tool-result` handlers expect:
- `on-tool-call` reads `:tool-name` ✓
- `on-tool-result` reads `:tool-name`, `:is-error`, `:content` ✓

Verified: `.psi/metrics.edn` now accumulates `:tools` entries after tool calls in live session.

## 2026-06-01 — design ambiguity review

Three ambiguities found:

1. **Double-dispatch on tool-plan path**: `tool_plan.clj/execute-tool-plan-step-in!` calls
   `dispatch-tool-call-in` / `dispatch-tool-result-in` directly. If plan steps also emit
   `:tool-start` / `:tool-result` lifecycle events through `emit-tool-lifecycle!`, extension
   handlers fire twice. Design claims `emit-tool-lifecycle!` is the single injection point
   but doesn't address this path.

2. **`wrap-tool-executor` status**: Design says it's "unused in the interactive path" but
   doesn't clarify whether it's live on any path. If it is, the new bridge could also create
   double-dispatch there.

3. **`extension-registry` nil guard**: `(when-let [reg (:extension-registry ctx)])` silently
   skips extension dispatch when the registry is absent. Design doesn't state whether absence
   is a valid production state or a test-only artifact.

## 2026-06-01 — ambiguity resolution

1. **Double-dispatch — resolved**: `run-tool-plan-step-in!` calls `dispatch-tool-call-in` /
   `dispatch-tool-result-in` directly and does NOT call `emit-tool-lifecycle!`. The two
   paths are fully disjoint. No double-dispatch. Design updated with explicit path map.

2. **`wrap-tool-executor` — resolved**: Confirmed dead code in production. Defined in
   `extensions.clj`, referenced only in `extensions_test.clj`. No production caller exists
   anywhere in the codebase. No double-dispatch risk.

3. **`extension-registry` nil guard — resolved**: `context.clj` line 277 always sets
   `:extension-registry (ext/create-registry)`. `test_support.clj` line 208 also always
   sets it. Absence is test-only (minimal unit-test ctx bypassing `make-session-ctx`).
   Decision: keep `when-let` guard as-is — asserting presence would break low-level unit
   tests that legitimately omit the registry.
