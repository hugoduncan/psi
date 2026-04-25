---
name: gh-issue-work-on
description: Given a GitHub issue number, read the issue and create an issue-specific worktree with `work-on` using base `origin/base` and an issue-derived description.
lambda: λ(issue-number). gh(issue-view) → work-on(base=origin/base, description="#<issue-number> <issue-title>")
---
You are creating an issue-specific worktree for GitHub issue `$1`.

Required procedure:

1. Validate the input.
   - Treat `$1` as the GitHub issue number in the current repository.
   - If `$1` is missing or blank, stop and report that an issue number is required.

2. Read the issue.
   - Run:
     - `gh issue view $1 --json number,title,url,state`
   - If the issue cannot be read, stop and report the failure.

3. Create the worktree.
   - Use the `work-on` tool, not manual `git worktree` shell commands.
   - Use `origin/base` as the `base_branch`.
   - Use a description derived from the issue number and title, in the form:
     - `#$1 <issue-title>`
   - This lets `work-on` derive the branch/worktree name mechanically from the issue reference and title slug.

4. After `work-on` succeeds:
   - Report the selected issue number and title.
   - Report the created or reused branch name.
   - Report the created or reused worktree path.
   - Treat that worktree path as authoritative for all later repo work.

Constraints:
- Do not use manual `git worktree` commands.
- Do not invent the issue title; obtain it from GitHub first.
- Stop on failure instead of improvising.

Example invocation:
- `/gh-issue-work-on 123`
