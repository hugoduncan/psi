---
name: review-step-review
description: Review a Munera task using a named skill
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
---
Review the Munera task at {{input}} using the {{skill}} skill. Work independently. Read the skill file at `.psi/skills/{{skill}}/SKILL.md` and apply it. Read the task artifacts and any code/tests/docs they reference. Then:

1. append a terse review note to the task's implementation.md
2. add unchecked follow-up items to the task's steps.md for every new actionable issue you found
3. avoid duplicating review notes or steps that already exist
4. commit. if there is no new actionable feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: NO_ACTIONABLE_FEEDBACK
