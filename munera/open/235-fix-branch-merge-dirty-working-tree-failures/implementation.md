- no architectural review feedback
- ambiguity review added 1 new design step
- no inconsistency review feedback
- Design-step handoff: when resolving the dirty-check ambiguity, preserve root-cause-first/no-mocks isolated-temp-repo testing; inspect `components/history/src/psi/history/git.clj` (`status`, `dirty-working-tree?`, `branch-merge`) and `components/history/test/psi/history/git_worktree_test.clj` (`linked-worktree-path`, `branch-merge-*`) before choosing fixture cleanup/configuration vs production status semantics.

- Follow-up resolved: `branch-merge` dirty checks should count untracked target-worktree files/directories as dirty; nested linked worktrees under the repo root are test-fixture artifacts to isolate rather than production status entries to ignore. Local reproduction: after `git worktree add` into `<repo>/worktrees/...`, main `git status --porcelain` reports `?? worktrees/`, while the linked worktree itself is clean.

- no new ambiguity review feedback

- Design-step carry-forward: no open design follow-ups remain; implementation should treat the resolved untracked-file decision as a constraint, not a scope change. If the fix touches the EQL mutation surface, `components/history/src/psi/history/resolvers.clj` only delegates to `git/branch-merge`; keep behaviour owned in `components/history/src/psi/history/git.clj` and proof in `components/history/test/psi/history/git_worktree_test.clj`.
