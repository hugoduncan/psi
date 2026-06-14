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
For the Munera task identified by {{input}}, execute newly added actionable follow-up items in design-steps.md. Work independently. Read the task's design-steps.md and implementation.md, then inspect task-scoped git history to identify unchecked items added by the preceding review pass.

In the merged `review-task-design` workflow, the preceding review pass is the immediately preceding whole `design-review` batch: the architecture, ambiguity, and inconsistency review prompts run back-to-back in one shared child session before this follow-up. Treat "newly added" as spanning all three review prompts in that immediately preceding batch.

Use this evidence rule for batch review workflows:

1. Identify the contiguous latest task-scoped review-batch segment since the previous design-follow-up completion for the same task, or since task creation if no previous follow-up exists.
2. Use the parent of the oldest commit in that segment as the batch baseline, then compare that baseline to current HEAD for the task's design-steps.md (for example, `git diff <baseline>..HEAD -- <task>/design-steps.md`).
3. The candidate work set is exactly the checklist lines added by that diff that match unchecked design-step items and still exist unchecked in design-steps.md at follow-up start.
4. Execute only those candidate items. Do not execute unchecked items that predate the preceding review pass, edited stale items whose addition cannot be attributed to the just-finished batch, checked items, or items from steps.md.
5. If the review-batch segment or baseline cannot be identified confidently, or if a diff-added checklist item cannot be matched unambiguously to a current unchecked item, leave the item unchecked and record the blocking reason tersely in implementation.md rather than guessing.

Read and update the task's design.md, design-steps.md, and implementation.md as needed. Complete any newly added unchecked design-steps when possible, updating design.md as you work. If a design-step is completed, mark it done in design-steps.md. Do not touch plan.md or steps.md. Commit when done.
