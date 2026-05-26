# Steps

- [x] Remove `:prompt-contributions` from schema in `session_state/model.clj` (line 190)
- [x] Remove `:prompt-contributions` from `initial-session` defaults in `session_state/model.clj` (line 281)
- [x] Remove `:prompt-contributions` from `select-keys` in `session_state/init.clj` — new (line 94), resume (line 150), fork (line 196)
- [x] Remove `assoc-in ... :prompt-contributions next*` from `:session/register-prompt-contribution` handler in `prompt_handlers.clj` (line 94)
- [x] Remove `assoc-in ... :prompt-contributions next*` from `:session/update-prompt-contribution` handler in `prompt_handlers.clj` (line 118)
- [x] Remove `assoc-in ... :prompt-contributions next*` from `:session/unregister-prompt-contribution` handler in `prompt_handlers.clj` (line 140)
- [x] Remove `assoc-in ... :prompt-contributions next*` from `:session/reset-prompt-contributions` handler in `prompt_handlers.clj` (line 157)
- [x] Remove `:prompt-contributions prompt-contributions` from `child-session-base-state*` in `child_session_state.clj` (line 114)
- [x] Remove `prompt-contributions` let-binding from `child-session-base-state*` if now unused
- [x] Remove `:prompt-contributions []` seed from `nullable_api.clj` (line 37)
- [x] Grep tests for `:prompt-contributions` assertions and update
- [x] `bb test` — all green
- [ ] Remove dead `prompt-contribution-schema` def from `session_state/model.clj` (line 66–76) — no longer referenced after `:prompt-contributions` schema entry removal
- [ ] `bb test` — verify no regression after schema def removal
