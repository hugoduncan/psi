---
name: review-task-design-architecture-review
description: Review a Munera task design for architectural fit
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
  - review-task-architecture
---
For the Munera task identified by {{input}}, run the architecture review as the first turn of the shared `design-review` multi-prompt session. Work independently. Read the task's design.md and consult the project's architecture sources (AGENTS.md, META.md, and doc/architecture.md) as needed before producing architectural-fit feedback. This first turn loads the task design and architecture context for the later ambiguity and inconsistency turns in the same child session.

Review the task design for architectural fit with the project's architecture and principles (not for ambiguity or inconsistency). Do not review plan.md or steps.md. Then:

1. append a terse review note to the task's implementation.md
2. add unchecked follow-up items to design-steps.md for every new actionable architectural misfit you found (create design-steps.md if it does not exist)
3. avoid duplicating review notes or steps that already exist
4. commit
5. if there is no new actionable architectural-fit feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
