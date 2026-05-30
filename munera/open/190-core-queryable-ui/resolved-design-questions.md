# Resolved design questions

No design blockers remain. The task is ready for implementation planning.

## Resolved by design discussion

- Attribute namespace: use runtime-scoped `:psi.ui/...` unless implementation discovery finds a stronger convention.
- Scope: UI capability surface is runtime-scoped, not session-scoped.
- Extension behaviour: extensions must be capability-driven, not UI-type-driven.
- Capabilities and actions remain separate.
- Active UI model: single UI for this slice.
- Permissions: no new permissions for query. Side-effecting invocation has no new manifest `allowed-events` permission only if implemented through the dedicated constrained `:psi.ui/request-action` helper described in `design.md`; otherwise this slice remains descriptor-only and permission-aware submission is deferred to a follow-up.
- Registration/storage: frontend descriptors are derived on demand; do not store advertised capabilities/actions in root state.
- Request delivery: UI action requests use a core-owned event/subscription path.
- Tests: include nullable tests and frontend-specific tests.
- Docs: update extension-authoring documentation.

## Resolved decisions

1. Attribute names and resolver shape:
   - root/runtime-scoped attrs under `:psi.ui/...`
   - `:psi.ui/type`
   - `:psi.ui/available?`
   - `:psi.ui/capabilities`
   - `:psi.ui/actions`
   - `:psi.ui/make-visible-action`
   - resolver input only `:psi/agent-session-ctx`
2. Action descriptors use fully namespaced pure-data maps under `:psi.ui.action/...` keys.
3. Invocation is a tagged union under `:psi.ui.action/invocation`, allowing `:emacs-command`, `:ui-event`, `:bash-command`, and `:mutation` kinds with explicit per-kind schemas: Emacs command string, UI event keyword plus optional payload map, bash argv vector plus optional env map, and qualified mutation symbol plus params map.
4. Queryability and descriptor/event contract are required in this task; side-effecting generic invocation is optional and should become a follow-up if it is not small.
5. Emacs make-visible should be a small command, proposed as `psi-emacs-show-active`, that shows/focuses the active Psi buffer using existing `pop-to-buffer` and prompt-focus behaviour.
6. TUI/console should not claim make-visible unless real tmux target metadata and a safe command exist.
7. Unsupported states use both absence of capability for normal extension logic and required unavailable descriptors for stable inspection: `:psi.ui/make-visible-action` always returns a descriptor, and unsupported/headless/provider-error cases return an unavailable descriptor with `:psi.ui.action/available? false`, a `:psi.ui.unavailable.reason/...` keyword, and `:psi.ui.action/unavailable-message`.
8. UI capabilities/actions are derived on demand from the currently attached UI adapter/request channel. UI action requests use a core-owned event/subscription path: core emits a declarative UI request, active UI adapters subscribe and execute if they currently support it. Root state, if used, is limited to existing extension UI contribution state and optional request/result diagnostics.
9. Docs to update: `doc/extensions.md`, `doc/extension-api.md`, and `doc/architecture.md`; README only if an existing pointer needs adjustment.

## Implementation-time checks

These are not design blockers; resolve them while planning/building:

- Confirm whether adding a dedicated UI capability resolver is cleaner than extending `extension-ui-resolver`. Current preference: dedicated resolver.
- Confirm whether generic side-effecting invocation is small enough for this task or should be split out. Current requirement: descriptor plus event/request contract; generic invocation may be follow-up.
- Confirm whether any existing Emacs command can serve as `psi-emacs-show-active`; otherwise add that small command.
