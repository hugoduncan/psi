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

## Plan-review session outcome
- Plan-review (ambiguity + inconsistency) added no new design-steps; no unchecked design-steps remain — task is design-stable and ready to advance to plan.md/steps.md creation, where Open Questions 1-4 (esp. exact set to flip, Q3) must be resolved.

## Design-follow-up resolution (inconsistency step)
- Resolved the byte-identical inconsistency by scoping the Constraints invariant to items whose `advertise` remains absent/`true`, and explicitly stating the in-task `review-*`/`issue-*` + sub-only-workflow flip is the intended exception. No scope change to the design — the frozen scope (which items get flipped) is unchanged; only the invariant wording was reconciled. Exact enumeration of flipped items remains deferred to planning per Open Question 3.

## Slice 1 — Mechanism (implemented)
- Decisions on Open Questions recorded in plan.md (Q1=keep both fields; Q2=prompt-contribution only; Q3=enumerated in plan, applied slice 2; Q4=only literal `false` disables).
- Design refinement: `advertise` is also supported in **markdown** workflow frontmatter (not just EDN), because many sub-only workflows are `.md` files. Uniform concept across both file kinds.
- Skills (`prompt_assets/skills.clj`): `parse-skill-file` derives `:advertise` (default true; only `"false"` disables), `->skill` propagates it. New private `prompt-hidden?` = `disable-model-invocation OR (false? :advertise)`; both `format-skills-for-prompt` and `-lambda` use it. Absent `:advertise` ⇒ advertised (robust for skills built elsewhere).
- EDN workflows: `:advertise` already flows through `compile-edn-workflow-file` (config passthrough). `text/build-prompt-contribution` now removes `(false? (:advertise defn-map))`. User-facing `available-workflows-text`/`delegate-list-text` deliberately unchanged (Q2).
- Markdown workflows: `:advertise` added to `allowed-md-frontmatter-keys`; parsed with same false-coercion; `compile-markdown-workflow-file` sets `:advertise (if (nil? advertise) true advertise)`.
- Filter predicate uses `false?` (not `not`) so absent values stay advertised — byte-identical default behaviour preserved.
- Verified: clj-kondo clean; 30 tests / 269 assertions pass (skills, parser, compiler, text).

## Remaining (Slice 2 — Apply the field)
- Flip enumerated review-*/issue-* skills and sub-only workflows to `advertise: false` and verify drop-from-context + still-invocable.

## Slice 2 — Apply the field (implemented)
- Skills flipped (advertise: false): review-implementation-architecture, review-task-architecture, review-task-docs, task-implementation-review, task-test-review, issue-bug-triage, issue-feature-triage.
- Workflows flipped — auditable basis: sub-only iff referenced as :prompt-workflow or :target by another workflow, or described as a lower-level/handoff sub-workflow; top-level user-facing entries left advertised.
  - markdown (22): create-task-plan-create-plan, implement-task-implement-pass, implement-task-final-summary, implement-task-in-worktree, resolve-task-design-entities-resolve, review-follow-up-{design,plan,steps}, review-task-design-{ambiguity,architecture,inconsistency}-review, review-task-design-final-summary, review-task-note-info, review-task-plan-{ambiguity,inconsistency}-review, review-task-plan-final-summary, gh-bug-{discover-and-read,post-repro,reproduce}, gh-issue-{create-worktree,push-intent,task-intent}.
  - edn (8): review-task-{design,implementation,plan}-core, review-step, review-design-turn, gh-bug-request-more-info, review-implementation-in-worktree, task-lifecycle-in-worktree.
- Left advertised (shared sub-loops/ambiguous, conservative): gh-pr-heal-check-loop, resolve-task-design-entities (top-level wrapper), review-task-{design,plan,implementation} (standalone-summary wrappers).
- Live verification (loader + text/build-prompt-contribution on this worktree): all flipped workflows registered but absent from prompt contribution; plan-build-review/task-lifecycle still advertised. Pre-existing 7 markdown-body load errors are unrelated to this change.
