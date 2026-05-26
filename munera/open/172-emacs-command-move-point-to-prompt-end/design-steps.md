# Design follow-up steps

- [x] Choose the exact public interactive command name and loading/autoload location so `M-x` discovery is deterministic rather than inferred by implementers.
- [x] Define behavior when the command is invoked outside an initialized Psi Emacs session buffer, including whether it signals `user-error` or no-ops.
- [x] Clarify whether the command should delegate to `psi-emacs--focus-input-area` and therefore update the selected/all visible window points, or only move point in the current buffer.
- [x] Create `plan.md` and `steps.md` before implementation so the command location, tests, and verification order are explicit.
- [x] Align plan/steps verification with acceptance criterion 6 by either adding an explicit post-command prompt editing/submission smoke check or narrowing the acceptance wording to prompt text preservation only.

- [x] Decide whether the new user-visible `M-x psi-emacs-move-point-to-prompt-end` command requires updates to `components/emacs-ui/README.md` and/or `doc/emacs-ui.md`, or explicitly mark documentation changes out of scope.
- [x] Clarify the exact post-command prompt editing/submission smoke test expectation for acceptance criterion 6, including whether submission must go through the existing send/dispatch path and what prompt text/state should be asserted after invoking `psi-emacs-move-point-to-prompt-end`.
