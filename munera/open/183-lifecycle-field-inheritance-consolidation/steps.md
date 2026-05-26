# Steps

- [x] Add `common-inherited-fields` constant with classification docstring to `init.clj`
- [x] Add `prompt-state-fields` constant to `init.clj`
- [x] Add `model-identity-fields` constant to `init.clj`
- [x] Replace `select-keys` vector in `initialize-new-session-state` with `(into common-inherited-fields (concat prompt-state-fields model-identity-fields))`
- [x] Replace `select-keys` vector in `initialize-resumed-session-state` with `(into common-inherited-fields prompt-state-fields)`
- [x] Replace `select-keys` vector in `initialize-forked-session-state` with `(into common-inherited-fields model-identity-fields)`
- [x] Verify composed key sets match originals exactly (REPL or inline assertion)
- [x] Add child-session classification comment to `child_session_state.clj` covering all three constant groups
- [x] `bb test` — all green, no test modifications
