# Steps

- [x] Diagnose: trace why `:tools {}` is always empty in metrics.edn
- [x] Identify root cause: `dispatch-in "tool_call"/"tool_result"` never called on interactive path
- [x] Fix: bridge `:tool-start`/`:tool-result` lifecycle events → extension `dispatch-in` in `emit-tool-lifecycle!`
- [x] Lint: `clj-kondo` clean on changed file
- [x] Test: focused tool execution + extensions tests pass (`d16e90286`)
- [ ] Close task
