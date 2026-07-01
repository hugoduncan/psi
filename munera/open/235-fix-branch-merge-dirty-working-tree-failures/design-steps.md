# Design follow-ups

- [ ] Clarify whether `branch-merge`'s dirty-working-tree precondition should treat untracked files/directories as dirty, including nested linked-worktree directories created by tests, so the implementation can choose test-fixture cleanup/configuration vs production dirty-check semantics without weakening genuine dirty-tree rejection.
