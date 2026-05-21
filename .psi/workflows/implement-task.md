---
name: implement-task
description: Repeatedly implement a Munera task in autonomous passes until no concrete implementation work remains
---
{:terminal-contract {:handoff {:type :markdown-handoff-data}}
 :steps [{:name "implement-pass"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :skills ["work-independently" "clojure-coding-standards" "testing-without-mocks"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Implement the specific Munera task described by {{input}}. Work independently. Read `.psi/skills/work-independently/SKILL.md` and apply it. Also apply `clojure-coding-standards` and `testing-without-mocks` as relevant.

Use the actor-step context to identify the specific task and, when present, the associated `munera_task_path`, `worktree_path`, PR metadata, and other handoff data. Focus only on that task.

Required procedure:
1. Read the task artifacts, especially `design.md`, `steps.md`, and `implementation.md`, plus `plan.md` when present.
2. If `plan.md` is missing and the design is complete and unambiguous, create or refine `plan.md` before implementation.
3. Execute the next concrete implementation slice for the task.
4. Keep `design.md`, `plan.md`, `steps.md`, and `implementation.md` synchronized with what you learned and changed.
5. Add or refine tests as required by the task design.
6. Run relevant verification for the affected area.
7. Mark completed checklist items done in `steps.md`; add new implementation follow-up items when discovered.
8. Record important deviations from the initial design tersely in `implementation.md`.
9. Commit any changes made during this pass with an appropriate commit message.
10. If the task is already complete or no further concrete implementation work is available, say so explicitly.

End your final response with exactly one of:
PASS_STATUS: MORE_WORK_REMAINS
PASS_STATUS: IMPLEMENTATION_COMPLETE"
                           :vars {"input" {:from :workflow-input}}}]}
         {:name "implementation-status"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "implement-pass" :yield :text}}
                          {:type :template
                           :text "Review the specific Munera task described by {{input}} and decide whether implementation work still remains. Independently inspect that task's artifacts, especially `design.md`, `plan.md`, `steps.md`, and `implementation.md`, and use the prior implementation-pass output as supporting context.

Respond with exactly one word: REPEAT or DONE.

Return REPEAT if the task still has remaining unchecked implementation work, unmet acceptance criteria, missing verification, or newly discovered follow-up work that should be executed in another implementation pass.

Return DONE only if the task implementation is complete enough for handoff: the implementation work is done, relevant verification has been run, and the task artifacts do not indicate remaining implementation work.

Do not review the repository generically. Judge only the specific named task from the actor-step context."
                           :vars {"input" {:from :workflow-input}}}]
          :judge {:type :llm
                  :contributions [{:type :template
                                   :text "Respond exactly with one word: REPEAT or DONE."
                                   :vars {}}]}
          :on {"REPEAT" {:goto "implement-pass"
                          :max-iterations 8}
               "DONE"   {:goto "final-summary"}}}
         {:name "final-summary"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "implement-pass" :yield :text}}
                          {:type :template
                           :text "Produce the user-facing final result for the specific Munera task described by {{input}}. Independently inspect that task's artifacts, especially `design.md`, `plan.md`, `steps.md`, and `implementation.md`, and use the prior implementation-pass output as supporting context.

Output requirements:
- Output a compact Markdown summary with these headings exactly:
  - `## Implementation Outcome`
  - `## Verification`
  - `## Handoff Data`
- Under `## Implementation Outcome`, summarize:
  - whether the implementation loop completed cleanly
  - the main implementation work completed in this run
  - the task artifact files updated
  - any remaining notes or risks explicitly recorded in the task artifacts
  - any commit ids created during the run that are evident from the provided step outputs
- Under `## Verification`, summarize the verification performed in this run.
- Under `## Handoff Data`, include machine-friendly bullet lines for every field you can determine from the actor-step context and inspected task artifacts. Include these when available:
  - `pr_number:`
  - `pr_url:`
  - `pr_branch:`
  - `worktree_path:`
  - `munera_task_path:`
  - `deviation_summary:`

If a field is not available, omit it rather than inventing it."
                           :vars {"input" {:from :workflow-input}}}]}]}

Repeatedly executes autonomous implementation passes against a specific Munera task until the task appears complete. Each pass performs concrete implementation work, updates task artifacts, runs relevant verification, and commits changes. A control step judges REPEAT or DONE based on the current state of that specific task.

Input shapes:
- `{:input "munera/open/003-foo"}` for direct use
- `{:input "structured-handoff-text-containing-munera_task_path-and-optional-worktree/PR-fields"}` for pipeline use

Child sessions inherit the invoking session's worktree — no worktree setup is needed when already in the correct worktree.