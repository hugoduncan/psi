# Steps

## Slice 1 — Reproduce and observe dirty evidence

- [x] Run `clojure -M:test-paths -m kaocha.runner --focus psi.history.git-worktree-test/branch-merge-fast-forward` and confirm it still fails with `"working tree is dirty"`.
- [x] Run the full focused namespace command `clojure -M:test-paths -m kaocha.runner --focus psi.history.git-worktree-test` and record the current target failure count.
- [x] Instrument or locally inspect the failing fixture immediately before `git/branch-merge` calls its dirty check, capturing `git status --porcelain`, `git status --porcelain=v1 -uno`, `git status --porcelain=v1 -uall`, and `git worktree list --porcelain` for the target repo.
- [x] Inspect the target repo directory layout at the same point, especially any generated linked worktree directories under the main worktree.
- [x] Capture relevant environment/config facts only if needed: not needed beyond porcelain/worktree evidence because the dirty entry was a deterministic fixture path.

## Slice 2 — Classify root cause

- [x] Decide whether the dirty signal is genuine Git porcelain output, a parser/classification bug in `psi.history.git/status`, or an environment/configuration artifact.
- [x] If untracked nested linked worktree directories are present under the target worktree, verify that they are the entries causing `status --porcelain` to be non-empty.
- [x] If porcelain output is non-empty for another reason, trace which fixture or production step creates that state before `branch-merge` — not applicable; the porcelain entry was the nested linked-worktree directory.
- [x] Record the root-cause conclusion and chosen boundary in `implementation.md`.

## Slice 3 — Patch the owning boundary

- [x] If fixture layout is the cause, change `linked-worktree-path` or the relevant test setup so linked worktrees are created outside the main target worktree while still being unique and cleaned up.
- [x] If status classification is the cause, update `components/history/src/psi/history/git.clj` so `status` accurately reports clean/dirty while still treating untracked files and directories as dirty — not applicable; status correctly reported a real untracked path.
- [x] If repo configuration is the cause, pin the minimal relevant config in `create-null-context` or the test fixture without depending on user-global config — not applicable; no config issue was implicated.
- [x] Ensure cleanup still removes both the main null repo and any linked worktree directories created by the fixture.
- [x] Add executable coverage that `with-null-context` removes the external `linked-worktree-root` it now creates, not only the main repo dir.
- [x] Run `clj-paren-repair` on any edited Clojure files.

## Slice 4 — Lock behaviour with tests

- [x] Keep `branch-merge-rejects-dirty-working-tree` passing and verify it still dirties the target worktree before merge.
- [x] Add or preserve coverage proving untracked target-worktree paths are considered dirty by the merge precondition.
- [x] Run all `branch-merge-*` tests in `components/history/test/psi/history/git_worktree_test.clj` and confirm the former dirty false positives are gone.
- [x] Run `clojure -M:test-paths -m kaocha.runner --focus psi.history.git-worktree-test` and confirm the namespace passes.

## Slice 5 — Validate and document

- [x] Run `bb test` or the closest available broad test command and compare any remaining failures against the pre-existing non-target baseline.
- [x] Update `munera/open/235-fix-branch-merge-dirty-working-tree-failures/implementation.md` with the root cause, patch summary, and verification commands/results.
- [x] Review the final diff for minimality and for preserving production dirty-tree semantics.
- [x] Commit the implementation changes separately from this planning commit when the fix is complete.

## Test review follow-up

- [x] Add executable coverage that an untracked target-worktree directory, not only an untracked file, makes `branch-merge` reject with `"working tree is dirty"`.
- [x] Add a focused fixture regression proving that `git/worktree-add` through `linked-worktree-path` leaves the target null repo `git/status` clean before any `branch-merge` call, so a future nested-worktree regression fails at the fixture invariant instead of only through merge behaviour.
