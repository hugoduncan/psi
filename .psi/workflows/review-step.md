---
name: review-step
description: Run a single named review skill against a Munera task, record terse notes, execute added follow-up steps, and repeat until the review returns no actionable feedback
---
{:steps [{:name "review"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :skills ["work-independently"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Review the Munera task at {{input}} using the {{skill}} skill. Work independently. Read the skill file at `.psi/skills/{{skill}}/SKILL.md` and apply it. Read the task artifacts and any code/tests/docs they reference. Then:\n\n1. append a terse review note to the task's implementation.md\n2. add unchecked follow-up items to the task's steps.md for every new actionable issue you found\n3. avoid duplicating review notes or steps that already exist\n4. commit. if there is no new actionable feedback, say so explicitly\n\nEnd your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: NO_ACTIONABLE_FEEDBACK"
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}
                                  "skill" {:from :workflow-input
                                           :path [:skill]}}}]}
         {:name "follow-up"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :skills ["work-independently"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "review" :yield :text}}
                          {:type :template
                           :text "Execute the newly added actionable follow-up items for the Munera task at {{input}}. Work independently. Use the preloaded review result to understand what was added in the preceding review pass. Read the task's steps.md, implementation.md, design.md, and plan.md as needed. Complete the newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. Commit."
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}}}]}
         {:name "review-status"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "review" :yield :text}}
                          {:type :source
                           :from {:step "follow-up" :yield :text}}
                          {:type :template
                           :text "Review the Munera task at {{input}} and decide whether the just-completed review cycle surfaced remaining new actionable follow-up work. Independently inspect the task artifacts, especially steps.md, implementation.md, design.md, and plan.md when present. This is an internal control step. Respond with exactly one word: REPEAT or DONE. Return REPEAT if the task still has new actionable follow-up work to address. Return DONE if the task has no remaining new actionable feedback from that cycle."
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}}}]
          :judge {:type :llm
                  :contributions [{:type :template
                                   :text "Respond exactly with one word: REPEAT or DONE.\n\nUse the actor step context to identify the specific Munera task under review. Independently inspect that task's artifacts, especially `steps.md`, `implementation.md`, `design.md`, and `plan.md` when present, to determine whether the review cycle surfaced any new actionable feedback that still needs work.\n\nReturn REPEAT if the task still has new actionable follow-up work to address. Return DONE only if the task has no remaining new actionable feedback from that cycle.\n\nDo not re-review the repository generically. Judge the specific Munera task named by the actor output."
                                   :vars {}}]}
          :on {"REPEAT" {:goto "review"
                         :max-iterations 6}
               "DONE"   {:goto :done}}}]}

Run a single named review skill against a Munera task. The review pass records terse notes in `implementation.md` and adds follow-up checklist items to `steps.md`. The follow-up pass executes the newly added work. A judge step decides REPEAT or DONE; the loop repeats up to 6 times until a full pass produces no new actionable feedback.

Input shape: `{:input "munera/open/003-foo" :skill "skill-name"}`

The skill is resolved at runtime by reading `.psi/skills/<skill>/SKILL.md`. Any skill available in the project can be named.

Child sessions inherit the invoking session's worktree — no worktree setup is needed.