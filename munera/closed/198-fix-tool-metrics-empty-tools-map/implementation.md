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

## 2026-06-01 — inconsistency review

Two inconsistencies found:

1. **"All tool executions" claim contradicts disjoint-paths clarification**: Fix section
   states "all tool executions (interactive, batch, background) pass through
   `emit-tool-lifecycle!`". Clarifications section states the plan path "does NOT route
   through `emit-tool-lifecycle!`" and the two paths are "disjoint". These are directly
   contradictory within design.md. The Fix section claim should be scoped to
   "interactive/batch" only.

2. **Acceptance criterion "no regressions on tool blocking/override" is ambiguous**: The
   bridge calls `dispatch-in` directly (not `dispatch-tool-call-in`), so `{:block true}`
   handler returns are silently ignored on the interactive/batch path. The design does not
   state whether this is intentional. The criterion is vacuously satisfied (existing
   blocking tests cover the plan path only), but a reader could infer blocking is preserved
   on the interactive path too.

## 2026-06-01 — implementation review

**Fix correct.** `emit-tool-lifecycle!` change is minimal, single-responsibility, and
matches the payload shapes consumed by `on-tool-call`/`on-tool-result`. Changelog entry
present. Design, plan, and code are coherent.

**Gap: no regression test for the bridge itself.**
`tool_execution_test.clj` exercises `run-tool-call!` but no test registers a `"tool_call"`
or `"tool_result"` handler on the `extension-registry` in the session ctx and asserts it
fires. The new `case` branch in `emit-tool-lifecycle!` has zero unit coverage at the
integration point — the fix could be silently reverted without any test failing.

The metrics `extension_test.clj` tests fire events directly via `fire-event` (bypassing the
adapter), so they don't cover the bridge either.

**Minor: `{:block true}` silently ignored on interactive path — untested.**
Design documents this intentional asymmetry (blocking only enforced on the plan path) but no
test asserts the non-blocking behavior, leaving it unguarded against future accidental
enforcement.

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
