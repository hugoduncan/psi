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

- [x] AC4 ("`bb test` passes with no regressions") is not literally
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
      Resolved via the second branch only: this task's `design.md` is
      read-only context for this pass (per the invoking instructions), so
      the wording-clarification branch was not taken; opened
      `munera/open/235-fix-branch-merge-dirty-working-tree-failures/`
      (design-only) to track the 10 pre-existing `branch-merge`/"working
      tree is dirty" failures instead.
- [x] No automated regression test exists for the new shutdown-hook
      cleanup behaviour added to `temp-cwd`/`temp-session-root`
      (`register-cleanup-shutdown-hook!` in `test_support.clj`) —
      verification was manual/empirical only (per implementation.md's
      "Verification performed" notes). A future refactor of `temp-cwd`/
      `temp-session-root` could silently drop the hook registration with
      nothing failing. Consider a minimal follow-up test (e.g. asserting
      the created dir no longer exists after invoking the registered
      shutdown-hook thread directly, without waiting for real JVM exit).
      Added `components/agent-session/test/psi/agent_session/test_support_test.clj`
      with a regression test that starts+joins the registered shutdown-hook
      `Thread` directly (then deregisters it via `removeShutdownHook` to
      avoid a second run at real JVM exit) and asserts the directory is
      gone. `register-cleanup-shutdown-hook!` now returns the `Thread` (was
      previously `void`) to make this possible.

## Implementation review follow-up (task-implementation-review skill)

- [x] `components/agent-session/test/psi/agent_session/query_graph_test.clj`
      (~lines 82-96): the `fix-repeated-thinking-output` worktree-attach
      test ("isolated extension mutation path can attach a worktree to an
      existing branch") is a top-level `(testing ...)` form, not wrapped in
      a `(deftest ...)`. It is not registered as a test var, so it does not
      run as part of `bb test`/kaocha's reported suite (the namespace has
      exactly 8 `deftest`s, matching implementation.md's "8 tests, 0
      failures" claim — this 9th block is excluded). It only executes once
      at namespace load/compile time, so its `try`/`finally` cleanup and
      `is` assertions provide no ongoing regression protection despite
      appearing to verify Pattern C's `fix-repeated-thinking-output`
      prefix. Wrap it in `deftest` (with a suitable test name) so it is
      actually exercised by the test suite.
      Wrapped in `(deftest ext-mutation-attach-worktree-to-existing-branch-test
      ...)`; namespace now runs 9 `deftest`s (was 8).
- [x] `extensions/work-on/test/extensions/work_on_test.clj`'s
      `work-on-command-with-remote-base-ref-integration-test` creates a
      `Files/createTempDirectory "psi-work-on-remote-base-"` directory,
      clones real git repos into it, and calls the real (non-stubbed)
      `git/worktree-add` to create an actual git worktree inside it — with
      no `try`/`finally` and no `delete-recursively!` cleanup anywhere in
      the test. Confirmed empirically: running this test in isolation
      leaves a `psi-work-on-remote-base-*` directory (containing the
      clones and the added worktree) under the OS temp dir after the test
      completes. This prefix isn't one of design.md's 10 originally-listed
      prefixes, so it falls outside AC1/AC3's literal scope, but it is a
      genuine leak in one of this task's 4 in-scope files that this task's
      own `work_on_test.clj` verification step missed (that step was
      scoped only to confirming Pattern D's `fix-repeated-thinking-output`
      literals are assertion-only, not to auditing the rest of the file).
      Add `try`/`finally` + `test-support/delete-recursively!` (or
      equivalent) cleanup for `base-dir` in this test.
      `psi.agent-session.test-support` is not reachable from this
      namespace's classpath (only agent-session's `src` path is a declared
      dep of `work-on`'s `:test` alias, not its `test` path — confirmed via
      `clojure -A:test -Spath`), so added a local `delete-recursively!`
      helper in `work_on_test.clj` (mirrors `test-support`'s
      `java.io.File`-based implementation) and wrapped the test body in
      `try`/`finally` calling it on `base-dir`.

## Implementation review follow-up (task-implementation-review skill, this pass)

- [ ] `extensions/work-on/test/extensions/work_on_test.clj` is 1292 lines
      (already 1277 before this task's changes; this task's own
      `delete-recursively!` helper + `try`/`finally` wrap added ~15 more),
      well over the project's 800-line-per-file standard
      (`clojure-coding-standards` SKILL.md: "File size limit: 800 lines
      maximum per file"). `bb commit-check:file-lengths` does not catch
      this because it only scans `components/` and `bases/`, not
      `extensions/`, so the violation goes unenforced. Not caused by this
      task, but this task's edit nudged it further over the limit.
      Consider splitting `work_on_test.clj` into multiple files (a
      follow-up task, not this one — Out of Scope forbids restructuring
      beyond cleanup-path fixes), and/or widening
      `commit-check:file-lengths` to also scan `extensions/`.
