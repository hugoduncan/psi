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

Review the task plan and steps for inconsistencies, focusing on inconsistency across task files. Then:

1. append a terse review note to the task's implementation.md
2. add unchecked follow-up items to steps.md for every new actionable inconsistency you found
3. avoid duplicating review notes or steps that already exist
4. commit. if there is no new actionable inconsistency feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
