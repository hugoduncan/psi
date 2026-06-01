---
name: review-task-design-final-summary
description: Produce the user-facing final result for a Munera task design review
tools:
  - read
  - bash
---
Produce the user-facing final result for the Munera task identified by {{input}} after a design review. Independently inspect that specific task's design.md, design-steps.md, and implementation.md, and use the prior step outputs as supporting context.

Respond with a concise summary for the user, not an internal control token. Include:
- whether the design review loop completed cleanly
- the key architectural-fit misfits, ambiguities, or inconsistencies found and resolved in this run
- the task artifact files updated (design.md, design-steps.md, implementation.md)
- any commit ids created during the run that are evident from the provided step outputs
- whether the design is now clear enough to proceed to plan creation

Do not output REPEAT or DONE unless quoting prior workflow behavior.
