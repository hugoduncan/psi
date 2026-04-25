# Plan — 053 TUI thinking display: style and footer parity

Implement the three gaps in dependency order.

## Step order

1. **Style** (Gap 1) — introduce `thinking-style` constant and `render-thinking-line`
   helper. Update `render-stream-thinking` to use `· ` prefix + thinking style.
   Purely additive; touches only the render layer.

2. **Archive on turn complete** (Gap 2) — add `:thinking` role to `render-message`.
   In `handle-agent-result`, collect thinking items from `active-turn-items`
   (sorted by content-index) and prepend them to `messages` before clearing the
   live turn.

3. **Rehydration** (Gap 3) — update `agent-messages->tui-resume-state` in
   `transcript.clj` to collect `:thinking` content blocks from assistant messages
   and emit `{:role :thinking :text ...}` entries in the reconstructed message list.

## Test plan

- `render-stream-thinking` uses `· ` prefix + thinking style (not `dim-style`)
- `render-message` renders `{:role :thinking}` with `· ` prefix + thinking style
- `handle-agent-result` with thinking in flight → thinking appears in `messages`
  before the assistant reply
- `agent-messages->tui-resume-state` with assistant message containing `:thinking`
  block → thinking entry in reconstructed messages
