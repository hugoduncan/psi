# Plan

## Approach

Fix this as a root-cause-first bug, not by relaxing `branch-merge`'s dirty-tree precondition.

1. Reproduce the focused `psi.history.git-worktree-test` failure cluster and capture the exact dirty signal seen by `git/branch-merge` immediately before the merge.
2. Determine whether the dirty state is a real fixture artifact, an environment/configuration artifact, or a production dirty-check bug.
3. Apply the smallest fix at the correct boundary:
   - If the test fixture creates real untracked paths inside the target worktree (for example nested linked worktrees under `repo-dir/worktrees`), change the fixture layout/configuration so merge source worktrees live outside the target worktree.
   - If `psi.history.git/status` misclassifies Git porcelain output, fix the status implementation while preserving detection of modified, staged, and untracked files/directories.
   - If seeded null repos inherit problematic environment/global config, pin only the relevant test-repo config in `create-null-context` or the fixture.
4. Preserve and, if needed, tighten executable coverage so genuine dirty worktrees are still rejected, including untracked files/directories.
5. Document the root cause and decision in `implementation.md` before closing the task.

Key decision: production `branch-merge` should continue to treat untracked files and directories as dirty. Fixture artifacts must not be hidden by weakening production semantics.

## Risks

- The failure may depend on local Git version or global Git config; capture command output/config before choosing a fix.
- Moving linked worktree fixture paths can affect other worktree-add/remove/list tests that assume paths are nested under the main repo.
- A production status change could accidentally stop detecting untracked directories or staged changes; guard this with focused tests before changing semantics.
- `bb test` may still have unrelated pre-existing failures after this fix; compare focused results and the non-target failure baseline rather than treating unrelated suite failures as part of this task.

## Slice order

1. **Reproduce and observe dirty evidence** — run the focused failing tests and inspect `git status --porcelain`/related Git state at the merge precondition.
2. **Classify root cause** — decide whether the dirty signal comes from fixture layout, dirty-check implementation, or environment/global config; record the finding.
3. **Patch the owning boundary** — update the fixture or production code with the smallest semantics-preserving change.
4. **Lock behaviour with tests** — ensure all `branch-merge-*` paths pass and genuine dirty-tree rejection remains meaningful, including untracked paths.
5. **Validate and document** — run focused and broader tests, record root cause and verification in `implementation.md`, and confirm unrelated failures are unchanged.
