# Design: Emacs search previous prompt inputs

GitHub issue: #51

## Intent

Add a command to the Emacs UI that lets the user **search** their prompt input
history for the current session, rather than stepping through it one entry at a
time with `M-p` / `M-n`.

## Problem Statement

The existing `M-p` / `M-n` navigation is linear — it steps through history one
entry at a time.  When history is long, finding a specific prior prompt requires
many keystrokes and mental scanning.  A search surface (minibuffer completion
over the history list) would let the user jump directly to any prior input.

## Scope

**In scope:**
- A new interactive command `psi-emacs-search-input-history` in `psi-compose.el`
- A keymap binding `M-r` in `psi-mode.el`
- Selecting a candidate populates the input area (same as `M-p` navigation does)
- Pure Emacs-UI concern — no backend changes, no new RPC ops, no new session state

**Out of scope:**
- Persistent history across sessions (history already lives only in
  `psi-emacs-state-input-history`; persistence is a separate concern)
- Cross-session search
- Fuzzy/consult integration as a hard dependency (nice-to-have, not required)
- Editing history entries in the picker

## Concepts

- **`input-history`** — the `psi-emacs-state-input-history` list; newest-first,
  already maintained by `psi-emacs--history-record-input`
- **`completing-read`** — standard Emacs completion UI; already used throughout
  `psi-session-commands.el` and `psi-events.el`; works with consult/vertico/ivy
  automatically when installed
- **`psi-emacs--replace-input-text`** — the canonical setter for the input area;
  already used by `psi-emacs-previous-input` / `psi-emacs-next-input`
- **navigation state** — `input-history-index` + `input-history-stash`; on
  successful selection the current draft is stashed (same as `M-p` does) and the
  navigation index is reset to nil (top of history) so subsequent `M-p`/`M-n`
  steps from the top; `M-n` can recover the pre-search draft; on cancel the
  stash is never written so navigation state is untouched

## Implementation Approaches

### A — `completing-read` over the history list (preferred)

```
psi-emacs-search-input-history
  → guard: psi-emacs--state exists + history non-empty
  → completing-read "Previous input: " history-list  ; history shown as-is, no dedup
  → C-g / empty-string → leave input + navigation state unchanged (no error)
  → on selection: stash current draft (as M-p does)
                  + psi-emacs--replace-input-text(chosen)
                  + reset navigation index to nil (top of history)
```

- Zero new dependencies
- Automatically benefits from consult/vertico/ivy if installed
- Consistent with the existing `psi-emacs--ordered-completing-read` pattern in
  `psi-events.el`
- History entries may be multi-line; `completing-read` displays them as-is —
  acceptable, as entries are typically short-to-medium

### B — `read-string` with `minibuffer-history`

Populate a standard `minibuffer-history` variable and use `read-string` with
that history.  Gives `M-p`/`M-n` inside the minibuffer for free.  Less
discoverable than completing-read; harder to jump to an arbitrary entry.
Not preferred.

### C — Consult-backed search

Use `consult--read` for a richer narrowing experience.  Hard dep on consult;
overkill for this feature.  Not preferred as the primary implementation.

**Decision: approach A.**  Simple, consistent with existing patterns, no new
deps, consult/vertico users get the rich experience automatically.

## Architecture

- **File**: `psi-compose.el` — all history manipulation lives here; new command
  belongs alongside `psi-emacs-previous-input` / `psi-emacs-next-input`
- **Keymap**: `psi-mode.el` — bind in the same `map*` block as `M-p`/`M-n`
- **Pattern followed**: `psi-emacs--ordered-completing-read` in `psi-events.el`
  for the completing-read call shape; `psi-emacs--replace-input-text` +
  `psi-emacs--history-reset-navigation` for the selection effect
- No new architecture introduced; no existing architecture removed

## Acceptance Criteria

1. `M-r` in a psi buffer invokes `psi-emacs-search-input-history`
2. The command opens a minibuffer completion over all entries in
   `psi-emacs-state-input-history` for the current buffer; entries are shown
   as-is with no deduplication
3. Selecting an entry: stashes the current draft, replaces the input area with
   the selected text, and resets the navigation index to nil (top of history)
4. After selection, `M-p` steps to the next older entry from the top of history
   (stash is overwritten with the selected entry at that point); `M-n`
   immediately after selection signals `user-error` (no navigation position yet);
   `M-n` after at least one `M-p` recovers the M-p stash as usual.
   The pre-search draft is stashed on selection and survives until the first `M-p`.
5. `C-g` or empty-string submission cancels without modifying the input area or
   navigation state
6. The command signals `user-error` when history is empty
7. The command signals `user-error` when the buffer is not a psi buffer
8. The binding is documented alongside the existing `M-p`/`M-n` bindings
