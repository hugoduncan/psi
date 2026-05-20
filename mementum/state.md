# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — project meta model
- `munera/plan.md` — active task orchestration
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state

- Task 160 remove-mutation-mediated-bootstrap-resource-loading is complete and closed:
  - Core rewrite: `load-startup-resources-via-mutations-in!` → `load-startup-resources-in!`
  - Direct `session/dispatch-in!` for templates, skills, tools; direct `ext-rt/add-extension-in!` for extensions
  - Removed throwaway query-context, `run-mutation-in!`, requires for `mutations` and `query.core`
  - `:mutations` key removed from summary, schema, resolvers; tests updated
  - Review-implementation confirmed: no remaining actionable feedback; two unchecked steps (18, 19) are optional refactoring follow-ons (not correctness)
  - 61 focused tests, 397 assertions, 0 failures; lint clean

- Tasks 159, 151, 145, 140, 139, 138, 136, 134, 130, 128, 125 are complete and closed

## Suggested next step
- Bootstrap simplification continues — main remaining complexity in `psi.app-runtime/adopt-startup-plan-into-session!`:
  - Two-stage system-prompt build (base prompt persisted, then rebuilt after graph-capability + extension tool refresh)
  - Duplicate active-tool composition: `bootstrap-in!` has `refresh-active-tools-in!` (disabled), then `adopt-startup-plan-into-session!` does its own `merge-tool-defs-by-name` + `set-active-tools` dispatch
  - `bootstrap-in!` and `adopt-startup-plan-into-session!` overlap on startup-summary persistence
  - Consider: can `adopt-startup-plan-into-session!` delegate more to `bootstrap-in!` or be simplified now that bootstrap uses direct dispatch?
- `149-reload-fixup-inventory-and-safety` — reload correctness
- `124-turn-execution-contract-extraction` — component extraction
- `141`, `144`, `147` — workflow architecture items

## Latest session notes
- Reopened task 160, executed the core rewrite (steps 1–5, 12, 14) that had been left undone
- Review-implementation workflow confirmed completion — no new actionable issues
