---
name: review-task-design-inconsistency-review
description: Review a Munera task design for inconsistencies
tools:
  - read
  - bash
  - edit
  - write
skills:
  - work-independently
  - task-design
---
For the Munera task identified by {{input}}, run the inconsistency review as the third turn of the shared `design-review` multi-prompt session. Work independently. Use the already-loaded task design.md, architecture sources, architecture-review reply, and ambiguity-review reply from the shared session context by default. Perform only targeted re-reads for specific missing or stale referenced material needed to decide an inconsistency; do not unconditionally re-read the whole task design and architecture source set.

Review the task design for inconsistencies, focusing on internal inconsistency within design.md and between design.md and referenced artifacts. Do not review plan.md or steps.md.

**Scope is frozen.** Treat the design's stated scope boundary (its scope sections — e.g. "What this slice does", "Scope (in)"/"Scope (out)", or any explicit frozen-boundary statement) as fixed for this review. Do not file follow-ups that widen, narrow, or re-draw that boundary, and never resolve a finding by changing scope. If you conclude the scope boundary itself is wrong, do not edit it: add exactly one follow-up item prefixed `SCOPE_QUESTION:` stating the concern for a human to decide, and do not raise further variants of that same boundary concern in this or any later pass. A `SCOPE_QUESTION:` item counts as actionable feedback.

Then:

1. add unchecked follow-up items to design-steps.md for every new actionable inconsistency you found (create design-steps.md if it does not exist)
2. append a minimalist review note on the outcome of the review to the task's implementation.md, e.g. "- no inconsistency review feedback" or "- inconsistency review added 2 new design steps"
3. avoid duplicating review notes or steps that already exist
4. commit
5. if there is no new actionable inconsistency feedback, say so explicitly

End your final response with exactly one of:
PASS_STATUS: ACTIONABLE_FEEDBACK
PASS_STATUS: REVIEW_COMPLETE
