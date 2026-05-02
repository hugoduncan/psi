---
name: gh-pr-fix-checks
description: Select a PR and run the shared PR check-healing loop until all checks pass or the work is blocked
---
{:steps [{:name "select"
          :workflow "builder"
          :session {:input {:from :workflow-input}
                    :reference {:from :workflow-original}
                    :skills ["work-independently"]}
          :prompt "Find exactly one open GitHub PR in this repository whose checks should be healed. Work independently. Use `$INPUT` only as an optional narrowing hint such as a PR number, URL, branch name, or short selector.\n\nRequired procedure:\n1. Use `gh pr list --state open --json number,title,url,headRefName,baseRefName,labels,statusCheckRollup` to discover candidate PRs.\n2. If `$INPUT` identifies a PR directly, use it to narrow to exactly one PR.\n3. If `$INPUT` is empty, prefer the PR associated with the current branch when that is unambiguous; otherwise pick the lowest-numbered open PR.\n4. Read the selected PR with `gh pr view <pr> --json number,title,body,url,headRefName,baseRefName,statusCheckRollup`.\n5. Emit a compact Markdown handoff with these headings exactly:\n   - `## PR Selection`\n   - `## Initial Check Snapshot`\n   - `## Handoff Data`\n6. Under `## Handoff Data`, include machine-friendly bullet lines for:\n   - `pr_number:`\n   - `pr_title:`\n   - `pr_url:`\n   - `pr_branch:`\n   - `pr_base_branch:`\n   - `worktree_description:`\n   - `initial_check_summary:`\n\nThe worktree description should be a short branch-derived slug suitable for a branch-specific worktree. Keep `initial_check_summary` terse but informative."}
         {:name "heal-checks"
          :workflow "gh-pr-heal-check-loop"
          :session {:input {:from {:step "select" :kind :accepted-result}}
                    :reference {:from :workflow-original}}
          :prompt "$INPUT"}]}

Select a GitHub PR to operate on, then run the shared PR check-healing loop until all checks pass or the workflow reaches a clearly reported blocked state.