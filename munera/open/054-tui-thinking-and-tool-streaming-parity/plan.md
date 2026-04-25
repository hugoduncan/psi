# Plan — 054 TUI thinking and tool streaming parity

## Step order

1. **Style** — add `thinking-style` constant and `render-thinking-line` helper
   (`· ` prefix + thinking style) to `render.clj`. Update `render-stream-thinking`
   to use it. Purely additive.

2. **Switch render source** — rewrite `render-active-turn` to iterate
   `active-turn-order` + `active-turn-items` instead of `active-turn-events`.
   For `:tool` items use `tool-calls` state. For `:thinking` use
   `render-thinking-line`. For `:text` use `render-stream-text`.

3. **Remove event-log** — delete `append-active-turn-event` calls from all
   `handle-agent-event` branches. Remove `:active-turn-events` from
   `clear-live-turn`, state init, `render-view` destructuring, and
   `has-progress?` check (use `(seq active-turn-order)` instead).

4. **Archive on turn complete** — in `handle-agent-result`, before
   `clear-live-turn`, collect `:thinking` items from `active-turn-items`
   (sorted by content-index) and conj them into `messages` as
   `{:role :thinking :text ...}`. Add `:thinking` role to `render-message`.

5. **Rehydration** — update `agent-messages->tui-resume-state` in
   `transcript.clj` to collect `:thinking` content blocks from assistant
   messages and emit `{:role :thinking :text ...}` entries.

6. **Tests** — add focused tests for each gap; confirm existing ordering tests
   still green; run full suite.

## Notes

- Steps 2 and 3 are one coherent change — do them together
- Archive (step 4) depends on `active-turn-items` being current, which step 2
  ensures (rendering now reads from items, not events)
- Rehydration (step 5) is independent of the rest and can be done in any order
