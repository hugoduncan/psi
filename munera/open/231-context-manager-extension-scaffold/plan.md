# Plan — Context Manager Extension Scaffold

## Approach

Create a minimal extension scaffold following the `auto-session-name` pattern exactly:
same directory structure, same `(:on api)` event subscription pattern, same nullable-API
test pattern. The extension does nothing beyond logging `session_turn_finished` events.

Key decisions:
- Namespace: `extensions.context-manager` (kebab, matching dir name)
- No `psi/ai` dependency — scaffold only logs, no AI calls needed
- No manifest `:allowed-events` — same as `auto-session-name`
- Single test verifying handler registration and firing on synthetic event
- Both catalogs (runtime + launcher) must be updated in parity

## Risks

- **Catalog parity test**: Adding to one catalog but not the other breaks the
  `psi-owned-extension-catalog-parity-with-launcher` test. Must update both atomically.
- **Extension deps.edn test alias**: Must include `psi/extension-test-helpers` and
  `psi/agent-session` for the nullable API pattern to work.
- **Top-level extensions/deps.edn**: Must add the lib to `:deps` and the test path
  to the `:test` alias `:extra-paths`, or `bb test` won't find it.

## Slice Order

1. **Extension scaffold** — directory, deps.edn, namespace with init + handler
2. **Wiring** — runtime catalog, launcher catalog, top-level deps.edn
3. **Test** — nullable API test verifying handler registration and firing
4. **Verify** — lint, test, confirm clean load
