---
name: review-implementation
description: Review a Munera task implementation, record terse notes, execute added follow-up steps, and repeat until no new actionable feedback remains
---
{:steps [{:name "implementation-review"
          :workflow "builder"
          :session {:input {:from :workflow-input}
                    :reference {:from :workflow-original}
                    :skills ["work-independently" "task-implementation-review"]}
          :prompt "Review the implementation for the Munera task identified by $INPUT. Use the task-implementation-review skill and work independently. Read the task artifacts and the implemented code/tests they reference. Then:\n\n1. append a terse review note to the task's implementation.md\n2. add unchecked follow-up items to the task's steps.md for every new actionable issue you found\n3. avoid duplicating review notes or steps that already exist\n4. if there is no new actionable feedback, say so explicitly\n\nEnd your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: NO_ACTIONABLE_FEEDBACK"}
         {:name "implementation-follow-up"
          :workflow "builder"
          :session {:input {:from :workflow-input}
                    :reference {:from :workflow-original}
                    :skills ["work-independently"]
                    :preload [{:from {:step "implementation-review" :kind :accepted-result}
                               :projection :text}]}
          :prompt "Execute the newly added actionable follow-up items for the Munera task identified by $INPUT. Work independently. Use the preloaded implementation-review result to understand what was added in the preceding review pass. Read the task's steps.md, implementation.md, design.md, and plan.md as needed. Complete the newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md."}
         {:name "code-shape-review"
          :workflow "builder"
          :session {:input {:from :workflow-input}
                    :reference {:from :workflow-original}
                    :skills ["work-independently" "code-shaper"]
                    :preload [{:from {:step "implementation-review" :kind :accepted-result}
                               :projection :text}
                              {:from {:step "implementation-follow-up" :kind :accepted-result}
                               :projection :text}]}
          :prompt "Review the same Munera task implementation for simplicity, consistency, and robustness. Use the code-shaper skill and work independently. Read the task artifacts and the implemented code/tests they reference. Also consider the preloaded results from the implementation-review and implementation-follow-up steps so this pass can avoid duplicating already recorded or already addressed feedback. Then:\n\n1. append a terse review note to the task's implementation.md\n2. add unchecked follow-up items to the task's steps.md for every new actionable issue you found\n3. avoid duplicating review notes or steps that already exist\n4. if there is no new actionable feedback, say so explicitly\n\nEnd your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: NO_ACTIONABLE_FEEDBACK"}
         {:name "code-shape-follow-up"
          :workflow "builder"
          :session {:input {:from :workflow-input}
                    :reference {:from :workflow-original}
                    :skills ["work-independently"]
                    :preload [{:from {:step "code-shape-review" :kind :accepted-result}
                               :projection :text}]}
          :prompt "Execute the newly added actionable follow-up items for the Munera task identified by $INPUT. Work independently. Use the preloaded code-shape-review result to understand what was added in the preceding review pass. Read the task's steps.md, implementation.md, design.md, and plan.md as needed. Complete the newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md."
          :judge {:system-prompt "You are a workflow routing judge. Respond with exactly one word: REPEAT or DONE. Judge from a fresh context by independently reviewing the Munera task identified in the actor step output, not by trusting the editing narrative."
                  :prompt "Respond exactly with one word: REPEAT or DONE.\n\nUse the actor step context to identify the specific Munera task under review, especially the task identifier or `munera_task_path` if present. Then independently inspect that task's artifacts, especially `steps.md`, `implementation.md`, `design.md`, and `plan.md` when present, to determine whether the review cycle surfaced any new actionable feedback that still needs work.\n\nReturn REPEAT if the identified task still has new actionable follow-up work to address after the implementation-review and code-shape-review cycle. Return DONE only if the identified task has no remaining new actionable feedback from that cycle.\n\nDo not re-review the repository generically. Judge the specific Munera task named by the actor output."}
          :on {"REPEAT" {:goto "implementation-review" :max-iterations 6}
               "DONE"   {:goto :done}}}]}

Run an implementation-review pass followed by execution of the added steps, then a code-shaping pass followed by execution of the added steps. Every step in the workflow uses the `work-independently` skill. Review passes record terse notes in `implementation.md` and add follow-up checklist items to `steps.md`. Follow-up passes execute the newly added work and keep task artifacts synchronized. Repeat the cycle until a full pass produces no new actionable feedback.
