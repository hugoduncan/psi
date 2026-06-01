# Design follow-up steps — 201

## Ambiguity review (2026-06-01)

- [x] A1: Resolve stale per-aspect follow-up premise. The design (Scope,
  "Adjacent task-like work", Architectural alignment) states the new step follows
  "the existing dedicated per-aspect follow-up pattern (one follow-up prompt per
  aspect)" and that consolidation into a single reusable step is a *separate*
  task. But the existing ambiguity/inconsistency follow-up steps already share one
  `review-follow-up-design.md` prompt (landed by task 202). Update design.md to
  reflect the shared-profile reality and decide whether `architecture-follow-up`
  reuses `review-follow-up-design.md` or introduces a new prompt.
- [x] A2: Reconcile follow-up prompt filename. AC2a and Scope name
  `review-task-design-architecture-follow-up.md`, but existing follow-ups use the
  shared `review-follow-up-design.md`. State the exact follow-up prompt file the
  new step will reference (reuse vs. new), consistent with A1.
- [x] A3: Specify how `clarity-status` transitions to `final-summary`. AC3
  describes "clarity-status → final-summary" terminating cleanly, but the current
  `clarity-status` step has no `:on` map and `final-summary` follows positionally.
  Define the actual termination/transition mechanism the rewired workflow relies
  on (implicit next, `:goto :done`, positional fall-through) so the routing claim
  is unambiguous.
- [x] A4: Specify how the workflow entry/start step is determined. The design says
  the loop "starts at architecture-review" and will "rewire the workflow's entry,"
  but does not state whether the entry is the first `:steps` element or an explicit
  declaration. Clarify what makes architecture-review the start step.
- [x] A5: Specify the `architecture-follow-up` step's DONE routing target. AC2 says
  the new pair mirrors the existing pattern (constant-routing `{:route "DONE"}`
  with `:on {"DONE" {:goto ...}}`), but the design does not state where DONE goes
  (e.g. `ambiguity-review`). Define the `:goto` target.
- [x] A6: Specify whether `final-summary` adds an architecture-review contribution
  source. final-summary currently sources ambiguity-review and inconsistency-review
  yields; AC3 says it must "mention the architectural-fit pass." Clarify whether the
  architecture-review yield is added to `:contributions` and/or only referenced in
  the template prose.

## Inconsistency review (2026-06-01)

- [x] I1: Reconcile AC2a's PASS_STATUS line form with the existing prompt
  convention. AC2a says the new `architecture-review` prompt "ends with exactly
  one `PASS_STATUS: ACTIONABLE_FEEDBACK | REVIEW_COMPLETE` line," but Scope and
  "Architectural alignment" require consistency with the existing
  `review-task-design-*-review.md` files, which end with a two-line menu ("End
  your final response with exactly one of:" / `PASS_STATUS: ACTIONABLE_FEEDBACK`
  / `PASS_STATUS: REVIEW_COMPLETE`). Restate AC2a so the prescribed prompt
  ending matches the established two-line form (the agent emits exactly one
  status; the prompt lists both options), removing the internal contradiction.
