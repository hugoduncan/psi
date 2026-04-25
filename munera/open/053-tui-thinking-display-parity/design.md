# 053 — TUI thinking display parity with Emacs

## Goal

Bring TUI thinking display to parity with Emacs across four gaps:
archive, boundary split, rehydration, and styling.

## Context

Emacs treats thinking as a first-class transcript element:
- thinking text is archived (frozen) into the buffer when a turn completes
- thinking is archived at tool-call boundaries so it stays visible mid-turn
- rehydrated sessions reconstruct thinking blocks from message history
- thinking has a visually distinct face (italic shadow, `· ` prefix)

TUI currently:
- streams thinking live during a turn (`ψ⋯ <text>` in dim-style)
- drops thinking when the turn completes — it does not survive into `messages`
- skips `:thinking` content blocks in `agent-messages->tui-resume-state`
- skips `:thinking` blocks in `content-display-text` (message-text ns)
- has no boundary split when a tool call interrupts live thinking
- uses generic `dim-style` for thinking — no typographic distinction

## Scope

Four gaps to close, in dependency order:

### Gap 1 — Archive thinking into transcript on turn complete

When a turn finishes (`stream-done` / `:on-agent-done`), any accumulated
thinking text for that turn must be preserved in the rendered message list,
not discarded.

Emacs analogue: `psi-emacs--archive-thinking-line` freezes the in-progress
thinking line as permanent transcript history.

TUI approach: extend `render-message` (or introduce a thinking message kind)
so that thinking blocks are rendered with their own prefix/style. Ensure
`handle-agent-event` for `:stream-done` promotes accumulated thinking items
into the message list alongside the assistant text.

### Gap 2 — Archive thinking at tool-call boundary (mid-turn split)

When a tool-call event arrives while a thinking block is in progress, the
live thinking text must be frozen before the tool row is inserted, so the
ordering is: thinking-so-far → tool row → (possibly more thinking after).

Emacs analogue: `psi-emacs--pre-tool-output-thinking-split`.

TUI approach: in `handle-agent-event` for `:tool-call-assembly` (and related
tool lifecycle events), if `stream-thinking` is non-blank, emit a thinking
archive step before processing the tool event.

### Gap 3 — Thinking visible in rehydrated sessions

On session resume/navigation, past thinking blocks must be reconstructed from
message history and rendered alongside tool calls and assistant text.

Two sites need updating:
- `psi.app-runtime.transcript/agent-messages->tui-resume-state` — currently
  skips `:thinking` content blocks; should collect them and include them in
  the reconstructed message list (as a thinking message kind).
- `psi.agent-session.message-text/content-display-text` currently returns nil
  for `:thinking` kind blocks — this is correct for plain text extraction but
  the transcript reconstruction path needs a separate thinking-aware pass.

### Gap 4 — Visually distinct thinking style

Thinking text in TUI uses `dim-style` — indistinguishable from other
de-emphasized content. It should have a distinct visual identity matching
the intent of Emacs' italic shadow face.

Proposed: prefix `· ` (middle dot + space, matching Emacs) rendered in a
dedicated thinking style (e.g. dim + italic where the terminal supports it,
or a distinct colour). Both live streaming and archived thinking use the
same style.

## Constraints

- No new external dependencies.
- `content-display-text` must not be changed to return thinking text —
  it is used for prompt construction and must remain text-only.
- The thinking message kind must be clearly distinguished from `:assistant`
  in `render-message` so it cannot be accidentally submitted as prompt content.
- Archived thinking in `messages` must be read-only display data only.

## Acceptance criteria

1. After a turn completes, thinking text from that turn is visible in the TUI
   transcript (not blank/missing).
2. When a tool call arrives mid-thinking, the thinking text accumulated so far
   is visible above the tool row, and any further thinking after the tool
   completes appears below it.
3. On session resume (rehydration), past thinking blocks are visible in the
   reconstructed transcript.
4. Live and archived thinking use a visually distinct style — different from
   both `dim-style` assistant text and `user-style` — with the `· ` prefix.
5. All existing TUI unit tests remain green.
6. New tests cover: archive-on-done, boundary-split, rehydration, style.
