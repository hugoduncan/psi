# Steps

- [x] Diagnose: trace why `:tools {}` is always empty in metrics.edn
- [x] Identify root cause: `dispatch-in "tool_call"/"tool_result"` never called on interactive path
- [x] Fix: bridge `:tool-start`/`:tool-result` lifecycle events → extension `dispatch-in` in `emit-tool-lifecycle!`
- [x] Lint: `clj-kondo` clean on changed file
- [x] Test: focused tool execution + extensions tests pass (`d16e90286`)
- [ ] Add integration test: register a `"tool_call"` handler on the session ctx's `extension-registry`, call `run-tool-call!`, assert handler was invoked (regression guard for the `emit-tool-lifecycle!` bridge)
- [ ] Add test: assert `{:block true}` from a `"tool_call"` handler on the interactive path does NOT block execution (documents intentional non-enforcement)
- [ ] Close task
