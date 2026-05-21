# Plan — 161 Collapse startup prompt build and tool composition

## Approach

Inline `bootstrap-in!`'s startup responsibilities into `adopt-startup-plan-into-session!` and reorder operations so each concern happens exactly once. The change is entirely within `adopt-startup-plan-into-session!` — no handler changes, no new events.

### Key decisions

1. **`bootstrap-in!` fate**: Retained as-is for test callers. Its call is removed from `adopt-startup-plan-into-session!`; responsibilities are inlined.

2. **Ordering**: Developer-prompt seeding → resource loading (templates + skills only) → manifest extensions → compose tools → register domains → query graph-caps → build prompt → set-system-prompt → set-system-prompt-build-opts → set-active-tools → persist summary → memory sync → register extension run fn → capture rehydrate.

3. **`set-active-tools` after prompt**: Placed after prompt build + build-opts persist so the side-effect `:runtime/refresh-system-prompt` finds build-opts and rebuilds an equivalent prompt (not an empty one).

4. **Tool registration**: Individual `:session/add-tool` dispatches skipped — tools excluded from `load-startup-resources-in!` call. Only `:session/set-active-tools` sets the final tool set.

5. **Summary**: Built locally in `adopt-startup-plan-into-session!` from resource-loading counts + manifest extension results, dispatched once.

## Risks

- Tests that redef `bootstrap-in!` should still pass since those stubs return summary maps and the code no longer calls `bootstrap-in!`. The redefs become inert.
- The `with-main-bootstrap-stubs` helper in `app_runtime_test.clj` redefs `bootstrap-in!` — since we no longer call it, this redef is harmless but should be removed for clarity.
