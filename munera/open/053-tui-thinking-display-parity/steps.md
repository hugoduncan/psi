# Steps — 053 TUI thinking display parity

- [ ] Gap 4 — style: add `thinking-style` constant + `· ` prefix to `render-stream-thinking` in `render.clj`
- [ ] Gap 4 — style: add `render-thinking-line` helper (used by both live and archived rendering)
- [ ] Gap 1 — archive: add `:thinking` role handling to `render-message` in `render.clj`
- [ ] Gap 1 — archive: in `handle-agent-event` for `:stream-done`, promote thinking items into `messages`
- [ ] Gap 1 — test: `render-message` with `{:role :thinking}` renders `· ` prefix + thinking style
- [ ] Gap 1 — test: `:stream-done` with in-flight thinking → thinking in messages
- [ ] Gap 2 — boundary split: in `:tool-call-assembly` handler, archive live thinking before tool row
- [ ] Gap 2 — test: tool event mid-thinking → thinking above tool row in messages
- [ ] Gap 3 — rehydration: update `agent-messages->tui-resume-state` to collect `:thinking` blocks
- [ ] Gap 3 — test: rehydration with thinking blocks → thinking entries in reconstructed messages
- [ ] Run full TUI test suite — green
- [ ] Commit
