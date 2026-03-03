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
- `error` — set on RPC error events (errors are surfaced in minibuffer)

Header/status strings include this state: `psi [transport/process/run-state] tools:<mode>`.

### Transition sketch

- `idle -> streaming`: send/queue dispatches `prompt` (non-slash or non-intercepted slash)
- `streaming -> idle`: `assistant/message` finalize or explicit abort
- `* -> error`: RPC error event/callback
- `reconnecting -> idle`: RPC transport reaches `ready`

## Streaming behavior

Streaming send/queue behavior:

- send while streaming => `prompt_while_streaming` with steer behavior
- queue while streaming => `prompt_while_streaming` with queue behavior

Idle slash interception applies only to idle compose send/queue paths.
