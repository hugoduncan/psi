---
name: review-follow-up-plan
description: Execute newly added plan-review follow-up items for a Munera task (plan profile)
tools:
  - read
  - bash
  - edit
  - write
skills:
  - task-design
  - work-independently
advertise: false
---
For the Munera task identified by {{input}}, execute the unchecked,
actionable plan-review follow-up items in steps.md with updates to plan.md and
steps.md. Work independently.

The preceding review pass is the immediately preceding whole `plan-review` batch:
ambiguity and inconsistency review prompts run back-to-back. Treat candidate follow-up work as spanning both review prompts in that immediately preceding batch, not just one prompt within it.

Identify the contiguous latest task-scoped review-batch segment since the previous plan-follow-up completion. Use the parent of the oldest commit in that segment as the batch baseline, then inspect:

```sh
git diff <baseline>..HEAD -- <task>/steps.md
```

The candidate work set is exactly the checklist lines added by that diff that:

- match unchecked step items
- still exist unchecked in steps.md at follow-up start

Do not execute unchecked items that predate the preceding review pass, edited stale items whose addition cannot be attributed to the just-finished batch, or checked items.

If the review-batch segment or baseline cannot be identified confidently, or an added checklist line cannot be matched unambiguously to a current unchecked item, leave the item unchecked and record the blocking reason tersely in implementation.md rather than guessing.

Read the task's design.md as read-only context as needed. The plan profile may update plan.md, steps.md, implementation.md, and referenced code, tests, and docs when a current attributed follow-up item requires it.

Be careful not to introduce new ambiguity or inconsistencies.

If a step is completed, mark it done in steps.md.

When finished, ask yourself: "What would the next review or implementation step
need to know that you had to discover?" Append a minimalist entry to
implementation.md to record important information for subsequent reviews or
implementation.

**What to add:**
- task information that doesn't belong in design.md, but helps with carrying out
  the task
- paths to relevant project (non-task) files
- existing behaviour relevant to implementing the current task

Avoid adding duplicate information already in the task files.

Commit when done.
