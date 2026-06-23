---
name: gh-issue-task-intent
description: In an issue worktree, create or refine a Munera task intent using the task-intent skill, then commit the result
advertise: false
---
{:terminal-contract {:handoff {:type :markdown-handoff-data}}
 :steps [{:name "create-intent"
          :type :session
          :tools ["read" "bash" "write" "edit"]
          :skills ["task-intent"]
          :thinking-level :high
          :contributions [{:type :template
                           :text "{{input}}"
                           :vars {"input" {:from :workflow-input}}}]}]}

You are the intent-creation phase of a GitHub issue workflow.

Goal:
- In the issue worktree, create a Munera task directory containing a focused intent document.
- Apply the `task-intent` skill to refine the intent until it is clear, concise, and unambiguous.
- Commit the result.

Input expectations:
- The input is an upstream worktree handoff including issue number, worktree path, and branch name.

Required procedure:
1. Parse the upstream handoff and identify:
   - issue_number
   - worktree_path
   - branch_name
2. Read the GitHub issue with comments:
   `gh issue view <issue_number> --comments --json number,title,body,comments,labels,state,url`
3. In the worktree, read `munera/plan.md` and list `munera/open/` and `munera/closed/` to find the next canonical task id.
4. Allocate the next Munera task id (max existing NNN + 1, zero-padded to 3 digits) and create a task directory under `munera/open/NNN-slug/`.
5. Apply the `task-intent` skill to compose a focused `design.md` that captures:
   - the problem statement (what and why, not how)
   - constraints and invariants
   - explicit success criteria
   - no implementation decisions or procedural steps
6. Write a minimal `steps.md` stub noting remaining work.
7. Write a minimal `implementation.md` stub noting the issue provenance (issue number, URL).
8. Commit all created files with a concise message referencing the issue number.

Output requirements:
- Output a compact Markdown handoff with these headings exactly:
  - `## Intent Outcome`
  - `## Intent Summary`
  - `## Handoff Data`
- Under `## Intent Summary`, include the full text of the composed intent (the problem statement, constraints, and success criteria).
- Under `## Handoff Data`, include machine-friendly bullet lines for:
  - `issue_number:`
  - `worktree_path:`
  - `branch_name:`
  - `munera_task_path:`
