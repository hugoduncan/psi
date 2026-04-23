---
name: gh-bug-triage
description: Find labeled GitHub bug-triage issues, attempt reproduction in an issue worktree, request more information when not reproducible, or fix and PR when reproducible
---
{:tools ["read" "bash" "edit" "write" "work-on"]
 :skills ["issue-bug-triage" "munera-task-design" "work-independently"]
 :thinking-level :high}

You are executing a focused GitHub bug-triage workflow in this repository.

Goal:
- Find open GitHub bug issues marked for triage.
- Read the selected issue carefully.
- Use the `issue-bug-triage` skill to attempt reproduction in an issue-specific worktree created from `origin/master`.
- If reproduction fails, post a concise reply requesting the minimum information likely to unblock reproduction, remove the `triage` label, and add `waiting`.
- If reproduction succeeds, create a Munera task in the issue worktree, refine the design with `munera-task-design`, fix the bug autonomously with `work-independently`, push the branch, create a PR mentioning the original issue number, and remove the `triage` label.

Use the `issue-bug-triage` skill during reproduction and evidence gathering.
Use the `munera-task-design` skill when shaping the Munera bug-fix task.
Use the `work-independently` skill once the design is clean and implementation begins.

Primary selection rule:
- Look for open GitHub issues carrying both the `triage` label and the `bug` label.
- If there are no matching issues, stop and report that there is nothing to process.
- If multiple matching issues exist, process them in ascending issue-number order unless the input narrows the target.

Input expectations:
- `$INPUT` is optional.
- If provided, treat it as an optional narrowing hint such as an issue number, repo-qualified issue reference, full issue URL, or a short instruction that identifies a specific matching issue.
- If `$INPUT` is absent, discover candidate issues from labels.

Required procedure:

1. Discover and select the issue.
   - Use `gh issue list` with JSON output to find open issues with both the `triage` and `bug` labels.
   - Retrieve enough list information to identify candidates, including at least: number, title, labels, state, and URL.
   - Treat the current repository as authoritative unless the input explicitly identifies another repository.
   - If the request input narrows the target, use it to select exactly one matching issue.

2. Read the selected issue.
   - Use `gh issue view` with JSON output.
   - Retrieve enough detail to understand and attempt reproduction, including at least: number, title, body, labels, author, assignees, state, and URL.
   - If the issue cannot be read, stop and report the failure instead of fabricating details.

3. Refresh the base branch.
   - Run `git fetch origin master`.
   - Treat `origin/master` as the authoritative base for the reproduction worktree.
   - If the fetch fails, stop and report the failure rather than proceeding on a stale base.

4. Create the issue worktree before reproduction.
   - Use the `work-on` tool, not manual `git worktree` shell commands.
   - Base the worktree on `origin/master`, using the explicit base-branch input supported by `work-on`.
   - Use a short issue-derived description, such as the issue number plus a concise bug title fragment.
   - After `work-on`, treat the resulting worktree path as authoritative for all repository edits, git commands, reproduction attempts, and later PR work.
   - If the `work-on` tool is unavailable, stop and report that limitation instead of improvising another mechanism.

5. Attempt reproduction.
   - Use the `issue-bug-triage` skill.
   - Distill the issue into these surfaces:
     - reported behavior
     - expected behavior if inferable
     - reproduction plan
     - attempted reproduction
     - reproduction status
     - missing information needed next
     - reproduction evidence for implementation handoff
   - Attempt the smallest concrete reproduction that the issue supports.
   - Keep the analysis faithful to the issue and to observed runtime/test evidence.
   - Conclude with exactly one explicit reproduction status: `reproducible` or `not-yet-reproducible`.
   - Do not claim the bug is reproduced unless the evidence supports that conclusion.

6. If reproduction failed.
   - Write a concise GitHub reply that says the issue could not yet be reproduced.
   - Ask only for the most useful additional information likely to unblock reproduction.
   - Prefer concrete requests such as exact reproduction steps, environment details, logs, screenshots, sample inputs, expected output, actual output, or version/commit details, depending on the issue.
   - Use `gh issue comment` to post the reply.
   - If posting the comment fails, stop and report the failure.
   - Remove the `triage` label from the issue.
   - Add the `waiting` label to the issue.
   - Stop after reporting the partial outcome.

7. If reproduction succeeded, create the Munera task.
   - Orient in Munera inside the issue worktree by reading `munera/plan.md` and inspecting `munera/open/` and `munera/closed/`.
   - Allocate the next canonical `NNN-slug` task id.
   - Create a new task directory under `munera/open/NNN-slug/`.
   - Write at least:
     - `design.md`
     - `steps.md`
     - `implementation.md`
   - Include issue provenance in the task files, especially the issue number and URL.
   - Seed the design from the concrete reproduction evidence rather than from speculation.

8. Refine the task design.
   - Use the `munera-task-design` skill.
   - Refine until the design is complete and unambiguous enough to pass the Munera design gate.
   - If the design cannot be made complete and unambiguous without external decisions or new information, stop implementation, preserve the design work, and report that blocked state clearly.

9. If the design is blocked after successful reproduction.
   - Commit the task-design work in the issue worktree.
   - Push the branch.
   - Create a PR that explains the blocked design state and mentions the original issue number.
   - Remove the `triage` label from the issue.
   - Do not add `waiting`; this workflow's requested label change on the reproducible path is only to remove `triage`.

10. If the design is clean, fix the bug autonomously.
   - Follow the `work-independently` skill.
   - Add or refine `plan.md` only after the design is complete and unambiguous.
   - Implement the bug fix in small, reviewable steps.
   - Keep Munera task files synchronized with what was learned and done.
   - Run relevant verification for the affected area.
   - If the bug is fixed successfully, commit the work, push the branch, and create a PR.
   - The PR must mention the original issue number, for example `Closes #<issue-number>` when appropriate.
   - Remove the `triage` label from the issue.

Execution constraints:
- Prefer one issue per run unless the input explicitly asks for batch processing.
- Use `work-on` rather than manual git worktree creation.
- Reproduction must happen in the issue-specific worktree, not the caller's current checkout.
- Do not create a Munera task when the issue is not yet reproducible.
- Do not proceed to implementation on an ambiguous design.
- Do not invent reproduction evidence, requirements, or bug causes.
- Keep changes scoped to the selected issue.
- Preserve Munera protocol distinctions among `design.md`, `plan.md`, `steps.md`, and `implementation.md`.

Suggested GitHub and git command shapes:
- `gh issue list --state open --label triage --label bug --json number,title,labels,state,url`
- `gh issue view "$ISSUE" --json number,title,body,labels,author,assignees,state,url`
- `git fetch origin master`
- `gh issue comment "$ISSUE" --body-file <path-or-stdin>`
- `gh issue edit "$ISSUE" --remove-label triage --add-label waiting`
- `gh issue edit "$ISSUE" --remove-label triage`
- `gh pr create ...`

Final response requirements:
- Report the selected issue.
- State whether reproduction succeeded.
- State whether a worktree was created and give its path when available.
- If reproduction failed, summarize the information requested and state whether labels were updated from `triage` to `waiting`.
- If reproduction succeeded, report the created Munera task id/path, whether the result was design-only or implementation-complete, the pushed branch name, and the PR URL if created.
- If nothing matched, say so clearly.
