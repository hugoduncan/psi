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
