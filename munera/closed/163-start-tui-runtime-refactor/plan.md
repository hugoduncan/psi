# Plan

Three surgical changes to `start-tui-runtime!`, each independently verifiable:

1. **Remove dead `ai-ctx` binding** — trace all uses, remove the binding, pass `nil` directly at call sites or remove the parameter where the callee also ignores it.

2. **Extract nullable execution mode** — move the `PSI_NULLABLE_EXECUTION_MODE` env-var check and deterministic executor construction to a named helper.

3. **Fix `:on-new-session!` parameter shadowing** — the closure receives `_source-session-id` but ignores it, reading `@tui-focus*` instead. Either use the parameter or rename to clarify intent.

## Risks
- `ai-ctx` removal may touch callers outside `app_runtime.clj` (e.g. `start-new-session-with-startup!`, `new-session-with-startup-in!`). Trace before removing.
- The `:on-new-session!` parameter may be intentionally ignored — verify the callback contract before changing behavior.
