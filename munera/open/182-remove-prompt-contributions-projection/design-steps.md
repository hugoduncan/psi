# Design follow-up steps

- [x] Correct write site table: remove `:session/refresh-system-prompt` line 55 entry — it passes `:prompt-contributions nil` as a build-opts param to `build-system-prompt`, not as a session-state write; the refresh handler needs no changes for this task
- [x] Correct read site table: `resolvers/session.clj:196` already derives contributions via `ss/list-prompt-contributions-in` (calls `prompt-storage/list-contributions`); the `:prompt-contributions` at line 196 is an output map key in `:prompt-layers`, not a session-state read; remove the misleading migration note
- [x] Add `nullable_api.clj:37` to write/init site inventory: seeds `:prompt-contributions []` in nullable extension test helper initial state; must be cleaned up alongside schema/lifecycle removal
- [x] Fix date typo in implementation.md: ambiguity review header says "2025-05-25" — should be "2026-05-25"
- [ ] Fix plan summary site count: change "2 lifecycle/init sites" to match the design's 4 additional non-handler sites (or reword to avoid a specific count that conflicts with the detailed steps)
