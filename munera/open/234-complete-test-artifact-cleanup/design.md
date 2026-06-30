# 234 — Complete Test Artifact Cleanup

## Goal

Eliminate all remaining test artifact prefixes that are not being deleted after test execution, ensuring every test that creates temporary directories, worktrees, or git branches cleans them up in all code paths (including failures and exceptions).

## Context

We are partway through rectifying test artifact cleanup. The following prefixes are known to leak:

- `ext-mutation-worktree` — in `query_graph_test.clj`, worktree path created under `repo-dir/worktrees/`
- `existing-path` — in `git_test.clj`, linked worktree path
- `feature-attached` — in `git_test.clj`, linked worktree paths (src + target)
- `feature-diverged` — in `git_test.clj`, linked worktree path
- `feature-merge` — in `git_test.clj`, linked worktree paths (merge, merge-ff, merge-no-ff)
- `feature-rebase` — in `git_test.clj`, linked worktree path
- `fix-repeated-thinking` — in `query_graph_test.clj` and `work_on_test.clj`, branch names and worktree paths
- `legacy-create-branch` — in `git_test.clj`, linked worktree path
- `psi-agent-session` — in `test_support.clj`, `temp-cwd` and `temp-session-root` create OS temp dirs

### Root Cause Analysis

There are two distinct leak patterns:

**Pattern A: `linked-worktree-path` in `git_test.clj`**
The `linked-worktree-path` helper creates paths under `repo-dir/worktrees/<name>-<uuid>`. The `with-null-context` macro cleans up `:repo-dir` recursively in its `finally`, which should include the worktrees subdirectory. However, if a test creates a worktree via `git/worktree-add` and then the test throws before reaching the `finally`, or if the worktree is outside the `repo-dir` tree, cleanup fails. Need to verify: are all `linked-worktree-path` worktrees actually under `repo-dir`? If so, the `delete-recursively!` in `with-null-context` should handle them — the leak may be in tests that don't use `with-null-context` or that have early returns/exceptions.

**Pattern B: `Files/createTempDirectory` in `test_support.clj`**
`temp-cwd` and `temp-session-root` create OS-level temp directories under `/tmp/` (or the OS temp dir). These are NOT under any `repo-dir` and are only cleaned up if the calling code explicitly invokes `delete-recursively!` in a `finally` block. If a test using these helpers throws before cleanup, or if `safe-context-opts` creates a `temp-cwd` that is never cleaned up (because the test uses a different cleanup path), the directory leaks.

**Pattern C: `query_graph_test.clj` inline worktree paths**
The `register-mutations-in!-includes-history-mutations-test` creates worktree paths inline (not via `linked-worktree-path`) and relies on the outer `finally` to `delete-recursively!` the `repo-dir`. The `ext-mutation-worktree-` path is under `repo-dir/worktrees/` so should be covered. The `fix-repeated-thinking-output-` test also creates worktrees under `repo-dir/worktrees/`.

**Pattern D: `work_on_test.clj` stubbed paths**
The `fix-repeated-thinking-output` references in `work_on_test.clj` are string literals in assertions against stubbed/faked git context — these don't create real filesystem artifacts. They are not a leak source.

## Constraints

- Tests must remain isolated and deterministic (no shared state, no mocks for logic deps per project conventions).
- Cleanup must be in `finally` blocks to handle test failures.
- `delete-recursively!` is the established cleanup primitive — reuse it.
- Do not change test behaviour or assertions — only fix cleanup paths.
- After the fix, running `bb test` repeatedly should not accumulate temp directories or worktree artifacts.

## Acceptance Criteria

1. **No leaked temp directories**: After running the full test suite, no directories matching the listed prefixes exist under `/tmp/` or any test repo directory.
2. **No leaked git worktrees**: After running the full test suite, `git worktree list` shows no test-created worktrees (only the real project worktrees).
3. **All listed prefixes addressed**: Each of the 9 prefixes is either (a) confirmed not to leak (false positive), (b) fixed with proper cleanup, or (c) removed if the test is no longer needed.
4. **Tests pass**: `bb test` passes with no regressions.
5. **Lint clean**: `clj-kondo --lint src test` passes on changed files.

## Scope

### In Scope
- `components/history/test/psi/history/git_test.clj` — verify `linked-worktree-path` worktrees are cleaned by `with-null-context` finally; fix any gaps.
- `components/agent-session/test/psi/agent_session/test_support.clj` — ensure `temp-cwd` and `temp-session-root` callers always clean up; consider adding OS-level temp dir auto-cleanup (e.g., `Files/deleteIfExists` in finally).
- `components/agent-session/test/psi/agent_session/query_graph_test.clj` — verify `ext-mutation-worktree-` and `fix-repeated-thinking-output-` worktrees are cleaned by the test's finally block.
- `extensions/work-on/test/extensions/work_on_test.clj` — confirm `fix-repeated-thinking-output` references are assertion-only (no real artifacts).

### Out of Scope
- Other test artifact cleanup not involving these 9 prefixes.
- Changing test logic or assertions.
- Adding new tests (only fix cleanup in existing tests).

## Key Questions

1. Are the `linked-worktree-path` worktrees actually leaking, or is the `with-null-context` finally already cleaning them? Need to verify by running tests and checking for leftover dirs.
2. For `temp-cwd` / `temp-session-root`: which callers fail to clean up? Is it a missing `finally` in a specific test, or a systemic issue where `safe-context-opts` creates a default `temp-cwd` that callers don't track?
3. Should we add an `:each` fixture that sweeps known temp prefixes after every test, as a safety net?
