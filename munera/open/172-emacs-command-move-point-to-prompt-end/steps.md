# Implementation steps

- [x] Add `psi-emacs-move-point-to-prompt-end` to `components/emacs-ui/psi-entry.el` as an autoloaded interactive command.
- [x] Make the command signal `user-error` outside an initialized Psi Emacs session buffer.
- [x] Delegate command movement to `psi-emacs--focus-input-area` with the current buffer and selected window.
- [x] Add focused Emacs tests for empty prompt, non-empty prompt, point-in-output recovery, outside-session error, visible-window point synchronization, and post-command prompt editing/submission behavior. The submission smoke test must invoke `psi-emacs-send-from-buffer` with no prefix after appending to the prompt post-command, capture the existing dispatch/send seam, assert exact prompt text `before after` on the normal non-slash steer path, and assert the draft/input area reaches the existing post-submit consumed/reset state.
- [x] Update Emacs frontend user docs that enumerate commands (`components/emacs-ui/README.md` and `doc/emacs-ui.md`) to mention `M-x psi-emacs-move-point-to-prompt-end`.
- [x] Verify focused Emacs tests.
