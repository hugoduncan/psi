# Steps: Emacs search previous prompt inputs

- [ ] Add `psi-emacs-search-input-history` to `psi-compose.el`
  - guard: `psi-emacs--state` present, history non-empty
  - `completing-read` over `psi-emacs-state-input-history` as-is (no dedup)
  - `C-g` (quit signal) → leave input + navigation state unchanged (no error)
  - empty-string result → same as cancel
  - on selection: stash current draft (same as `M-p` does)
                  + `psi-emacs--replace-input-text(chosen)`
                  + reset navigation index to nil
- [ ] Bind `M-r` → `psi-emacs-search-input-history` in `psi-mode.el` keymap block
- [ ] Add tests covering:
  - search selects and populates input area
  - draft is stashed before selection; M-n recovers it after
  - navigation index reset to nil after selection
  - C-g cancel leaves input and navigation state unchanged
  - empty-string cancel leaves input and navigation state unchanged
  - user-error when history empty
  - user-error when buffer is not a psi buffer
- [ ] Update keymap help / command listing if one exists
- [ ] Commit
