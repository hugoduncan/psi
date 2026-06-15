---
name: review-follow-up-plan
description: Execute newly added plan-review follow-up items for a Munera task (plan profile)
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
---
For the Munera task identified by {{input}}, execute newly added actionable follow-up items in steps.md. Work independently. Read the task's steps.md and implementation.md, and read the task's design.md as read-only context as needed. Then inspect task-scoped git history to identify unchecked items added by the preceding review pass.

In the merged `review-task-plan` workflow, the preceding review pass is the immediately preceding whole `plan-review` batch: the ambiguity and inconsistency review prompts run back-to-back in one shared child session before this follow-up. Treat "newly added" as spanning both review prompts in that immediately preceding batch.

Use this evidence rule for batch review workflows:

1. Identify the contiguous latest task-scoped review-batch segment since the previous plan-follow-up completion for the same task, or since task creation if no previous follow-up exists.
2. Use the parent of the oldest commit in that segment as the batch baseline, then compare that baseline to current HEAD for the task's steps.md (for example, `git diff <baseline>..HEAD -- <task>/steps.md`).
3. The candidate work set is exactly the checklist lines added by that diff that match unchecked step items and still exist unchecked in steps.md at follow-up start.
4. Execute only those candidate items. Do not execute unchecked items that predate the preceding review pass, edited stale items whose addition cannot be attributed to the just-finished batch, or checked items.
5. If the review-batch segment or baseline cannot be identified confidently, or if a diff-added checklist item cannot be matched unambiguously to a current unchecked item, leave the item unchecked and record the blocking reason tersely in implementation.md rather than guessing.

Read and update the task's plan.md, steps.md, and implementation.md as needed. When a follow-up item requires it, also update the code, tests, and docs the item references. Complete any newly added unchecked steps when possible, updating the task's code, tests, docs, and task artifacts as you work. If a step is completed, mark it done in steps.md. Do not touch design.md beyond read-only context. Commit when done.
