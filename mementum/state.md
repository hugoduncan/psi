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

- Task 162 collapse-bootstrap-runtime-session-arities created (design.md written):
  - `bootstrap-runtime-session!` has 3 arities (2, 3, 4) conflating two responsibilities
  - 2-arity sniffs `(:state* x)` to decide if arg is ctx or model — confusing
  - 3-arity and 4-arity near-identical (differ only by session-id creation)
  - Plan: merge 3+4 into single `(ctx ai-model opts)` with optional `:session-id` in opts; extract 2-arity to test helper

- Task 161 complete and closed: single-pass startup, `bootstrap-in!` and `refresh-active-tools-in!` removed
- Tasks 160, 159, 151, 145, 140, 139, 138, 136, 134, 130, 128, 125 are complete and closed

## Suggested next step
- Execute task 162: collapse `bootstrap-runtime-session!` arities
- Then: `app_runtime.clj` (788 lines) has further extraction targets: `start-tui-runtime!` monolith, model resolution fns
- Backlog: `149-reload-fixup-inventory-and-safety`, `124-turn-execution-contract-extraction`, `141`/`144`/`147` workflow items

## Latest session notes
- Oriented on bootstrap simplification branch; identified `bootstrap-runtime-session!` arity overload as next target
- 301 tests, 0 failures; `bootstrap.clj` down to 64 lines with single fn `load-startup-resources-in!`
