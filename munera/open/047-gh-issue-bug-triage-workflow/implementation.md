2026-04-23

- Created task `047-gh-issue-bug-triage-workflow` to add a bug-specific GitHub workflow parallel to the existing enhancement-ingest workflow.
- Chose to implement this as workflow/skill/prompt/task-definition content only, reusing existing `work-on`, `munera-task-design`, and `work-independently` capabilities.
- Intended new surfaces:
  - `.psi/skills/issue-bug-triage/SKILL.md`
  - `.psi/workflows/gh-bug-triage.md`
  - `.psi/prompts/gh-bug-triage.md`
- Key behavioral split captured in design:
  - if reproduction fails, comment on the issue asking for reproduction-helping information and relabel from `triage` to `waiting`
  - if reproduction succeeds, create a Munera task, fix the bug in the issue worktree, push, create a PR mentioning the issue, and remove `triage`
