---
name: plan-build
description: Plan and build without review
---
{:steps [{:name "plan"
          :workflow "planner"
          :session {:input {:from :workflow-input}
                    :reference {:from :workflow-original}}
          :prompt "$INPUT"}
         {:name "build"
          :workflow "builder"
          :session {:input {:from {:step "plan" :kind :accepted-result}}
                    :reference {:from :workflow-original}}
          :prompt "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}]}

Plan and build in two steps using the session-first workflow authoring surface.
