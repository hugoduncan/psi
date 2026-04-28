---
name: gh-bug-triage
description: Find labeled GitHub bug-triage issues, attempt reproduction in an issue worktree, request more information when not reproducible, or publish a repro branch and relabel for fixing when reproducible
---
{:tools ["read" "bash" "edit" "write" "work-on"]
 :skills ["issue-bug-triage"]
 :thinking-level :high}

You are executing a focused GitHub bug-triage workflow in this repository.

Goal:
- Find open GitHub bug issues marked for triage.
- Read the selected issue carefully.
- Use the `issue-bug-triage` skill to attempt reproduction in an issue-specific worktree created from `origin/master`.
- If reproduction fails, post a concise reply requesting the minimum information likely to unblock reproduction, remove the `triage` label, and add `waiting`.
- If reproduction succeeds, preserve and push the reproduction branch, comment on the issue with a link to that branch and a concise reproduction summary, remove the `triage` label, and add `fix`.

Use the `issue-bug-triage` skill during reproduction and evidence gathering.

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
   - Call the `work-on` tool directly.
   - Do not use `/work-on` slash-command syntax.
   - Do not reason from command-line usage text such as `--base`; this workflow must use the structured tool surface.
   - Invoke `work-on` with structured arguments:
     - `description`: a short issue-derived description, such as the issue number plus a concise bug title fragment
     - `base_branch`: `origin/master`
   - Example tool call shape:
     - `{"description":"25 custom llm providers","base_branch":"origin/master"}`
   - Do not use manual `git worktree` shell commands.
   - After a successful `work-on` tool call, treat the returned worktree path as authoritative for all repository edits, git commands, reproduction attempts, and later handoff work.
   - If the `work-on` tool is unavailable or the tool call fails, stop and report that limitation or failure instead of improvising another mechanism.

5. Attempt reproduction.
   - Use the `issue-bug-triage` skill.
   - Distill the issue into these surfaces:
     - reported behavior
     - expected behavior if inferable
     - reproduction plan
     - attempted reproduction
     - reproduction status
     - missing information needed next
     - reproduction evidence for handoff
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

7. If reproduction succeeded.
   - Use the issue worktree as authoritative.
   - Preserve any reproduction artifacts or notes that should travel with the handoff branch.
   - Commit that reproduction-only handoff state if needed.
   - Push the branch.
   - Comment on the issue with:
     - a concise statement that the bug was reproduced
     - a link to the pushed branch containing the reproduction work
     - a brief summary of the strongest reproduction evidence
   - Remove the `triage` label from the issue.
   - Add the `fix` label to the issue.
   - Stop after reporting the classification outcome.

Execution constraints:
- Prefer one issue per run unless the input explicitly asks for batch processing.
- Use `work-on` rather than manual git worktree creation.
- Reproduction must happen in the issue-specific worktree, not the caller's current checkout.
- Do not create a Munera task.
- Do not implement a fix.
- Do not create a PR.
- Do not invent reproduction evidence, requirements, or bug causes.
- Keep changes scoped to the selected issue and reproduction handoff only.

Suggested GitHub and git command shapes:
- `gh issue list --state open --label triage --label bug --json number,title,labels,state,url`
- `gh issue view "$ISSUE" --json number,title,body,labels,author,assignees,state,url`
- `git fetch origin master`
- `gh issue comment "$ISSUE" --body-file <path-or-stdin>`
- `gh issue edit "$ISSUE" --remove-label triage --add-label waiting`
- `gh issue edit "$ISSUE" --remove-label triage --add-label fix`
- `git push -u origin <branch>`

Final response requirements:
- Report the selected issue.
- State whether reproduction succeeded.
- State whether a worktree was created and give its path when available.
- If reproduction failed, summarize the information requested and state whether labels were updated from `triage` to `waiting`.
- If reproduction succeeded, report the pushed branch name, the branch URL if available, the issue comment outcome, and whether labels were updated from `triage` to `fix`.
- If nothing matched, say so clearly.
