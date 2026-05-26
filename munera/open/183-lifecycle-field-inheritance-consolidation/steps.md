# Steps

- [ ] Add `common-inherited-fields` constant with classification docstring to `init.clj`
- [ ] Add `prompt-state-fields` constant to `init.clj`
- [ ] Add `model-identity-fields` constant to `init.clj`
- [ ] Replace `select-keys` vector in `initialize-new-session-state` with `(into common-inherited-fields (concat prompt-state-fields model-identity-fields))`
- [ ] Replace `select-keys` vector in `initialize-resumed-session-state` with `(into common-inherited-fields prompt-state-fields)`
- [ ] Replace `select-keys` vector in `initialize-forked-session-state` with `(into common-inherited-fields model-identity-fields)`
- [ ] Verify composed key sets match originals exactly (REPL or inline assertion)
- [ ] Add child-session classification comment to `child_session_state.clj` covering all three constant groups
- [ ] `bb test` — all green, no test modifications
