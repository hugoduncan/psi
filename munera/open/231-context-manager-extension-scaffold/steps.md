## Slice 1: Extension Scaffold

- [x] Create `extensions/context-manager/deps.edn` with `:paths ["src"]`, `:deps` including only `org.clojure/clojure`, and `:aliases/test` with kaocha, `psi/extension-test-helpers`, and `psi/agent-session`
- [x] Create `extensions/context-manager/src/extensions/context_manager.clj` with namespace docstring, `init` function that subscribes to `session_turn_finished` via `(:on api)`, handler that logs `:session-id` and `:turn-id` then returns nil

## Slice 2: Wiring

- [x] Add `'psi/context-manager` entry to `psi-owned-extension-catalog` in `components/agent-session/src/psi/agent_session/extension_installs.clj` with `:psi/init 'extensions.context-manager/init` and `:source-policies {:installed {:local/root "extensions/context-manager"}}`
- [x] Add `'psi/context-manager` entry to `psi-owned-extension-catalog` in `bases/main/src/psi/launcher/extensions.clj` with all three source policies (development, installed, jar)
- [x] Add `psi/context-manager {:local/root "context-manager"}` to `:deps` in `extensions/deps.edn`
- [x] Add `"context-manager/test"` to `:extra-paths` in the `:test` alias of `extensions/deps.edn`

## Slice 3: Test

- [x] Create `extensions/context-manager/test/extensions/context_manager_test.clj` (ns `extensions.context-manager-test`) with one test using nullable API pattern: verify `init` registers a `session_turn_finished` handler and that the handler fires on a synthetic event

## Slice 4: Verify

- [x] Run `clj-kondo --lint extensions/context-manager/src` — must be clean
- [x] Run `bb test` focused on `extensions.context-manager-test` — must pass
- [x] Run the catalog parity test to confirm both catalogs are in sync

## Test Review Follow-ups

- [ ] Add reload safety test: verify calling `init` twice on the same nullable API does not register duplicate `session_turn_finished` handlers (design constraint: "load cleanly on reload without state corruption")
- [ ] Add negative test: verify `init` registers no commands, tools, operations, or prompt contributions (design scope: "No commands, tools, operations, or prompt contributions yet")
- [ ] Add edge case test: handler invoked with payload missing `:session-id` or `:turn-id` — verify log output handles `nil` gracefully or document expected behaviour

## Review Follow-ups

- [x] Remove unnecessary `com.taoensso/timbre` dep from `extensions/context-manager/deps.edn` — code uses `(:log api)` exclusively, never requires timbre
- [x] Tighten test regex assertions in `context_manager_test.clj` — remove `(?i)` flag; log output is deterministic and case-sensitive
- [x] Tighten regex patterns in `turn-finished-handler-fires-and-logs-test` — `#"s1"` and `#"t1"` are too broad; use `#"session-id=s1"` and `#"turn-id=t1"` to avoid false positives from unrelated log content
