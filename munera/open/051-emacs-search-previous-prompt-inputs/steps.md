# Steps: Emacs search previous prompt inputs

- [ ] Add `psi-emacs-search-input-history` to `psi-compose.el`
  - guard: `psi-emacs--state` present, history non-empty
  - `completing-read` over `psi-emacs-state-input-history`
  - on selection: `psi-emacs--replace-input-text` + `psi-emacs--history-reset-navigation`
  - on cancel (nil result): leave input unchanged
- [ ] Bind `M-r` → `psi-emacs-search-input-history` in `psi-mode.el` keymap block
- [ ] Add tests covering:
  - search selects and populates input area
  - navigation state reset after selection
  - user-error when history empty
  - cancel leaves input unchanged
- [ ] Update keymap help / command listing if one exists
- [ ] Commit
