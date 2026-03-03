# psi Emacs frontend (MVP)

This frontend runs psi over `--rpc-edn` in a dedicated Emacs buffer.

## Idle slash commands

When the frontend is **idle** (not streaming), these built-in slash commands are intercepted locally and do **not** fall through to `prompt` RPC:

- `/quit`, `/exit` — close the frontend buffer/process
- `/resume` — explicit MVP fallback message (`resume selector unavailable in Emacs MVP`)
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

## Error surface

- RPC errors still emit minibuffer messages.
- The frontend also keeps a single persistent in-buffer error line (`Error: ...`) that is updated in place.
- Reconnect/new-session transcript reset clears the persistent error line and `last-error` state.
