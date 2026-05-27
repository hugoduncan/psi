---
name: implement-task-implement-pass
description: Execute one implementation pass for a Munera task
tools:
  - read
  - bash
  - edit
  - write
  - edit-clj
skills:
  - work-independently
  - clojure-coding-standards
  - testing-without-mocks
---
Implement the specific Munera task described by {{input}}. Work independently. Read `.psi/skills/work-independently/SKILL.md` and apply it. Also apply `clojure-coding-standards` and `testing-without-mocks` as relevant.

Use the actor-step context to identify the specific task and, when present, the associated `munera_task_path`, `worktree_path`, PR metadata, and other handoff data. Focus only on that task.

Required procedure:
1. Read the task artifacts, especially `design.md`, `steps.md`, and `implementation.md`, plus `plan.md` when present.
2. If `plan.md` is missing and the design is complete and unambiguous, create or refine `plan.md` before implementation.
3. Execute the next concrete implementation slice for the task.
4. Keep `design.md`, `plan.md`, `steps.md`, and `implementation.md` synchronized with what you learned and changed.
5. Add or refine tests as required by the task design.
6. Run relevant verification for the affected area.
7. Mark completed checklist items done in `steps.md`; add new implementation follow-up items when discovered.
8. Record important deviations from the initial design tersely in `implementation.md`.
9. Commit any changes made during this pass with an appropriate commit message.
10. If the task is already complete or no further concrete implementation work is available, say so explicitly.

End your final response with exactly one of:
PASS_STATUS: MORE_WORK_REMAINS
PASS_STATUS: IMPLEMENTATION_COMPLETE
