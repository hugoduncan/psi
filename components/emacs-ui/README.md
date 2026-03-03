# psi Emacs frontend

This frontend runs psi over `--rpc-edn` in a dedicated Emacs buffer.

## Extension UI parity gate

Extension UI parity is controlled by `psi-emacs-enable-extension-ui-parity` (default `nil`).

- `nil` (default): subscribe to `psi-rpc-mvp-topics` only (MVP behavior)
- non-`nil`: subscribe to `psi-rpc-parity-topics` (MVP + extension UI/footer topics)

Exact parity extension topics:

- `ui/dialog-requested`
- `ui/widgets-updated`
- `ui/status-updated`
- `ui/notification`
- `footer/updated`

When parity is disabled, UI/footer topic handling is not subscribed.

## Idle slash commands

When the frontend is **idle** (not streaming), these built-in slash commands are intercepted locally and do **not** fall through to `prompt` RPC:

- `/quit`, `/exit` — close the frontend buffer/process
- `/resume` — resume prior session (`/resume <path>`), or open selector when no path is provided
  - set `psi-emacs-enable-resume-parity` to `nil` to force MVP fallback message
- `/new` — request `new_session`, reset transcript/session rendering state, and continue in the new session
- `/status` — append deterministic frontend/session diagnostics text
- `/help`, `/?` — render slash command help

Unknown slash commands (for example `/foo`) are not handled locally and are sent through the normal `prompt` RPC path.

## Run-state model

Frontend routing is driven by an explicit run-state:

- `idle` — compose send/queue routes through idle slash interception first, then `prompt`
- `streaming` — compose send/queue routes to `prompt_while_streaming`
- `reconnecting` — transient state during manual reconnect (cleared to `idle` once transport is ready)
- `error` — set on RPC errors or streaming watchdog timeout

Header status includes this state: `psi [transport/process/run-state] tools:<mode>`.
`/status` diagnostics include `last-error: ...` when present.

### Transition sketch

- `idle -> streaming`: send/queue dispatches `prompt` (non-slash or non-intercepted slash)
- `streaming -> idle`: `assistant/message` finalize or explicit abort
- `streaming -> error`: watchdog timeout (no streaming progress for `psi-emacs-stream-timeout-seconds`)
- `* -> error`: RPC error event/callback
- `reconnecting -> idle`: RPC transport reaches `ready`

## Streaming behavior

Streaming send/queue behavior:

- send while streaming => `prompt_while_streaming` with steer behavior
- queue while streaming => `prompt_while_streaming` with queue behavior
- streaming progress events reset a watchdog timer
- watchdog timeout sends `abort`, upserts a deterministic transcript error line, and sets run-state to `error`

Idle slash interception applies only to idle compose send/queue paths.

## Extension dialog response flow (parity)

On `ui/dialog-requested`, Emacs maps dialog kinds to prompts and sends exactly one response op:

- confirm => yes/no prompt -> `resolve_dialog`
- select => completion over option labels (returns selected option `value`) -> `resolve_dialog`
- input => text prompt -> `resolve_dialog`
- user quit/cancel (`C-g`) -> `cancel_dialog`

RPC request shapes:

- `resolve_dialog`: `{:dialog-id <string> :result <boolean|string|null>}`
- `cancel_dialog`: `{:dialog-id <string>}`

`dialog-id` is always echoed from the inbound event; Emacs never invents IDs.

## Renderer boundary (explicit)

Emacs does **not** execute extension-provided renderer functions from extension UI state.
For parity mode, tool/message payload text is treated as display-ready text.
Renderer registration/query metadata remains read-only via `query_eql`.

## Error surface

- RPC errors still emit minibuffer messages.
- The frontend also keeps a single persistent in-buffer error line (`Error: ...`) that is updated in place.
- Reconnect/new-session transcript reset clears the persistent error line and `last-error` state.
