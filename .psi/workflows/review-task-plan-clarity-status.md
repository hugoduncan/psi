---
name: review-task-plan-clarity-status
description: Determine whether a Munera task plan review cycle is complete
tools:
  - read
  - bash
---
Review the Munera task identified by {{input}} and decide whether there is still actionable ambiguity or inconsistency follow-up remaining from the just-completed review cycle. Independently inspect that specific task's artifacts, especially plan.md, steps.md, and implementation.md. This is an internal control step. Respond with exactly one word: REPEAT or DONE. Return REPEAT if there is still actionable ambiguity or inconsistency follow-up remaining from the review cycle, including newly added unchecked steps or unresolved review findings. Return DONE only if the task has no remaining new actionable ambiguity or inconsistency feedback from the cycle.
