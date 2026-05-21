# Task 163 — start-tui-runtime! refactor

## Intent
Reduce complexity and improve clarity of `start-tui-runtime!` by removing dead code, extracting inline concerns, and fixing a subtle parameter-shadowing issue.

## Context
The tui-wiring extraction (commit 09038987) moved callback construction and options assembly out of `start-tui-runtime!`, reducing it from 183 to ~100 lines. The function is now primarily runtime setup + delegation, but several inline concerns remain that reduce clarity:

- `ai-ctx` is bound to `nil` and threaded through multiple call sites — dead binding
- `nullable-execution-mode` env-var handling is a 10-line inline closure for a dev/test concern
- The `:on-new-session!` closure ignores its `_source-session-id` parameter and reads `@tui-focus*` instead — parameter shadowing that obscures intent
- `session-state` global atom reset is inlined in the let binding

## Scope
In scope:
- Remove the dead `ai-ctx` binding and its downstream references
- Extract nullable execution mode handling to a named helper
- Fix the `:on-new-session!` parameter shadowing
- Keep `session-state` reset inline (it's a single expression, extraction would add ceremony without clarity)

Out of scope:
- Structural unification of `start-tui-runtime!` and `run-session` (separate task)
- Arity consolidation to map-based args (separate task)
- Further tui-wiring decomposition

## Acceptance
- `ai-ctx` binding removed; all downstream callers updated to pass `nil` directly or have the parameter removed
- Nullable execution mode handling extracted to a named helper
- `:on-new-session!` closure uses its parameter correctly or documents why it ignores it
- All existing tests pass
- No behavioral change
