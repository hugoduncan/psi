# Operation Mapping Table

The transport op router MUST remain a thin translation boundary over existing `psi.agent-session.core` APIs.

| RPC op | Params contract | Runtime mapping target | Success `:data` shape | Canonical error mapping |
|---|---|---|---|---|
| `handshake` | `{:client-info {:name s :version s :protocol-version s :features [s*]?}}` | transport-level negotiation (not session domain op) | `{:server-info {:protocol-version s :features [s*] :session-id s? :model-id s? :thinking-level s?}}` | `protocol/unsupported-version`, `request/invalid-params` |

Handshake remains transport-focused. Initial session/UI snapshots are delivered through normal subscribed events rather than a special handshake bootstrap event. `context/updated` now carries the canonical context snapshot plus backend-projected session-tree widget payload.

Runtime-owned public projection delivery is event-driven:
- dispatch/session mutations emit semantic invalidations (`:projection/context-changed`, `:projection/ui-changed`)
- RPC recomputes payloads from canonical state at fanout time rather than carrying final payloads on effects
- per-connection focus remains an RPC concern, so the same invalidation may yield different payloads per connection
- runtime-owned context and shared UI delivery do not rely on polling loops

| `query_eql` | `{:query <edn-string-vector>}` | `session/query-in` | `{:result any}` | `request/invalid-params`, `request/invalid-query`, `runtime/query-failed` |
| `command` | `{:text s}` | backend slash-command dispatch | `{:accepted true}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `frontend_action_result` | `{:request-id s :action-name s :status ("submitted"\|"cancelled"\|"failed") :value ? :error-message s?}` | apply/cancel/fail a backend-requested frontend action | `{:accepted true}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `prompt` | `{:message s :images ?}` | `session/prompt-in!` | `{:accepted true}` | `transport/not-ready`, `request/invalid-params`, `request/session-not-idle`, `runtime/failed` |
| `steer` | `{:message s :images ?}` | `session/steer-in!` | `{:accepted true}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `follow_up` | `{:message s :images ?}` | `session/follow-up-in!` | `{:accepted true}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `abort` | `{}` | `session/abort-in!` | `{:accepted true}` | `transport/not-ready`, `runtime/failed` |
| `login_begin` | `{:session-id s? :provider s?}` | `oauth/begin-login!` + `commands/select-login-provider` | `{:provider {:id s :name s} :url s :uses-callback-server boolean :pending-login true}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `login_complete` | `{:input s?}` | `oauth/complete-login!` using transport pending-login state | `{:provider {:id s :name s} :logged-in true}` | `transport/not-ready`, `request/no-pending-login`, `request/invalid-params`, `runtime/failed` |
| `new_session` | `{:session-id s? :parent-session ?}` | `session/new-session-in!` | `{:session-id s :session-file s?}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `switch_session` | `{:session-id s? :session-path s}` | `session/resume-session-in!` | `{:session-id s :session-file s?}` | `transport/not-ready`, `request/invalid-params`, `request/not-found`, `runtime/failed` |
| `fork` | `{:session-id s? :entry-id s}` | `session/fork-session-in!` | `{:session-id s :session-file s?}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `set_session_name` | `{:name s}` | `session/set-session-name-in!` | `{:session-name s}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `set_model` | `{:provider s :model-id s}` | resolve model + reject unsupported runtime models + `session/set-model-in!`; with OpenAI OAuth, catalog-visible `gpt-5.6` is currently rejected as unsupported rather than persisted | `{:model {:provider s :id s}}` | `transport/not-ready`, `request/invalid-params`, `request/unknown-model`, `request/unsupported-model`, `runtime/failed` |
| `cycle_model` | `{:direction ("next"\|"prev")?}` | `session/cycle-model-in!`; skips OAuth-unsupported or unresolvable scoped candidates such as OpenAI OAuth-backed `gpt-5.6` rather than persisting them | `{:model {:provider s :id s}}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `set_thinking_level` | `{:level keyword\|string\|int}` | `session/set-thinking-level-in!` | `{:thinking-level any}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `cycle_thinking_level` | `{}` | `session/cycle-thinking-level-in!` | `{:thinking-level any}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `compact` | `{:custom-instructions s?}` | `session/manual-compact-in!` | `{:compacted true :summary ?}` | `transport/not-ready`, `request/session-not-idle`, `runtime/failed` |
| `set_auto_compaction` | `{:enabled boolean}` | `session/set-auto-compaction-in!` | `{:enabled boolean}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `set_auto_retry` | `{:enabled boolean}` | `session/set-auto-retry-in!` | `{:enabled boolean}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `get_state` | `{}` | `session/query-in` (state attrs projection) | `{:state map}` | `transport/not-ready`, `runtime/query-failed` |
| `get_messages` | `{}` | `session/query-in` (messages projection) | `{:messages vector}` | `transport/not-ready`, `runtime/query-failed` |
| `get_session_stats` | `{}` | `session/diagnostics-in` and/or query projection | `{:stats map}` | `transport/not-ready`, `runtime/query-failed` |
| `subscribe` | `{:topics [string*]?}` | transport subscription state | `{:subscribed [string*]}` | `transport/not-ready`, `request/invalid-params` |
| `unsubscribe` | `{:topics [string*]?}` | transport subscription state | `{:subscribed [string*]}` | `transport/not-ready`, `request/invalid-params` |
| `ping` | `{}` | transport heartbeat | `{:pong true :protocol-version s}` | `transport/not-ready` |
