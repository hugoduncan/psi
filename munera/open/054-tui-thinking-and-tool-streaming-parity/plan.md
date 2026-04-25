# Plan — 054 TUI thinking and tool streaming parity

## Step order

1. **Style** — add `thinking-style` constant and `render-thinking-line` helper
   (`· ` prefix + thinking style) to `render.clj`. Update `render-stream-thinking`
   to use it. Purely additive.

2. **Switch render source + remove event-log** — rewrite `render-active-turn` to
   iterate `active-turn-order` + `active-turn-items` instead of `active-turn-events`.
   For `:tool` items use `tool-calls` state. For `:thinking` use
   `render-thinking-line`. For `:text` use `render-stream-text`. Remove
   `append-active-turn-event` calls from `upsert-thinking-item`, `upsert-text-item`,
   and all `handle-agent-event` branches. Remove `:active-turn-events` from
   `clear-live-turn`, state init, `render-view` destructuring. Update
   `has-progress?` to use `(seq active-turn-order)`.

3. **Archive on turn complete** — in `handle-agent-result`, iterate the result
   `:content` blocks in order: emit `{:role :thinking :text ...}` for each
   `:thinking` block before the `:assistant` message. Add `:thinking` role to
   `render-message`.

4. **Rehydration** — rewrite the `"assistant"` branch of
   `agent-messages->tui-resume-state` as a single pass over content blocks,
   emitting `:thinking` and tool entries in block order, then `:assistant` after
   all blocks. Normalize content to a block sequence first (plain vector or
   structured map) before iterating.

5. **Tests** — add focused tests for each gap; confirm existing ordering tests
   still green; run full suite.

## Notes

- Step 2 is one coherent change — render rewrite and event-log removal together
- Archive (step 3) reads from `result` content, not `active-turn-items` — same
  source as rehydration, keeping the two paths symmetric
- Rehydration (step 4) is independent and can be done in any order after step 3
  establishes the `:thinking` message role
