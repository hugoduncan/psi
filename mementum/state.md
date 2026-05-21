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

- Bootstrap simplification arc (159–163) complete:
  - 159: in-process bootstrap simplification
  - 160: removed mutation-mediated bootstrap resource loading
  - 161: single-pass startup, `bootstrap-in!` and `refresh-active-tools-in!` removed
  - 162: `bootstrap-runtime-session!` collapsed to single `(ctx ai-model opts)` arity
  - 163: `start-tui-runtime!` refactored — dead `ai-ctx` removed, nullable exec mode extracted
- Tasks 151, 145, 140, 139, 138, 136, 134, 130, 128, 125 also complete and closed

## Test health

bb tests ✅. 5 former test errors fixed (commit 0b37b83f: NPE on nil session-file, SOE in git resolvers). Task 158 addressed persistence test garbage (still open but test-review showed no actionable feedback).

## Suggested next step
- Backlog: `105-agent-session-component-extraction-map`, `124-turn-execution-contract-extraction`, `149-reload-fixup-inventory-and-safety`, `141`/`144`/`147` workflow items

## Latest session notes
- 2026-05-20: oriented on bootstrap-simplification branch; 159–163 arc confirmed complete; test errors confirmed fixed
