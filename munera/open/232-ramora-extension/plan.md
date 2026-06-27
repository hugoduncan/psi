# 232-ramora-extension — Plan

## Approach

Mirror the munera/mementum extension pattern exactly: a single `extensions.ramora` namespace that reads a `protocol.txt` resource at init, queries prompt-mode via the nullable API, and registers a prompt contribution with id `"ramora-protocol"`, section `"Ramora Protocol"`, priority 52.

Key decisions:
- Single resource file `resources/extensions/ramora/protocol.txt` containing lambda-form protocol text (placeholder content for now)
- Static-at-init content: `prompt-content` computed once during `read-protocol-resource` at var-creation time; runtime prompt-mode changes do not trigger updates
- Engage prefix identical to munera/mementum
- No resolvers, mutations, or operations — prompt-contribution-only
- Catalog registrations in both launcher and runtime, parity test enforces sync
- `extensions/deps.edn` gets `psi/ramora` in `:deps` only (not `:extra-paths`)

## Risks

- **Parity test**: `psi-owned-extension-catalog-parity-with-launcher` will fail if launcher and runtime catalogs are not updated together. Must add to both in one commit.
- **Resource classpath**: `protocol.txt` must be on the classpath under `extensions/ramora/protocol.txt`. The extension's own `deps.edn` declares `["src" "resources"]` which maps `resources/extensions/ramora/protocol.txt` correctly.
- **Placeholder content**: The actual Ramora protocol text is TBD. Using a minimal placeholder that exercises the injection path; content can be swapped later without code changes.

## Slice Order

1. **Extension skeleton** — directory structure, deps.edn, protocol.txt placeholder
2. **Extension source** — `extensions.ramora` namespace mirroring munera/mementum
3. **Extension tests** — `extensions.ramora-test` mirroring munera/mementum tests
4. **Wiring** — `extensions/deps.edn`, launcher catalog, runtime catalog
5. **Validation** — run tests, verify parity, lint
