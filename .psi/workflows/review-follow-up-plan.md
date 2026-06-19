---
name: review-follow-up-plan
description: Execute newly added plan-review follow-up items for a Munera task (plan profile)
tools:
  - read
  - bash
  - edit
  - write
skills:
  - task-design
  - work-independently
---
For the Munera task identified by {{input}}, execute the unchecked,
actionable, follow-up items in design-steps.md with updates to plan.md
and steps.md. Work independently.

Read the task's design.md as needed.

Be careful not to introduce new ambiguity or inconsistencies.

If a design-step is completed, mark it done in design-steps.md.

When finished, ask yourself: "What would the next review or
   implementation step need to know that you had to discover?". Append a
   minimalist entry to implementation.md to record important information
   for subsequent reviews or implementation.
   **What to add:**
  - task information that deosn't belong in design.md, but helps with
    carrying out the task
  - paths to important files
  - existing behaviour relevant to implementing the current task
  Avoid adding duplicate information already in the task files.

Commit when done.
