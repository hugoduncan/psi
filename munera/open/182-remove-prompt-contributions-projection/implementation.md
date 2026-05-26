# Implementation Notes

## Design ambiguity review — 2026-05-25

Reviewed design.md against current source code. Found 3 actionable ambiguities:

1. **Misclassified write site**: Design lists `:session/refresh-system-prompt` line 55 as a write site with pattern `assoc :prompt-contributions nil`. Actual code passes `:prompt-contributions nil` as a build-opts parameter to `sys-prompt/build-system-prompt`, not as a session-state write. The handler's `:root-state-update` writes only `:base-system-prompt` and `:system-prompt`. This entry should be removed from the write site table — the refresh handler needs no changes for this task.

2. **Read site already migrated**: Design says `resolvers/session.clj:196` reads `:prompt-contributions` from session state and needs migration to derive from `prompt-storage/list-contributions`. But the resolver already derives contributions via `ss/list-prompt-contributions-in` (which calls `prompt-storage/list-contributions`). The `:prompt-contributions` at line 196 is an output map key in the `:prompt-layers` response, not a session-state read. The read site table entry and its migration note are incorrect — no resolver migration is needed.

3. **Missing write/init site**: `nullable_api.clj:37` seeds `:prompt-contributions []` in the nullable extension test helper's initial state. This is a source file (not test) and is not listed in the design's write site inventory. It must be updated alongside schema/lifecycle changes.

## Design inconsistency review — 2026-05-25

Reviewed design.md, implementation.md, and design-steps.md for cross-artifact inconsistencies. Verified all write/read site inventories, line numbers, handler patterns, scope items, acceptance criteria, backward compatibility claims, and context references (task 178 follow-on C, task 180 pattern) against current codebase. All are consistent.

One minor inconsistency found:

1. **Date typo in implementation.md**: The ambiguity review header says "2025-05-25" but the session date is 2026-05-25. Year is off by one.

## Design ambiguity review (pass 2) — 2026-05-25

Re-reviewed design.md, plan.md, and steps.md against current source after prior fixes were applied. Verified: all line numbers in design match current source; all 4 handler write sites, 3 init.clj select-keys sites, child_session_state.clj:114, model.clj:190+281, and nullable_api.clj:37 confirmed; resolver at session.clj:196 confirmed as output key not session-state read; backward compatibility claim sound (journal-based persistence, select-keys controls carry-forward, extra keys in persisted state harmlessly ignored). Steps enumerate all sites correctly.

No new actionable ambiguities found.
