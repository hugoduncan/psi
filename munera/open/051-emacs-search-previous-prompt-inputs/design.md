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
- A keymap binding in `psi-mode.el` (e.g. `C-c /` or `M-r`)
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
- **navigation state** — `input-history-index` + `input-history-stash`; search
  selection should reset this (same as recording a new input does) so `M-p`/`M-n`
  still work coherently after a search pick

## Implementation Approaches

### A — `completing-read` over the history list (preferred)

```
psi-emacs-search-input-history
  → guard: psi-emacs--state exists + history non-empty
  → completing-read "Previous input: " history-list
  → on selection: psi-emacs--replace-input-text(chosen)
                  + psi-emacs--history-reset-navigation
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

1. `M-r` (or `C-c /`) in a psi buffer invokes `psi-emacs-search-input-history`
2. The command opens a minibuffer completion over all entries in
   `psi-emacs-state-input-history` for the current buffer
3. Selecting an entry replaces the input area with that text (point at end)
4. After selection, `M-p`/`M-n` navigation works correctly from the new input
   (navigation index is reset)
5. The command signals `user-error` when history is empty
6. The command signals `user-error` when the buffer is not a psi buffer
7. Cancelling the completing-read leaves the input area unchanged
8. The binding is documented alongside the existing `M-p`/`M-n` bindings
