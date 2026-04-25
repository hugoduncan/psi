# Steps — 054 TUI thinking and tool streaming parity

- [ ] Add `thinking-style` constant to `render.clj`
- [ ] Add `render-thinking-line` helper (`· ` prefix + thinking style)
- [ ] Update `render-stream-thinking` to use `render-thinking-line`

- [ ] Rewrite `render-active-turn` to iterate `active-turn-order` + `active-turn-items`
- [ ] `:thinking` items → `render-thinking-line(item.text)`
- [ ] `:text` items → `render-stream-text(item.text, width)`
- [ ] `:tool` items → `render-tool-calls(tool-calls, [item-id], ...)`
- [ ] Remove dead `render-active-turn-event`
- [ ] Remove `append-active-turn-event` call from `upsert-thinking-item`
- [ ] Remove `append-active-turn-event` call from `upsert-text-item`
- [ ] Remove `append-active-turn-event` calls from all `handle-agent-event` branches
- [ ] Remove `:stream-thinking` write from `:thinking-delta` handling
- [ ] Remove `:active-turn-events` and `:stream-thinking` from `clear-live-turn` and state init
- [ ] Remove `:active-turn-events` from `render-view` destructuring
- [ ] Remove `:stream-thinking` from `restore-session-view`
- [ ] Update `has-progress?` to use `(seq active-turn-order)`

- [ ] In `handle-agent-result`, iterate result `:content` blocks: emit
      `{:role :thinking :text ...}` for each `:thinking` block before the
      `:assistant` message
- [ ] Add `:thinking` role handling to `render-message`

- [ ] Extract private `content-blocks` helper in `transcript.clj` (normalize
      plain vector or structured map to flat block sequence); use it in
      `assistant-tool-call-blocks` and the new single-pass branch
- [ ] Rewrite `"assistant"` branch of `agent-messages->tui-resume-state`:
      single pass over `(content-blocks content)` emitting `:thinking` and tool
      entries in block order, `:assistant` after all blocks

- [ ] Test: thinking with N deltas → one `· <text>` line (latest text)
- [ ] Test: tool through all lifecycle stages → one row (latest status)
- [ ] Test: `[thinking-A] [tool] [thinking-B]` renders in correct order
- [ ] Test: `render-message` with `{:role :thinking}` uses `· ` prefix + thinking style
- [ ] Test: `handle-agent-result` with `:thinking` in result content → thinking
      messages appear before the assistant message
- [ ] Test: rehydration with `:thinking` blocks → thinking entries appear before
      the assistant entry in the reconstructed message list
- [ ] Confirm existing ordering tests still green
- [ ] Run full TUI test suite — green
- [ ] Commit
