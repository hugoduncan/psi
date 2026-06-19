---
name: review-follow-up-steps
description: Execute newly added review follow-up items for a Munera task (steps profile)
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
---
For the Munera task identified by {{input}}, execute any unchecked,
actionable, follow-up items in steps.md. Work independently.

Only execute follow-up items that were added by the immediately preceding
review pass. Do not execute older unchecked items that predate the preceding review pass;
leave them for their owning workflow or human decision.

Read the task's design.md as read-only context as needed. Read and
update the task's plan.md, steps.md as needed. When a follow-up item
requires it, also update the code, tests, and docs the item references.

If a step is completed, mark it done in steps.md. If a step cannot yet
be completed, leave it unchecked and record the blocking reason tersely,
concisely and precisely in implementation.md.

Append a minimal entry to implementation.md, e.g. "- addressed 3 review steps".

Commit when done.
