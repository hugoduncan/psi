---
name: gh-issue-implement
description: Find an implement-labeled PR, prepare its branch worktree, design and implement the task, then push back to the PR branch and advance PR labels
---
{:steps [{:name      "search"
          :type      :invoke
          :operation "github/find-pr"
          :args      {:labels ["implement"]
                      :input  {:from :workflow-input :path [:input]}}
          :outputs   {:summary {:source :invoke/summary}
                      :data    {:source :invoke/data}}
          :yields    {:type :text :text :summary}}
         {:name "prep"
          :type :delegate
          :target "builder"
          :prompt-string {:type :template
                          :text "Prepare the PR branch worktree described by {{search_report}}. Work independently.\n\nRequired procedure:\n1. Read the upstream handoff to identify the PR number, PR branch, base branch, and desired worktree description.\n2. Create or reuse a branch-specific worktree for the PR branch. Reuse in place if an appropriate worktree already exists for that branch.\n3. In that worktree, fetch the PR branch and `origin/master`.\n4. Check out the PR branch if needed.\n5. Rebase the PR branch onto `origin/master`.\n6. If the rebase rewrites history, push back to the PR branch with `--force-with-lease`. Otherwise do a normal push.\n7. If prep fails at any point, stop and report the failure instead of continuing on a stale or ambiguous branch state.\n\nOutput requirements:\n- Output a compact Markdown handoff with these headings exactly:\n  - `## Prep Outcome`\n  - `## Handoff Data`\n- Under `## Handoff Data`, include machine-friendly bullet lines for:\n  - `pr_number:`\n  - `pr_url:`\n  - `pr_branch:`\n  - `worktree_path:`\n  - `rebase_status:`\n  - `push_mode:`"
                          :vars {"search_report" {:from {:step "search" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "search" :yield :text}}]}
         {:name "design"
          :type :delegate
          :target "builder"
          :prompt-string {:type :template
                          :text "Using the prepared PR branch worktree described by {{prep_report}}, create and refine the Munera task for the PR. Use the `task-design`, `clojure-coding-standards`, and `testing-without-mocks` skills, and work independently.\n\nRequired procedure:\n1. Read the upstream handoff to identify the PR number, PR URL, PR branch, and worktree path.\n2. In the worktree, read `munera/plan.md` and inspect `munera/open/` and `munera/closed/`.\n3. Read the PR and its current discussion context as needed with `gh pr view <pr> --comments`.\n4. Allocate the next canonical Munera task id and create a new task directory under `munera/open/NNN-slug/` if one does not already exist for this PR. Reuse and refine it if it already exists.\n5. Write or refine at least `design.md`, `steps.md`, and `implementation.md`.\n6. Include PR provenance in the task files, especially the PR number, URL, and branch name.\n7. Refine `design.md` with the `task-design` skill until it is complete and unambiguous. The design must specify not only conceptual behavior but also the intended implementation approach.\n8. In `design.md`, make the implementation approach explicit enough for a builder to execute without inventing core mechanics. Cover at least:\n   - implementation strategy and architectural fit\n   - key algorithms or procedural approach\n   - main data structures, state shapes, and configuration shapes\n   - interface changes, including commands, flags, API/resolver/tool surfaces, and file/config format changes as applicable\n   - important invariants, edge cases, and verification expectations\n   - important alternatives considered and rejected when that matters for clarity\n9. Shape the planned Clojure implementation and test approach so it follows `clojure-coding-standards` and `testing-without-mocks`.\n10. Record terse design/refinement notes in `implementation.md`.\n11. Keep `steps.md` synchronized with the planned implementation work.\n12. At the end of the refinement pass, commit the design/task-artifact updates with an appropriate commit message if there are changes to record.\n13. If ambiguities remain after this pass, say so explicitly and list them tersely. If no ambiguities remain, say so explicitly.\n\nOutput requirements:\n- Output a compact Markdown summary with these headings exactly:\n  - `## Design Outcome`\n  - `## Munera Task`\n  - `## Handoff Data`\n- Under `## Handoff Data`, include machine-friendly bullet lines for:\n  - `pr_number:`\n  - `pr_url:`\n  - `pr_branch:`\n  - `worktree_path:`\n  - `munera_task_path:`\n  - `ambiguity_status:`\n\nSet `ambiguity_status:` to either `ambiguous` or `clear`."
                          :vars {"prep_report" {:from {:step "prep" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "search" :yield :text}}
                    {:type :source
                     :from {:step "prep" :yield :text}}]}
         {:name "design-status"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "search" :yield :text}}
                          {:type :source
                           :from {:step "prep" :yield :text}}
                          {:type :source
                           :from {:step "design" :yield :text}}
                          {:type :template
                           :text "Respond exactly with one word: REPEAT or DONE.\n\nUse the actor step context to identify the specific Munera task under review, especially the `munera_task_path`, `worktree_path`, and PR metadata. Then independently inspect the task artifacts in that task directory, especially `design.md`, and use `steps.md` / `implementation.md` when helpful.\n\nReturn REPEAT if the identified task design still has material ambiguities, missing decisions, incomplete acceptance criteria, or an underspecified implementation approach. Return DONE only if the identified Munera task design is complete and unambiguous enough to begin implementation, including the implementation strategy, key algorithms, data structures, and interface changes.\n\nDo not re-review the whole repository generically. Judge the specific Munera task named by the actor output."
                           :vars {}}]
          :judge {:type :llm
                  :contributions [{:type :template
                                   :text "Respond exactly with one word: REPEAT or DONE."
                                   :vars {}}]}
          :on {"REPEAT" {:goto "design"
                          :max-iterations 6}
               "DONE" {:goto :next}}}
         {:name "implement"
          :type :delegate
          :target "builder"
          :prompt-string {:type :template
                          :text "Execute the Munera task described by {{design_report}}. Work independently. Use the `clojure-coding-standards` and `testing-without-mocks` skills.\n\nRequired procedure:\n1. Read the upstream handoff to identify the PR number, PR branch, worktree path, and Munera task path.\n2. In the worktree, execute the task autonomously using the refined design as authoritative guidance.\n3. Add or refine `plan.md` only after the design is complete and unambiguous.\n4. Implement in small, reviewable steps.\n5. Keep `design.md`, `plan.md`, `steps.md`, and `implementation.md` synchronized with what was learned and done.\n6. Run relevant verification for the affected area.\n7. Shape the implementation and tests to follow `clojure-coding-standards` and `testing-without-mocks`.\n8. Record any important deviations from the initial design in `implementation.md` so they can be summarized back onto the PR.\n9. At the end of the implementation pass, commit the implementation/task-artifact updates with an appropriate commit message if there are changes to record.\n\nOutput requirements:\n- Output a compact Markdown summary with these headings exactly:\n  - `## Implementation Outcome`\n  - `## Verification`\n  - `## Handoff Data`\n- Under `## Handoff Data`, include machine-friendly bullet lines for:\n  - `pr_number:`\n  - `pr_url:`\n  - `pr_branch:`\n  - `worktree_path:`\n  - `munera_task_path:`\n  - `deviation_summary:`"
                          :vars {"design_report" {:from {:step "design" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "design" :yield :text}}]}
         {:name "review"
          :type :delegate
          :target "review-implementation"
          :prompt-string {:type :template
                          :text "Improve the implemented Munera task described by {{implementation_report}} by running the review-implementation workflow in the same PR worktree. Work independently. Use the preloaded design handoff so the review can compare implementation against the intended design. Use the `clojure-coding-standards` and `testing-without-mocks` skills while carrying out review follow-up work. Execute the review workflow until it converges, update the task artifacts as it goes, preserve any meaningful deviations from the initial design in `implementation.md` so they can be summarized back onto the PR, and commit the review/task-artifact updates at the end of the review pass if there are changes to record."
                          :vars {"implementation_report" {:from {:step "implement" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "design" :yield :text}}
                    {:type :source
                     :from {:step "implement" :yield :text}}]}
         {:name "push"
          :type :delegate
          :target "builder"
          :prompt-string {:type :template
                          :text "Push the reviewed implementation back to the existing PR branch. Work independently.\n\nRequired procedure:\n1. Read the upstream handoff to identify the PR number, PR URL, PR branch, worktree path, Munera task path, and any deviation summary.\n2. In the worktree, verify the local branch matches the PR branch and review the current git status.\n3. Commit any remaining implementation or review follow-up changes if needed.\n4. Push the work back to the PR branch.\n5. Post a PR comment summarizing any meaningful deviations from the initial design that were recorded during implementation or review. If there were no meaningful deviations, say so explicitly.\n6. If the push fails, report the failure clearly.\n\nOutput requirements:\n- Output a compact Markdown summary with these headings exactly:\n  - `## Push Outcome`\n  - `## Verification`\n  - `## Handoff Data`\n- Under `## Handoff Data`, include machine-friendly bullet lines for:\n  - `pr_number:`\n  - `pr_url:`\n  - `pr_branch:`\n  - `worktree_path:`\n  - `munera_task_path:`"
                          :vars {"review_report" {:from {:step "review" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "design" :yield :text}}
                    {:type :source
                     :from {:step "implement" :yield :text}}
                    {:type :source
                     :from {:step "review" :yield :text}}]}
         {:name      "remove-implement"
          :type      :invoke
          :operation "github/remove-label"
          :args      {:number {:from {:step "search" :output :data} :path [:pr-number]}
                      :labels ["implement"]
                      :target "pr"}}
         {:name      "add-review"
          :type      :invoke
          :operation "github/add-label"
          :args      {:number {:from {:step "search" :output :data} :path [:pr-number]}
                      :labels ["review"]
                      :target "pr"}}]}

Coordinate implementation work for an existing GitHub PR labeled `implement`: select the PR deterministically, prepare or reuse its branch-specific worktree, rebase the PR branch onto `origin/master`, create and refine a Munera task design with explicit implementation approach detail, implement the task, review and improve the task implementation through the `review-implementation` workflow, then push back to the PR branch, summarize any meaningful deviations from the initial design on the PR, remove the PR's `implement` label, and add the `review` label. All stages use the `work-independently` skill.
