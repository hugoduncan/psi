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
advertise: false
---
For the Munera task identified by {{input}}, run the ambiguity review as the first turn of the shared `plan-review` multi-prompt session. Work independently. Read the task artifacts, especially plan.md, steps.md, and implementation.md, plus any referenced code/tests/docs needed for the batch review. This first turn loads the task plan context for the later inconsistency turn in the same child session.

Review the task plan and steps for ambiguities, treating steps.md as read-only task context.

Implementation notes for future task steps:
- Ask yourself: "What would the next task-lifecycle step, implementation slice, or review need to know about this review pass?"
- Append useful discoveries to `implementation.md` when they will help later steps or reviews, including non-obvious rationale, important context loaded during review, unresolved options, artifact implications, or why a finding was classified as actionable.
- Keep entries minimalist and avoid duplicating information already obvious in design.md, plan.md, steps.md, design-steps.md, or prior implementation.md notes.
- If the only useful information is the review outcome already recorded by the required minimalist review note, do not add noise.

Then:

1. add unchecked follow-up items to design-steps.md for every new actionable ambiguity you found (create design-steps.md if it does not exist)
2. append a minimalist review note on the outcome of the review to the task's implementation.md, e.g. "- no ambiguity review feedback" or "- ambiguity review added 2 new design steps"
3. avoid duplicating review notes or design-steps that already exist
4. commit
5. if there is no new actionable ambiguity feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
