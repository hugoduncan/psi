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
- [ ] Remove `:active-turn-events`, `:stream-thinking`, and `:active-turn-next-seq`
      from `clear-live-turn`, `support.clj` init state, and `restore-session-view`
- [ ] Remove `:active-turn-events` from `render-view` destructuring
- [ ] Update `has-progress?` to use `(or (seq active-turn-order) (seq tool-order))`
      — retain `tool-order` arm so a tool-only turn does not suppress the spinner
- [ ] Confirm `:stream-text` is dead state (set in `:text-delta` but never read);
      remove from `restore-session-view` and `clear-live-turn` if so

- [ ] In `handle-agent-result`, iterate result `:content` blocks: emit
      `{:role :thinking :text ...}` for each `:thinking` block before the
      `:assistant` message
- [ ] Add `:thinking` role handling to `render-message`

- [ ] Extract private `content-blocks` helper in `transcript.clj` (normalize
      plain vector or structured map to flat block sequence); use it in
      `assistant-tool-call-blocks` and the new single-pass branch
- [ ] Rewrite `"assistant"` branch of `agent-messages->tui-resume-state`:
      single pass over `(content-blocks content)` emitting `:thinking` and tool
      entries in block order, `:assistant` after all blocks if non-blank

- [ ] Rewrite existing `active-turn-events` ordering tests
      (`active-turn-renders-thinking-before-tool-in-arrival-order-test` and
      `active-turn-renders-multiple-thinking-blocks-around-tool-test`) to assert
      on `active-turn-order` + `active-turn-items` — the `(:active-turn-events s)`
      assertions will no longer compile after the field is removed
- [ ] Update `agent-messages->tui-resume-state-rehydrates-tool-rows-test` in
      `app_runtime_test.clj`: after step 4 the assistant text message is emitted
      after tool entries, so the expected `:messages` order changes
- [ ] Test: thinking with N deltas → one `· <text>` line (latest text)
- [ ] Test: tool through all lifecycle stages → one row (latest status)
- [ ] Test: `[thinking-A] [tool] [thinking-B]` renders in correct order
- [ ] Test: `render-message` with `{:role :thinking}` uses `· ` prefix + thinking style
- [ ] Test: `handle-agent-result` with `:thinking` in result content → thinking
      messages appear before the assistant message
- [ ] Test: rehydration with `:thinking` blocks → thinking entries appear before
      the assistant entry in the reconstructed message list
- [ ] Run full TUI test suite — green
- [ ] Commit

## Tmux integration scenario (§6)

- [ ] Add `write-thinking-fixture!` to `test_harness/tmux.clj`:
      compute encoded session dir path from tmpdir, mkdir, write minimal
      `.ndedn` with header + user entry + assistant entry (`:thinking` block +
      `:text` block); return fixture file path
- [ ] Add `delete-thinking-fixture!` to `test_harness/tmux.clj`:
      delete fixture file; delete session dir if empty; do not touch parent dirs
- [ ] Add `run-thinking-rehydration-scenario!` to `test_harness/tmux.clj`:
      preflight → write fixture → start session (working-dir = tmpdir) →
      wait for ready marker → send `/resume` → wait for selector marker →
      send Enter → wait for `"· "` → assert → `/quit` → clean exit;
      cleanup fixture on both success and failure
- [ ] Add `tui-tmux-thinking-rehydration-scenario-test` (`^:integration`) to
      `tmux_integration_harness_test.clj`; delegate to `assert-scenario-result`
- [ ] Commit
