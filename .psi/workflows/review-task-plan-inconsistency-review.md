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
For the Munera task identified by {{input}}, run the inconsistency review as the second turn of the shared `plan-review` multi-prompt session. Work independently. Use the already-loaded task plan.md, steps.md, implementation.md, and ambiguity-review reply from the shared session context by default. Perform only targeted re-reads for specific missing or stale referenced material needed to decide an inconsistency; do not unconditionally re-read the whole task plan and referenced source set.

Review the task plan and steps for inconsistencies, focusing on inconsistency across task files, treating steps.md as read-only task context. Then:

1. add unchecked follow-up items to design-steps.md for every new actionable inconsistency you found (create design-steps.md if it does not exist)
2. append a terse review note to the task's implementation.md
   Do not note compliance with these instructions; only note any non-compliance.
   Do not duplicate information that is already in the design-steps.
3. avoid duplicating review notes or design-steps that already exist
4. commit
5. if there is no new actionable inconsistency feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
