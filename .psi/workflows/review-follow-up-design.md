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
actionable design-review follow-up items in design-steps.md with updates to
design.md. Work independently.

The preceding review pass is the immediately preceding whole `design-review` batch:
architecture, ambiguity, and inconsistency review prompts run back-to-back. Treat candidate follow-up work as spanning all three review prompts in that immediately preceding batch, not just one prompt within it.

Identify the contiguous latest task-scoped review-batch segment since the previous design-follow-up completion. Use the parent of the oldest commit in that segment as the batch baseline, then inspect:

```sh
git diff <baseline>..HEAD -- <task>/design-steps.md
```

The candidate work set is exactly the checklist lines added by that diff that:

- match unchecked design-step items
- still exist unchecked in design-steps.md at follow-up start

Do not execute unchecked items that predate the preceding review pass, edited stale items whose addition cannot be attributed to the just-finished batch, checked items, or items from steps.md.

If the review-batch segment or baseline cannot be identified confidently, or an added checklist line cannot be matched unambiguously to a current unchecked item, leave the item unchecked and record the blocking reason tersely in implementation.md rather than guessing.

Be careful not to introduce new ambiguity or inconsistencies.

If a design-step is completed, mark it done in design-steps.md.

When finished, ask yourself: "What would the next review or implementation step
need to know that you had to discover?" Append a minimalist entry to
implementation.md to record important information for subsequent reviews or
implementation.

**What to add:**
- task information that doesn't belong in design.md, but helps with carrying out
  the task
- existing behaviour relevant to implementing the current task

Avoid adding duplicate information already in the task files.

Commit when done.

**Do not execute `SCOPE_QUESTION:` items.** A follow-up item prefixed
`SCOPE_QUESTION:` raises a concern about the design's scope boundary itself,
which only a human may decide. Leave every such item unchecked, do not change
the design's scope boundary in response to it, and record tersely in
implementation.md that the scope question is deferred to the user.
