Goal: fix projection upsert injecting a newline into the user's in-progress draft.

Context:
- On psi launch the RPC connection is asynchronous.  The user can start typing in the
  prompt area before the connection completes.
- When the first real `footer/updated` event arrives (or any event that triggers
  `psi-emacs--upsert-projection-block`), the old "connecting..." projection block is
  deleted and a new one reinserted at `point-max`.
- Before reinsertion, `psi-emacs--ensure-newline-before-projection-append` was called.
  After deleting the old block, `point-max` is the end of the user's draft text, which
  has no trailing newline.  The helper therefore inserted `"\n"` directly into the draft
  area — splitting whatever the user had typed.

Root cause:
- `psi-emacs--projection-render-block` always returns a string starting with `"\n"` when
  non-empty (the blank breathing-room line before the separator).
- `psi-emacs--ensure-newline-before-projection-append` was therefore redundant: the
  rendered block already carries its own leading newline.
- Being redundant, the call's only observable effect was to corrupt the draft.

Required behavior:
- Upserting the projection block (footer update, status update, widget update, etc.) must
  never modify the user's draft text.
- The visual separation between the draft area and the projection block is provided by
  the leading `"\n"` in the rendered block, not by a separate insertion.

Acceptance:
- A user who has typed text in the draft area before the first footer/updated event sees
  their draft text unchanged after the projection block is reinserted.
- All existing Emacs ERT suites continue to pass.
- A focused regression test covers the scenario: draft text is `"hello"` before upsert
  and remains `"hello"` after upsert with a new footer value.

Constraints:
- Change must be localized to `psi-projection.el`; no changes to render logic.
- `psi-emacs--ensure-newline-before-projection-append` may be removed or left as dead
  code; it is no longer called from the upsert path.
