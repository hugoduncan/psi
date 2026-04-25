# Steps — 054 TUI active-turn event-log rendering

- [ ] Rewrite `render-active-turn` to iterate `active-turn-order` + `active-turn-items`
      (not `active-turn-events`)
- [ ] For `:tool` items in `render-active-turn`, use `tool-calls` state (not snapshot)
- [ ] For `:thinking` items, use `render-thinking-line` (from 053)
- [ ] For `:text` items, use `render-stream-text`

- [ ] Audit `handle-agent-event` tool lifecycle branches — ensure `active-turn-items`
      is updated with latest snapshot/status for each event kind
- [ ] Remove `append-active-turn-event` calls from all `handle-agent-event` branches
- [ ] Remove `:active-turn-events` from `clear-live-turn`
- [ ] Remove `:active-turn-events` from state init / `render-view` destructuring
- [ ] Update `has-progress?` in `render-view` to use `(seq active-turn-order)`

- [ ] Test: thinking with N deltas → one rendered `· <text>` line (latest text)
- [ ] Test: tool through all lifecycle stages → one rendered row (latest status)
- [ ] Test: interleaved `[thinking-A] [tool] [thinking-B]` renders in correct order

- [ ] Confirm existing ordering tests still green
- [ ] Run full TUI test suite — green
- [ ] Commit
