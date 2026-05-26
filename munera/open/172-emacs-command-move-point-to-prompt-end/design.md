# Emacs command to move point to prompt entry end

## Intent

Add a user-invokable Emacs `M-x` command that moves point to the end of the current Psi prompt entry area.

The command should give Emacs users a direct, discoverable way to return to where new prompt text is entered after navigating or inspecting earlier session output.

## Problem

Psi Emacs buffers contain generated conversation output and a prompt entry area. Users can move point away from the prompt while reading, copying, or inspecting output. Returning to the exact prompt-entry end should not require manual buffer navigation or knowledge of internal buffer layout.

## Scope

This task covers one Emacs interactive command for the current Psi session buffer:

- The command is available via `M-x` as `psi-emacs-move-point-to-prompt-end`.
- The public command is defined/autoloaded from `components/emacs-ui/psi-entry.el`, the same module that owns `psi-emacs-start`, `psi-emacs-project`, and the existing prompt-focus helper.
- When invoked from a Psi session buffer, it moves point to the end of the prompt entry area.
- It preserves the existing prompt entry contents.
- It behaves consistently whether the prompt entry is empty or already contains text.
- It uses the existing Emacs session buffer/prompt-entry model rather than introducing a second prompt representation.
- It delegates to `psi-emacs--focus-input-area` so the selected window and any other visible windows showing the same Psi buffer have their window point synchronized to the prompt entry end.

## Out of scope

- New keybindings, unless an existing command-discovery pattern requires registration beyond `M-x`.
- TUI behavior.
- Changes to prompt submission semantics.
- Reworking prompt-entry rendering or buffer layout.
- Multi-session navigation outside the current Emacs buffer.
- Moving point in non-Psi buffers or uninitialized buffers.

## Behavior

`psi-emacs-move-point-to-prompt-end` is an interactive command.

When the current buffer is an initialized Psi Emacs session buffer, the command ensures the prompt input area exists, then moves point to `psi-emacs--draft-end-position` through `psi-emacs--focus-input-area`. Because the helper synchronizes visible windows, all live windows displaying that Psi buffer should end at the same prompt-entry point after the command runs.

When invoked outside an initialized Psi Emacs session buffer, the command signals `user-error` with a short message such as `Not in an initialized Psi session buffer`. It must not create a Psi session, create prompt-entry state, or mutate unrelated buffers.

## Acceptance criteria

1. A named interactive Emacs command `psi-emacs-move-point-to-prompt-end` exists and can be called with `M-x` after loading the Psi Emacs frontend.
2. In a Psi Emacs session buffer with an empty prompt entry, invoking the command places point at the prompt entry end.
3. In a Psi Emacs session buffer with existing prompt text, invoking the command places point after the final prompt-entry character without changing the text.
4. Invoking the command after point has been moved into earlier conversation output returns point to the prompt entry end.
5. The command does not move point into generated output, divider text, overlays, or read-only session content.
6. Existing prompt submission and editing behavior still works after the command runs.
7. Focused Emacs tests cover the empty-prompt, non-empty-prompt, and point-in-output cases.
8. Focused Emacs tests cover the outside-initialized-Psi-buffer error path.
9. Focused Emacs tests prove the command delegates to the existing focus-input behavior by checking the selected window point, and at least one additional visible window showing the same Psi buffer when practical in the existing test harness.

## Notes

Prefer a small command that delegates to `psi-emacs--focus-input-area`. The command should be an interactive wrapper plus precondition check, not a duplicate implementation of prompt/input boundary logic.
