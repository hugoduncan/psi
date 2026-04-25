# 053 — TUI thinking display: style and footer parity

## Goal

Close the visual/style gaps for thinking display in the TUI — the `· ` prefix,
distinct style, and persistence of thinking text in the footer/transcript after
a turn completes.

This task does NOT address the event-log structural rendering problems
(duplicate thinking lines, duplicate tool rows per lifecycle stage, mid-turn
boundary split). Those are tracked in task 054.

## Context

Emacs renders thinking with:
- `· ` prefix (middle dot + space)
- `psi-emacs-assistant-thinking-face` — italic shadow, visually distinct from
  both assistant text and user text
- thinking text archived into the buffer permanently when a turn completes
- thinking text archived as a frozen block at tool-call boundaries (mid-turn)
- thinking blocks reconstructed from message history on rehydration

TUI currently:
- streams thinking live with `ψ⋯ ` prefix + `dim-style` (no visual distinction)
- discards thinking when the turn completes (`clear-live-turn` wipes it)
- skips `:thinking` content blocks in `agent-messages->tui-resume-state`
- has no rehydration path for thinking blocks

## Scope

Three gaps, in dependency order:

### Gap 1 — Style

Thinking text uses the wrong prefix and style.

- Change `render-stream-thinking` to use `· ` prefix (matching Emacs)
- Introduce a `thinking-style` constant distinct from `dim-style`
- Add a `render-thinking-line` helper used by both live and archived rendering

### Gap 2 — Archive thinking into transcript on turn complete

When a turn finishes, accumulated thinking text must be preserved in the
rendered `messages` list, not discarded.

Emacs analogue: `psi-emacs--archive-thinking-line` freezes the in-progress
thinking line as permanent transcript history.

TUI approach:
- Add `:thinking` role handling to `render-message` in `render.clj`
- In `handle-agent-result`, before calling `clear-live-turn`, collect all
  thinking items from `active-turn-items` and promote them into `messages`
  as `{:role :thinking :text ...}` entries (in content-index order)

### Gap 3 — Thinking visible in rehydrated sessions

On session resume, past thinking blocks must appear in the reconstructed
transcript.

- Update `agent-messages->tui-resume-state` in `transcript.clj` to collect
  `:thinking` content blocks from assistant messages and emit
  `{:role :thinking :text ...}` entries alongside assistant text and tool rows

## Out of scope

- Live rendering deduplication (multiple `ψ⋯` lines per frame from event-log
  replay) — tracked in 054
- Mid-turn boundary split (freezing thinking before a tool row during streaming)
  — tracked in 054
- Duplicate tool rows per lifecycle stage — tracked in 054

## Constraints

- `content-display-text` must not return thinking text — it is used for prompt
  construction and must remain text-only
- The `:thinking` message kind must be clearly distinct from `:assistant` in
  `render-message` so it cannot accidentally be submitted as prompt content
- Archived thinking in `messages` is read-only display data only

## Acceptance criteria

1. Live streaming thinking renders with `· ` prefix and a visually distinct
   style (not `dim-style` alone)
2. After a turn completes, thinking text from that turn is visible in the TUI
   transcript (not blank/missing)
3. On session resume, past thinking blocks are visible in the reconstructed
   transcript
4. All existing TUI unit tests remain green
5. New tests cover: style, archive-on-done, rehydration
