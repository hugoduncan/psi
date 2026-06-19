# Context Manager Extension Scaffold

## Intent

Add a new psi extension called `context-manager` that provides a hook point
for context-aware processing after each agent turn. The initial version is a
pure scaffold — it subscribes to the `session_turn_finished` event (same
pattern as `auto-session-name`) but performs no action beyond logging.

This gives future work a stable extension boundary, namespace, test harness,
and event subscription to build on without touching core dispatch or other
extensions.

## Why

Context management (summarisation, pruning, state tracking, etc.) is a natural
extension point. Rather than baking it into core or piggybacking on another
extension, a dedicated extension provides:

- Clean separation of concerns
- Independent load/reload lifecycle
- Testable in isolation with the extension test helpers
- Easy to evolve with commands, tools, or operations later

## Scope

- Create the extension directory structure under `extensions/context-manager/`
- `deps.edn` with standard extension dependencies
- Single namespace `extensions.context-manager` with an `init` function
- Subscribe to `session_turn_finished` via `(:on api)`
- Handler logs the event (session-id, turn-id) and returns nil
- One basic test verifying the handler is registered and fires
- No commands, tools, operations, or prompt contributions yet

## Constraints

- Follow the same patterns as the `auto-session-name` extension
- Use `(:on api)` for event subscription (not `psi.extension/register-handler`)
- Extension must load cleanly on reload without state corruption
- No dependencies on other extensions or external libraries beyond Clojure core
  and timbre

## Wiring Details

- **Runtime catalog**: Add `psi/context-manager` to `psi-owned-extension-catalog` in
  `components/agent-session/src/psi/agent_session/extension_installs.clj` with
  `:psi/init 'extensions.context-manager/init` and
  `:source-policies {:installed {:local/root "extensions/context-manager"}}`.

- **Launcher catalog**: Add the matching entry to
  `psi.launcher.extensions/psi-owned-extension-catalog` in
  `bases/main/src/psi/launcher/extensions.clj` with all three policies:
  ```clojure
  'psi/context-manager
  {:psi/init 'extensions.context-manager/init
   :source-policies
   {:development {:local/root "extensions/context-manager"}
    :installed   {:local/root "extensions/context-manager"}
    :jar         {:mvn/version :psi/release-version}}}
  ```
  Parity is asserted by the `psi-owned-extension-catalog-parity-with-launcher` test.

- **Top-level deps**: Add `psi/context-manager {:local/root "context-manager"}`
  to `:deps` in `extensions/deps.edn` and add `"context-manager/test"` to the
  `:test` alias `:extra-paths`.

- **Extension deps.edn**: `:paths ["src"]`, `:deps` includes only
  `org.clojure/clojure` (no `psi/ai` — the scaffold performs no action beyond
  logging and does not need the AI component). `:aliases/test` includes
  `kaocha`, `psi/extension-test-helpers`, and `psi/agent-session`.

- **Event payload**: The `session_turn_finished` event carries `:session-id`
  (string) and `:turn-id` (string). Log both.

- **Manifest**: No `:allowed-events` or capability declarations needed — same as
  `auto-session-name`. The extension only subscribes via `(:on api)`.

- **Test**: One test namespace `extensions.context_manager_test` using the
  nullable API pattern from `auto-session-name` — verify the handler is
  registered and fires on a synthetic `session_turn_finished` event.

## Acceptance

- `bb test` passes (focused on the new extension test namespace)
- `clj-kondo --lint extensions/context-manager/src` is clean
- Extension loads without error when the runtime loads it
- `session_turn_finished` events are logged (verifiable in test)
