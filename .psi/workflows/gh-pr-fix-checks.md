---
name: gh-pr-fix-checks
description: Select a PR and run the shared PR check-healing loop until all checks pass or the work is blocked
---
{:steps [{:name "select"
          :type :delegate
          :target "builder"
          :prompt-string {:type :template
                          :text "Find exactly one open GitHub PR in this repository whose checks should be healed. Work independently. Use `{{input}}` only as an optional narrowing hint such as a PR number, URL, branch name, or short selector.\n\nRequired procedure:\n1. Use `gh pr list --state open --json number,title,url,headRefName,baseRefName,labels,statusCheckRollup` to discover candidate PRs.\n2. If `{{input}}` identifies a PR directly, use it to narrow to exactly one PR.\n3. If `{{input}}` is empty, prefer the PR associated with the current branch when that is unambiguous; otherwise pick the lowest-numbered open PR.\n4. Read the selected PR with `gh pr view <pr> --json number,title,body,url,headRefName,baseRefName,statusCheckRollup`.\n5. Emit a compact Markdown handoff with these headings exactly:\n   - `## PR Selection`\n   - `## Initial Check Snapshot`\n   - `## Handoff Data`\n6. Under `## Handoff Data`, include machine-friendly bullet lines for:\n   - `pr_number:`\n   - `pr_title:`\n   - `pr_url:`\n   - `pr_branch:`\n   - `pr_base_branch:`\n   - `worktree_description:`\n   - `initial_check_summary:`\n\nThe worktree description should be a short branch-derived slug suitable for a branch-specific worktree. Keep `initial_check_summary` terse but informative."
                          :vars {"input" {:from :workflow-input
                                           :path [:input]}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "heal-checks"
          :type :delegate
          :target "gh-pr-heal-check-loop"
          :prompt-string {:type :template
                          :text "{{report}}"
                          :vars {"report" {:from {:step "select" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "select" :yield :text}}]}]}

Select a GitHub PR to operate on, then run the shared PR check-healing loop until all checks pass or the workflow reaches a clearly reported blocked state.
