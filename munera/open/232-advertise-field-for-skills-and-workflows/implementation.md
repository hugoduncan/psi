# Implementation notes

## Review notes
- architectural review: no architectural review feedback (design fits the existing prompt-contribution presentation pattern; advertise is orthogonal to registration/discovery/invocation; advertise-vs-disable-model-invocation overlap is already captured in design.md Open Question 1, so not a new finding)
- ambiguity review: no ambiguity review feedback (substantive ambiguities — disable-model-invocation overlap, interactive-listing scope, exact set to mark, false-coercion rule — are already enumerated as design.md Open Questions 1-4)
- plan ambiguity review: no plan ambiguity review feedback (no plan.md/steps.md yet — task in design phase; substantive ambiguities already captured as design.md Open Questions 1-4)
- plan inconsistency review: no plan inconsistency review feedback (no plan.md/steps.md yet; prior byte-identical inconsistency already reconciled in design.md and design-steps; task files mutually consistent)
- inconsistency review added 1 new design step (Constraints "byte-identical for all currently-advertised" contradicts the in-task flip of currently-advertised items to advertise:false; invariant should be scoped to advertise absent/true items)

## Context for addressing design-steps
- The flagged design-step is a design.md wording fix (Constraints section) — keep the byte-identical invariant scoped to `advertise` absent/`true`; do not widen/narrow the frozen scope while doing so.
- Relevant non-task source files (for later implementation, not the wording fix):
  - Skills: `components/prompt-assets/src/psi/prompt_assets/skills.clj` — frontmatter parse (~L137) and `format-skills-for-prompt`/`-lambda` (~L502/527); existing `disable-model-invocation` filter is the pattern to mirror for `advertise`.
  - Workflows: `components/agent-session/src/psi/agent_session/workflow/text.clj` — `build-prompt-contribution` (~L93) is the system-context listing to filter on `:advertise false`.
  - `/delegate list` / `action=list` listing also lives in `text.clj` (`available-workflows-text`/`delegate-list-text`); design Open Question 2 governs whether those are affected — confirm before touching them.

## Design-follow-up resolution (inconsistency step)
- Resolved the byte-identical inconsistency by scoping the Constraints invariant to items whose `advertise` remains absent/`true`, and explicitly stating the in-task `review-*`/`issue-*` + sub-only-workflow flip is the intended exception. No scope change to the design — the frozen scope (which items get flipped) is unchanged; only the invariant wording was reconciled. Exact enumeration of flipped items remains deferred to planning per Open Question 3.
