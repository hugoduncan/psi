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
For the Munera task identified by {{input}}, run the ambiguity review as the second turn of the shared `design-review` multi-prompt session. Work independently. Use the already-loaded task design.md, architecture sources, and architecture-review reply from the shared session context by default. Perform only targeted re-reads when specific referenced material is missing from context, ambiguous, or plausibly stale; do not unconditionally re-read the whole task design and architecture source set.

Review the task design for ambiguities. Do not review plan.md or steps.md. Then:

1. append a terse review note to the task's implementation.md
2. add unchecked follow-up items to design-steps.md for every new actionable ambiguity you found (create design-steps.md if it does not exist)
3. avoid duplicating review notes or steps that already exist
4. commit
5. if there is no new actionable ambiguity feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
