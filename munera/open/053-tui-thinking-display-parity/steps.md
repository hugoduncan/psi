# Steps — 053 TUI thinking display: style and footer parity

- [ ] Gap 1 — style: add `thinking-style` constant to `render.clj`
- [ ] Gap 1 — style: add `render-thinking-line` helper (prefix `· ` + thinking style)
- [ ] Gap 1 — style: update `render-stream-thinking` to use `render-thinking-line`
- [ ] Gap 1 — test: `render-stream-thinking` output uses `· ` prefix + thinking style

- [ ] Gap 2 — archive: add `:thinking` role handling to `render-message` in `render.clj`
- [ ] Gap 2 — archive: in `handle-agent-result`, collect thinking items from
      `active-turn-items` (sorted by content-index) and conj as `{:role :thinking}`
      entries into `messages` before `clear-live-turn`
- [ ] Gap 2 — test: `render-message` with `{:role :thinking}` renders `· ` prefix
- [ ] Gap 2 — test: `handle-agent-result` with in-flight thinking → thinking in messages

- [ ] Gap 3 — rehydration: update `agent-messages->tui-resume-state` in `transcript.clj`
      to collect `:thinking` content blocks and emit `{:role :thinking :text ...}` entries
- [ ] Gap 3 — test: rehydration with thinking blocks → thinking entries in reconstructed messages

- [ ] Run full TUI test suite — green
- [ ] Commit
