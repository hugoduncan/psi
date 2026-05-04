---
name: review-task-until-clear
description: Repeatedly review a Munera task for ambiguities and inconsistencies, record terse notes in implementation.md, add follow-up steps, execute them, and loop until no actionable feedback remains.
---
{:steps
 [{:name "ambiguity-review"
   :workflow "builder"
   :session {:input {:from :workflow-input}
             :reference {:from :workflow-original}
             :skills ["work-independently" "task-design"]}
   :prompt "For the Munera task identified by $INPUT, review the task design, plan and steps for ambiguities. Work independently. Read the task artifacts, especially design.md, plan.md, steps.md, and implementation.md, plus any referenced code/tests/docs. Then:\n\n1. append a terse review note to the task's implementation.md\n2. add unchecked follow-up items to design-steps.md for every new actionable ambiguity you found\n3. avoid duplicating review notes or steps that already exist\n4. commit\n5. if there is no new actionable ambiguity feedback, say so explicitly\n\nEnd your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: NO_ACTIONABLE_FEEDBACK"}

  {:name "ambiguity-follow-up"
   :workflow "builder"
   :session {:input {:from :workflow-input}
             :reference {:from :workflow-original}
             :skills ["work-independently"]
             :preload [{:from {:step "ambiguity-review" :kind :accepted-result}
                        :projection :text}]}
   :prompt "For the Munera task identified by $INPUT, execute the newly added actionable follow-up itemsin design-steps.md for ambiguities. Work independently. Use the preloaded ambiguity-review result to understand what was added in the preceding review pass. Read and update the task's design.md, plan.md, steps.md, and implementation.md, as needed. Complete the newly added unchecked design-steps when possible, updating task artifacts as you work. If a design-step is completed, mark it done in design-steps.md. If a design-step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. Commit when done"}

  {:name "inconsistency-review"
   :workflow "builder"
   :session {:input {:from :workflow-input}
             :reference {:from :workflow-original}
             :skills ["work-independently" "task-design"]}
   :prompt "For the Munera task identified by $INPUT, review the task design, plan and steps for inconsistencies. Work independently. Read the task artifacts, especially design.md, plan.md, steps.md, and implementation.md, plus any referenced code/tests/docs. Focus on inconsistency across task files. Then:\n\n1. append a terse review note to the task's implementation.md\n2. add unchecked follow-up items to design-steps.md for every new actionable inconsistency you found\n3. avoid duplicating review notes or design-steps that already exist\n4.\ncommit.\nif there is no new actionable inconsistency feedback, say so explicitly\n\nEnd your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: NO_ACTIONABLE_FEEDBACK"}

  {:name "inconsistency-follow-up"
   :workflow "builder"
   :session {:input {:from :workflow-input}
             :reference {:from :workflow-original}
             :skills ["work-independently"]
             :preload [{:from {:step "inconsistency-review" :kind :accepted-result}
                        :projection :text}]}
   :prompt "for the Munera task identified by $INPUT, execute the newly added actionable follow-up itemsin design-steps.md. Work independently. Use the preloaded inconsistency-review result to understand what was added in the preceding review pass. Read and update the task's steps.md, implementation.md, design.md, and plan.md as needed. Complete the newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in design-steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. commit when done."
   :judge {:system-prompt "You are a workflow routing judge. Respond with exactly one word: REPEAT or DONE."
           :prompt "Respond exactly with one word: REPEAT or DONE.\n\nUse the actor step context to identify the Munera task under review. Independently inspect that specific task's artifacts, especially design.md, plan.md, steps.md, and implementation.md.\n\nReturn REPEAT if there is still actionable ambiguity or inconsistency follow-up remaining from the review cycle, including newly added unchecked steps or unresolved review findings. Return DONE only if the task has no remaining new actionable ambiguity or inconsistency feedback from the cycle.\n\nDo not review the repository generically. Judge only the specific named task."}
   :on {"REPEAT" {:goto "ambiguity-review" :max-iterations 6}
        "DONE"   {:goto :done}}}]}
