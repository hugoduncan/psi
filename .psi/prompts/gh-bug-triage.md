---
name: gh-bug-triage
description: Example invocations for the gh-bug-triage workflow.
lambda: Show how to invoke the gh-bug-triage workflow to process labeled GitHub bug-triage issues, attempt reproduction in an issue worktree, and either request more information or continue to a fix PR.
---
Use this prompt when you want to run the `gh-bug-triage` workflow against GitHub issues labeled for bug triage.

Example invocations:

- Process the next matching issue in the current repository:
  - `Run workflow gh-bug-triage`
  - `Use gh-bug-triage`

- Narrow to a specific matching issue number:
  - `Run workflow gh-bug-triage for issue 123`
  - `Use gh-bug-triage on 123`

- Repo-qualified issue reference:
  - `Run workflow gh-bug-triage for hugoduncan/psi#123`

- Full URL:
  - `Run workflow gh-bug-triage for https://github.com/hugoduncan/psi/issues/123`

Expected outcome:
- the workflow finds an open issue with both `triage` and `bug` labels
- creates an issue-specific worktree from `origin/master`
- attempts reproduction using the `issue-bug-triage` skill
- if reproduction fails:
  - posts a concise reply requesting the most useful additional reproduction information
  - removes the `triage` label
  - adds the `waiting` label
- if reproduction succeeds:
  - creates a Munera task for the bug fix
  - refines the design and fixes the bug
  - pushes the branch and creates a PR mentioning the original issue number
  - removes the `triage` label

When invoked without an explicit issue reference, the workflow should discover and process the next matching issue automatically.
