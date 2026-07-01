# Steps — 234 Complete Test Artifact Cleanup

- [x] Resolve scope question, write plan.md/steps.md (this slice)
- [x] `test_support.clj`: add shutdown-hook cleanup to `temp-cwd`
- [x] `test_support.clj`: add shutdown-hook cleanup to `temp-session-root`
- [x] Verify `git_worktree_test.clj` (Pattern A) — confirmed no gaps
      (`with-null-context`'s `finally` already cleans up `:repo-dir`, which
      contains all `linked-worktree-path` worktrees); ran the namespace, no
      leaked worktrees or temp dirs. No code change needed.
- [x] Verify `query_graph_test.clj` (Pattern C) — confirmed no gaps (both
      worktree-creating test bodies already have `try`/`finally` +
      `test-support/delete-recursively!` on `repo-dir`); ran the namespace
      (8 tests, 0 failures), no leaked worktrees or temp dirs. No code
      change needed.
- [x] Verify `work_on_test.clj` (Pattern D) — confirmed all
      `fix-repeated-thinking-output` occurrences are string literals in
      stubbed assertion data (`/repo/...`), no real filesystem artifacts;
      ran the namespace (21 tests, 0 failures). No code change needed.
- [x] Run `clj-kondo --lint` on changed files — 0 errors, 0 warnings.
- [x] Run full `bb test`; checked `/tmp` and `git worktree list` for leaks
      — no leaked `psi-agent-session-*`/prefix dirs, no leaked test
      worktrees. 15 pre-existing failures unrelated to this task's changes
      (confirmed via `git stash` re-run on 2 sample failures: identical
      failures reproduce without this task's diff).
- [x] Update implementation.md, design.md/design-steps.md
- [x] Commit
