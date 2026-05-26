# Plan

## Approach

Add the public command as a small interactive wrapper around the existing prompt focus helper.

## Decisions

- Public command name: `psi-emacs-move-point-to-prompt-end`.
- Location: `components/emacs-ui/psi-entry.el`, because that file already owns public buffer/session entry commands and the private `psi-emacs--focus-input-area` helper.
- Discovery: add an autoload cookie for the public command so `M-x`/autoload generation can discover it consistently with `psi-emacs-start` and `psi-emacs-project`.
- Outside-session behavior: signal `user-error` when `psi-emacs--state` is absent, instead of opening or initializing a session implicitly.
- Movement behavior: delegate to `psi-emacs--focus-input-area`; do not duplicate prompt-boundary math. This intentionally synchronizes visible window points for the current Psi buffer.

## Implementation outline

1. Add `psi-emacs-move-point-to-prompt-end` in `psi-entry.el` near the other public interactive entry commands.
2. Check for initialized Psi state before delegating; raise `user-error` if missing.
3. Delegate to `(psi-emacs--focus-input-area (current-buffer) (selected-window))`.
4. Add focused Emacs tests for empty prompt, non-empty prompt, point in output, outside-session error, and visible-window point synchronization.
5. Run focused Emacs tests, then a broader Emacs test command if practical.

## Risks

- Tests that create multiple windows must restore the original window configuration to avoid leaking editor state across test cases.
- The command should not accidentally initialize prompt state in ordinary buffers; the precondition check should run before the helper.
