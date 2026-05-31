# Investigate and close completed open tasks from 077 onward

## Goal

Review open Munera tasks starting at `077-custom-provider-string-provider-auth-normalization` and continuing through later-numbered open tasks, determine whether any are already effectively complete, and close any task whose current artifacts and implementation state justify closure.

## Context

`munera/plan.md` still lists a long tail of open tasks after `077`, including tasks that may already be complete in code, tests, docs, or adjacent follow-on task notes.

The project already records some completion state outside the open-task directory list:

- `mementum/state.md` may describe tasks as complete
- `munera/plan.md` notes may say some tasks are complete and closed while other nearby tasks remain open
- implementation/review follow-on work may have landed under later tasks
- some open tasks may have been superseded or fully absorbed by later completed tasks

The open-task list should reflect only genuinely active work. Completed tasks should live under `munera/closed/`.

## Why

Stale open tasks distort project planning and orientation.

If a task is already complete but remains in `munera/open/`, then:

- `munera/plan.md` overstates active work
- future sessions may spend time re-investigating finished work
- dependency and backlog decisions become less reliable

This task creates a deliberate audit pass so the open-task surface becomes trustworthy again.

## Scope

Start at `077-custom-provider-string-provider-auth-normalization` and review later-numbered open tasks in `munera/open/`.

For each candidate task in scope:

- inspect its design/plan/steps/implementation artifacts when present
- inspect the current code/tests/docs state relevant to its acceptance
- inspect `munera/plan.md`, `mementum/state.md`, and related later-task notes for evidence of completion, supersession, or remaining gaps
- decide one of:
  - still open as-is
  - close as completed
  - close as superseded/absorbed with rationale recorded in-task
  - leave open but update artifacts if the current task intent is stale or misleading

This task is about investigation and backlog hygiene first.

It may close zero, one, or many tasks, depending on evidence.

## Out of scope

- implementing unfinished product work just to make a task closable
- broad replanning of the whole backlog beyond the reviewed range
- merging multiple task directories into one
- changing task numbering or task history

## Constraints

- Closure decisions must be evidence-based from current artifacts and repository state, not guesswork.
- Preserve Munera history: if a task is closed, move it to `munera/closed/` rather than deleting it.
- Record the closure rationale in the task artifacts when it is not already obvious from existing notes.
- Do not mark a task complete if acceptance is still materially unmet, even if related later work exists.
- If a task is superseded rather than completed directly, make that relationship explicit.
- Keep changes per reviewed task minimal and local.

## Required behavior

- The investigation starts with `077-custom-provider-string-provider-auth-normalization`.
- The review continues through later-numbered open tasks unless a narrower stopping rule is explicitly chosen and recorded.
- For each task reviewed, the outcome is explicit.
- Any task that is already complete is moved from `munera/open/` to `munera/closed/`.
- `munera/plan.md` is updated so open-task ordering reflects only tasks that remain open.
- If a task remains open, its continued-open rationale is discoverable from task artifacts or the review notes.

## Acceptance

- A review pass has been performed beginning with task `077`.
- At least one concrete reviewed-task outcome is recorded for each task inspected.
- Any task found already complete is closed and removed from the open-task list.
- Any task left open after inspection has an explicit rationale for remaining open.
- `munera/plan.md` reflects the resulting open/closed state accurately.
- No task is closed solely because adjacent or later work looks similar; the closure decision is tied to that task's own intent and acceptance.

## Suggested execution shape

Review tasks in small batches, for example:

1. start with `077`
2. continue through the next few open tasks in numeric order
3. close clearly completed tasks immediately
4. stop and record findings when a task needs design clarification or real implementation work rather than closure

## Notes

This is a backlog-audit task, not an implementation umbrella. The primary deliverable is a trustworthy open-task surface.