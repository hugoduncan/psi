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

- Task 162 complete and closed: `bootstrap-runtime-session!` collapsed to single `(ctx ai-model opts)` arity with `:session-id` in opts; 2-arity type-sniffing dispatch removed; test helper `psi.app-runtime.test-support/bootstrap-fresh-session!` created for "create everything from ai-model + opts" pattern; all callers migrated.

- Task 161 complete and closed: single-pass startup, `bootstrap-in!` and `refresh-active-tools-in!` removed
- Tasks 160, 159, 151, 145, 140, 139, 138, 136, 134, 130, 128, 125 are complete and closed

## Suggested next step
- `app_runtime.clj` further extraction targets: `start-tui-runtime!` monolith, model resolution fns
- Backlog: `149-reload-fixup-inventory-and-safety`, `124-turn-execution-contract-extraction`, `141`/`144`/`147` workflow items

## Latest session notes
- 162 implemented in 3 steps: (1) collapse 3+4 arities, (2) create test helper, (3) migrate 2-arity callers and remove 2-arity
- 301 bb tests + 171 Kaocha tests, 0 failures throughout
- `bootstrap-runtime-session!` is now a clean single-arity fn: `(ctx ai-model opts)`
- `bootstrap.clj` remains at 64 lines
