# Steps

- [x] Diagnose: trace why `:tools {}` is always empty in metrics.edn
- [x] Identify root cause: `dispatch-in "tool_call"/"tool_result"` never called on interactive path
- [x] Fix: bridge `:tool-start`/`:tool-result` lifecycle events → extension `dispatch-in` in `emit-tool-lifecycle!`
- [x] Lint: `clj-kondo` clean on changed file
- [x] Test: focused tool execution + extensions tests pass (`d16e90286`)
- [x] Add integration test: register a `"tool_call"` handler on the session ctx's `extension-registry`, call `run-tool-call!`, assert handler was invoked (regression guard for the `emit-tool-lifecycle!` bridge)
- [x] Add test: assert `{:block true}` from a `"tool_call"` handler on the interactive path does NOT block execution (documents intentional non-enforcement)
- [x] Close task
- [x] Add end-to-end test: register the metrics extension on a real session ctx (via `ext/init`), call `run-tool-call!`, and assert `ext/store` accumulates a `:tools` entry — covers the full path (adapter → bridge → metrics handler → store) as a single regression guard for the primary acceptance criterion
- [x] Fix e2e test so it actually runs: added `extensions/metrics/src` to the `:unit` suite `:source-paths` in `tests.edn` so the suite explicitly declares the metrics namespace. Verified: `clojure -M:test --focus psi.agent-session.tool-execution-test` → **12 tests, 61 assertions, 0 failures**, stable across repeated runs. The earlier "11 tests" count is no longer reproducible — the committed test (`4630d40c0`) loads and executes the e2e deftest under the standard test command.
- [x] Commit the uncommitted working-tree changes: already committed in `4630d40c0` ("⚒ 198: add end-to-end test"). No uncommitted test change existed at the start of this pass.
- [x] Resolve the unrelated `.clj-kondo/config.edn` change — MOOT: the final committed test (`4630d40c0`) never introduced it. Working tree is clean of any `.clj-kondo/config.edn` change.
- [x] Fix clj-kondo warnings in `tool_execution_test.clj` — MOOT: the committed test avoids the `use-fixtures` referral (`:refer [deftest testing is]`) and uses a quoted `'psi.metrics.extension/init` symbol (no inline-def warning). `clj-kondo --lint` on the file → 0 errors, 0 warnings.
