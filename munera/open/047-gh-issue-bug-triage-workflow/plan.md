Approach:
- Keep the change documentation-only and workflow-definition-only unless a real loader/runtime gap is discovered.
- Reuse the established pattern from `gh-issue-ingest` and `gh-issue-implement` rather than inventing a new workflow shape.
- Add one new bug-focused skill, one new workflow, and one prompt/example file.
- Record the task in `munera/plan.md` so the new work is part of active orchestration.

Decisions:
- Workflow name: `gh-bug-triage`.
- Skill name: `issue-bug-triage`.
- The workflow should declare tools `read`, `bash`, `edit`, `write`, and `work-on` because it needs GitHub CLI, task-file creation, and issue-specific worktree creation.
- The workflow should declare skills `issue-bug-triage`, `munera-task-design`, and `work-independently`.
- The reproduction phase should explicitly create the issue worktree from `origin/master` via `work-on` with `base_branch`/`--base` support.
- The workflow should process one issue per run unless narrowed input specifies a particular issue.

Implementation steps:
1. Add the Munera task and update `munera/plan.md`.
2. Add `.psi/skills/issue-bug-triage/SKILL.md`.
3. Add `.psi/workflows/gh-bug-triage.md`, mirroring the clarity and structure of the existing GitHub workflows.
4. Add `.psi/prompts/gh-bug-triage.md` with example invocations and expected outcome.
5. Re-read the new files for coherence with existing `gh-issue-ingest` and `gh-issue-implement` behavior.

Verification:
- Read the new workflow/skill/prompt files for consistency and completeness.
- Ensure label names and branch behavior exactly match the requested contract.
- Ensure the workflow refers to the correct existing skills and tools and does not require nonexistent mechanisms.

Risks:
- The exact GitHub reply wording on the failure branch should stay helpful but not over-prescriptive.
- The workflow should not imply that reproduction is guaranteed; it must allow a clean not-yet-reproducible outcome.
