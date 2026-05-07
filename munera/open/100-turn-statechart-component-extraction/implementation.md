Completed.

Settled target:
- component path: `components/turn-statechart/`
- namespace family: `psi.turn-statechart.*`
- authoritative namespace: `psi.turn-statechart.core`
- authoritative source file: `components/turn-statechart/src/psi/turn_statechart/core.clj`

Implementation notes:
- created new component `components/turn-statechart/` with its own `deps.edn`
- moved the authoritative turn statechart implementation from `components/agent-session/src/psi/agent_session/turn_statechart.clj` to `components/turn-statechart/src/psi/turn_statechart/core.clj`
- authoritative namespace now uses kebab-case: `psi.turn-statechart.core`
- moved the focused statechart test to `components/turn-statechart/test/psi/turn_statechart/core_test.clj`
- renamed the focused test namespace to `psi.turn-statechart.core-test`
- updated all known direct production consumers to require `psi.turn-statechart.core`
- updated direct test consumers to require `psi.turn-statechart.core`
- added the new component as a local dep in the repo root `deps.edn`
- added new source/test paths in root `deps.edn` aliases and `tests.edn`
- added `psi/turn-statechart` as a component dep of `components/agent-session/deps.edn`
- no compatibility shim was kept; the old `psi.agent-session.turn-statechart` namespace was removed in the same slice
- `tests-component-isolated.edn` did not require change for this slice

Verification:
- `clojure -M:test --focus psi.turn-statechart.core-test`
  - green: `13 tests, 62 assertions, 0 failures`
- `clojure -M:test --focus psi.agent-session.turn-accumulator-test --focus psi.agent-session.prompt-execution-test`
  - green: `44 tests, 131 assertions, 0 failures`
- final repo search confirms no remaining `psi.agent-session.turn-statechart` code requires/usages remain outside task-planning documents

Final ownership:
- lower-level per-turn stream statechart ownership now lives below `agent-session` in `components/turn-statechart/`
- higher-level prompt/session orchestration remains in `agent-session`

Review note:
- follow-up review findings addressed: removed duplicate `components/turn-statechart/src` in root `deps.edn :run`, aligned `design.md` + `plan.md` with the corrected kebab-case authoritative namespace `psi.turn-statechart.core`, and reran focused verification green (`57 tests, 193 assertions, 0 failures`)
- code-shaper follow-up completed: split the component into `data`, `chart`, and `runtime` namespaces behind the stable `core` facade; documented that `:thinking-blocks` and `:stop-reason` are intentionally shared turn-data shape; narrowed `send-event!` to return a component-level snapshot instead of raw working memory; and reran focused verification green (`57 tests, 193 assertions, 0 failures`)
- review follow-up completed: replaced `core` facade `def` re-exports with explicit API functions/docstrings for richer public-surface introspection, added a focused test that locks in the narrowed `send-event!` return contract, and reran focused verification green (`58 tests, 197 assertions, 0 failures`)
