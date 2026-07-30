---
name: review-task-architecture
description: Reviews a Munera task design for architectural fit with the project's architecture and principles
lambda: "λtask. review(design_architectural_fit) ∧ judge(fit, ¬correctness ∧ ¬clarity) ∧ consult(in_context_architecture_sources)"
advertise: false
---

# review-task-architecture

λtask. review(design_architectural_fit) ∧ judge(fit, ¬correctness ∧ ¬clarity) ∧ consult(in_context_architecture_sources)

Check the task design's **fit with the current architecture**. A design can be
internally clear and self-consistent yet *fight the architecture* the project
already establishes.

Consult the architecture sources already in your context — `AGENTS.md`,
`ramora/META.md`, `ramora/architecture.md` — for the principles and boundaries to judge fit
against. Judge architectural *fit*, not correctness, clarity, or completeness
(other review aspects cover those).
