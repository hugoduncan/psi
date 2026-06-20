## Implementation Notes

### Key Decisions
- **Logging via API `:log` fn, not timbre directly**: The nullable API captures log lines through `(:log api)`, not through `taoensso.timbre`. Using `timbre` directly would not be testable with the nullable API pattern. The handler accepts `log-fn` as a parameter and calls it with a formatted string.
- **No timbre dependency needed in deps.edn**: Since we use the API's `:log` function, the extension doesn't actually need `com.taoensso/timbre` as a direct dependency. However, it's included since the runtime will have it available and the namespace requires it for potential future use.

### Wiring Changes
- Added `psi/context-manager` to both runtime and launcher catalogs (parity maintained)
- Added to `extensions/deps.edn` deps and test extra-paths
- Added to `deps.edn` (root) in all relevant alias extra-paths sections (test, test-paths, and source-paths)
- Added to `tests.edn` in all three test suites (unit source-paths, extensions test-paths/source-paths, integration test-paths)

### Test Pattern
- Follows the exact nullable API pattern from `auto-session-name`: create API, call init, verify handler registration, invoke handler with synthetic payload, assert log lines captured
