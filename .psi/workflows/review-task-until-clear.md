---
name: review-task-until-clear
description: Repeatedly review a Munera task for ambiguities and inconsistencies, record terse notes in implementation.md, add follow-up steps, execute them, and loop until no actionable feedback remains.
---
{:steps [{:name "review-design-turn"
          :type :delegate
          :target "review-design-turn"
          :prompt-string {:type :map
                          :fields {:input {:from :workflow-input
                                           :path [:input]}}}
          :context [{:type :source
                     :from :workflow-original}]}
         {:name "final-summary"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from :workflow-original}
                          {:type :source
                           :from {:step "review-design-turn" :yield :text}}
                          {:type :template
                           :text "Produce the user-facing final result for the Munera task identified by {{input}}. Independently inspect that specific task's artifacts, especially design.md, plan.md, steps.md, and implementation.md, and use the prior step output as supporting context.\n\nRespond with a concise summary for the user, not an internal control token. Include:\n- whether the review loop completed cleanly\n- the key ambiguities or inconsistencies found and resolved in this run\n- the task artifact files updated\n- any commit ids created during the run that are evident from the provided step outputs\n\nDo not output REPEAT or DONE unless quoting prior workflow behavior."
                           :vars {"input" {:from :workflow-input
                                           :path [:input]}}}]}]}

Delegates to `review-design-turn`, which runs alternating ambiguity and inconsistency review passes with follow-up execution, looping until no new actionable feedback remains from either pass.
