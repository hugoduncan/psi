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

This is the single correct injection point: all tool executions (interactive,
batch, background) pass through `emit-tool-lifecycle!`.

## Scope

- One function change: `psi.agent-session.tool-runtime-adapter/emit-tool-lifecycle!`
- Add `[psi.agent-session.extensions :as ext]` require
- No schema changes, no new files, no other components touched

## Acceptance Criteria

- After the fix, tool invocations appear in `:tools` in `.psi/metrics.edn`
- Tool errors (`:is-error true`) increment `:errors` counters
- Existing extension tests pass (no regressions on tool blocking/override)
- `clj-kondo` clean on changed file
