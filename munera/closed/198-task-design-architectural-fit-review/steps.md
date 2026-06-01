# 198 — Steps: task-design architectural-fit review

## Slice 1 — Review skill (AC1)

- [x] Create `.psi/skills/review-task-architecture/SKILL.md` with frontmatter
      `name: review-task-architecture`, a `description`, and a `lambda` line,
      modelled on `.psi/skills/review-task-docs/SKILL.md`.
- [x] Write a minimal body: frame the architectural-fit lens ("check the task
      design's fit with the current architecture") and tell the reviewing agent
      to consult the in-context architecture sources (AGENTS.md, META.md,
      doc/architecture.md) as needed. No duplicated principle list, no elaborate
      checklist.
- [x] Verify the skill is discoverable (name in frontmatter matches the path
      segment `review-task-architecture`).
- [x] Commit Slice 1.

## Slice 2 — Architecture review prompt (AC2a)

- [x] Create `.psi/workflows/review-task-design-architecture-review.md` modelled
      on `review-task-design-ambiguity-review.md`: frontmatter `tools`
      (`read/bash/edit/write`) and `skills` (`work-independently`,
      `review-task-architecture`).
- [x] Body: review the task design for architectural fit (not ambiguity); then
      (1) append a terse review note to implementation.md, (2) add unchecked
      `design-steps.md` items for every new actionable architectural misfit
      (create design-steps.md if absent), (3) avoid duplicates, (4) commit,
      (5) state explicitly when there is no new actionable feedback.
- [x] End the prompt with the **two-line** PASS_STATUS menu: "End your final
      response with exactly one of:" / `PASS_STATUS: ACTIONABLE_FEEDBACK` /
      `PASS_STATUS: REVIEW_COMPLETE` (matching the existing `*-review.md`
      convention; not a single `A | B` line).
- [x] Confirm `{{input}}` is wired into the prompt body (matches existing
      review prompts).
- [x] Commit Slice 2.

## Slice 3 — Workflow rewiring + tests (AC2, AC3, AC5)

- [x] In `.psi/workflows/review-task-design.edn`, prepend an
      `architecture-review` step as the **first** `:steps` element: `:session`,
      `:prompt-workflow "review-task-design-architecture-review.md"`, judge
      `workflow/pass-status-routing` reading
      `{:from {:step "architecture-review" :output :final-llm-reply}}` with
      `:allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]`, and
      `:on {"REPEAT" {:goto "architecture-follow-up"} "DONE" {:goto "ambiguity-review"}}`.
- [x] Insert `architecture-follow-up` as the **second** `:steps` element:
      `:session`, `:prompt-workflow "review-follow-up-design.md"` (shared
      profile), judge `workflow/constant-routing {:route "DONE"}`, and
      `:on {"DONE" {:goto "ambiguity-review"}}`.
- [x] Add `{:type :source :from {:step "architecture-review" :yield :text}}` to
      `final-summary`'s `:contributions` (alongside ambiguity/inconsistency
      yields).
- [x] Update the `final-summary` template prose to mention the architectural-fit
      pass alongside ambiguity/inconsistency (both in the `.edn` inline template
      and in `.psi/workflows/review-task-design-final-summary.md`, keeping them
      in sync).
- [x] Verify the existing ambiguity → inconsistency → `clarity-status` →
      `final-summary` flow and the implicit `clarity-status → final-summary`
      positional fall-through are unchanged.
- [x] Add `"review-task-design-architecture-review.md"` to the md-refs list in
      `review-task-design-test` (`load-edn-with-md-refs`).
- [x] Update `review-task-design-test`: change `(= 6 (count steps))` to `8`;
      update the expected name vector to start with `"architecture-review"
      "architecture-follow-up"` and the type vector accordingly
      (`[:session :session :session :session :session :session :invoke :session]`).
- [x] Add assertions for the architecture pair mirroring the existing
      ambiguity/inconsistency assertions: `architecture-review` judge =
      `pass-status-judge-from-step "architecture-review" [...]` and
      `:on {"REPEAT" {:goto "architecture-follow-up"} "DONE" {:goto "ambiguity-review"}}`;
      `architecture-follow-up` judge = `constant-routing-judge "DONE"` and
      `:on {"DONE" {:goto "ambiguity-review"}}`; `architecture-follow-up` shares
      the design-profile body (same `design-steps.md` / `Do not touch
      plan.md or steps.md` / predate-guard assertions as the other follow-ups).
- [x] Add an assertion that `final-summary` `:contributions` includes the
      `architecture-review` `:yield :text` source.
- [x] Run `bb` workflow-definition tests (or `clojure -M:test` for the
      workflow-loader component) and confirm green, including
      `review-task-prompt-artifact-targets-test` (orphan-ref + design-steps.md
      target guards).
- [x] Run `clj-kondo --lint` on the changed `.clj` test file and
      `clj-paren-repair` on the edited `.edn`.
- [x] Commit Slice 3.

## Slice 4 — Docs (AC6)

- [x] Update `doc/workflows.md` to describe the new architectural-fit review
      aspect in `review-task-design` (it runs first, loads
      `review-task-architecture`, reuses the shared `design`-profile follow-up).
- [x] Verify no other review-workflow reference doc needs the new aspect; update
      if found.
- [x] Re-read changed docs for accuracy/consistency (correct names, file paths,
      step order).
- [x] Commit Slice 4.

## Closeout

- [x] Confirm all acceptance criteria (AC1–AC6) are satisfied.
- [x] Append a terse closeout note to implementation.md.
- [x] Move task open/ → closed/ and update munera/plan.md (per munera protocol).

## Implementation review follow-up (2026-06-01)

- [x] R1: Reconcile `review-task-architecture/SKILL.md` with AC1 /
      Resolved-decision 4's "No duplicated principle list, no elaborate
      checklist". Resolved by trimming the enumeration (chose AC1-literal over
      weakening the criterion): the frontmatter `lambda` now reads
      `review(design_architectural_fit) ∧ judge(fit, ¬correctness ∧ ¬clarity) ∧
      consult(in_context_architecture_sources)` (no enumerated `check(...)` list),
      and the body prose drops the example principle enumeration, pointing instead
      to the in-context architecture sources for the principles/boundaries to judge
      fit against. Tests rerun green (9 tests, 148 assertions, 0 failures).

## Test review follow-up (2026-06-01)

- [x] TR1: Add a regression guard for the AC2a prompt contract on
      `review-task-design-architecture-review.md`. The two-line PASS_STATUS menu
      ("End your final response with exactly one of:" / `PASS_STATUS:
      ACTIONABLE_FEEDBACK` / `PASS_STATUS: REVIEW_COMPLETE`) and the loading of the
      `review-task-architecture` skill (not `task-design`) are both unguarded by
      tests, despite I1 flagging the menu convention as contradiction-prone.
      Extend `review-task-prompt-artifact-targets-test` (or
      `review-task-design-test`) with a `slurp-workflow-file` + `.contains`
      assertion — following the existing `design-steps.md` ownership guard — that
      the architecture-review prompt content includes the two-line PASS_STATUS
      menu and references `review-task-architecture` in its `skills:` frontmatter.
      Run the workflow-loader suite to confirm green.

## Test-shaper review follow-up (2026-06-01)

- [x] SH1: Strengthen the TR1 AC2a guard in
      `review-task-prompt-artifact-targets-test`
      (`workflow_definitions_test.clj`) so it enforces AC2a's *ends-with*
      contract, not mere presence. Currently `.contains` passes even if prose is
      appended after the two-line PASS_STATUS menu or the lead-in is separated
      from the status lines. Assert the menu lead-in
      ("End your final response with exactly one of:") plus the two
      `PASS_STATUS:` lines form a contiguous, terminal block (e.g. via a regex
      anchored to end-of-string after trimming trailing whitespace). Re-run the
      workflow-loader suite to confirm green.
- [x] SH2: Relocate the AC2a menu+skill guard out of
      `review-task-prompt-artifact-targets-test` (scoped by docstring/siblings to
      artifact ownership: design-steps.md vs steps.md) into its own clearly-named
      `deftest` (e.g. `architecture-review-prompt-contract-test`) so a menu/skill
      regression fails under a test name that describes the violated AC2a
      contract rather than "artifact targets". Re-run the suite.

## Docs review follow-up (2026-06-01)

- [x] D1: Add a `CHANGELOG.md [Unreleased]` entry for the new architectural-fit
      `review-task-design` review aspect (user-visible behaviour /
      extension-capability change to `/delegate review-task-design`: a third
      review aspect — architectural fit — that now runs first, before ambiguity
      and inconsistency). Place under `### Added` (or `### Changed`) following the
      keep-a-changelog + AGENTS.md changelog policy. Do not rewrite the released
      `[0.1.2166]` entry.
- [x] D2: Update the `review-task-design.edn` `:description` (currently
      "Repeatedly review a Munera task design for ambiguities and inconsistencies
      …") to also name the architectural-fit aspect, matching the implemented
      three-aspect behaviour. The description is surfaced verbatim in the
      `delegate` workflow capability listing, so it must stay consistent with the
      implementation (review-task-docs checklist items 1 & 5). Re-run the
      workflow-loader suite after editing to confirm green.

## Code-shaper review follow-up (2026-06-01)

- [x] CS1: Make `review-task-architecture/SKILL.md`'s body `λtask.` line
      consistent with its frontmatter `lambda`. The R1 follow-up appended `∧
      consult(in_context_architecture_sources)` to the frontmatter lambda but
      left the body lambda line as `review(design_architectural_fit) ∧
      judge(fit, ¬correctness ∧ ¬clarity)`, so the two copies of the file's
      defining lambda diverge by one conjunct (consistency violation;
      sibling `review-task-docs/SKILL.md` repeats its frontmatter lambda
      verbatim). Append `∧ consult(in_context_architecture_sources)` to the body
      `λtask.` line so it matches the frontmatter verbatim.
