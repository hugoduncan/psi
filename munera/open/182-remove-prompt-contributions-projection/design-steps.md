# Design follow-up steps

- [x] Correct write site table: remove `:session/refresh-system-prompt` line 55 entry — it passes `:prompt-contributions nil` as a build-opts param to `build-system-prompt`, not as a session-state write; the refresh handler needs no changes for this task
- [x] Correct read site table: `resolvers/session.clj:196` already derives contributions via `ss/list-prompt-contributions-in` (calls `prompt-storage/list-contributions`); the `:prompt-contributions` at line 196 is an output map key in `:prompt-layers`, not a session-state read; remove the misleading migration note
- [x] Add `nullable_api.clj:37` to write/init site inventory: seeds `:prompt-contributions []` in nullable extension test helper initial state; must be cleaned up alongside schema/lifecycle removal
