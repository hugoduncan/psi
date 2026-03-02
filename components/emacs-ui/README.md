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

## Streaming behavior

Streaming send/queue behavior is unchanged:

- send while streaming => `prompt_while_streaming` with steer behavior
- queue while streaming => `prompt_while_streaming` with queue behavior

Idle slash interception applies only to idle compose send/queue paths.
