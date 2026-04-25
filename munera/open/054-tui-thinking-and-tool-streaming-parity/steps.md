# Steps — 054 TUI thinking and tool streaming parity

- [ ] Add `thinking-style` constant to `render.clj`
- [ ] Add `render-thinking-line` helper (`· ` prefix + thinking style)
- [ ] Update `render-stream-thinking` to use `render-thinking-line`

- [ ] Rewrite `render-active-turn` to iterate `active-turn-order` + `active-turn-items`
- [ ] `:thinking` items → `render-thinking-line(item.text)`
- [ ] `:text` items → `render-stream-text(item.text, width)`
- [ ] `:tool` items → `render-tool-calls(tool-calls, [item-id], ...)`
- [ ] Remove `append-active-turn-event` calls from all `handle-agent-event` branches
- [ ] Remove `:active-turn-events` from `clear-live-turn` and state init
- [ ] Remove `:active-turn-events` from `render-view` destructuring
- [ ] Update `has-progress?` to use `(seq active-turn-order)`

- [ ] In `handle-agent-result`, collect `:thinking` items from `active-turn-items`
      (sorted by content-index) and conj into `messages` before `clear-live-turn`
- [ ] Add `:thinking` role handling to `render-message`

- [ ] Update `agent-messages->tui-resume-state` to collect `:thinking` content
      blocks and emit `{:role :thinking :text ...}` entries

- [ ] Test: thinking with N deltas → one `· <text>` line (latest text)
- [ ] Test: tool through all lifecycle stages → one row (latest status)
- [ ] Test: `[thinking-A] [tool] [thinking-B]` renders in correct order
- [ ] Test: `render-message` with `{:role :thinking}` uses `· ` prefix + thinking style
- [ ] Test: `handle-agent-result` with in-flight thinking → thinking in messages
- [ ] Test: rehydration with `:thinking` blocks → thinking entries in reconstructed messages
- [ ] Confirm existing ordering tests still green
- [ ] Run full TUI test suite — green
- [ ] Commit
