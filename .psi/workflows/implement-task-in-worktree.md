---
name: implement-task-in-worktree
description: Resolve worktree from a structured handoff, then implement a Munera task via the implement-task workflow
advertise: false
---
{:terminal-contract {:handoff {:type :markdown-handoff-data}}
 :steps [{:name "resolve-worktree"
          :type :session
          :tools ["read" "bash" "work-on"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Extract the worktree path and Munera task path from the following handoff data. Call `work-on` with the extracted worktree path to set the session worktree. Then respond with ONLY the Munera task path (e.g. `munera/open/003-foo`) on a single line — nothing else.\n\nHandoff data:\n{{input}}"
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}}}]}
         {:name "implement"
          :type :delegate
          :target "implement-task"
          :prompt-string {:type :map
                          :fields {:input {:from {:step "resolve-worktree" :yield :text}}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "summary"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "implement" :yield :text}}
                          {:type :template
                           :text "Produce the user-facing final result for the Munera task. Independently inspect that specific task's artifacts, especially `design.md`, `plan.md`, `steps.md`, and `implementation.md`, and use the prior step outputs as supporting context.\n\nOutput requirements:\n- Output a compact Markdown summary with these headings exactly:\n  - `## Implementation Outcome`\n  - `## Verification`\n  - `## Handoff Data`\n- Under `## Implementation Outcome`, summarize:\n  - whether the implementation loop completed cleanly\n  - the main implementation work completed in this run\n  - the task artifact files updated\n  - any remaining notes or risks explicitly recorded in the task artifacts\n  - any commit ids created during the run that are evident from the provided step outputs\n- Under `## Verification`, summarize the verification performed in this run.\n- Under `## Handoff Data`, include machine-friendly bullet lines for every field you can determine from the provided context and inspected task artifacts. Include these when available:\n  - `pr_number:`\n  - `pr_url:`\n  - `pr_branch:`\n  - `worktree_path:`\n  - `munera_task_path:`\n  - `deviation_summary:`\n\nIf a field is not available, omit it rather than inventing it."
                           :vars {}}]}]}

Wraps `implement-task` for pipeline use where the input is a structured handoff blob containing `worktree_path:` and `munera_task_path:` fields.

Step 1 extracts the worktree path, calls `work-on` to set the session worktree, and emits the plain task path. Step 2 delegates to `implement-task` which inherits the resolved worktree. Step 3 produces a user-facing summary.

Input shape: `{:input "structured-handoff-text-containing-worktree_path-and-munera_task_path"}`

For interactive use where you're already in the right worktree, use `implement-task` directly.
