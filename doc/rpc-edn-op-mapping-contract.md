# RPC EDN Op Mapping Contract (Story #76 / Task #78)

Status: normative implementation contract for runtime transport work.

References:
- `spec/rpc-edn.allium` (canonical wire contract)
- `spec/coding-agent.allium` (`RpcApi` operation surface)
- `spec/emacs-frontend.allium` (frontend consumer expectations)

## 1) Canonical wire envelope rules

All wire keys are EDN kebab-case keywords.

### Request frame (stdin)
Required keys: `:id :kind :op`
Allowed keys: `:id :kind :op :params`
Constraints:
- `:kind` MUST be `:request`
- `:id` MUST be non-empty string
- `:op` MUST be non-empty string

### Response frame (stdout)
Required keys: `:id :kind :op :ok`
Allowed keys: `:id :kind :op :ok :data`
Constraints:
- `:kind` MUST be `:response`
- `:ok` is boolean
- no extra envelope keys

### Error frame (stdout)
Required keys: `:kind :error-code :error-message`
Allowed keys: `:kind :id :op :error-code :error-message :retryable :data`
Constraints:
- `:kind` MUST be `:error`
- no extra envelope keys

### Event frame (stdout)
Required keys: `:kind :event :data`
Allowed keys: `:kind :event :data :id :seq :ts`
Constraints:
- `:kind` MUST be `:event`
- `:seq` (when present) MUST be monotonic increasing
- no extra envelope keys

### Transport discipline
- Exactly one top-level EDN map per line.
- stdout in RPC mode is protocol-only (`:response | :event | :error`).
- diagnostics/logging go to stderr only.

## 2) Handshake and readiness policy

- Only `handshake` is allowed before ready.
- Any non-handshake request pre-ready returns:
  - `{:kind :error :id <req-id?> :op <op?> :error-code "transport/not-ready" ...}`
- Unsupported protocol major returns:
  - `:error-code "protocol/unsupported-version"`
  - endpoint transitions to disconnected/non-ready (disconnect behavior)
- Successful handshake negotiates server protocol within major `1` and sets transport ready.

## 3) Pending request lifecycle policy

- Accepted request adds pending entry `id -> op`.
- Terminal `:response` or `:error` with matching `:id` clears entry.
- Enforce `max_pending_requests` guard.
- Duplicate/invalid IDs return canonical request errors (no transport crash).

## 4) `query_eql` request contract

Canonical input contract for wire requests:
- `:params {:query <string>}` where `<string>` parses as EDN vector query.

Validation outcomes:
- not EDN => canonical request/protocol error
- EDN but non-vector => canonical request error
- vector => run via live runtime query path

Success response shape:
- `{:id ... :kind :response :op "query_eql" :ok true :data {:result <query-result>}}`

Parity expectation:
- queries containing `:psi.graph/*` and `:psi.memory/*` must return values when runtime context provides them.

## 5) Operation mapping table

The transport op router MUST remain a thin translation boundary over existing `psi.agent-session.core` APIs.

| RPC op | Params contract | Runtime mapping target | Success `:data` shape | Canonical error mapping |
|---|---|---|---|---|
| `handshake` | `{:client-info {:name s :version s :protocol-version s :features [s*]?}}` | transport-level negotiation (not session domain op) | `{:server-info {:protocol-version s :features [s*] :session-id s? :model-id s? :thinking-level s?}}` | `protocol/unsupported-version`, `request/invalid-params` |
| `query_eql` | `{:query <edn-string-vector>}` | `session/query-in` | `{:result any}` | `request/invalid-params`, `request/invalid-query`, `runtime/query-failed` |
| `prompt` | `{:message s :images ?}` | `session/prompt-in!` | `{:accepted true}` | `transport/not-ready`, `request/invalid-params`, `request/session-not-idle`, `runtime/failed` |
| `steer` | `{:message s :images ?}` | `session/steer-in!` | `{:accepted true}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `follow_up` | `{:message s :images ?}` | `session/follow-up-in!` | `{:accepted true}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `abort` | `{}` | `session/abort-in!` | `{:accepted true}` | `transport/not-ready`, `runtime/failed` |
| `new_session` | `{:parent-session ?}` | `session/new-session-in!` | `{:session-id s :session-file s?}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `switch_session` | `{:session-path s}` | `session/resume-session-in!` | `{:session-id s :session-file s?}` | `transport/not-ready`, `request/invalid-params`, `request/not-found`, `runtime/failed` |
| `fork` | `{:entry-id s}` | `session/fork-session-in!` | `{:session-id s :session-file s?}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `set_session_name` | `{:name s}` | `session/set-session-name-in!` | `{:session-name s}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `set_model` | `{:provider s :model-id s}` | resolve model + `session/set-model-in!` | `{:model {:provider s :id s}}` | `transport/not-ready`, `request/invalid-params`, `request/unknown-model`, `runtime/failed` |
| `cycle_model` | `{:direction ("next"\|"prev")?}` | `session/cycle-model-in!` | `{:model {:provider s :id s}}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `set_thinking_level` | `{:level keyword\|string\|int}` | `session/set-thinking-level-in!` | `{:thinking-level any}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `cycle_thinking_level` | `{}` | `session/cycle-thinking-level-in!` | `{:thinking-level any}` | `transport/not-ready`, `runtime/failed` |
| `compact` | `{:custom-instructions s?}` | `session/manual-compact-in!` | `{:compacted true :summary ?}` | `transport/not-ready`, `request/session-not-idle`, `runtime/failed` |
| `set_auto_compaction` | `{:enabled boolean}` | `session/set-auto-compaction-in!` | `{:enabled boolean}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `set_auto_retry` | `{:enabled boolean}` | `session/set-auto-retry-in!` | `{:enabled boolean}` | `transport/not-ready`, `request/invalid-params`, `runtime/failed` |
| `get_state` | `{}` | `session/query-in` (state attrs projection) | `{:state map}` | `transport/not-ready`, `runtime/query-failed` |
| `get_messages` | `{}` | `session/query-in` (messages projection) | `{:messages vector}` | `transport/not-ready`, `runtime/query-failed` |
| `get_session_stats` | `{}` | `session/diagnostics-in` and/or query projection | `{:stats map}` | `transport/not-ready`, `runtime/query-failed` |
| `subscribe` | `{:topics [string*]?}` | transport subscription state | `{:subscribed [string*]}` | `transport/not-ready`, `request/invalid-params` |
| `unsubscribe` | `{:topics [string*]?}` | transport subscription state | `{:subscribed [string*]}` | `transport/not-ready`, `request/invalid-params` |
| `ping` | `{}` | transport heartbeat | `{:pong true :protocol-version s}` | `transport/not-ready` |

## 6) Unsupported-op policy

If `:op` is unknown or not implemented in current runtime:
- return canonical error frame with:
  - `:kind :error`
  - `:id` echoed when present
  - `:op` echoed when present
  - `:error-code "request/op-not-supported"`
  - `:error-message` stable, human-readable
  - optional `:data {:supported-ops [...]}`
- do not crash/disconnect transport solely for unsupported op.

Ops currently expected to start as unsupported until later implementation tasks land:
- `abort_retry`
- `bash`
- `abort_bash`
- `export_html`
- `get_fork_messages`
- `get_last_assistant_text`
- `get_commands`
- `get_available_models`

## 7) Event topic bridge mapping

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
- `ui/widgets-updated`
- `ui/status-updated`
- `ui/notification`
- `footer/updated`
- `error`

Planned source signal exemplars for bridge implementation:
- executor progress event kinds: `:text-delta`, `:tool-start`, `:tool-delta`, `:tool-executing`, `:tool-execution-update`, `:tool-result`
- session lifecycle hooks: `session_switch`, resume/bootstrap flows
- UI extension state atom updates for widgets/status/notifications/footer

Bridge requirements:
- map source fields to required kebab-case payload keys per topic
- include optional `:seq` + `:ts` policy support
- preserve interleaving with direct responses

## 8) Canonical error-code taxonomy (minimum for Story #76)

- transport:
  - `transport/not-ready`
  - `transport/invalid-frame`
  - `transport/max-pending-exceeded`
- protocol:
  - `protocol/unsupported-version`
  - `protocol/invalid-envelope`
- request:
  - `request/invalid-id`
  - `request/invalid-op`
  - `request/invalid-params`
  - `request/invalid-query`
  - `request/op-not-supported`
  - `request/session-not-idle`
  - `request/not-found`
  - `request/unknown-model`
- runtime:
  - `runtime/query-failed`
  - `runtime/failed`

## 9) Acceptance-criteria traceability (Story #76)

| Story criterion | Covered by this contract |
|---|---|
| 1. Canonical request validation | Sections 1, 8 |
| 2. Handshake gate + compatibility | Sections 2, 8 |
| 3. Canonical response/error/event envelopes | Section 1 |
| 4. Pending request lifecycle | Section 3 |
| 5. Event stream interleaving | Sections 7, 1 |
| 6. Event topic/payload catalog compliance | Section 7 |
| 7. `query_eql` graph/memory parity | Sections 4, 5 |
| 8. stdout discipline | Section 1 (Transport discipline) |
| 9. Tests (contract target) | Sections 1–8 define testable rules |

## 10) Out-of-scope reminders (for this task)

- No protocol version bump (stay `1.0`).
- No new event topics beyond `rpc-edn.allium` catalog.
- No HTTP transport.
- No Emacs rendering internals.
