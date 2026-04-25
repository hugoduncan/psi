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
messages by extracting text first, then tool-call blocks — two separate passes
over content. `:thinking` content blocks are never visited. Thinking is
invisible on session resume.

### Style: no visual distinction

Live thinking uses `ψ⋯ ` prefix + `dim-style` — indistinguishable from other
de-emphasized content. Emacs uses `· ` prefix + italic shadow face.

## The fix

### 1. Switch rendering from event-log to item-map

`active-turn-items` (map from item-id → latest item data) and
`active-turn-order` (ordered, deduplicated list of item-ids) already exist in
state and already have the right semantics: one entry per item-id regardless of
how many lifecycle events arrive.

- `upsert-thinking-item` already merges the latest `:text` into
  `active-turn-items[thinking/<content-index>]` on every delta.
- `ensure-tool-row` sets `{:item-kind :tool :tool-id ui-id}` in
  `active-turn-items`; full tool state lives in `tool-calls`.

Rewrite `render-active-turn` to iterate `active-turn-order` and look up each
item in `active-turn-items`, dispatching on `:item-kind`:

- `:thinking` → `render-thinking-line(item.text)`
- `:text` → `render-stream-text(item.text, width)`
- `:tool` → `render-tool-calls(tool-calls, [item-id], ...)` — use `tool-calls`
  state (not a snapshot), so the latest status is always shown

This gives one rendered block per item-id. `active-turn-events` no longer
drives rendering and can be removed.

Update `has-progress?` in `render-view` to use `(seq active-turn-order)`
instead of `(seq active-turn-events)`.

### 2. Remove the event-log

`upsert-thinking-item` and `upsert-text-item` both call `append-active-turn-event`
as their first step. Remove those calls. Remove `append-active-turn-event` from
all `handle-agent-event` branches. Remove `:active-turn-events` from
`clear-live-turn`, state init, and the `render-view` destructuring binding.

With `render-active-turn` rewritten, `render-active-turn-event` becomes dead
code — remove it. `stream-thinking` is set on each `:thinking-delta` event but
only read inside `render-active-turn-event` — remove the write in
`:thinking-delta` handling and the field from `clear-live-turn`, state init,
and `restore-session-view`.

Mid-turn ordering falls out naturally: `thinking-item-id` keys on
`content-index`, so a thinking block that arrives before a tool call
(`thinking/0`) and one that arrives after (`thinking/2`) are different items in
`active-turn-order`. Tool item-ids are `"tool/<tool-id>"` — they do not use
content-index — so there is no id collision between thinking and tool items.
No explicit freeze step is needed.

### 3. Archive thinking on turn complete

`handle-agent-result` receives `result` = the last assistant message from the
journal. Its `:content` is a plain vector of typed block maps produced by
`build-final-content`: thinking blocks first (sorted by content-index), then
text, then tool-calls. For the final turn (which is what `:done` signals),
there are no tool-call blocks.

Archive by scanning `:content` for `:thinking` blocks and emitting them into
`messages` before the `:assistant` entry:

- For each block where `(= :thinking (or (:type block) (:kind block)))`: emit
  `{:role :thinking :text (:text block)}` into `messages`
- Then emit `{:role :assistant :text (content-text content)}` as today —
  `content-text` already skips thinking blocks, so no change needed there

If the result has no thinking blocks the behaviour is unchanged: only the
`:assistant` message is added.

This is simpler than reading from `active-turn-items` (no sorting needed) and
is symmetric with rehydration — both read the same canonical content structure.

Add `:thinking` role handling to `render-message` so archived thinking renders
with the `· ` prefix and thinking style.

### 4. Rehydration

`agent-messages->tui-resume-state` currently handles each assistant message
with two separate passes over its `:content`: one for text (via
`message->display-text`) and one for tool-call blocks (via
`assistant-tool-call-blocks`). Replace the `"assistant"` branch with a single
pass over the content blocks of that message, emitting in block order:

- block where `(= :thinking (or (:type block) (:kind block)))` → `{:role :thinking :text (:text block)}` into `messages`
- block where `(= :tool-call (or (:type block) (:kind block)))` → tool-calls/tool-order entry (unchanged logic)
- after iterating all blocks, emit `{:role :assistant :text (message->display-text msg)}` into `messages`
  if non-blank — continue using `message->display-text` for text extraction so the existing
  normalization is preserved

Content may be a plain vector or a `{:kind :structured :blocks [...]}` map.
Extract a private `content-blocks` helper in `transcript.clj` that normalizes
either shape to a flat sequence of block maps, and call it from both the new
single-pass `"assistant"` branch and the existing `assistant-tool-call-blocks`
function (which currently inlines the same normalization). This normalization
step is required before the single pass can proceed.

This is symmetric with the archive approach (step 3) and eliminates the
fragility of multiple passes.

### 5. Style

In `render.clj`, introduce a `thinking-style` constant and `render-thinking-line`
helper (`· ` prefix + thinking style). Use it for both live streaming and
archived rendering.

## Constraints

- No change to the backend event protocol or shared app-runtime code
- `active-turn-order` deduplication invariant must be preserved
- `tool-calls` is the authoritative source for tool status in rendering;
  `active-turn-items` is not used for tool rendering even though it also stores
  `:status` for tool items as a side-effect of lifecycle event handling
- `:thinking` message kind must be clearly distinct from `:assistant` in
  `render-message` — archived thinking is read-only display data, not prompt content
- neither `content-text` nor `content-display-text` return thinking block text —
  do not change either; the archive path uses `content-text`, rehydration uses
  `content-display-text` (via `message->display-text`)
- Archive (step 3) and rehydration (step 4) must read thinking from the same
  canonical content structure so the two paths stay symmetric

## 6. Tmux integration scenario

### Goal

Prove that thinking rehydration (fix 4) and the `· ` style (fix 5) are
observable through a real terminal boundary, without a live LLM.

### Why rehydration is the right target

Live streaming dedup and archive-on-done both require injecting events into a
running turn, which needs either a live LLM or a mock provider wired through
the full CLI stack — out of scope for this slice. Rehydration is fully
provider-independent: the TUI reads a pre-written journal file and reconstructs
the transcript before any model interaction. It is deterministic, stable, and
directly proves the most user-visible regression in the current code (thinking
is invisible after session resume).

### Fixture

The harness writes a minimal `.ndedn` journal file to the correct session
directory before launching the TUI. The file contains:

- **Header line** — `{:type :session :version 4 :id "<uuid>" :timestamp #inst
  "..." :worktree-path "<tmpdir>" :parent-session-id nil :parent-session nil}`
- **User message entry** — `{:id "<uuid>" :parent-id nil :timestamp #inst "..."
  :kind :message :data {:message {:role "user" :content [{:type :text :text
  "explain recursion"}]}}}`
- **Assistant message entry** — same shape; `:content` is a vector with a
  `:thinking` block first, then a `:text` block:
  `[{:type :thinking :text "Let me think about this carefully."} {:type :text
  :text "Recursion is when a function calls itself."}]`

Session directory path: `~/.psi/agent/sessions/--<encoded-tmpdir>--/` where
encoding strips the leading `/` and replaces `/` and `:` with `-`. The file
name is `<timestamp>_<uuid>.ndedn` (any timestamp/uuid values work; the
selector lists by mtime).

The harness creates the temp dir, computes the encoded session dir path, writes
the fixture file, and registers cleanup to delete both on exit.

### Scenario steps

1. Write fixture to `~/.psi/agent/sessions/--<encoded-tmpdir>--/<ts>_<uuid>.ndedn`
2. Launch TUI with `working-dir` set to `tmpdir`
3. Wait for the ready marker
4. Send `/resume` — opens session selector scoped to `tmpdir`
5. Wait for a session-selector marker (the fixture session will be the only
   entry; any stable selector UI text works, e.g. `"Enter=confirm"`)
6. Send `Enter` to select the first (only) session
7. Wait for `"· "` to appear in the pane
8. Assert `"· "` is present (proves thinking rehydration + style)
9. Send `/quit` and assert clean exit

### Fragility notes

- **Session selector navigation**: with a single fixture session, `Enter` on
  the pre-selected first item is sufficient. No `Down` key needed.
- **Selector scope**: `session-selector-init` scopes to `cwd`, so the fixture
  session is the only entry. No risk of selecting a different session.
- **Fixture cleanup**: delete the fixture file and the session dir (if empty)
  after the scenario, regardless of outcome. Do not delete `~/.psi/agent/sessions/`
  itself — it may contain real user sessions.
- **ANSI**: assert on `sanitize-pane-text` output; `· ` survives ANSI stripping.
- **Timestamp**: use `(System/currentTimeMillis)` for the filename prefix and
  `#inst "2024-01-01T00:00:00.000-00:00"` as a static timestamp in the fixture
  body — avoids Instant serialization complexity in the harness.

### Harness additions

- `write-thinking-fixture!` — takes `tmpdir` string, writes the `.ndedn` file,
  returns the fixture file path
- `delete-thinking-fixture!` — deletes the fixture file and the session dir if
  empty
- `run-thinking-rehydration-scenario!` — full scenario function following the
  same shape as `run-slash-autocomplete-scenario!`; accepts the same
  `session-name`, `working-dir`, `launch-command`, timeout, and
  `keep-session-on-failure?` opts; adds `:thinking-marker` (default `"· "`)
  and `:selector-marker` (default `"Enter=confirm"`)

New `^:integration` test in `tmux_integration_harness_test.clj`:
`tui-tmux-thinking-rehydration-scenario-test` — calls
`run-thinking-rehydration-scenario!` and delegates to `assert-scenario-result`.

## Acceptance criteria

1. A thinking block with N deltas renders as exactly one `· <text>` line
   (latest text), not N lines
2. A tool going through all lifecycle stages renders as exactly one row
   (latest status), not one row per stage
3. Thinking and tool calls interleave in correct arrival order:
   `[thinking-A] [tool] [thinking-B]`
4. After a tool event arrives, subsequent thinking for a new content-index
   appears below the tool row
5. After a turn completes, thinking from that turn is visible in the transcript
   as separate `· ` prefixed messages, ordered before the assistant reply
6. On session resume, past thinking blocks are visible in the reconstructed
   transcript ordered before the assistant reply and before any tool rows from
   the same turn
7. Live and archived thinking use `· ` prefix and a visually distinct style
8. All existing TUI unit tests remain green
9. New tests cover: dedup (thinking, tool lifecycle), interleaving,
   archive-on-done, rehydration, style
