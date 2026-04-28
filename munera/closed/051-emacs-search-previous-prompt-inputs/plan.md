# Plan: Emacs search previous prompt inputs

## Approach

Single vertical slice: new command + binding + tests. No backend changes.

## Steps

1. Add `psi-emacs-search-input-history` to `psi-compose.el` alongside
   `psi-emacs-previous-input` / `psi-emacs-next-input`
2. Bind `M-r` in `psi-mode.el` keymap block
3. Add unit tests in the existing compose/history test file
4. Update keymap documentation (help string / `C-c ?` listing if one exists)

## Risks

- None significant — pure additive UI change, no state model changes
