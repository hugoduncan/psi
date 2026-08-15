---
name: create-task-plan-create-plan
description: Create plan.md and steps.md for a Munera task from a stable design.md
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
  - task-design
advertise: false
---
For the Munera task identified by {{input}}, create or update plan.md and steps.md from the stable design.md. Work independently.

Required procedure:
1. Read design.md to understand the intent, scope, approach, and acceptance criteria.
2. If design.md is not complete or has unresolved ambiguities, stop and say so explicitly — do not create plan.md yet.
3. Create or update plan.md with:
   - Approach: the implementation strategy and key decisions
   - Risks: any known risks or blockers
   - Slice order: the ordered sequence of implementation slices
4. Create or update steps.md with a concrete implementation checklist:
   - One checklist item per concrete action (`- [ ] ...`)
   - Grouped by slice or phase
   - Each item is specific enough to be independently executable and verifiable
5. Check the task plan and steps for ambiguities, treating steps.md as read-only task context.
6. Check the task design for inconsistencies, focusing on internal inconsistency within design.md and  between design.md and referenced artifacts.
7. Commit the created/updated plan.md and steps.md.
8. Summarize what was created and any open questions.

Output a concise summary including:
- Whether plan.md and steps.md were created or updated
- The main implementation slices identified
- Any ambiguities in design.md that were noted but not blocking
