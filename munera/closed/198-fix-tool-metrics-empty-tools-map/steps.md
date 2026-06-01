# Steps

- [x] Diagnose: trace why `:tools {}` is always empty in metrics.edn
- [x] Identify root cause: `dispatch-in "tool_call"/"tool_result"` never called on interactive path
- [x] Fix: bridge `:tool-start`/`:tool-result` lifecycle events → extension `dispatch-in` in `emit-tool-lifecycle!`
- [x] Lint: `clj-kondo` clean on changed file
- [x] Test: focused tool execution + extensions tests pass (`d16e90286`)
- [x] Add integration test: register a `"tool_call"` handler on the session ctx's `extension-registry`, call `run-tool-call!`, assert handler was invoked (regression guard for the `emit-tool-lifecycle!` bridge)
- [x] Add test: assert `{:block true}` from a `"tool_call"` handler on the interactive path does NOT block execution (documents intentional non-enforcement)
- [x] Close task
- [ ] Add end-to-end test: register the metrics extension on a real session ctx (via `ext/init`), call `run-tool-call!`, and assert `ext/store` accumulates a `:tools` entry — covers the full path (adapter → bridge → metrics handler → store) as a single regression guard for the primary acceptance criterion
- [ ] Fix e2e test so it actually runs: `metrics-extension-accumulates-tools-via-bridge-test` is silently skipped because `extensions/metrics/src` is missing from kaocha's `:unit` suite `:source-paths` in `tests.edn` (focus on it → "all tests skipped"; suite count is 11 not 12). Add `extensions/metrics/{src,test}` to the `:unit` suite paths OR relocate the test to the metrics/`:extensions` suite, then verify it executes and passes.
- [ ] Commit the uncommitted working-tree changes (the e2e test in `tool_execution_test.clj`) or decide and document why they are dropped — task is closed but the final test artifact is not in git.
- [ ] Resolve the unrelated `.clj-kondo/config.edn` change (new `:discouraged-var` for `clojure.test/use-fixtures`): it is out of scope for task 198 — either commit it under its own task/change or revert it.
- [ ] Fix clj-kondo warnings in `tool_execution_test.clj`: remove the unused `use-fixtures` referral (line 5) and resolve the `inline def` warning at the e2e deftest.
