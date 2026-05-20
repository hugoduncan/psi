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

## Test health

301 bb tests ✅, ~1062 Kaocha tests with **5 real errors** (exit 0 — Kaocha treats errors as non-fatal):

### NPE trio (`:session-file` nil → `File.<init>` NPE)
Tests need persistence for `flush-journal!`/`/resume` but context defaults to `:persist? false` via `safe-context-opts`:
- `resolvers_test/multi-session-context-eql-process-and-persisted-test` (line 202)
- `rpc_anthropic_regression_test/rpc-resume-session-rehydrates-agent-messages-not-tui-projection-test` (line 44)
- `rpc_session_navigation_test/rpc-session-resume-and-rehydrate-events-test` (line 101)
Fix: pass `{:cwd cwd :persist? true :session-root cwd}` to context helpers.

### StackOverflow duo
- `resolvers_test/git-history-commits-query-test` — queries `:git.repo/commits` against real repo; commit data overflows on print
- `graph_surface_test/root-queryable-attrs-contract-test` — per-attr root query hits a graph-introspection attr with pathological expansion

## Suggested next step
- Fix the 5 test errors (two distinct bugs: persistence opt-in, stack overflow on graph data)
- `app_runtime.clj` further extraction targets: `start-tui-runtime!` monolith, model resolution fns
- Backlog: `149-reload-fixup-inventory-and-safety`, `124-turn-execution-contract-extraction`, `141`/`144`/`147` workflow items

## Latest session notes
- 2026-05-19: oriented, ran full suite, diagnosed 5 test errors across 2 root causes
