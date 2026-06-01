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

### `tool_result` cross-path payload shape (unified)

Both paths that fire the `"tool_result"` extension bus event now deliver the
same key set, including `:input` (the parsed tool args):

- Data-driven plan path: `dispatch-tool-result-in` — already emitted `:input`.
- Interactive/batch bridge: `emit-tool-lifecycle!` — now emits `:input`,
  sourced from `:parsed-args` on the `:tool-result` lifecycle event.

To make this possible, `tool-runtime/core` `record-tool-call-result!` now
threads `:parsed-args` (from the shaped result's `:tool-call`) into the
`:tool-result` lifecycle event. This unifies the extension contract so a
`tool_result` handler reading `:input` behaves identically regardless of which
path triggered the event (`consistent(data_shapes)`; chosen resolution (a) over
drop-`:input` (b) or document-divergence (c) — addition preserves both existing
consumers and removes the silent-`nil` hazard).

`:content` value shape is also unified to canonical content-blocks on both
paths. The interactive/batch bridge already emits `:content` as normalized
content-blocks (`(:content result-message)`). The plan path's
`dispatch-tool-result-in` previously emitted the raw, un-normalised
`(:content result)` (e.g. a plain string), so the same bus event carried a
different value shape per path. `dispatch-tool-result-in` now coerces its
`:content` via `tool-runtime/normalize-tool-content`, so a `tool_result`
handler reading `:content` sees the canonical `[{:type :text :text …}]` block
vector regardless of triggering path (chosen resolution: normalise-by-addition
over document-divergence — eliminates the path-dependent value hazard rather
than codifying it; the change is idempotent for the already-normalised
interactive path).

`:is-error` value type is also unified to a strict boolean on both paths.
The interactive/batch bridge already emits `:is-error (boolean …)`. The plan
path's `dispatch-tool-result-in` previously passed its `is-error?` argument
through raw, and `run-tool-plan-step-in!` supplies the uncoerced
`(:is-error result)`, which can be `nil`. The same bus event therefore carried
`:is-error` as a strict boolean on one path and a possibly-`nil` value on the
other. `dispatch-tool-result-in` now coerces `:is-error (boolean is-error?)`,
so a `tool_result` handler sees a strict boolean regardless of triggering path
(chosen resolution: coerce-by-addition for symmetry with the `:input`/`:content`
unifications and `one_way ¬ambiguity`; idempotent for the already-boolean
interactive path).

The `:input`/`:content`/`:is-error` value alignments above each closed one
field of a per-field divergence class whose **structural** cause was payload
shape duplicated across the two paths: `emit-tool-lifecycle!`
(`tool_runtime_adapter.clj`) hand-built both bus payloads inline, duplicating
the canonical `dispatch-tool-call-in`/`dispatch-tool-result-in` constructors
(`extensions.clj`, also used by the plan path). Any field added to one
constructor would reopen the divergence. The payload shape (and value
coercions) is now single-sourced in two builders in `extensions.clj` —
`tool-call-event` and `tool-result-event` — called by both the
`dispatch-tool-*-in` constructors and the `emit-tool-lifecycle!` bridge
(chosen resolution (b) shared-builder over route-through-`dispatch-tool-*-in`
(a) — the bridge needs the payload, not the dispatch-return semantics, so a
pure builder is the smaller, more orthogonal seam; `consistent(idioms)`/DRY,
`robust → enforceable(invariants)` — the contract is now defined once). The
bridge still discards the dispatch return value (interactive-path
non-enforcement, documented). A `tool-event-payload-constructors-test` pins the
canonical shape and the content/is-error coercions as the cross-path guard.

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
