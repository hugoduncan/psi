---
name: review-task-design-clarity-status
description: Determine whether a Munera task design review cycle is complete
tools:
  - read
  - bash
---
Review the Munera task identified by {{input}} and decide whether there is still actionable ambiguity or inconsistency follow-up remaining from the just-completed design review cycle. Independently inspect that specific task's design.md and design-steps.md only. Do not inspect plan.md or steps.md. This is an internal control step. Respond with exactly one word: REPEAT or DONE. Return REPEAT if there is still actionable ambiguity or inconsistency follow-up remaining from the review cycle, including newly added unchecked design-steps or unresolved review findings in design.md. Return DONE only if the task design has no remaining new actionable ambiguity or inconsistency feedback from the cycle.
