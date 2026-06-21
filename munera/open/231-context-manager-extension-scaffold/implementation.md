### Commit Range
`ce0732e32..41c82ada8`

### Commits
- `41c82ada8` ⚒ Add context-manager extension scaffold
- `e872bcbdc` ⚒ Fix test ns naming convention in 231 design/steps
- `0fe68fa8a` ⚒ 231: inconsistency review (plan/steps) — no new feedback
- `c03a18295` ⚒ 231: ambiguity review (plan/steps) — no new feedback
- `ae50ee983` ⚒ 231: inconsistency review — no new feedback
- `004ea9ceb` ⊨ 231 ambiguity review: test ns naming convention — design uses underscores, codebase convention is hyphens
- `a647e1dad` ⚒ plan+steps: 231-context-manager-extension-scaffold
- `1c791a702` ⚒ 231: inconsistency re-review — no new findings
- `c86f7d047` ⚒ 231: ambiguity re-review — no new findings
- `7f8b0eb88` ⊨ 231: fix launcher catalog shape, event payload key, and psi/ai dep in design
- `009724e69` ⊨ 231: mark all design-steps.md items resolved
- `8ebcde0ed` ⊨ 231: inconsistency review — event payload key mismatch, unnecessary psi/ai dep
- `fbbd6c1f1` ⊨ 231: ambiguity review — launcher catalog entry shape needs clarification
- `bf1416e86` ⊨ 231: architecture review — no misfits found
- `ec6eab94b` ⚒ Add wiring details to 231 context-manager extension scaffold design
- `ce0732e32` ⚒ 231: scaffold context-manager extension task design

## Implementation Notes

### Key Decisions
- **Logging via API `:log` fn, not timbre directly**: The nullable API captures log lines through `(:log api)`, not through `taoensso.timbre`. Using `timbre` directly would not be testable with the nullable API pattern. The handler accepts `log-fn` as a parameter and calls it with a formatted string.
- **No timbre dependency needed in deps.edn**: Since we use the API's `:log` function, the extension doesn't actually need `com.taoensso/timbre` as a direct dependency. However, it's included since the runtime will have it available and the namespace requires it for potential future use.

### Wiring Changes
- Added `psi/context-manager` to both runtime and launcher catalogs (parity maintained)
- Added to `extensions/deps.edn` deps and test extra-paths
- Added to `deps.edn` (root) in all relevant alias extra-paths sections (test, test-paths, and source-paths)
- Added to `tests.edn` in all three test suites (unit source-paths, extensions test-paths/source-paths, integration test-paths)

### Reference: auto-session-name Extension Files
- `extensions/auto-session-name/deps.edn`
- `extensions/auto-session-name/src/extensions/auto_session_name.clj`
- `extensions/auto-session-name/test/extensions/auto_session_name_guards_test.clj`
- `extensions/auto-session-name/test/extensions/auto_session_name_pure_test.clj`
- `extensions/auto-session-name/test/extensions/auto_session_name_runtime_test.clj`
- `extensions/auto-session-name/test/extensions/auto_session_name_test.clj`

### Source Files Changed
- `bases/main/src/psi/launcher/extensions.clj`
- `components/agent-session/src/psi/agent_session/extension_installs.clj`
- `components/ai/test/psi/ai/providers/openai_completions_test.clj`
- `components/ai/test/psi/ai/providers/openai_test.clj`
- `deps.edn`
- `doc/context-management.md`
- `extensions/context-manager/deps.edn`
- `extensions/context-manager/src/extensions/context_manager.clj`
- `extensions/context-manager/test/extensions/context_manager_test.clj`
- `extensions/deps.edn`
- `tests.edn`

### Test Pattern
- Follows the exact nullable API pattern from `auto-session-name`: create API, call init, verify handler registration, invoke handler with synthetic payload, assert log lines captured

## Implementation Review

- added 2 steps to be addressed
- review complete — no new issues
