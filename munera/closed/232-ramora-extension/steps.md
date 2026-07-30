# 232-ramora-extension — Steps

## Slice 1: Extension skeleton

- [x] Create `extensions/ramora/deps.edn` matching munera/mementum pattern
- [x] Create `extensions/ramora/src/extensions/ramora.clj`
- [x] Create `extensions/ramora/resources/extensions/ramora/protocol.txt` with placeholder lambda-form content
- [x] Create `extensions/ramora/test/extensions/ramora_test.clj`

## Slice 2: Extension source

- [x] Implement `extensions.ramora` namespace mirroring `extensions.munera`:
  - `defonce state` atom
  - `prompt-contribution-id` = `"ramora-protocol"`
  - `protocol-resource-path` = `"extensions/ramora/protocol.txt"`
  - `engage-prefix` (same string as munera/mementum)
  - `read-protocol-resource` private fn (same error-handling pattern, `:extension :ramora`)
  - `ramora-lambda` var (result of `read-protocol-resource` at init)
  - `lambda-mode?` private fn (query `:psi.agent-session/prompt-mode`)
  - `prompt-content` private fn (lambda → raw, prose → engage-prefix + raw)
  - `register-prompt-contribution!` private fn (section `"Ramora Protocol"`, priority 52)
  - `init` public fn (store api, register contribution)

## Slice 3: Extension tests

- [x] Implement `extensions.ramora-test` mirroring `extensions.munera-test`:
  - `init-registers-lambda-mode-prompt-contribution-test` — verify lambda mode content (no engage prefix), id, section, priority 52
  - `init-registers-prose-mode-prompt-contribution-test` — verify prose mode content (engage prefix + protocol), id, section, priority 52
  - `missing-resource-load-fails-fast-test` — verify ex-info with `:extension :ramora` and resource-path

## Slice 4: Wiring

- [x] Add `'psi/ramora {:local/root "ramora"}` to `extensions/deps.edn` `:deps` (not `:extra-paths`)
- [x] Add `psi/ramora` entry to `psi.launcher.extensions/psi-owned-extension-catalog` in `bases/main/src/psi/launcher/extensions.clj` (after munera, same shape)
- [x] Add `psi/ramora` entry to `psi.agent-session.extension-installs/psi-owned-extension-catalog` in `components/agent-session/src/psi/agent_session/extension_installs.clj` (after munera, same shape)

## Slice 5: Validation

- [x] Run `bb test` — all tests pass including `psi-owned-extension-catalog-parity-with-launcher`
- [x] Run `clj-kondo --lint src` — no new lint errors
- [x] Verify extension loads without error in a running session (manual or via test)
- [x] Add CHANGELOG.md entry for ramora extension under [Unreleased] → Added

## Docs review follow-ups

- [x] Add **ramora** to the built-in extensions list in `README.md` (after munera, describing it as a prompt-contribution extension that injects the Ramora protocol)
- [x] Add a ramora extension entry to `doc/extensions.md` under the built-in extensions section (after context-manager or hello-ext, describing purpose and that it is prompt-contribution-only)
