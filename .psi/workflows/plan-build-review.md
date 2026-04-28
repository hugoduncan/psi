---
name: plan-build-review
description: Plan, build, and review code changes
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
          :prompt "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}
         {:name "review"
          :workflow "reviewer"
          :session {:input {:from {:step "build" :kind :accepted-result}}
                    :reference {:from :workflow-original}}
          :prompt "Review the following implementation:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}]}

Coordinate a plan-build-review cycle using explicit step names and session-first data flow.
