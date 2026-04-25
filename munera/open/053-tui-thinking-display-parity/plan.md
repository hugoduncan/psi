# Plan — 053 TUI thinking display parity

Implement the four gaps in dependency order.

## Step order

1. **Style** (Gap 4) — introduce a thinking style constant and `· ` prefix in
   `tool_render` / `render.clj`. Purely additive; touches only render layer.
   Update `render-stream-thinking` to use the new style.

2. **Archive on turn complete** (Gap 1) — extend the message kind model to
   include `:thinking`. Update `render-message` to render thinking messages.
   In `handle-agent-event` for `:stream-done`, promote accumulated thinking
   items into `messages`. Clear them from `active-turn-items`.

3. **Boundary split** (Gap 2) — in `handle-agent-event` for
   `:tool-call-assembly` (phase `:start`), if `stream-thinking` is non-blank,
   archive the current thinking block into `messages` before processing the
   tool event.

4. **Rehydration** (Gap 3) — update
   `psi.app-runtime.transcript/agent-messages->tui-resume-state` to collect
   `:thinking` content blocks from assistant messages and include them as
   `{:role :thinking :text ...}` entries in the reconstructed message list.

## Test plan

- `render-stream-thinking` uses thinking style with `· ` prefix
- `render-message` renders `{:role :thinking}` with `· ` prefix + thinking style
- `handle-agent-event :stream-done` with thinking in flight → thinking appears in messages
- `handle-agent-event :tool-call-assembly` mid-thinking → thinking archived before tool row
- `agent-messages->tui-resume-state` with assistant message containing `:thinking` block
  → thinking entry in reconstructed messages
