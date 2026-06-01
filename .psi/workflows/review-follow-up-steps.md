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
For the Munera task identified by {{input}}, execute any newly added actionable follow-up items in steps.md. Work independently. Read the task's steps.md to identify unchecked items added by the preceding review pass. Read the task's design.md as read-only context as needed. Read and update the task's plan.md, steps.md, and implementation.md as needed. When a follow-up item requires it, also update the code, tests, and docs the item references. Complete any newly added unchecked steps when possible, updating the task's code, tests, docs, and task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. Do not execute items from steps.md that predate the preceding review pass. Commit when done.
