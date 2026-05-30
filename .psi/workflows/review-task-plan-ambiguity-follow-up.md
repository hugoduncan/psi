---
name: review-task-plan-ambiguity-follow-up
description: Execute ambiguity follow-up items for a Munera task plan review
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
---
For the Munera task identified by {{input}}, execute any newly added actionable follow-up items in steps.md for ambiguities. Work independently. Read the task's steps.md to identify unchecked items added by the preceding ambiguity-review pass. Read and update the task's plan.md, steps.md, and implementation.md as needed. Complete any newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. Do not execute items from steps.md that predate the preceding review pass. Commit when done.
