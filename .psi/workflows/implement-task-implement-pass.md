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
advertise: false
---
Implement the specific Munera task described by {{input}}. Work independently. Also apply `clojure-coding-standards` and `testing-without-mocks` as relevant.

Use the actor-step context to identify the specific task and, when present, the associated `munera_task_path`, `worktree_path`, PR metadata, and other handoff data. Focus only on that task.

Required procedure:
1. Read the task artifacts, especially `design.md`, `steps.md`, and `plan.md`.
3. Execute the next concrete implementation slice for the task.
4. Keep `design.md`, `plan.md`, and `steps.md` synchronized with what you learned and changed.
5. Add or refine tests as required by the task design.
6. Run relevant verification for the affected area.
7. Mark completed checklist items done in `steps.md`; add new implementation follow-up items when discovered.
8. Ask yourself: "What would the next slice need to know about the work
   done in this slice?". Append a minimalist entry to implementation.md
   to record important information for subsequent slices or reviews.
   **What to add:**
    - Key decisions made and their rationale
    - Implementation discoveries (e.g., "function X now returns Y format")
    - API or schema changes
    - Dependencies added or modified
    - Edge cases handled
    - Deviations from original design
   Avoid adding duplicate information already in the task files.
9. Commit any changes made during this pass with an appropriate commit message.

End your final response with exactly one of:
PASS_STATUS: MORE_WORK_REMAINS
PASS_STATUS: IMPLEMENTATION_COMPLETE
