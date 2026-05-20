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

- Task 161 collapse-startup-prompt-and-tool-composition is complete and closed:
  - Rewrote `adopt-startup-plan-into-session!` to single-pass startup
  - System prompt built once (after graph-caps + extension tools known), persisted once via `:session/set-system-prompt`
  - Tool set composed and dispatched once via `:session/set-active-tools` (placed after build-opts persist so side-effect `refresh-system-prompt` rebuilds correctly)
  - Summary built and persisted once with complete information
  - Removed `finalize-startup-system-prompt!` (logic inlined)
  - Removed `bootstrap-in!` call from startup flow; retained as test-oriented convenience
  - Removed 6 now-inert `bootstrap-in!` redefs from test files + unused requires
  - Dispatch counts verified per acceptance criteria
  - 301 tests, 0 failures; lint clean

- Tasks 160, 159, 151, 145, 140, 139, 138, 136, 134, 130, 128, 125 are complete and closed

## Suggested next step
- Bootstrap simplification continues — remaining areas:
  - `bootstrap-in!` itself is now only used by tests; consider whether it should be slimmed or removed
  - `refresh-active-tools-in!` in bootstrap.clj is unused in startup (tools excluded from `load-startup-resources-in!` call); may be dead code
- `149-reload-fixup-inventory-and-safety` — reload correctness
- `124-turn-execution-contract-extraction` — component extraction
- `141`, `144`, `147` — workflow architecture items

## Latest session notes
- Implemented task 161: single-pass startup that collapses 4 prompt persists → 1+1 side-effect, 2 tool paths → 1, 2 summary persists → 1
