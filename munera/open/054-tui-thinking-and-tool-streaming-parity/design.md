# 054 — TUI thinking and tool streaming parity

## Goal

Bring TUI live streaming display to parity with Emacs for thinking blocks and
tool calls: correct rendering during a turn, correct persistence after a turn,
and correct reconstruction on rehydration.

## The problem

### Structural: event-log replay

During a streaming turn, every incoming event is appended to
`active-turn-events` (always `conj`). `render-active-turn` iterates the entire
list on every frame and concatenates the output of `render-active-turn-event`
for every entry.

This produces two rendering bugs:

**Duplicate thinking lines.** Each `:thinking-delta` carries the full
cumulative text so far. 10 deltas → 10 entries → 10 `ψ⋯ <text>` lines in the
frame, each longer than the last. One thinking block should render as one line.

**Duplicate tool rows per lifecycle stage.** Every tool lifecycle event
(`:tool-call-assembly`, `:tool-start`, `:tool-executing`,
`:tool-execution-update`, `:tool-result`) is appended with a `:snapshot`.
Each renders a separate tool row. One tool should render as one row showing
the latest status.

### Persistence: thinking is discarded on turn complete

When a turn finishes, `clear-live-turn` wipes `active-turn-events`,
`active-turn-items`, `active-turn-order`, and `stream-thinking`. Accumulated
thinking text is not promoted into `messages` — it is lost. Thinking is
invisible after a turn completes.

### Rehydration: thinking blocks are skipped

`agent-messages->tui-resume-state` in `transcript.clj` processes assistant
messages by extracting text and tool-call blocks. `:thinking` content blocks
are explicitly skipped (`message-text/content-text-parts` returns `[]` for
`:thinking` kind). Thinking is invisible on session resume.

### Style: no visual distinction

Live thinking uses `ψ⋯ ` prefix + `dim-style` — indistinguishable from other
de-emphasized content. Emacs uses `· ` prefix + italic shadow face.

## The fix

### 1. Switch rendering from event-log to item-map

`active-turn-items` (map from item-id → latest item data) and
`active-turn-order` (ordered, deduplicated list of item-ids) already exist in
state and already have the right semantics: one entry per item-id regardless of
how many lifecycle events arrive.

Rewrite `render-active-turn` to iterate `active-turn-order` and look up each
item in `active-turn-items`, dispatching on `:item-kind`:

- `:thinking` → `render-thinking-line(item.text)`
- `:text` → `render-stream-text(item.text, width)`
- `:tool` → `render-tool-calls(tool-calls, [item-id], ...)` — use `tool-calls`
  state, not a snapshot, so the latest status is always shown

This gives one rendered block per item-id. `active-turn-events` no longer
drives rendering and can be removed.

### 2. Keep item data current on each event

Audit `handle-agent-event` to ensure every event branch updates
`active-turn-items[item-id]` with the latest text/status, not just appends to
`active-turn-events`. Remove `append-active-turn-event` calls and the
`:active-turn-events` key from state entirely.

Mid-turn ordering falls out naturally: `thinking-item-id` keys on
`content-index`, so pre-tool thinking (`thinking/0`) and post-tool thinking
(`thinking/2`) are different items in `active-turn-order` — no explicit freeze
step needed.

### 3. Archive thinking on turn complete

In `handle-agent-result`, before `clear-live-turn`, collect all `:thinking`
items from `active-turn-items` (sorted by content-index) and promote them into
`messages` as `{:role :thinking :text ...}` entries, interleaved with the
assistant reply in content-index order.

Add `:thinking` role handling to `render-message` so archived thinking renders
with the `· ` prefix and thinking style.

### 4. Rehydration

Update `agent-messages->tui-resume-state` in `transcript.clj` to collect
`:thinking` content blocks from assistant messages and emit
`{:role :thinking :text ...}` entries in the reconstructed message list,
interleaved with assistant text and tool rows.

### 5. Style

Introduce a `thinking-style` constant and `render-thinking-line` helper
(`· ` prefix + thinking style). Use it for both live streaming and archived
rendering. `content-display-text` must not be changed — it is used for prompt
construction and must remain text-only.

## Constraints

- No change to the backend event protocol or shared app-runtime code
- `active-turn-order` deduplication invariant must be preserved
- `tool-calls` is the authoritative source for tool status in rendering
- `:thinking` message kind must be clearly distinct from `:assistant` in
  `render-message` — archived thinking is read-only display data, not prompt content
- `content-display-text` returns `""` for thinking blocks — do not change this

## Acceptance criteria

1. A thinking block with N deltas renders as exactly one `· <text>` line
   (latest text), not N lines
2. A tool going through all lifecycle stages renders as exactly one row
   (latest status), not one row per stage
3. Thinking and tool calls interleave in correct content-index arrival order:
   `[thinking-A] [tool] [thinking-B]`
4. After a tool event arrives, subsequent thinking for a new content-index
   appears below the tool row
5. After a turn completes, thinking from that turn is visible in the transcript
6. On session resume, past thinking blocks are visible in the reconstructed
   transcript
7. Live and archived thinking use `· ` prefix and a visually distinct style
8. All existing TUI unit tests remain green
9. New tests cover: dedup (thinking, tool lifecycle), interleaving,
   archive-on-done, rehydration, style
