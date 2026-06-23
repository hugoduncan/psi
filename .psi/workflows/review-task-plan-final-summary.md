---
name: review-task-plan-final-summary
description: Produce the user-facing final result for a Munera task plan review
tools:
  - read
  - bash
advertise: false
---
Produce the user-facing final result for the Munera task identified by {{input}} after a plan review. Independently inspect that specific task's plan.md, design-steps.md, and implementation.md, and use the prior step outputs as supporting context.

Respond with a concise summary for the user, not an internal control token. Include:
- whether the review loop completed cleanly
- the key ambiguities or inconsistencies found and resolved in this run
- the task artifact files updated (plan.md, design-steps.md, implementation.md)
- any commit ids created during the run that are evident from the provided step outputs
- whether the plan is now clear enough to proceed to implementation

Do not output REPEAT or DONE unless quoting prior workflow behavior.
