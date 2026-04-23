Implemented explicit optional base-branch support for `extensions.work-on` while preserving the single canonical execution path shared by `/work-on` and the `work-on` tool.

Key decisions and outcomes:
- Added a local command parser supporting only:
  - `/work-on <description>`
  - `/work-on --base <branch> <description>`
- Kept parsing local to `extensions.work-on`; no command-framework changes.
- Extended canonical execution to accept `{:description ... :base-branch ...}`.
- Preserved default behavior when `base-branch` is omitted by continuing to call `git.worktree/add!` with `:base_ref nil`.
- Threaded explicit base branch to `git.worktree/add!` as `:base_ref` on initial new-creation attempts.
- Preserved reuse semantics:
  - existing-branch attach remains reuse for reporting purposes
  - existing-worktree/session reuse remains unchanged
- Added canonical result fields when a base branch is supplied:
  - `:requested-base-branch`
  - `:base-branch-applied?`
- `:base-branch-applied?` is true only for successful new-creation paths and false for reuse paths.
- Tool schema now includes optional `base_branch` and blank values are rejected with a canonical runtime error.

Notable behavior details:
- On branch-already-exists fallback, the second `git.worktree/add!` call still uses `:create-branch false` and `:base_ref nil`; requested base branch is recorded but not applied.
- Fresh branch creation now passes `--no-track` through the git worktree add path so a requested base ref such as `origin/master` sets ancestry without incorrectly configuring the new branch to track that base ref.
- Command parse errors surface usage/help before execution; git/base-branch validity remains enforced by the canonical git mutation path rather than prevalidation.

Tests added/updated:
- tool registration/schema coverage for optional `base_branch`
- parser coverage for plain description and `--base <branch> <description>`
- command parse error coverage for missing `--base` value and missing description
- tool validation coverage for blank `base_branch`
- command/tool happy-path coverage for explicit base-branch threading
- default-path assertions proving omitted base branch keeps `:base_ref nil`
- reuse-path coverage proving requested base branch is recorded but not applied

Focused verification:
- ran `clojure -M:test --focus extensions.work-on-test`
- result: `20 tests, 111 assertions, 0 failures`

Review follow-up summary:
- review found that existing-branch attach success was not distinguished from fresh creation, so `:base-branch-applied?` could be overstated
- review also found that `/work-on --base <branch>` without a description fell back to generic usage instead of the specific malformed-flag error
- follow-up changes fixed both issues and the focused `extensions.work-on` namespace remained green
