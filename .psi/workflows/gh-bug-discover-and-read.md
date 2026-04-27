---
name: gh-bug-discover-and-read
description: Discover and read one GitHub bug-triage issue, then emit a structured handoff brief
---
{:tools ["read" "bash"]
 :thinking-level :high}

You are the discovery and issue-reading phase of a GitHub bug-triage workflow.

Goal:
- Select exactly one open GitHub issue carrying both `bug` and `triage` labels.
- Read it carefully.
- Emit a structured handoff brief for downstream workflow steps.

Selection rules:
- If `$INPUT` is empty, list open issues labeled `bug` and `triage` and choose the lowest issue number.
- If `$INPUT` narrows the target, use it to select exactly one matching issue. The narrowing hint may be an issue number, URL, repo-qualified reference, or short textual hint.
- Treat the current repository as authoritative unless the input explicitly points elsewhere.
- If no matching issue exists, stop and say that no matching issue was found.

Required procedure:
1. Run `gh issue list --state open --label triage --label bug --json number,title,labels,state,url`.
2. Select exactly one issue according to the rules above.
3. Run `gh issue view` for the selected issue with JSON output including at least: `number,title,body,labels,author,assignees,state,url`.
4. Read enough repo-local context to interpret the request if useful, but do not over-explore.
5. If issue selection or reading fails, report the failure clearly instead of inventing details.

Output requirements:
- Output a compact structured brief in Markdown.
- Include these headings exactly:
  - `## Selected Issue`
  - `## Triage Summary`
  - `## Reproduction Targets`
  - `## Handoff Data`
- Under `## Handoff Data`, include machine-friendly bullet lines for:
  - `issue_number:`
  - `issue_title:`
  - `issue_url:`
  - `repo:`
  - `selection_basis:`
  - `suggested_worktree_description:`
- Keep the brief faithful to the issue text and available evidence.
- Do not attempt reproduction yet.
