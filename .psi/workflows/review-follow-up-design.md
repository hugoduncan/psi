---
name: review-follow-up-design
description: Execute newly added design-review follow-up items for a Munera task (design profile)
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
---
For the Munera task identified by {{input}}, execute any newly added actionable follow-up items in design-steps.md. Work independently. Read the task's design-steps.md to identify unchecked items added by the preceding review pass. Read and update the task's design.md, design-steps.md, and implementation.md as needed. Complete any newly added unchecked design-steps when possible, updating design.md as you work. If a design-step is completed, mark it done in design-steps.md. If a design-step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. Do not execute items from design-steps.md that predate the preceding review pass. Do not execute items from steps.md. Do not touch plan.md or steps.md. Commit when done.
