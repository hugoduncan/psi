# Implementation steps

- [ ] Add `psi-emacs-move-point-to-prompt-end` to `components/emacs-ui/psi-entry.el` as an autoloaded interactive command.
- [ ] Make the command signal `user-error` outside an initialized Psi Emacs session buffer.
- [ ] Delegate command movement to `psi-emacs--focus-input-area` with the current buffer and selected window.
- [ ] Add focused Emacs tests for empty prompt, non-empty prompt, point-in-output recovery, outside-session error, visible-window point synchronization, and post-command prompt editing/submission behavior.
- [ ] Verify focused Emacs tests.
