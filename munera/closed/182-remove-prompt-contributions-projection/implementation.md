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

## Design inconsistency review (pass 2) — 2026-05-25

Reviewed design.md, plan.md, steps.md, and implementation.md for cross-artifact inconsistencies. Verified write/read site inventories, line numbers, handler patterns, scope, acceptance criteria, backward compat, and context refs against current codebase.

One actionable inconsistency found:

1. **Plan summary understates non-handler site count**: Plan's summary paragraph says "stop writing it at 4 handler sites and 2 lifecycle/init sites". The design's persistence/init table lists 4 additional non-handler sites (child_session_state.clj, init.clj ×3 grouped as 1, model.clj, nullable_api.clj), and the plan's own detailed ordering describes 6 steps covering schema+defaults, 3 lifecycle select-keys, child-session, and test helper — all distinct from the 4 handler sites. The "2" in the summary is inconsistent with both the design inventory and the plan's own detailed steps.

## Implementation — 2026-05-25

All steps complete. Mechanical removal of `:prompt-contributions` from:

**Source changes (commit a0ec01a9):**
- `model.clj`: removed from schema and `initial-session` defaults
- `init.clj`: removed from `select-keys` in new/resume/fork lifecycle paths
- `prompt_handlers.clj`: removed `assoc-in ... :prompt-contributions` from 4 handler `root-state-update` fns (register, update, unregister, reset)
- `child_session_state.clj`: removed persistence line and now-unused `prompt-contributions` let-binding
- `nullable_api.clj`: removed `[]` seed from initial test helper state

**Test changes (commit 8b5a7fe2):**
- `model_test.clj`: removed default `[]` assertion
- `init_test.clj`: removed stale seed data from test fixtures, replaced value assertions with `(not (contains? sd :prompt-contributions))`, kept `prompt-storage/list-contributions` derivation proofs
- `session_test.clj`: removed default `[]` assertion
- `eql_introspection_test.clj`: removed stale session-state write (resolver already derives from registry)
- `child_session_state_test.clj`: removed seed data from `parent-session-data`, asserted absence
- `workflow_execution_test.clj`: removed session-state writes, asserted ids + absence
- `child_session_mutation_test.clj`: updated `normalized-sorted-contributions` helper to derive from root-state via `prompt-storage/list-contributions`, rewrote selection filter test with registry-backed data
- `nullable_api_test.clj`: removed stale write and unused `state` binding
- `root_storage_test.clj`: removed stale `:prompt-contributions` from test session data

**Verification:**
- Focused tests: 60 tests, 487 assertions, 0 failures
- Full suite: `bb test` all green
- `clj-kondo` lint: 0 errors, 0 warnings on all changed files

No deviations from the design. The implementation was purely mechanical — every site listed in the design was addressed exactly as planned.

## Task test review — 2026-05-25

Reviewed tests against task-test-review skill criteria: well-formedness, behaviour coverage, and infrastructure dependency quality.

**Verification:** focused tests 51 tests, 431 assertions, 0 failures; `clj-kondo` 0 errors/warnings on all changed source and test files.

**Behaviour coverage:** all 8 acceptance criteria have covering tests:
- AC1 (no `:prompt-contributions` after init): `init_test.clj` ×3 lifecycle paths, `child_session_state_test.clj`, `workflow_execution_test.clj`, `child_session_mutation_test.clj` — all assert `(not (contains? sd :prompt-contributions))`
- AC2 (handler writes removed): `model_dispatch_test.clj` dispatch + `eql_introspection_test.clj` resolver derivation — no test asserts `:prompt-contributions` presence in session state post-dispatch
- AC3 (child-session): `child_session_state_test.clj`, `child_session_mutation_test.clj` assert absence + derivation
- AC4 (lifecycle): `init_test.clj` new/resume/fork
- AC5 (resolver derives on demand): `eql_introspection_test.clj` prompt contribution attrs
- AC6 (schema validates): `model_test.clj` initial-session passes schema
- AC7 (all tests pass): `bb test` green
- AC8 (no regression): derivation proofs via `prompt-storage/list-contributions` in init, child-mutation, workflow, nullable tests

**Infrastructure deps:** no mocks or stubs introduced. Tests use real dispatch, real root-registry storage, and nullable extension API. Pre-existing `with-redefs` in workflow/child tests target turn execution boundaries, not this task's changes.

**Findings:** no new actionable test issues.

## Task implementation review — 2026-05-25

Reviewed implementation against design, architecture, and skill criteria (`matches(code, design)`, `follows(code, architecture)`, `new_pattern`, `unnecessary_abstraction`, `structural_performance_issue`).

**Verification performed:**
- Confirmed all 5 source files changed match the design's write/init site inventory exactly
- Confirmed all 9 test files updated appropriately — assertions shifted from value presence to absence + registry-backed derivation
- Grepped all `components/` source (non-test) for `:prompt-contributions` — only 3 references remain, all correct: root-registry bucket id (`root_storage.clj:9`), resolver output map key (`resolvers/session.clj:196`), build-opts parameter (`prompt_handlers.clj:55`)
- Verified `next*` binding in all 4 prompt handlers is still used (feeds `effective-prompt`) — no dangling bindings
- Verified `prompt-storage` require in `child_session_state.clj` is still used (`prompt-storage/prompt-ids`)
- `clj-kondo` lint: 0 errors, 0 warnings on all changed source and test files
- `bb test`: all green

**Findings:** No new actionable issues. Implementation is mechanical, follows the task 180 pattern exactly, introduces no new patterns or abstractions, and has no structural performance concerns.

## Test-shaper review — 2026-05-25

Reviewed all 9 changed test files against test-shaper skill criteria: clarity, signal, robustness, consistency, and economy.

**Clarity:** All absence assertions use consistent message strings. `normalized-sorted-contributions` helper in `child_session_mutation_test.clj` compresses ceremony without hiding intent — takes `(root-state session-data)`, clearly named.

**Signal:** 6 absence assertions across 4 test files each cover a distinct code path (new/resume/fork lifecycle, child-session, workflow child, mutation child). Derivation proofs via `prompt-storage/list-contributions` confirm behavioral equivalence at each path.

**Robustness:** All tests deterministic, behavior-focused, no flakiness concerns. No new mocks or stubs introduced by this task.

**Consistency:** All absence assertions use identical pattern `(is (not (contains? sd :prompt-contributions)) ":prompt-contributions no longer persisted in session state")`. Inline root-state construction in `create-child-session-selection-filters-extension-contributions-coherently-test` is deliberate — unit-level filtering test with explicit data, appropriate for its scope.

**Economy:** No redundant assertions within this task's changes. Pre-existing observation: `session_test.clj` is a strict subset of `model_test.clj` — both test the same `psi.session-state.model` functions with nearly identical assertions. `model_test.clj` is the superset (adds temperature, retry metadata, provider-error-kind tests). This duplication is pre-existing (not introduced by task 182) and out of scope, but worth noting for a future cleanup task.

**Findings:** No actionable test-shaper issues within this task's scope.

## Code-shaper review — 2026-05-25

Reviewed all changed source and test files against code-shaper criteria: simplicity, consistency, robustness.

**Simplicity:** Mechanical removal — no new abstractions, no mixed concerns. Each handler retains single responsibility. `next*` binding still used in all 4 handlers (feeds `effective-prompt`). Clean.

**Consistency:** All 4 mutation handlers follow identical post-removal pattern: `sd → result → next* → base → prompt* → {:root-state-update ... :effects ...}`. Test absence assertions use identical pattern `(is (not (contains? sd :prompt-contributions)) "...")` across 6 sites. Naming, argument order, data shapes, idioms all consistent.

**Robustness:** Schema removal enforces invariant via malli validation. `select-keys` in init.clj silently drops stale persisted data — sound backward compatibility. No orphaned bindings or requires.

**Finding:**

1. **Dead schema definition**: `prompt-contribution-schema` at `model.clj:66` is defined but no longer referenced anywhere — its sole consumer was the removed `[:prompt-contributions ...]` schema entry. `grep -rn 'prompt-contribution-schema' components/ --include='*.clj'` returns only the definition itself. Should be removed.
