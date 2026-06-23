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

## Test Review — Log Precision (2026-06-22)

- [x] Tighten `handler-handles-missing-payload-keys-test` assertions: `some` over accumulated `:log-lines` allows `turn-id=nil` (second invocation) and `session-id=nil` (third invocation) to match the first invocation's log line — use `(last (:log-lines @state))` or clear log lines between invocations so each assertion verifies the handler's output for that specific call

## Test Review (2026-06-22)

- [x] Assert `nil` return value in `handler-handles-missing-payload-keys-test` for each of the three handler invocations — design says handler "returns nil" but this test only checks log lines
- [x] Rename `init-registers-no-commands-tools-or-prompts-test` to include "operations" (matching design scope) or add an in-test comment explaining that operations are not separately trackable in the nullable API

## Test Review Follow-ups

- [x] Add reload safety test: verify calling `init` twice on the same nullable API does not register duplicate `session_turn_finished` handlers (design constraint: "load cleanly on reload without state corruption")
- [x] Add negative test: verify `init` registers no commands, tools, operations, or prompt contributions (design scope: "No commands, tools, operations, or prompt contributions yet")
- [x] Add edge case test: handler invoked with payload missing `:session-id` or `:turn-id` — verify log output handles `nil` gracefully or document expected behaviour

## Test Shaper Review (2026-06-22)

- [x] Wrap the three sub-cases in `handler-handles-missing-payload-keys-test` with individual `testing` blocks (e.g. `testing "missing both keys"`, `testing "missing only :turn-id"`, `testing "missing only :session-id"`) — codebase convention uses `testing` blocks for sub-cases (see `auto_session_name_test.clj`); without them a failure doesn't identify which sub-case violated the contract

## Test Shaper Review (2026-06-22, second pass)

- [x] Align assertion style in `turn-finished-handler-fires-and-logs-test` — replace `(some #(re-find ... ) (:log-lines @state))` with `(re-find ... (last (:log-lines @state)))` to match the precise per-invocation pattern already used in `handler-handles-missing-payload-keys-test`

## Review Follow-ups

- [x] Remove unnecessary `com.taoensso/timbre` dep from `extensions/context-manager/deps.edn` — code uses `(:log api)` exclusively, never requires timbre
- [x] Tighten test regex assertions in `context_manager_test.clj` — remove `(?i)` flag; log output is deterministic and case-sensitive
- [x] Tighten regex patterns in `turn-finished-handler-fires-and-logs-test` — `#"s1"` and `#"t1"` are too broad; use `#"session-id=s1"` and `#"turn-id=t1"` to avoid false positives from unrelated log content
- [x] Make `init` idempotent for reload safety — design constraint requires clean reload without state corruption; currently `init` appends a new handler on every call (documented by `init-reload-safety-test` but not fixed); add a `compare-and-set!` guard on a private atom so repeated calls are no-ops
- [x] Complete edge case test for partial missing keys — `handler-handles-missing-payload-keys-test` only exercises `{}` (both keys missing); add cases for `{:session-id "s1"}` (turn-id missing) and `{:turn-id "t1"}` (session-id missing) to confirm the `or` fallback works in each direction
- [x] Fix test fixture in `context_manager_test.clj`: `alter-var-root` was creating a new atom instead of resetting the existing one, which would fail if the atom was not replaced in the namespace

## Docs Review (2026-06-22)

- [x] Add `context-manager` to the built-in extensions list in `README.md` (under "Built-in extensions that ship with this repo")
- [x] Add `context-manager` entry to `doc/extensions.md` under "Built-in extensions in this repo" section with brief purpose description
- [x] Add `context-manager` extension entry to `CHANGELOG.md` under `[Unreleased]` > `Added` (new extension capability)
