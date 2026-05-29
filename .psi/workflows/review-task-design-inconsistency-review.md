---
name: review-task-design-inconsistency-review
description: Review a Munera task design for inconsistencies
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
  - task-design
---
For the Munera task identified by {{input}}, review the task design for inconsistencies. Work independently. Read the task's design.md and any referenced concepts, code, or docs needed to evaluate the design. Do not review plan.md or steps.md. Focus on internal inconsistency within design.md and between design.md and referenced artifacts. Then:

1. append a terse review note to the task's implementation.md
2. add unchecked follow-up items to design-steps.md for every new actionable inconsistency you found (create design-steps.md if it does not exist)
3. avoid duplicating review notes or steps that already exist
4. commit. if there is no new actionable inconsistency feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
