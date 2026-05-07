# Plan

Approach:
- extract the namespace as a narrow structural move, not a semantic redesign
- create `components/turn-statechart/` with authoritative namespace `psi.turn_statechart.core`
- move the current implementation and focused tests into the new component first
- update all direct consumers in the same slice so the new namespace becomes authoritative immediately
- allow a temporary `psi.agent-session.turn-statechart` shim only if needed to keep the migration incremental, but remove it before task completion
- keep API shape stable unless a rename is required solely by the new namespace/component boundary

Consumer migration set:
- known direct production consumers:
  - `components/agent-session/src/psi/agent_session/turn_accumulator.clj`
  - `components/agent-session/src/psi/agent_session/prompt_stream.clj`
  - `components/agent-session/src/psi/agent_session/prompt_runtime.clj`
  - `components/agent-session/src/psi/agent_session/dispatch_effects.clj`
  - `components/agent-session/src/psi/agent_session/resolvers/telemetry.clj`
- direct test consumers and test helpers requiring the old namespace
- completion requires a final repo search confirming no remaining `psi.agent-session.turn-statechart` requires/usages remain

Implementation sequence:
1. create the new component directories and destination namespace/file
2. update project/test configuration so `components/turn-statechart/` participates in normal source and test paths
3. move `turn_statechart.clj` implementation to `psi.turn_statechart.core`
4. move focused statechart tests to the new component test tree and update requires
5. update all direct production/test consumers to require `psi.turn_statechart.core`
6. if an intermediate shim is needed during editing, keep it temporary and remove it before verification/finish
7. run focused verification for the extracted component and at least one higher-level consuming path
8. record final ownership and any migration notes in `implementation.md`

Configuration changes expected in this slice:
- `deps.edn`
  - add local component dep for `psi/turn-statechart`
  - add `components/turn-statechart/src` and `components/turn-statechart/test` in any explicit alias path lists required for normal test execution (at minimum the `:test` alias, and others only if needed)
- `tests.edn`
  - add `components/turn-statechart/src`
  - add `components/turn-statechart/test`
- `tests-component-isolated.edn`
  - optional only; not required for completion
  - update only if this slice adds a dedicated isolated component test run

Verification intent:
- focused statechart tests from the new component location
- focused agent-session consumers that compile and still exercise the migrated dependency path
- no behavior changes beyond namespace/component ownership and imports
- focused commands after migration:
  - `clojure -M:test --focus psi.turn-statechart.core-test`
  - `clojure -M:test --focus psi.turn-statechart.core-test --focus psi.agent-session.turn-accumulator-test --focus psi.agent-session.prompt-execution-test`

Risk notes:
- the main risk is incomplete consumer migration, since task completion requires all direct consumers to move in this slice
- a lingering shim would hide ownership drift, so it must be removed before completion
- avoid opportunistic cleanup in `turn-accumulator` or prompt lifecycle code; this task is only about component extraction of the statechart namespace
