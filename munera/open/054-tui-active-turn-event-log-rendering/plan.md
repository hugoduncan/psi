# Plan — 054 TUI active-turn event-log rendering

Depends on 053 (provides `render-thinking-line`).

## Step order

1. **Rewrite `render-active-turn`** to iterate `active-turn-order` + `active-turn-items`
   instead of `active-turn-events`. For `:tool` items, look up from `tool-calls`
   state (not a snapshot). For `:thinking` items, use `render-thinking-line`.
   For `:text` items, use `render-stream-text`.

2. **Update item data on lifecycle events** — in each `handle-agent-event` branch,
   ensure `active-turn-items[item-id]` is updated with the latest snapshot/text
   (already done for most paths; audit and fill gaps for tool lifecycle events).

3. **Remove `active-turn-events` from rendering** — delete `append-active-turn-event`
   calls from all `handle-agent-event` branches. Remove `:active-turn-events`
   from `clear-live-turn` and state init. Remove the key from `render-view`
   destructuring and `has-progress?` check (use `active-turn-order` instead).

4. **Tests** — add focused tests proving:
   - thinking with N deltas → one rendered line (latest text)
   - tool through all lifecycle stages → one rendered row (latest status)
   - interleaved ordering: thinking/tool/thinking in content-index order

## Notes

- `active-turn-order` already deduplicates by item-id; no change needed there
- `has-progress?` in `render-view` should use `(seq active-turn-order)` instead
  of `(seq active-turn-events)`
- The existing ordering tests (`active-turn-renders-thinking-before-tool-in-arrival-order-test`,
  `active-turn-renders-multiple-thinking-blocks-around-tool-test`) should remain
  green with no changes — they test `active-turn-order` contents and position
  comparisons in the rendered output, both of which are preserved
