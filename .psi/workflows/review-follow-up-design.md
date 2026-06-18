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

If a design-step is completed, mark it done in design-steps.md.

When finished, add a minimalist note to implementation.md stating that
you have addressed the design-steps, and noting anything you were not
able to do. e.g. "- design-steps completed"

Commit when done.

**Do not execute `SCOPE_QUESTION:` items.** A follow-up item prefixed `SCOPE_QUESTION:` raises a concern about the design's scope boundary itself, which only a human may decide. Leave every such item unchecked, do not change the design's scope boundary in response to it, and record tersely in implementation.md that the scope question is deferred to the user.
