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

- [x] Add test verifying that the handler is registered with the correct event name `session_turn_finished` by inspecting the registration call or the resulting state map's structure more explicitly than just counting handlers.

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

## Test Shaper Review (2026-06-22, third pass)

- [x] Add a test verifying that the handler is registered with the correct event name `session_turn_finished` by inspecting the registration call or the resulting state map's structure more explicitly than just counting handlers.
- [x] Refactor `init-registers-turn-finished-handler-test` to remove redundant nested `testing` block that repeats the same `contains?` check on the handler map.
- [x] Add a test case to `turn-finished-handler-fires-and-logs-test` verifying that the handler does not throw an exception when the event payload is `nil` (current tests check empty map `{}`, but not `nil`).

## Review Follow-ups (2026-06-22)

- [x] Refactor `init` to use a more robust check for `api` keys (e.g. `(get api :on)`) and ensure `initialized?` is only set if registration actually succeeds, to avoid blocking subsequent `init` calls if the first one failed due to a malformed API.
- [x] Add a test case to `init-robustness-test` verifying that if `init` fails due to a missing `:on` key, a subsequent call with a valid API still succeeds (currently `initialized?` is not set on failure, but explicit verification is missing).
- [x] Add test for `init` with `api` as `nil` — verify it returns `nil` and does not throw NPE (current `init` handles `(if (and api ...))` but explicit test coverage is missing).
- [x] Add test for `init` with `api` as a non-map (e.g. a string or number) — verify it returns `nil` and does not throw NPE (current `(and api (:on api))` will throw `ClassCastException` if `api` is not a map).
- [x] Add test verifying that `init` returns `true` on successful first-time initialization (design says "returns true" implicitly via the `do` block, but no test explicitly asserts the return value of `init` on success).

## Test Shaper Review (2026-06-22, fifth pass)

- [x] Refactor `turn-finished-handler-fires-and-logs-test` to use `testing` blocks for the nominal case and the `nil` payload case, ensuring failures are precisely located.
- [x] Add a test verifying that the handler does not log anything (and does not throw) when the `:log` function is missing from the API, ensuring the `(when (:log api) ...)` guard is effective.
- [x] Verify that `initialized?` is reset to `nil` in `use-fixtures` of `context_manager_test.clj` (it is, but ensure it's consistent across all test files if more are added).



## Docs Review (2026-06-22)

- [x] Add `context-manager` to the built-in extensions list in `README.md` (under "Built-in extensions that ship with this repo")
- [x] Add `context-manager` entry to `doc/extensions.md` under "Built-in extensions in this repo" section with brief purpose description
- [x] Add `context-manager` extension entry to `CHANGELOG.md` under `[Unreleased]` > `Added` (new extension capability)

## Implementation Review (2026-06-22)

- [x] Refactor `init` to avoid `println` when `:log` is missing in `api`; use a fallback log-fn or simply no-op, as `println` violates the "ui-agnostic" and "pure core" ethos of the VSM (S5/S1).

## Test Shaper Review (2026-06-23)

- [x] Add a test verifying that the handler does not throw when the payload is not a map (e.g. a string or number), ensuring robustness against malformed event payloads. (Note: `turn-finished-handler-fires-and-logs-test` has a "payload is not a map" case, but verify it's comprehensive).
- [x] Verify that the handler's log output is consistent with the project's logging standards (e.g. prefixing with `context-manager: `) and that this is explicitly asserted in tests. (Note: `on-turn-finished` uses the prefix, but tests use `re-find` for parts of the string; add a test for the exact prefix).
## Test Shaper Review (2026-06-23)

- [x] Add a test verifying that the handler does not throw when the payload is not a map (e.g. a string or number), ensuring robustness against malformed event payloads. (Note: `turn-finished-handler-fires-and-logs-test` has a "payload is not a map" case, but verify it's comprehensive).
- [x] Verify that the handler's log output is consistent with the project's logging standards (e.g. prefixing with `context-manager: `) and that this is explicitly asserted in tests. (Note: `on-turn-finished` uses the prefix, but tests use `re-find` for parts of the string; add a test for the exact prefix).
- [x] Refactor `init-registration-contract-test` to explicitly verify the registration call arguments (event name and handler function) using a spy or custom nullable API, rather than just inspecting the resulting state map.
- [x] Add a test verifying that the handler does not throw when the `log-fn` itself throws an exception, ensuring the extension doesn't crash the dispatch pipeline (robustness/isolation).
- [x] Add a test verifying that the handler does not throw when the `log-fn` returns a non-nil value, ensuring the extension doesn't accidentally return the log-fn's result instead of `nil`.

## Task Test Review (2026-06-23)

- [x] Add test verifying that the handler is registered with the correct event name `session_turn_finished` by inspecting the registration call (e.g. using a spy or a custom nullable API) rather than just checking the resulting state map. (Note: `init-registration-contract-test` checks the state map, but not the call itself as requested in the 6th pass).
- [x] Add test verifying that the handler is registered as a function (not a map or other type) to ensure compatibility with the dispatch pipeline's expectation of a handler function.
- [x] Add test verifying that the handler does not mutate any external state (beyond the provided log-fn) to ensure it remains a pure-result handler as per the VSM S1/S3 purity goals.
- [x] Verify that the handler's return value is explicitly asserted as `nil` in all test cases (nominal, empty map, nil payload) to ensure compliance with the design requirement "returns nil".





