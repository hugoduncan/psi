# 054 — TUI active-turn event-log rendering

## Goal

Fix the structural rendering problems in the TUI's live streaming view that
arise from `active-turn-events` being an append-only log rendered in full on
every frame. Replace the event-log replay model with an in-place item model
that matches Emacs's upsert semantics.

## Context

### The current model

During a streaming turn, `handle-agent-event` appends every incoming event to
`active-turn-events` (via `append-active-turn-event`, always `conj`). On each
render tick, `render-active-turn` iterates the entire list and concatenates the
output of `render-active-turn-event` for every entry.

This produces two classes of bug:

**Bug 1 — Duplicate thinking lines.**
Each `:thinking-delta` event carries the full cumulative thinking text at that
moment. If 10 deltas arrive, `active-turn-events` contains 10 entries, each
with progressively longer text. `render-active-turn` renders all 10 → 10
separate `· <text>` lines appear in the frame output for a single thinking
block. The user sees the same thinking content repeated N times, once per
delta received so far.

**Bug 2 — Duplicate tool rows per lifecycle stage.**
Every tool lifecycle event (`:tool-call-assembly`, `:tool-start`,
`:tool-executing`, `:tool-execution-update`, `:tool-result`) is appended with a
`:snapshot`. `render-active-turn-event` calls `render-tool-snapshot(snapshot)`
for each → a single tool going through all five stages produces five separate
rendered rows in the frame, each showing a different status snapshot.

### The Emacs model (reference)

Emacs uses in-place mutation:
- `psi-emacs--set-thinking-line` replaces the single live thinking region on
  every delta (upsert, not append)
- `psi-emacs--upsert-tool-row` replaces the single tool row region for a
  tool-id on every lifecycle event
- `psi-emacs--assistant-before-tool-event` archives the live thinking block
  before inserting a tool row, so mid-turn ordering is:
  `[thinking-so-far] [tool-row] [more-thinking-if-any]`

### What the TUI needs

The TUI's full-screen repaint model means we do not need buffer markers. But
we do need the same *semantic* invariants:

1. One rendered block per thinking content-index (not one per delta)
2. One rendered row per tool-id (not one per lifecycle event)
3. Correct interleaved ordering: thinking/tool/thinking preserved by
   content-index arrival order
4. Mid-turn thinking freeze: when a tool event arrives while thinking is
   in progress, the thinking text accumulated so far is locked to its position
   above the tool row; subsequent thinking for a new content-index appears
   below

## Design

### Replace event-log render with item-map render

`active-turn-events` currently drives rendering. Replace it with
`active-turn-items` (already maintained in state) as the render source.

`active-turn-items` is a map from item-id → item data, and `active-turn-order`
is the ordered list of item-ids (already deduplicated — an item-id only appears
once in `active-turn-order` regardless of how many lifecycle events it receives).

Rendering change in `render-active-turn`:
- iterate `active-turn-order` (not `active-turn-events`)
- for each item-id, look up the current item in `active-turn-items`
- dispatch on `:item-kind` to render thinking / text / tool

This gives one rendered block per item-id, always showing the latest state.

### Update item data on each lifecycle event

Each lifecycle event should update the item in `active-turn-items` with the
latest snapshot, not just append to `active-turn-events`.

- `:thinking-delta` → update `active-turn-items[thinking/<content-index>]` text
- `:tool-call-assembly`, `:tool-start`, `:tool-executing`,
  `:tool-execution-update`, `:tool-result` → update
  `active-turn-items[<ui-id>]` with latest snapshot/status

`active-turn-events` can be retained for debugging or dropped entirely; it
should no longer drive rendering.

### Mid-turn thinking freeze (boundary split)

When a tool event arrives while a thinking block is in progress:

- The thinking item for that content-index is already in `active-turn-items`
  with its current text and appears in `active-turn-order` before the tool item
- No explicit "freeze" step is needed for ordering — the item-map model
  preserves order naturally
- However: subsequent thinking deltas for a *new* content-index (after the
  tool) must appear as a *new* item in `active-turn-order` after the tool item,
  not merged into the pre-tool thinking item

This is already handled correctly by `thinking-item-id` keying on
`content-index`. A post-tool thinking block arrives with a different
`content-index`, gets a new item-id (`thinking/2`), and is appended to
`active-turn-order` after the tool item. No extra logic needed.

### Render-active-turn rewrite

```
render-active-turn [state spinner-char width]
  for each item-id in active-turn-order:
    item = active-turn-items[item-id]
    case item-kind:
      :thinking → render-thinking-line(item.text)   [from 053]
      :text     → render-stream-text(item.text, width)
      :tool     → render-tool-calls(tool-calls, [item-id], spinner-char, width, ...)
```

For `:tool`, use `tool-calls` state (not a snapshot from the event), so the
latest tool status is always shown.

### Clean up active-turn-events

`append-active-turn-event` and the `:active-turn-events` key can be removed
from state once rendering no longer depends on them. Remove the `conj` calls
from all `handle-agent-event` branches. Keep `active-turn-items` and
`active-turn-order` as the sole live-turn state.

## Constraints

- No change to the backend event protocol or shared app-runtime code
- `active-turn-order` deduplication invariant must be preserved
- `tool-calls` must remain the authoritative source for tool status in rendering
  (not event snapshots)
- Existing tests for ordering (thinking before tool, multiple thinking blocks
  around a tool) must remain green
- 053 (style + archive) should land first; this task depends on the
  `render-thinking-line` helper from 053

## Acceptance criteria

1. A thinking block with N deltas renders as exactly one `· <text>` line
   (showing the latest text), not N lines
2. A tool going through all lifecycle stages renders as exactly one tool row
   (showing the latest status), not one row per stage
3. Multiple thinking blocks interleaved with tool calls render in correct
   content-index order: `[thinking-A] [tool] [thinking-B]`
4. After a tool event arrives, subsequent thinking deltas for a new
   content-index appear below the tool row, not merged with pre-tool thinking
5. All existing TUI ordering tests remain green
6. New tests cover: single-line dedup for thinking, single-row dedup for tool
   lifecycle, interleaved ordering
