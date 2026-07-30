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

- [x] `extensions/work-on/test/extensions/work_on_test.clj` is 1292 lines
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
      Both actions (splitting the file, widening the lint scan) are
      restructuring/tooling work unrelated to this task's test-artifact-
      leak Acceptance Criteria and outside its 4-file In Scope list, per
      `design.md`, which is read-only context for this pass. Opened
      `munera/open/236-split-work-on-test-and-lint-extensions-file-lengths/`
      (design-only) to track both.

## Test review follow-up (task-test-review skill)

- [x] No automated test asserts the core AC1/AC2 leak-freeness invariant
      end-to-end. `with-null-context` (`git_worktree_test.clj`), the
      `try`/`finally` blocks in `query_graph_test.clj`, and the new
      `try`/`finally` in `work_on_test.clj` are all trusted to actually
      remove their directories/worktrees, but no test asserts
      `(.exists (io/file repo-dir))` (or `git worktree list`) is
      false/empty after the macro/body completes — every "no leak"
      confirmation in `implementation.md`/`steps.md` is a manual
      `ls /tmp`/`git worktree list` check, not an executable assertion. A
      future change that broke `with-null-context`'s `finally`, or
      dropped one of the new `try`/`finally` wraps, would not be caught by
      `bb test`. Consider adding one representative assertion per pattern
      (e.g. assert `repo-dir` is gone immediately after a
      `with-null-context` body returns) to guard the cleanup wiring
      itself, not just the behaviour it wraps.
      Added one representative assertion per pattern: a new
      `with-null-context-deletes-repo-dir-in-finally-test` in
      `git_worktree_test.clj` (Pattern A); an added assertion after the
      existing `try`/`finally` in `query_graph_test.clj`'s
      `register-mutations-in!-includes-history-mutations-test` (Pattern C);
      and an added assertion after the existing `try`/`finally` in
      `work_on_test.clj`'s
      `work-on-command-with-remote-base-ref-integration-test` (Pattern D),
      each asserting the directory no longer exists once the
      macro/try-finally body has returned.
- [x] `test_support_test.clj`'s regression test invokes the private
      `register-cleanup-shutdown-hook!` directly
      (`#'test-support/register-cleanup-shutdown-hook!`) against a
      manually created temp dir — it never calls `temp-cwd`/
      `temp-session-root`. It therefore verifies the hook mechanism works
      in isolation, but not that `temp-cwd`/`temp-session-root` actually
      invoke it: a refactor that dropped the
      `(register-cleanup-shutdown-hook! p)` call from either function
      (while leaving `register-cleanup-shutdown-hook!` itself intact)
      would not be caught by this test. Consider extending the test (or
      adding a second one) that calls `temp-cwd`/`temp-session-root`
      directly and confirms a shutdown hook for that exact path is
      registered (e.g. via `Runtime.getRuntime()`'s hook set through
      reflection, or by having `temp-cwd`/`temp-session-root` return the
      hook alongside the path in a test-only variant) and cleans up the
      directory when run.
      Took the test-only-variant branch: extracted the shared
      `create-temp-dir-with-cleanup-hook!` helper in `test_support.clj`
      (used by both `temp-cwd`/`temp-session-root` and their new
      `temp-cwd-with-hook`/`temp-session-root-with-hook` test-only
      variants, so a dropped hook-registration call would break both), and
      added two new tests calling `temp-cwd-with-hook`/
      `temp-session-root-with-hook` directly, asserting the returned hook
      deletes the directory when started+joined.

## Test review follow-up (task-test-review skill, this pass)

- [x] `components/agent-session/test/psi/agent_session/query_graph_test.clj`'s
      `ext-mutation-attach-worktree-to-existing-branch-test` (Pattern C,
      `fix-repeated-thinking-output-` prefix) creates a real worktree via
      `git.worktree/add!` and cleans up `repo-dir` in `finally` via
      `test-support/delete-recursively!`, but — unlike its sibling
      `register-mutations-in!-includes-history-mutations-test` in the same
      file, which asserts `(is (not (.exists (File. repo-dir))) ...)` right
      after its `try`/`finally` — it has no leak-freeness assertion. A
      regression that dropped this test's own `delete-recursively!` call
      would not be caught by `bb test`; only a manual `/tmp` check would
      catch it, which is the exact gap the prior "Test review follow-up"
      pass fixed for the other Pattern C/A/D tests but missed here. Add the
      same trailing `(is (not (.exists (File. ^String repo-dir))) ...)`
      assertion after this test's `try`/`finally`.
      Added the same trailing `testing` block + `(is (not (.exists (File.
      ^String repo-dir))) ...)` assertion after this test's
      `try`/`finally`, mirroring
      `register-mutations-in!-includes-history-mutations-test`.

## Test-shaper review follow-up

- [x] `test_support_test.clj`'s
      `register-cleanup-shutdown-hook-deletes-directory-test` manually
      inlines a `try`/`.start`/`.join`/`finally`/`removeShutdownHook`
      sequence instead of calling the `start-join-and-deregister!` helper
      defined earlier in the same file (which the other two tests in the
      file, `temp-cwd-registers-cleanup-shutdown-hook-test` and
      `temp-session-root-registers-cleanup-shutdown-hook-test`, both use).
      Inconsistent test-abstraction usage within one file. Refactor the
      first test to call `start-join-and-deregister!` like the other two.
      Done: `register-cleanup-shutdown-hook-deletes-directory-test` now
      calls `start-join-and-deregister!` instead of inlining the
      start/join/removeShutdownHook sequence.
- [x] The cleanup-wiring guard assertions added to
      `query_graph_test.clj`'s `register-mutations-in!-includes-history-mutations-test`
      and `work_on_test.clj`'s `work-on-command-with-remote-base-ref-integration-test`
      (each a single `(is (not (.exists ...)) ...)` appended after the
      test's existing `try`/`finally`) are nested inside those tests'
      pre-existing `(testing "...")` block, whose description names the
      unrelated behaviour under test (mutation registration / `--base`
      ref handling). A failure of the guard assertion reports that
      unrelated description, not "cleanup didn't run", misleading
      diagnosis. This also mixes two orthogonal concerns (behaviour +
      cleanup-wiring invariant) in one test, unlike
      `git_worktree_test.clj`'s `with-null-context-deletes-repo-dir-in-finally-test`,
      which extracted the equivalent guard into its own dedicated
      `deftest`/`testing` pair. Extract the two guard assertions into
      their own dedicated tests (or at minimum their own `testing` block
      with a cleanup-specific description) for consistency and meaningful
      failure messages.
      Took the "own `testing` block" branch (not a separate `deftest`,
      to avoid duplicating each test's non-trivial setup): both guard
      assertions are now wrapped in their own
      `(testing "cleanup wiring: ... is removed once the try/finally
      above completes" ...)` block, sibling to (not nested in) the
      pre-existing behaviour `testing` block, so a failure reports a
      cleanup-specific description.
- [x] The cleanup-guard comments in `git_worktree_test.clj`
      ("Guards the cleanup wiring itself (Pattern A)..."),
      `query_graph_test.clj` ("...Pattern C...") and `work_on_test.clj`
      ("...Pattern D...") reference this task's `design.md` Root Cause
      Analysis pattern labels (A/C/D) without local explanation. Once this
      task closes and `design.md` moves under `munera/closed/`, a future
      reader of the test file has no local way to resolve what "Pattern A"
      etc. means. Reword the comments to be self-contained (describe what
      is being guarded — finally-block/cleanup-path regression — without
      relying on the design doc's pattern taxonomy).
      Reworded all three comments to drop the "(Pattern A/C/D)"
      references, describing what's guarded (finally-block /
      try-finally cleanup wiring) in self-contained terms instead.

## Docs review follow-up

- [x] `ramora/TESTING.md` recommends warm REPL/nREPL Scry runs to amortize
      startup cost, but it does not document this task's new
      `temp-cwd`/`temp-session-root` shutdown-hook cleanup limitation:
      `psi-agent-session-test-`/`psi-agent-session-store-` directories
      created during long-lived REPL/in-process test runs may remain under
      the OS temp dir until the REPL JVM exits (unlike `bb test` CLI runs,
      where the short-lived JVM exits after the run). Add a brief note near
      the Scry REPL/performance guidance so future agents know to restart
      the REPL/nREPL process or manually sweep those prefixes when using
      in-process test iteration.
      Added a `ramora/TESTING.md` performance note explaining that
      long-lived REPL/nREPL runs keep those temp dirs until JVM exit (or a
      manual sweep), while short-lived `bb test` runs trigger shutdown-hook
      cleanup promptly.
