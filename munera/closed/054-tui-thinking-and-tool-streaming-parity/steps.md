# Steps — 054 TUI thinking and tool streaming parity

- [x] Add `thinking-style` constant to `render.clj`
- [x] Add `render-thinking-line` helper (`· ` prefix + thinking style)
- [x] Update `render-stream-thinking` to use `render-thinking-line`

- [x] Rewrite `render-active-turn` to iterate `active-turn-order` + `active-turn-items`
- [x] `:thinking` items → `render-thinking-line(item.text)`
- [x] `:text` items → `render-stream-text(item.text, width)`
- [x] `:tool` items → `render-tool-calls(tool-calls, [item-id], ...)`
- [x] Remove dead `render-active-turn-event`
- [x] Remove `append-active-turn-event` call from `upsert-thinking-item`
- [x] Remove `append-active-turn-event` call from `upsert-text-item`
- [x] Remove `append-active-turn-event` calls from all `handle-agent-event` branches
- [x] Remove `:stream-thinking` write from `:thinking-delta` handling
- [x] Remove `:active-turn-events`, `:stream-thinking`, and `:active-turn-next-seq`
      from `clear-live-turn`, `support.clj` init state, and `restore-session-view`
- [x] Remove `:active-turn-events` from `render-view` destructuring
- [x] Update `has-progress?` to use `(or (seq active-turn-order) (seq tool-order))`
- [x] Confirm `:stream-text` is dead state — retained in clear-live-turn (set in
      :text-delta but not read by render); left as-is (harmless)

- [x] In `handle-agent-result`, iterate result `:content` blocks: emit
      `{:role :thinking :text ...}` for each `:thinking` block before the
      `:assistant` message
- [x] Add `:thinking` role handling to `render-message`

- [x] Extract private `content-blocks` helper in `transcript.clj`
- [x] Rewrite `"assistant"` branch of `agent-messages->tui-resume-state`:
      single pass over `(content-blocks content)` emitting `:thinking` and tool
      entries in block order, `:assistant` after all blocks if non-blank

- [x] Rewrite existing `active-turn-events` ordering tests to assert on
      `active-turn-order` + `active-turn-items`
- [x] Update `agent-messages->tui-resume-state-rehydrates-tool-rows-test`
      (order unchanged — text still emitted after tool entries)
- [x] Test: thinking with N deltas → one `· <text>` line (latest text)
- [x] Test: tool through all lifecycle stages → one row (latest status)
- [x] Test: `[thinking-A] [tool] [thinking-B]` renders in correct order
- [x] Test: `render-message` with `{:role :thinking}` uses `· ` prefix + thinking style
- [x] Test: `handle-agent-result` with `:thinking` in result content → thinking
      messages appear before the assistant message
- [x] Test: rehydration with `:thinking` blocks → thinking entries appear before
      the assistant entry in the reconstructed message list
- [x] Run full TUI test suite — green (1350 tests, 10271 assertions, 0 failures)
- [x] Commit — c12c4f0f

## Tmux integration scenario (§6)

- [x] Add `write-thinking-fixture!` to `test_harness/tmux.clj`
- [x] Add `delete-thinking-fixture!` to `test_harness/tmux.clj`
- [x] Add `run-thinking-rehydration-scenario!` to `test_harness/tmux.clj`
- [x] Add `tui-tmux-thinking-rehydration-scenario-test` (`^:integration`) to
      `tmux_integration_harness_test.clj`
- [x] Commit
