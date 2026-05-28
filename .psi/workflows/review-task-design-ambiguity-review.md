---
name: review-task-design-ambiguity-review
description: Review a Munera task design for ambiguities
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
  - task-design
---
For the Munera task identified by {{input}}, review the task design for ambiguities. Work independently. Read the task's design.md and any referenced concepts, code, or docs needed to evaluate the design. Do not review plan.md or steps.md. Then:

1. append a terse review note to the task's implementation.md
2. add unchecked follow-up items to design-steps.md for every new actionable ambiguity you found (create design-steps.md if it does not exist)
3. avoid duplicating review notes or steps that already exist
4. commit
5. if there is no new actionable ambiguity feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: NO_ACTIONABLE_FEEDBACK
