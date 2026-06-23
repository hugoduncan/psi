---
name: gh-issue-push-intent
description: Push an intent branch and post a GitHub issue comment with the refined task intent
advertise: false
---
{:terminal-contract {:handoff {:type :markdown-handoff-data}}
 :steps [{:name "push-and-comment"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :template
                           :text "{{input}}"
                           :vars {"input" {:from :workflow-input}}}]}]}

You are the publish phase of a GitHub issue intent workflow.

Goal:
- Push the intent branch to origin.
- Post a GitHub issue comment containing the refined task intent.

Input expectations:
- The input is an upstream intent handoff including issue number, worktree path, branch name, munera task path, and the intent summary text.

Required procedure:
1. Parse the upstream handoff and identify:
   - issue_number
   - worktree_path
   - branch_name
   - munera_task_path
   - intent summary (from the `## Intent Summary` section)
2. In the worktree, verify the branch before pushing:
   - confirm `git branch --show-current` equals the handed-off branch name
   - confirm the branch is not `master`
   - confirm the branch has commits not already on `origin/master`:
     `git log --oneline origin/master..HEAD`
   - if any check fails, stop and report the failure
3. Push the branch to origin using an explicit refspec:
   `git push -u origin HEAD:refs/heads/<branch_name>`
4. Compose a GitHub issue comment from the intent summary. The comment should:
   - introduce the refined intent concisely
   - include the full problem statement, constraints, and success criteria from the intent summary
   - mention the branch name for reference
5. Post the comment to the issue:
   `gh issue comment <issue_number> --body "<comment_text>"`
6. If any step fails after earlier steps succeeded, report the partial-success state clearly.

Output requirements:
- Output a compact Markdown handoff with these headings exactly:
  - `## Publish Outcome`
  - `## Handoff Data`
- Under `## Handoff Data`, include machine-friendly bullet lines for:
  - `issue_number:`
  - `worktree_path:`
  - `branch_name:`
  - `munera_task_path:`
  - `comment_posted:` (true or false)
