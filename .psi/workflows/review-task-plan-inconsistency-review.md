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
advertise: false
---
For the Munera task identified by {{input}}, run the inconsistency review as the second turn of the shared `plan-review` multi-prompt session. Work independently. Use the already-loaded task plan.md, steps.md, implementation.md, and ambiguity-review reply from the shared session context by default. Perform only targeted re-reads for specific missing or stale referenced material needed to decide an inconsistency; do not unconditionally re-read the whole task plan and referenced source set.

Review the task plan and steps for inconsistencies, focusing on inconsistency across task files, treating steps.md as read-only task context.

Implementation notes for future task steps:
- Ask yourself: "What would the next task-lifecycle step, implementation slice, or review need to know about this review pass?"
- Append useful discoveries to `implementation.md` when they will help later steps or reviews, including non-obvious rationale, important context loaded during review, unresolved options, artifact implications, or why a finding was classified as actionable.
- Keep entries minimalist and avoid duplicating information already obvious in design.md, plan.md, steps.md, design-steps.md, or prior implementation.md notes.
- If the only useful information is the review outcome already recorded by the required minimalist review note, do not add noise.

Then:

1. add unchecked follow-up items to design-steps.md for every new actionable inconsistency you found (create design-steps.md if it does not exist)
2. append a minimalist review note on the outcome of the review to the task's implementation.md, e.g. "- no inconsistency review feedback" or "- inconsistency review added 2 new design steps"
3. avoid duplicating review notes or design-steps that already exist
4. commit
5. if there is no new actionable inconsistency feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
