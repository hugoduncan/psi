# Task 074 — Emacs footer inclusion in submitted prompt

## Goal

Fix an occasional bug where the projection/footer block content is included in
the submitted prompt text, both in what is sent to the backend and in the
visual transcript display.

## Problem statement

`psi-emacs--draft-end-position` determines the end of the editable input range.
It returns `(car (psi-emacs--region-bounds 'projection 'main))` — the start of
the projection block — when the projection region is live, correctly excluding
the footer.  When `psi-emacs--region-bounds` returns `nil` it falls back to
`point-max`, which includes the entire projection/footer block.

The projection region is deleted and reinserted on every
`psi-emacs--upsert-projection-block` call: old markers are nil'd during
`psi-emacs--region-unregister`, new markers are registered after reinsertion.
If `psi-emacs--composed-text` (or any path that calls
`psi-emacs--draft-end-position`) is evaluated during the narrow window between
unregister and re-register — or after a marker-staleness event on longer
sessions — `region-bounds` returns `nil` and the fallback fires.

This causes two visible symptoms:
1. The footer text is captured as part of the composed input and sent to the
   backend.
2. `psi-emacs--append-user-message-to-transcript` inserts the "User: ..." line
   at `point-max` (after the footer), so the transcript display shows the
   footer appearing inside the user turn.

The input separator marker (`psi-emacs-state-input-separator-marker`) is
maintained independently of the projection region and is more stable across
re-render cycles.  It provides a reliable upper bound for the input range.

## Required behaviour

- `psi-emacs--draft-end-position` must never return a position inside or beyond
  the projection/footer block.
- When the projection region markers are stale or absent, the function must use
  the input separator position as a backstop before falling back to `point-max`.
- The fix must not affect the normal path (live projection region markers).
- `psi-emacs--transcript-append-position` inherits the fix transitively through
  `psi-emacs--draft-end-position`; no separate change is needed there.

## Scope

- Single function change: `psi-emacs--draft-end-position` in `psi-compose.el`.
- No protocol, state-shape, or backend changes.
- Existing tests must continue to pass; a regression test for the stale-region
  path should be added.

## Acceptance criteria

- `psi-emacs--draft-end-position` returns the input separator position when the
  projection region is absent/stale and a valid separator marker exists.
- `psi-emacs--draft-end-position` returns `point-max` only when neither the
  projection region nor the input separator is available.
- Submitting a prompt when the projection region markers are nil does not
  include footer content in the composed text.
- The "User: ..." transcript line is inserted above the projection block, not
  after it, even when the projection region is transiently unregistered.
- A unit test covers the stale-projection-region fallback path.
