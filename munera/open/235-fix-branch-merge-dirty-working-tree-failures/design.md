# 235 — Fix `branch-merge` "working tree is dirty" Test Failures

## Goal

Make `psi.history.git-worktree-test`'s `branch-merge-*` tests pass, so
`bb test`/`clojure -M:test` can exit zero (or at least so this specific,
currently-permanent failure cluster is eliminated), restoring a trustworthy
signal for the project's overall test-suite health.

## Context

`psi.history.git-worktree-test` (`components/history/test/psi/history/git_worktree_test.clj`)
currently has 10 consistently-failing assertions across these `deftest`s,
every one rooted in `git/branch-merge` unexpectedly returning
`{:error "working tree is dirty"}` (or a result shaped by that early
rejection) on a freshly created, single-commit-ahead `with-null-context`
fixture that the test itself never dirties before calling `branch-merge`:

- `branch-merge-supports-ff-strategy`
- `branch-merge-ff-only-fails-when-not-fast-forwardable`
- `branch-merge-supports-no-ff-strategy`
- `branch-merge-fast-forward`

Reproduction (this environment, current `HEAD`):

```
clojure -M:test-paths -m kaocha.runner --focus psi.history.git-worktree-test
# => 37 tests, 92 pass, 10 failures, 0 errors
clojure -M:test-paths -m kaocha.runner --focus psi.history.git-worktree-test/branch-merge-fast-forward
# => 1 tests, 1 pass, 4 failures, 0 errors (fails the same way in isolation —
#    not a test-order/parallelism artifact)
```

This is pre-existing and unrelated to any in-flight task's changes: confirmed
during `munera/open/234-complete-test-artifact-cleanup`'s verification pass
via `git stash` + re-run — the identical 10 failures reproduce with zero
diff applied. No task currently tracks fixing it (`munera/open/` and
`munera/closed/` had no task referencing this failure cluster before this
task was created).

Because of this, `bb test`'s exit code is permanently non-zero on a clean
checkout, which undermines its use as a pass/fail gate for other tasks'
"tests pass with no regressions" acceptance criteria (they must instead
manually diff failure sets against a pre-existing baseline, e.g. via `git
stash` re-runs, rather than trusting a green `bb test`).

### What's already known (from prior investigation, not yet root-caused)

- The failing tests each build a fresh, isolated repo via `with-null-context`
  (`git/create-null-context` + seeded commits) — no shared state with other
  tests, and the same failure reproduces when the failing test is run alone.
- `branch-merge-rejects-dirty-working-tree` (a *different*, currently-passing
  test) intentionally dirties the tree first and asserts
  `(:error result)` is exactly `"working tree is dirty"` — so
  `git/branch-merge`'s dirty-check itself works correctly when the tree is
  actually dirty; the bug is that the failing tests' checkout is being
  reported as dirty when, per the test's own setup, it should be clean.
- Root cause has not yet been identified — candidates to investigate include
  environment/git-version differences in how `git status`/`git diff` is
  invoked or parsed by `git/branch-merge`'s dirty check, and any global git
  state (config, `.gitattributes`, line-ending normalization) that could
  make a freshly-checked-out worktree appear dirty on this environment.

## Constraints

- Do not weaken or remove `branch-merge-rejects-dirty-working-tree`'s
  coverage of genuine dirty-tree rejection — the fix must make the dirty
  check correctly distinguish "actually dirty" from "freshly checked out,
  clean" trees, not simply relax or bypass the check.
- No mocks for logic dependencies; keep `git-worktree-test`'s existing
  no-mocks, isolated-temp-repo test style.
- Root-cause first (`λfix(bug)`: trace → structural cause → redesign/patch;
  local cause → patch) — do not paper over the symptom (e.g. do not just
  retry the merge or add a sleep) without understanding why the tree is
  reported dirty.

## Acceptance Criteria

1. All `branch-merge-*` tests in `git_worktree_test.clj` pass, including
   `branch-merge-rejects-dirty-working-tree` (must remain passing/meaningful,
   not weakened).
2. `psi.history.git-worktree-test` as a whole passes (`37 tests, 0
   failures` or equivalent after any test-count changes made in service of
   the fix).
3. No regressions: `bb test`'s failure count outside this file/cluster is
   unchanged by the fix.
4. Root cause is documented (in the task's `implementation.md`), not just
   the applied patch.

## Scope

### In Scope
- `components/history/test/psi/history/git_worktree_test.clj` — test setup
  if the root cause turns out to be test-side (e.g. an environment/fixture
  issue causing false-positive dirty state).
- `components/history/src/psi/history/git.clj` (or wherever `git/branch-merge`
  and its dirty-check live) — production fix if the root cause is in the
  dirty-check implementation itself.

### Out of Scope
- Other pre-existing `bb test` failures outside this specific
  `branch-merge`/"working tree is dirty" cluster (e.g. any unrelated
  environment-dependent failures noted elsewhere in the suite) — those are
  a separate concern if/when they need tracking.
- Clarifying AC wording in other tasks' `design.md` files (e.g. whether
  "tests pass" criteria should carve out pre-existing failures) — that is a
  wording/process question for each such task, independent of this task's
  code-level fix.

## Key Questions

1. Is "working tree is dirty" a false positive (the tree is actually
   clean and the dirty-check is wrong), or is the tree genuinely left dirty
   by something upstream of the check (e.g. a prior step in `branch-merge`
   itself, or `create-null-context`'s seeding) that the tests don't
   anticipate?
2. Is this failure environment-specific (e.g. depends on the installed git
   version, global `~/.gitconfig`, or `core.autocrlf`/line-ending settings),
   or does it reproduce identically on other machines/CI? If
   environment-specific, should the fix be in the dirty-check's
   implementation (so it's robust everywhere) or in test/environment setup
   (e.g. pinning relevant git config for the test run)?
