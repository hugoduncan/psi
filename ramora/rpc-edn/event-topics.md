# Event Topic Bridge Mapping

Only catalog topics from `rpc-edn.allium` may be emitted:
- `session/updated`
- `session/resumed`
- `session/rehydrated`
- `assistant/delta`
- `assistant/message`
- `tool/start`
- `tool/delta`
- `tool/executing`
- `tool/update`
- `tool/result`
- `ui/dialog-requested`
- `ui/frontend-action-requested` (`:ui/action` is the canonical action model; legacy payload duplication removed)
- `ui/widgets-updated`
- `ui/status-updated`
- `ui/notification`
- `footer/updated`
- `command-result`
- `error`

Planned source signal exemplars for bridge implementation:
- executor progress event kinds: `:text-delta`, `:tool-start`, `:tool-delta`, `:tool-executing`, `:tool-execution-update`, `:tool-result`
- session lifecycle hooks: `session_switch`, resume flows, and subscribed initial snapshot emission
- UI extension state atom updates for widgets/status/notifications/footer

Bridge requirements:
- map source fields to required kebab-case payload keys per topic
- include optional `:seq` + `:ts` policy support
- preserve interleaving with direct responses
