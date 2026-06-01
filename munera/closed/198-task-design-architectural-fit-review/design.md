# 198 — Task design architectural-fit review

## Intent

Add an **architectural-fit** dimension to Munera task-design review: a new review
skill that evaluates a task's `design.md` against the project's architecture and
principles, and a new step in the `review-task-design` workflow that exercises that
skill in the existing review/follow-up loop.

The goal is to catch designs that are internally clear and consistent but that
*fight the architecture* — violating the one-way principle, the dispatch /
resolver / mutation boundaries, VSM layering, extension isolation, effects-as-data,
or the "no silent shims/adapters/compatibility layers" rule — before they reach
plan creation and implementation.

The new `architecture-follow-up` step **reuses the existing shared design
follow-up profile prompt** `review-follow-up-design.md` (landed by task 199),
exactly as the current `ambiguity-follow-up` and `inconsistency-follow-up` steps
do. There are no per-aspect follow-up `.md` files to mirror; the design/plan
review follow-ups already share one profile prompt per artifact scope. No new
follow-up prompt is introduced by this task.

## Problem

`review-task-design` currently reviews two aspects only:

- **ambiguity** — is every statement unambiguous? (loads `task-design`)
- **inconsistency** — are statements internally coherent with each other?

Neither aspect checks whether the design is coherent with the *system it lives in*.
A design can be perfectly clear and self-consistent while proposing an approach that
bypasses dispatch, reads state outside resolvers, mutates state outside mutations,
introduces a compatibility shim, or breaks VSM layering. Today the first signal of
such misfit arrives during planning, implementation, or code review — late and
expensive. The `task-design` skill already asks the author to
`explain(alignment(x), existing_architecture)` and
`decide(structures_patterns(x), {follow introduce remove})`, but nothing in the
review loop independently verifies that alignment.

## Scope

### In scope

- A new **minimal review skill** `review-task-architecture` that names the
  architectural-fit lens. Its content is intentionally thin: it directs the
  reviewing agent to *check the task design's fit with the current architecture*
  and find whatever it needs (AGENTS.md, META.md, doc/architecture.md are already
  in the reviewing agent's context). It does **not** duplicate the principle list or
  carry an elaborate checklist. (Analogous to how `task-design` serves the existing
  ambiguity/inconsistency aspects — the skill is the lens, the workflow prompt is
  the behaviour contract.)
- A new **review aspect step pair**, placed **first** in `review-task-design.edn`
  (before ambiguity), following the existing ambiguity/inconsistency pattern:
  - an `architecture-review` session step whose prompt loads the
    `review-task-architecture` skill and ends with the established two-line
    status menu used by the existing `review-task-design-*-review.md` prompts
    ("End your final response with exactly one of:" followed by
    `PASS_STATUS: ACTIONABLE_FEEDBACK` and `PASS_STATUS: REVIEW_COMPLETE` on
    separate lines; the agent emits exactly one of the two statuses), gated by
    `workflow/pass-status-routing` (allowed statuses
    `["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]`), with
    `:on {"REPEAT" {:goto "architecture-follow-up"} "DONE" {:goto "ambiguity-review"}}`;
  - an `architecture-follow-up` session step that executes newly added
    `design-steps.md` items, gated by `workflow/constant-routing {:route "DONE"}`
    with `:on {"DONE" {:goto "ambiguity-review"}}` (DONE always advances to the
    next aspect, `ambiguity-review`).
- One **new review prompt `.md`** file
  (`review-task-design-architecture-review.md`), consistent with the existing
  `review-task-design-*-review.md` files. **No new follow-up prompt**: the
  `architecture-follow-up` step reuses the shared `review-follow-up-design.md`
  profile prompt, exactly as `ambiguity-follow-up` and `inconsistency-follow-up`
  do today.
- Rewiring the workflow's entry and routing so the loop *starts* at
  `architecture-review` (the workflow start step is the first element of the
  `:steps` vector; inserting `architecture-review` as the first element makes it
  the entry), then continues into the existing ambiguity → inconsistency →
  `clarity-status` → `final-summary` flow. The existing
  `clarity-status → final-summary` termination is **implicit positional
  fall-through**: `clarity-status` is a non-judged leaf step with no `:on` map, so
  it advances on completion to the next step in `:steps` order (`final-summary`),
  which is last and therefore terminates the run. This task does not change that
  termination mechanism; it only prepends the architecture pair. `final-summary`
  is updated to also reference the architectural-fit pass.

### Out of scope

- Adding architectural-fit review to `review-task-plan`, `review-task-implementation`,
  or `review-design-turn` (the generic single-aspect turn). May be follow-on tasks.
- Changing the ambiguity or inconsistency review behaviour.
- Changing the `task-design` skill itself.
- Authoring new architectural rules; the skill distills the *existing* architecture
  (AGENTS.md / META.md / doc/architecture.md), it does not invent new constraints.
- Any runtime/engine changes to the workflow executor or `pass-status-routing`
  (the existing deterministic operations are expected to suffice).

### Adjacent task-like work (noted, not included)

- **Reusable review follow-up step**: already landed by task 199 — the
  design/plan review follow-ups now share `review-follow-up-design.md` /
  `review-follow-up-steps.md` profile prompts. This task's `architecture-follow-up`
  step reuses the shared design profile directly; no further consolidation work is
  needed here.
- A parallel architectural-fit lens for *plan* review.
- Distilling architecture into a shared, referenceable knowledge page.

## Concepts (minimum set)

- **Architectural fit**: the degree to which a proposed design follows the
  project's architecture and principles rather than working around them. The
  review judges *fit*, not correctness, clarity, or completeness.
- **Review aspect**: a named, loopable review dimension in `review-task-design`
  (currently ambiguity, inconsistency; this task adds architecture as the first),
  each a review step + follow-up step pair gated by `pass-status-routing`.
- **Actionable feedback**: a concrete, fixable architectural misfit recorded as an
  unchecked `design-steps.md` item, distinct from advisory commentary.

## Acceptance criteria

1. A new skill exists at `.psi/skills/review-task-architecture/SKILL.md` with valid
   frontmatter (`name`, `description`, `lambda`). Its body is minimal: it frames the
   architectural-fit lens — "check the task design's fit with the current
   architecture" — and tells the reviewing agent to consult the in-context
   architecture sources as needed. No duplicated principle list, no elaborate
   checklist.
2. `review-task-design.edn` contains an `architecture-review` step and an
   `architecture-follow-up` step that mirror the existing review-aspect pattern:
   - `architecture-review`: session step, judge `workflow/pass-status-routing`
     with `:allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]`, and
     `:on {"REPEAT" {:goto "architecture-follow-up"} "DONE" {:goto "ambiguity-review"}}`;
   - `architecture-follow-up`: session step using the shared
     `review-follow-up-design.md` profile prompt, judge
     `workflow/constant-routing {:route "DONE"}`, and
     `:on {"DONE" {:goto "ambiguity-review"}}` (DONE advances to the next aspect,
     `ambiguity-review`).
2a. The `architecture-review` prompt loads the `review-task-architecture` skill and
    ends with the same two-line status menu as the existing
    `review-task-design-*-review.md` prompts — a line "End your final response with
    exactly one of:" followed by `PASS_STATUS: ACTIONABLE_FEEDBACK` and
    `PASS_STATUS: REVIEW_COMPLETE` on their own lines. The prompt lists both
    options; the agent emits exactly one status line. (This matches the established
    convention rather than collapsing the two options onto a single `A | B` line.)
    The `architecture-follow-up` step reuses the shared `review-follow-up-design.md`
    profile (no new follow-up `.md`); that shared profile already executes only
    newly added `design-steps.md` items and does not touch `plan.md`/`steps.md`.
3. `architecture-review` is the workflow's **first** step (the first element of the
   `:steps` vector, which the runtime treats as the start step); on completion the
   loop continues into ambiguity → inconsistency → `clarity-status` →
   `final-summary`. Termination is the existing implicit positional fall-through:
   `clarity-status` (non-judged, no `:on`) advances to `final-summary`, the last
   step, which ends the run. `final-summary` mentions the architectural-fit pass
   alongside ambiguity/inconsistency, and adds the `architecture-review` step yield
   as a `:contributions` source (mirroring the existing ambiguity/inconsistency
   yield contributions).
4. The new review only adds **actionable** architectural-fit items to
   `design-steps.md`, appends a terse note to `implementation.md`, avoids
   duplicates, commits, and explicitly states when there is no new feedback —
   matching the behaviour contract of the existing review aspects.
5. Workflow-definition validation/tests recognise the new steps (the workflow loads
   and validates; existing workflow-definition tests updated/extended as needed).
6. User-facing workflow docs (`doc/workflows.md` and any review-workflow reference)
   describe the new architectural-fit review aspect.

## Architectural alignment

This task *is* a configuration/prompt change to the review tooling; it introduces
no runtime code paths. It follows existing patterns rather than introducing new
ones:

- The new step reuses the established review-aspect shape (session review step +
  shared-profile follow-up step gated by `workflow/constant-routing`, review step
  gated by `workflow/pass-status-routing`) — `follow` not `introduce`. The
  follow-up reuses the shared `review-follow-up-design.md` profile (post-199),
  adding no new prompt file.
- The new skill follows the existing standalone-review-skill shape
  (`review-task-docs`, `task-*-review`) — a focused lens loaded by a workflow
  prompt — but kept deliberately thin because the architectural source-of-truth
  (AGENTS.md / META.md / doc/architecture.md) is already available to the reviewing
  agent.

## Resolved decisions

1. **Skill name** → `review-task-architecture`.
2. **Step ordering** → architectural-fit runs **first**, before ambiguity, so
   structural misfit is caught before fine-grained clarity/consistency polishing.
3. **Skill structure** → a thin standalone `review-task-architecture` skill (the
   lens), loaded by the workflow prompt (the behaviour contract). The skill content
   is inline/minimal, not an elaborate reusable abstraction.
4. **Checklist depth** → the skill says only "check fit with the current
   architecture"; the reviewing agent finds what it needs from the in-context
   architecture sources. No duplicated principles.
5. **Follow-up prompt** → the `architecture-follow-up` step reuses the shared
   `review-follow-up-design.md` profile prompt (landed by task 199), not a new
   dedicated per-aspect file. The stale "per-aspect follow-up pattern" premise is
   retired; there are no per-aspect follow-up `.md` files to mirror.
6. **Workflow start step** → determined positionally as the first element of the
   `:steps` vector (`statechart/initial-step-id` returns the first step in
   definition order). Inserting `architecture-review` as the first `:steps` element
   makes it the entry; no explicit start declaration exists or is needed.
7. **`architecture-follow-up` DONE target** → `:goto "ambiguity-review"`, the next
   aspect, mirroring how `ambiguity-follow-up`/`inconsistency-follow-up` route DONE
   to the subsequent review step.
8. **`clarity-status → final-summary` termination** → unchanged implicit positional
   fall-through. `clarity-status` is a non-judged leaf step with no `:on` map; the
   runtime advances it to the next `:steps` element (`final-summary`), the last
   step, which terminates the run. This task does not alter this mechanism.
9. **`final-summary` architectural-fit reference** → the `architecture-review` step
   yield is added to `final-summary`'s `:contributions` as a source (alongside the
   existing ambiguity-review and inconsistency-review yields), and the template
   prose is updated to mention the architectural-fit pass.
