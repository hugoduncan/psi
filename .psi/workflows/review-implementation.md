---
name: review-implementation
description: Review a Munera task implementation, record terse notes, execute added follow-up steps, and repeat until no new actionable feedback remains
---
{:steps [{:name "implementation-review"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :skills ["work-independently" "task-implementation-review"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :template
                           :text "Review the implementation for the Munera task identified by {{input}}. Use the task-implementation-review skill and work independently. Read the task artifacts and the implemented code/tests they reference. Then:\n\n1. append a terse review note to the task's implementation.md\n2. add unchecked follow-up items to the task's steps.md for every new actionable issue you found\n3. avoid duplicating review notes or steps that already exist\n4. commit. if there is no new actionable feedback, say so explicitly\n\nEnd your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: NO_ACTIONABLE_FEEDBACK"
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]}
         {:name "implementation-follow-up"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :skills ["work-independently"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "implementation-review" :yield :text}
                           :projection :text}
                          {:type :template
                           :text "Execute the newly added actionable follow-up items for the Munera task identified by {{input}}. Work independently. Use the preloaded implementation-review result to understand what was added in the preceding review pass. Read the task's steps.md, implementation.md, design.md, and plan.md as needed. Complete the newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. commit."
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]}
         {:name "code-shape-review"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :skills ["work-independently" "code-shaper"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "implementation-review" :yield :text}
                           :projection :text}
                          {:type :source
                           :from {:step "implementation-follow-up" :yield :text}
                           :projection :text}
                          {:type :template
                           :text "Review the same Munera task implementation for simplicity, consistency, and robustness. Use the code-shaper skill and work independently. Read the task artifacts and the implemented code/tests they reference. Also consider the preloaded results from the implementation-review and implementation-follow-up steps so this pass can avoid duplicating already recorded or already addressed feedback. Then:\n\n1. append a terse review note to the task's implementation.md\n2. add unchecked follow-up items to the task's steps.md for every new actionable issue you found\n3. avoid duplicating review notes or steps that already exist\n4. commit. if there is no new actionable feedback, say so explicitly\n\nEnd your final response with exactly one of:\nPASS_STATUS: ACTIONABLE_FEEDBACK\nPASS_STATUS: NO_ACTIONABLE_FEEDBACK"
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]}
         {:name "code-shape-follow-up"
          :type :session
          :tools ["read" "bash" "edit" "write"]
          :skills ["work-independently"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "code-shape-review" :yield :text}
                           :projection :text}
                          {:type :template
                           :text "Execute the newly added actionable follow-up items for the Munera task identified by {{input}}. Work independently. Use the preloaded code-shape-review result to understand what was added in the preceding review pass. Read the task's steps.md, implementation.md, design.md, and plan.md as needed. Complete the newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. commit."
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]}
         {:name "review-status"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "implementation-review" :yield :text}
                           :projection :text}
                          {:type :source
                           :from {:step "implementation-follow-up" :yield :text}
                           :projection :text}
                          {:type :source
                           :from {:step "code-shape-review" :yield :text}
                           :projection :text}
                          {:type :source
                           :from {:step "code-shape-follow-up" :yield :text}
                           :projection :text}
                          {:type :template
                           :text "Review the specific Munera task identified by {{input}} and decide whether the just-completed review cycle surfaced remaining new actionable follow-up work. Independently inspect the task artifacts, especially steps.md, implementation.md, design.md, and plan.md when present. This is an internal control step. Respond with exactly one word: REPEAT or DONE. Return REPEAT if the identified task still has new actionable follow-up work to address after the implementation-review and code-shape-review cycle. Return DONE only if the identified task has no remaining new actionable feedback from that cycle."
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]
          :judge {:type :llm
                  :contributions [{:type :template
                                   :text "Respond exactly with one word: REPEAT or DONE.\n\nUse the actor step context to identify the specific Munera task under review, especially the task identifier or `munera_task_path` if present. Then independently inspect that task's artifacts, especially `steps.md`, `implementation.md`, `design.md`, and `plan.md` when present, to determine whether the review cycle surfaced any new actionable feedback that still needs work.\n\nReturn REPEAT if the identified task still has new actionable follow-up work to address after the implementation-review and code-shape-review cycle. Return DONE only if the identified task has no remaining new actionable feedback from that cycle.\n\nDo not re-review the repository generically. Judge the specific Munera task named by the actor output."
                                   :vars {}}]}
          :on {"REPEAT" {:goto "implementation-review"
                          :max-iterations 6}
               "DONE"   {:goto "final-summary"}}}
         {:name "final-summary"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "implementation-review" :yield :text}
                           :projection :text}
                          {:type :source
                           :from {:step "implementation-follow-up" :yield :text}
                           :projection :text}
                          {:type :source
                           :from {:step "code-shape-review" :yield :text}
                           :projection :text}
                          {:type :source
                           :from {:step "code-shape-follow-up" :yield :text}
                           :projection :text}
                          {:type :template
                           :text "Produce the user-facing final result for the Munera task identified by {{input}}. Independently inspect that specific task's artifacts, especially steps.md, implementation.md, design.md, and plan.md when present, and use the prior step outputs as supporting context.\n\nRespond with a concise summary for the user, not an internal control token. Include:\n- whether the review loop completed cleanly\n- the key implementation or code-shape issues found and resolved in this run\n- the task artifact files updated\n- any commit ids created during the run that are evident from the provided step outputs\n\nDo not output REPEAT or DONE unless quoting prior workflow behavior."
                           :vars {"input" {:from :workflow-input
                                            :path [:input]}}}]}]}

Run an implementation-review pass followed by execution of the added steps, then a code-shaping pass followed by execution of the added steps. Every step in the workflow uses the `work-independently` skill. Review passes record terse notes in `implementation.md` and add follow-up checklist items to `steps.md`. Follow-up passes execute the newly added work and keep task artifacts synchronized. Repeat the cycle until a full pass produces no new actionable feedback.
