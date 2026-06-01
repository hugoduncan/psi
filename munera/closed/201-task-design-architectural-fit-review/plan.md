# 201 — Plan: task-design architectural-fit review

## Approach

This is a configuration/prompt change to the review tooling — no runtime/engine
code paths. The work threads a new **architectural-fit** review aspect into the
existing `review-task-design` workflow by *following* the established
review-aspect shape, not introducing a new mechanism.

The change has four moving parts, all of which already have a concrete template
in the repository:

1. **New review skill** `.psi/skills/review-task-architecture/SKILL.md` — a thin
   lens (frontmatter `name`/`description`/`lambda` + minimal body) modelled on
   the existing standalone review skills (`review-task-docs`,
   `task-implementation-review`). It only frames the architectural-fit lens and
   points the reviewing agent at the in-context architecture sources
   (AGENTS.md / META.md / doc/architecture.md). No duplicated principle list,
   no elaborate checklist.

2. **New review prompt** `.psi/workflows/review-task-design-architecture-review.md`
   — modelled exactly on `review-task-design-ambiguity-review.md`: same
   frontmatter (tools `read/bash/edit/write`, skills `work-independently` +
   `review-task-architecture`), same five-step body
   (note→items→no-dup→commit→explicit-no-feedback), and the **same two-line
   PASS_STATUS menu** ("End your final response with exactly one of:" /
   `PASS_STATUS: ACTIONABLE_FEEDBACK` / `PASS_STATUS: REVIEW_COMPLETE`). It loads
   the new skill instead of `task-design`, and its body frames architectural fit
   rather than ambiguity. **No new follow-up prompt** — the follow-up reuses the
   shared `review-follow-up-design.md` profile (post-202).

3. **Rewire `review-task-design.edn`** — prepend an `architecture-review` /
   `architecture-follow-up` step pair as the **first two `:steps` elements**, so
   `architecture-review` becomes the start step (the runtime's
   `initial-step-id` = first `:steps` element). Wire:
   - `architecture-review`: `:session`, prompt
     `review-task-design-architecture-review.md`, judge
     `workflow/pass-status-routing` reading its own `:final-llm-reply` with
     `:allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]`,
     `:on {"REPEAT" {:goto "architecture-follow-up"} "DONE" {:goto "ambiguity-review"}}`.
   - `architecture-follow-up`: `:session`, prompt `review-follow-up-design.md`
     (shared profile), judge `workflow/constant-routing {:route "DONE"}`,
     `:on {"DONE" {:goto "ambiguity-review"}}`.
   - Add the `architecture-review` step yield to `final-summary`'s
     `:contributions` (alongside the existing ambiguity/inconsistency yields) and
     update the `final-summary` template prose to mention the architectural-fit
     pass.

   The existing ambiguity → inconsistency → `clarity-status` → `final-summary`
   flow and its implicit positional `clarity-status → final-summary`
   fall-through are **unchanged**.

4. **Tests + docs** — extend `workflow_definitions_test.clj`
   `review-task-design-test` (now 8 steps; assert names/types, the new
   architecture judge + `:on` wiring, and the architecture-review
   `final-summary` contribution); add `review-follow-up-design.md` is already in
   the md-refs list (the new review prompt must be added to it). Update
   `doc/workflows.md` to describe the architectural-fit aspect.

### Key decisions (inherited from design — already resolved)

- Skill name `review-task-architecture`; runs **first**, before ambiguity.
- Follow-up reuses shared `review-follow-up-design.md` (no per-aspect file).
- Start step is positional (first `:steps` element); no explicit declaration.
- `architecture-follow-up` DONE → `:goto "ambiguity-review"`.
- `clarity-status → final-summary` termination unchanged.
- Two-line PASS_STATUS menu, matching existing `*-review.md` prompts (I1).

## Risks

- **Test arity drift**: `review-task-design-test` currently hard-asserts
  `(= 6 (count steps))` and the exact 6-name/6-type vectors. Adding the pair
  makes it 8; the test *must* be updated in the same slice or the suite breaks.
  Mitigated by updating tests in the same vertical slice as the `.edn` rewiring.
- **md-refs list staleness**: `load-edn-with-md-refs` for `review-task-design.edn`
  lists the referenced `.md` files; the new `review-task-design-architecture-review.md`
  must be added or the loader test will not resolve it. Low risk, caught by the
  test run.
- **Start-step assumption**: the design relies on `initial-step-id` =
  `(first (effective-step-order definition))`. Verified in implementation.md
  against `statechart.clj`. If that invariant ever changed, entry would break —
  but it is out of scope and confirmed current.
- **Orphan-ref guard**: `review-task-prompt-artifact-targets-test` asserts no
  references to removed per-aspect follow-up files. Reusing the shared profile
  (not adding a new follow-up) keeps this green; introducing a new follow-up file
  would be a design violation.
- **Skill discoverability**: the new skill must be discoverable by the review
  prompt's `skills:` frontmatter; name in SKILL.md frontmatter must match the
  `skills:` reference exactly.

## Slice order

Vertical slices, each independently committable, in dependency order:

1. **Slice 1 — Skill**: add `review-task-architecture/SKILL.md`. Self-contained;
   no other artifact depends on it being wired yet.
2. **Slice 2 — Review prompt**: add
   `review-task-design-architecture-review.md` (loads the skill from slice 1,
   two-line PASS_STATUS menu).
3. **Slice 3 — Workflow rewiring**: prepend the architecture step pair to
   `review-task-design.edn`, add the `final-summary` contribution + prose, and
   update `workflow_definitions_test.clj` (+ md-refs list) in the same slice so
   the suite stays green. Run the workflow-definition tests.
4. **Slice 4 — Docs**: update `doc/workflows.md` to describe the
   architectural-fit aspect.

Slices 1→2→3 are ordered (3 depends on 1+2). Slice 4 depends on 3 being final.
