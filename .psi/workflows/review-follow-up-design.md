---
name: review-follow-up-design
description: Execute newly added design-review follow-up items for a Munera task (design profile)
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
actionable, follow-up items in design-steps.md with updates to
design.md. Work independently.

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

**Do not execute `SCOPE_QUESTION:` items.** A follow-up item prefixed `SCOPE_QUESTION:` raises a concern about the design's scope boundary itself, which only a human may decide. Leave every such item unchecked, do not change the design's scope boundary in response to it, and record tersely in implementation.md that the scope question is deferred to the user.
