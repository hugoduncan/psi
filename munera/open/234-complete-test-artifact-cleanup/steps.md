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

## Review follow-up

- [x] The shutdown-hook safety net in `temp-cwd`/`temp-session-root` only
      fires at JVM exit, so it satisfies AC1 for `bb test`'s CLI invocation
      (one subprocess per run, confirmed empirically: temp dirs are gone
      immediately after the `clojure -M:test-paths ...` subprocess exits)
      but does **not** clean up `psi-agent-session-test-`/
      `psi-agent-session-store-` directories promptly when tests are run
      via the project's own recommended in-process/REPL workflow
      (`.psi/skills/scry/SKILL.md`'s "REPL / in-process workflow", used
      while iterating in a long-lived nREPL process). In that workflow the
      hook only fires when the REPL/nREPL JVM eventually exits, which can
      be hours/days later, so leaked temp dirs accumulate under `/tmp`
      across a dev session — undermining the design's Goal ("every test...
      cleans them up in all code paths") for that path even though it
      doesn't violate AC1's literal "after a single `bb test` run"
      wording. Consider a per-invocation cleanup fallback for in-process
      test runs (e.g. a Scry pre/post-run hook that sweeps known
      `psi-agent-session-*` temp dirs) or explicitly document the
      limitation for REPL-based iteration.

## Implementation review follow-up

- [ ] AC4 ("`bb test` passes with no regressions") is not literally
      satisfied — `bb test`/`clojure -M:test` currently exits non-zero
      with pre-existing failures (10 confirmed in
      `psi.history.git-worktree-test` alone, matching implementation.md's
      claim of 15 full-suite failures), all in files untouched by this
      task. This wording ambiguity (does "passes" require a clean exit, or
      only "no *new* regressions" relative to a pre-existing-failure
      baseline?) parallels AC5's lint-scope ambiguity, which was formally
      raised and resolved in design.md — AC4 never received the same
      treatment. Clarify AC4's wording (e.g. explicitly carve out
      pre-existing/unrelated failures, mirroring AC5), and/or open a
      separate tracked task for the pre-existing `branch-merge`
      "working tree is dirty" failures in `git_worktree_test.clj` (none
      currently exists under `munera/open/` or `munera/closed/`) so the
      project's overall `bb test` health isn't silently treated as green.
- [ ] No automated regression test exists for the new shutdown-hook
      cleanup behaviour added to `temp-cwd`/`temp-session-root`
      (`register-cleanup-shutdown-hook!` in `test_support.clj`) —
      verification was manual/empirical only (per implementation.md's
      "Verification performed" notes). A future refactor of `temp-cwd`/
      `temp-session-root` could silently drop the hook registration with
      nothing failing. Consider a minimal follow-up test (e.g. asserting
      the created dir no longer exists after invoking the registered
      shutdown-hook thread directly, without waiting for real JVM exit).
