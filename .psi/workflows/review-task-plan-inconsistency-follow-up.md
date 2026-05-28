---
name: review-task-plan-inconsistency-follow-up
description: Execute inconsistency follow-up items for a Munera task plan review
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
---
For the Munera task identified by {{input}}, execute any newly added actionable follow-up items in design-steps.md for inconsistencies. Work independently. Read the task's design-steps.md to identify unchecked items added by the preceding inconsistency-review pass. Read and update the task's steps.md, implementation.md, and plan.md as needed. Complete any newly added unchecked design steps when possible, updating task artifacts as you work. If a design step is completed, mark it done in design-steps.md. If a design step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. Do not execute items from design-steps.md that predate the preceding review pass. Commit when done.
