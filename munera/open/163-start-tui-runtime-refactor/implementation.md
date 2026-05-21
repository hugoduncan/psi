# Implementation log

- Review: `start-tui-runtime!` post tui-wiring extraction is ~100 lines. Three actionable issues identified: dead `ai-ctx` binding, inline nullable execution mode, `:on-new-session!` parameter shadowing.
- `ai-ctx` analysis: traced through the full call chain. `ai-ctx` IS used deep in the stack (`turn_runtime/core.clj`, `stream.clj`) to switch between context-based and standalone AI execution. But the value is always `nil` in both `start-tui-runtime!` and `run-session`. Removed the local binding from both functions; pass `nil` directly at call sites. Left the parameter on deeper functions (`start-new-session-with-startup!`, `new-session-with-startup-in!`) since callers like `main.clj` pass `nil` explicitly. Both are `defn-` (private) but serve as internal entry points for session creation.
- Extracted `maybe-install-nullable-execution-mode` as a named helper before `start-tui-runtime!`. The env-var check + deterministic executor stub was 10 lines of inline closure that obscured the main flow.
- `:on-new-session!` parameter shadowing is intentional: the TUI/CLI always fork from the currently focused session (`@tui-focus*` / `@cli-focus*`), not the session that dispatched the `/new` command. Added intent comments on both closures. `main.clj` correctly uses the parameter for RPC/emacs where focus tracking is different.
- Verification: 39 tests, 195 assertions, 0 failures across app-runtime, bootstrap, nrepl, navigation, and rpc-prompt-command test namespaces. Focused lint clean.

## Implementation review (task-implementation-review)

**Matches design**: all three planned changes delivered — dead `ai-ctx` binding removed, `maybe-install-nullable-execution-mode` extracted, `:on-new-session!` intent documented.

**Observations (no action required)**:
- `cli.clj` functions (`cli-command-opts`, `run-cli-loop!`, `run-cli-prompt!`) still accept and thread `ai-ctx` as a live parameter (not `_ai-ctx`). Consistent with the design's decision to leave parameters on deeper functions, but the acceptance criterion ("all downstream callers updated") reads slightly broader. Acceptable — cli.clj cleanup would widen scope.
- Implementation log says `start-new-session-with-startup!` is "public/internal API" — it is actually `defn-` (private). The decision to keep the parameter is still reasonable; the rationale text is slightly inaccurate.
- `:ai-ctx nil` remains in `session-state` atom resets (both `run-session` and `start-tui-runtime!`). Dead data, but removing it is a separate atom-shape concern.
- `maybe-install-nullable-execution-mode` extraction is clean: clear docstring, returns modified or unmodified `ctx`, no unnecessary abstractions.
- No new patterns that duplicate existing reusable patterns.

## Review follow-up

- Fixed implementation log: corrected "public/internal API" → "private (`defn-`) but serve as internal entry points".
- Removed dead `:ai-ctx nil` from `session-state` atom resets in both `run-session` and `start-tui-runtime!`. Verified no code reads `:ai-ctx` from `session-state` — only `dispatch_effects.clj` reads it from the runtime `ctx` map (different path). All unit tests pass.
