---
name: gh-pr-fix-current-checks
description: Resolve the PR for the current branch and run the shared PR check-healing loop until all checks pass or the work is blocked
---
{:steps [{:name "resolve-current-pr"
          :workflow "builder"
          :session {:input {:from :workflow-input}
                    :reference {:from :workflow-original}
                    :skills ["work-independently"]}
          :prompt "Resolve the open GitHub PR associated with the current branch/worktree and prepare an authoritative handoff for check healing. Work independently.\n\nRequired procedure:\n1. Determine the current git branch in the current worktree.\n2. Resolve the open PR for that branch. Prefer branch-based discovery and use GitHub CLI as the authority.\n3. If there is no open PR for the current branch, stop and report that clearly.\n4. Read the resolved PR with `gh pr view <pr> --json number,title,body,url,headRefName,baseRefName,statusCheckRollup`.\n5. Emit a compact Markdown handoff with these headings exactly:\n   - `## Current Branch PR`\n   - `## Initial Check Snapshot`\n   - `## Handoff Data`\n6. Under `## Handoff Data`, include machine-friendly bullet lines for:\n   - `pr_number:`\n   - `pr_title:`\n   - `pr_url:`\n   - `pr_branch:`\n   - `pr_base_branch:`\n   - `worktree_description:`\n   - `initial_check_summary:`\n\nSet `worktree_description:` to a short branch-derived slug suitable for a branch-specific worktree. Keep `initial_check_summary` terse but informative."}
         {:name "heal-checks"
          :workflow "gh-pr-heal-check-loop"
          :session {:input {:from {:step "resolve-current-pr" :kind :accepted-result}}
                    :reference {:from :workflow-original}}
          :prompt "$INPUT"}]}

Resolve the PR associated with the current branch/worktree, then run the shared PR check-healing loop until all checks pass or the workflow reaches a clearly reported blocked state.