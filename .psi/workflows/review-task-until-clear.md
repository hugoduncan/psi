---
name: review-task-until-clear
description: Repeatedly review a Munera task for ambiguities and inconsistencies, record terse notes in implementation.md, add follow-up steps, execute them, and loop until no actionable feedback remains.
---
{:steps [{:name "ambiguity-turn"
          :type :delegate
          :target "review-design-turn"
          :prompt-string {:type :map
                          :fields {:input {:from :workflow-input
                                           :path [:input]}
                                   :aspect {:value "ambiguities"}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "inconsistency-turn"
          :type :delegate
          :target "review-design-turn"
          :prompt-string {:type :map
                          :fields {:input {:from :workflow-input
                                           :path [:input]}
                                   :aspect {:value "inconsistencies"}}}
          :context [{:type :source
                     :from :workflow-original}
                    {:type :source
                     :from {:step "ambiguity-turn" :yield :text}}]}
         {:name "clarity-status"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "ambiguity-turn" :yield :text}}
                          {:type :source
                           :from {:step "inconsistency-turn" :yield :text}}
                          {:type :template
                           :text "Review the Munera task identified by {{input}} and decide whether there is still actionable ambiguity or inconsistency follow-up remaining from the just-completed review cycle. Independently inspect that specific task's artifacts, especially design.md, plan.md, steps.md, and implementation.md. This is an internal control step. Respond with exactly one word: REPEAT or DONE. Return REPEAT if there is still actionable ambiguity or inconsistency follow-up remaining from the review cycle, including newly added unchecked steps or unresolved review findings. Return DONE only if the task has no remaining new actionable ambiguity or inconsistency feedback from the cycle."
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}}}]
          :judge {:type :llm
                  :contributions [{:type :template
                                   :text "Respond exactly with one word: REPEAT or DONE.\n\nUse the actor step context to identify the Munera task under review. Independently inspect that specific task's artifacts, especially design.md, plan.md, steps.md, and implementation.md.\n\nReturn REPEAT if there is still actionable ambiguity or inconsistency follow-up remaining from the review cycle, including newly added unchecked steps or unresolved review findings. Return DONE only if the task has no remaining new actionable ambiguity or inconsistency feedback from the cycle.\n\nDo not review the repository generically. Judge only the specific named task."
                                   :vars {}}]}
          :on {"REPEAT" {:goto "ambiguity-turn"
                         :max-iterations 6}
               "DONE"   {:goto "final-summary"}}}
         {:name "final-summary"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "ambiguity-turn" :yield :text}}
                          {:type :source
                           :from {:step "inconsistency-turn" :yield :text}}
                          {:type :template
                           :text "Produce the user-facing final result for the Munera task identified by {{input}}. Independently inspect that specific task's artifacts, especially design.md, plan.md, steps.md, and implementation.md, and use the prior step outputs as supporting context.\n\nRespond with a concise summary for the user, not an internal control token. Include:\n- whether the review loop completed cleanly\n- the key ambiguities or inconsistencies found and resolved in this run\n- the task artifact files updated\n- any commit ids created during the run that are evident from the provided step outputs\n\nDo not output REPEAT or DONE unless quoting prior workflow behavior."
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}}}]}]}

Delegates alternating ambiguity and inconsistency passes to `review-design-turn`, then judges whether to repeat the cycle or finish. Loops up to 6 times until a full cycle produces no new actionable feedback from either pass.
