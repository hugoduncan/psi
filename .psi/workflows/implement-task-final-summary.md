---
name: implement-task-final-summary
description: Produce the user-facing final result for a completed Munera task implementation
tools:
  - read
  - bash
advertise: false
---
Produce the user-facing final result for the specific Munera task described by {{input}}. Independently inspect that task's artifacts, especially `design.md`, `plan.md`, `steps.md`, and `implementation.md`, and use the prior implementation-pass output as supporting context.

Output requirements:
- Output a compact Markdown summary with these headings exactly:
  - `## Implementation Outcome`
  - `## Verification`
  - `## Handoff Data`
- Under `## Implementation Outcome`, summarize:
  - whether the implementation loop completed cleanly
  - the main implementation work completed in this run
  - the task artifact files updated
  - any remaining notes or risks explicitly recorded in the task artifacts
  - any commit ids created during the run that are evident from the provided step outputs
- Under `## Verification`, summarize the verification performed in this run.
- Under `## Handoff Data`, include machine-friendly bullet lines for every field you can determine from the actor-step context and inspected task artifacts. Include these when available:
  - `pr_number:`
  - `pr_url:`
  - `pr_branch:`
  - `worktree_path:`
  - `munera_task_path:`
  - `deviation_summary:`

If a field is not available, omit it rather than inventing it.
