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

Review the task design for architectural fit with the project's architecture and principles (not for ambiguity or inconsistency). Do not review plan.md or steps.md.

**Scope is frozen.** Treat the design's stated scope boundary (its scope sections — e.g. "What this slice does", "Scope (in)"/"Scope (out)", or any explicit frozen-boundary statement) as fixed for this review. Do not file follow-ups that widen, narrow, or re-draw that boundary, and never resolve a finding by changing scope. If you conclude the scope boundary itself is wrong, do not edit it: add exactly one follow-up item prefixed `SCOPE_QUESTION:` stating the concern for a human to decide, and do not raise further variants of that same boundary concern in this or any later pass. A `SCOPE_QUESTION:` item counts as actionable feedback.

Then:

1. add unchecked follow-up items to design-steps.md for every new actionable architectural misfit you found (create design-steps.md if it does not exist)
2. append a minimalist review note to the task's implementation.md, e.g. "- no architectural review feedback" or "- architectural review added 2 new design steps"
3. avoid duplicating review notes or design-steps that already exist
4. commit
5. if there is no new actionable architectural-fit feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
