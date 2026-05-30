---
name: review-task-plan-inconsistency-review
description: Review Munera task plan and steps for inconsistencies
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
  - task-design
---
For the Munera task identified by {{input}}, review the task plan and steps for inconsistencies. Work independently. Read the task artifacts, especially plan.md, steps.md, and implementation.md, plus any referenced code/tests/docs. Focus on inconsistency across task files. Then:

1. append a terse review note to the task's implementation.md
2. add unchecked follow-up items to steps.md for every new actionable inconsistency you found
3. avoid duplicating review notes or steps that already exist
4. commit. if there is no new actionable inconsistency feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
