---
name: review-step
description: Run a single named review skill against a Munera task, record terse notes, execute added follow-up steps, and repeat until the review returns no actionable feedback
---
{:steps [{:name "review"
          :type :session
          :tools ["read" "bash" "edit" "write" "work-on"]
          :skills ["work-independently"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Review the Munera task identified by {{input}} using the {{skill}} skill. Work independently. First extract `worktree_path:` from the handoff data in `{{input}}` and call `work-on <worktree_path>` before reading any task artifacts. Then read the skill file at `.psi/skills/{{skill}}/SKILL.md` and apply it. Read the task artifacts and any code/tests/docs they reference. Then:\n\n1. append a terse review note to the task's implementation.md\n2. add unchecked follow-up items to the task's steps.md for every new actionable issue you found\n3. avoid duplicating review notes or steps that already exist\n4. commit. if there is no new actionable feedback, say so explicitly\n\nEnd your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: NO_ACTIONABLE_FEEDBACK"
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}
                                  "skill" {:from :workflow-input
                                           :path [:skill]}}}]}
         {:name "follow-up"
          :type :session
          :tools ["read" "bash" "edit" "write" "work-on"]
          :skills ["work-independently"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "review" :yield :text}}
                          {:type :template
                           :text "Execute the newly added actionable follow-up items for the Munera task identified by {{input}}. Work independently. First extract `worktree_path:` from the handoff data in `{{input}}` and call `work-on <worktree_path>` before reading any task artifacts. Use the preloaded review result to understand what was added in the preceding review pass. Read the task's steps.md, implementation.md, design.md, and plan.md as needed. Complete the newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. commit."
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}}}]}
         {:name "review-status"
          :type :session
          :tools ["read" "bash" "work-on"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "review" :yield :text}}
                          {:type :source
                           :from {:step "follow-up" :yield :text}}
                          {:type :template
                           :text "Review the specific Munera task identified by {{input}} and decide whether the just-completed review cycle surfaced remaining new actionable follow-up work. First extract `worktree_path:` from the handoff data in `{{input}}` and call `work-on <worktree_path>` before reading any task artifacts. Independently inspect the task artifacts, especially steps.md, implementation.md, design.md, and plan.md when present. This is an internal control step. Respond with exactly one word: REPEAT or DONE. Return REPEAT if the identified task still has new actionable follow-up work to address. Return DONE only if the identified task has no remaining new actionable feedback from that cycle."
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}}}]
          :judge {:type :llm
                  :contributions [{:type :template
                                   :text "Respond exactly with one word: REPEAT or DONE.\n\nUse the actor step context to identify the specific Munera task under review, especially the task identifier or `munera_task_path` if present. Then independently inspect that task's artifacts, especially `steps.md`, `implementation.md`, `design.md`, and `plan.md` when present, to determine whether the review cycle surfaced any new actionable feedback that still needs work.\n\nReturn REPEAT if the identified task still has new actionable follow-up work to address. Return DONE only if the identified task has no remaining new actionable feedback from that cycle.\n\nDo not re-review the repository generically. Judge the specific Munera task named by the actor output."
                                   :vars {}}]}
          :on {"REPEAT" {:goto "review"
                         :max-iterations 6}
               "DONE"   {:goto "final-summary"}}}
         {:name "final-summary"
          :type :session
          :tools ["read" "bash" "work-on"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "review" :yield :text}}
                          {:type :source
                           :from {:step "follow-up" :yield :text}}
                          {:type :template
                           :text "Produce the user-facing final result for the Munera task identified by {{input}}. First extract `worktree_path:` from the handoff data in `{{input}}` and call `work-on <worktree_path>` before reading any task artifacts. Independently inspect that specific task's artifacts, especially steps.md, implementation.md, design.md, and plan.md when present, and use the prior step outputs as supporting context.\n\nRespond with a concise summary for the user, not an internal control token. Include:\n- whether the review loop completed cleanly\n- the key issues found and resolved in this run using the {{skill}} skill\n- the task artifact files updated\n- any commit ids created during the run that are evident from the provided step outputs\n\nDo not output REPEAT or DONE unless quoting prior workflow behavior."
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}
                                  "skill" {:from :workflow-input
                                           :path [:skill]}}}]}]}

Run a single named review skill against a Munera task. The review pass records terse notes in `implementation.md` and adds follow-up checklist items to `steps.md`. The follow-up pass executes the newly added work. A judge step decides REPEAT or DONE; the loop repeats up to 6 times until a full pass produces no new actionable feedback.

Input shape: `{:input "upstream-handoff-report-containing-worktree_path-and-munera_task_path" :skill "skill-name"}`

The skill is resolved at runtime by reading `.psi/skills/<skill>/SKILL.md`. Any skill available in the project can be named.