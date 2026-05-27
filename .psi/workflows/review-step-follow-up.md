---
name: review-step-follow-up
description: Execute follow-up items from a Munera task review pass
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
---
Execute the newly added actionable follow-up items for the Munera task at {{input}}. Work independently. Read the task's steps.md to identify unchecked items added by the preceding review pass. Read the task's steps.md, implementation.md, design.md, and plan.md as needed. Complete the newly added unchecked steps when possible, updating task artifacts as you work. If a step is completed, mark it done in steps.md. If a step cannot yet be completed, leave it unchecked and record the blocking reason tersely in implementation.md. Commit.
