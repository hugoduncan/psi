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

- Follow the same patterns as `auto-session-name` and `metrics` extensions
- Use `(:on api)` for event subscription (not `psi.extension/register-handler`)
- Extension must load cleanly on reload without state corruption
- No dependencies on other extensions or external libraries beyond Clojure core
  and timbre

## Acceptance

- `bb test` passes (focused on the new extension test namespace)
- `clj-kondo --lint extensions/context-manager/src` is clean
- Extension loads without error when the runtime loads it
- `session_turn_finished` events are logged (verifiable in test)
