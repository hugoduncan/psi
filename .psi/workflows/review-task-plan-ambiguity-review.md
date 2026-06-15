---
name: review-task-plan-ambiguity-review
description: Review Munera task plan and steps for ambiguities
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
  - task-design
---
For the Munera task identified by {{input}}, run the ambiguity review as the first turn of the shared `plan-review` multi-prompt session. Work independently. Read the task artifacts, especially plan.md, steps.md, and implementation.md, plus any referenced code/tests/docs needed for the batch review. This first turn loads the task plan context for the later inconsistency turn in the same child session.

Review the task plan and steps for ambiguities, treating steps.md as read-only task context. Then:

1. add unchecked follow-up items to design-steps.md for every new actionable ambiguity you found (create design-steps.md if it does not exist)
2. append a terse review note to the task's implementation.md
   Do not note compliance with these instructions; only note any non-compliance.
   Do not duplicate information that is already in the design-steps.
3. avoid duplicating review notes or design-steps that already exist
4. commit
5. if there is no new actionable ambiguity feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
