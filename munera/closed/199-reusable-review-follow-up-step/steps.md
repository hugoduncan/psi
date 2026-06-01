# Steps — 199 Unified review follow-up step

## Slice 1 — Author shared follow-up files

- [x] Create `.psi/workflows/review-follow-up-design.md` with frontmatter
      (`name: review-follow-up-design`, description, tools
      `read/bash/edit/write`, skill `work-independently`) and a body using only
      `{{input}}`.
- [x] In `review-follow-up-design.md` body, encode the `design` profile:
      identify unchecked items the **preceding review pass** added to
      `design-steps.md`; read/update `design.md`, `design-steps.md`,
      `implementation.md`; explicitly forbid touching `plan.md`/`steps.md`;
      include the predate-exclusion guard; mark done / record blockers; commit.
- [x] Create `.psi/workflows/review-follow-up-steps.md` with equivalent
      frontmatter (`name: review-follow-up-steps`) and `{{input}}`-only body.
- [x] In `review-follow-up-steps.md` body, encode the `steps` profile:
      identify unchecked items the **preceding review pass** added to
      `steps.md`; read/update `steps.md`, `plan.md`, `implementation.md`; treat
      `design.md` as read-only context (no write); include the
      predate-exclusion guard; mark done / record blockers; commit.
- [x] Confirm both files use generic "preceding review pass" wording (no named
      ambiguity/inconsistency step).

## Slice 2 — Rewire hosts and remove redundant prompts

- [x] Edit `.psi/workflows/review-task-design.edn`: set both
      `ambiguity-follow-up` and `inconsistency-follow-up` steps'
      `:prompt-workflow` to `"review-follow-up-design.md"`; leave `:judge`/`:on`
      routing unchanged.
- [x] Edit `.psi/workflows/review-task-plan.edn`: set both follow-up steps'
      `:prompt-workflow` to `"review-follow-up-steps.md"`; leave routing
      unchanged.
- [x] Edit `.psi/workflows/review-step.edn`: replace the inline `follow-up`
      step's template contribution with `:prompt-workflow
      "review-follow-up-steps.md"`, preserving the
      `constant-routing` judge, and the `REPEAT→review` `:on` (with
      `:max-iterations`). NOTE: the two `:source` contributions could **not**
      be preserved — the compiler forbids `:prompt-workflow` alongside
      `:contributions` (`prompt-source-conflict?`). Dropped; `steps.md` remains
      the authoritative item source. See implementation.md.
- [x] `git rm` the four obsolete files:
      `review-task-design-ambiguity-follow-up.md`,
      `review-task-design-inconsistency-follow-up.md`,
      `review-task-plan-ambiguity-follow-up.md`,
      `review-task-plan-inconsistency-follow-up.md`.
- [x] `grep -rn` the four old filenames across the repo (excluding git history)
      and confirm zero remaining references outside tests (tests handled in
      Slice 3).

## Slice 3 — Tests

- [x] Update `workflow_definitions_test.clj` `review-task-design-test`: replace
      old follow-up filename references with `review-follow-up-design.md`.
- [x] Update `review-task-plan-test`: replace old follow-up filename references
      with `review-follow-up-steps.md`.
- [x] Update the body-content/file-existence assertions (the `doseq` filename
      lists) to the two new shared filenames.
- [x] Add/extend coverage asserting `review-step`'s `follow-up` step references
      `review-follow-up-steps.md` and its loop wiring is unchanged.
- [x] Run the workflow-loader test suite; confirm all review workflows load and
      validate. (9 tests, 111 assertions, 0 failures.)

## Slice 4 — Docs

- [x] Add a review-workflow follow-up reference to `doc/workflows.md` describing
      the two shared profile follow-ups (`design` vs `steps`), which hosts use
      each profile, and that host routing/looping is unchanged.

## Slice 5 — Coherence and close

- [x] `clj-kondo --lint` any changed Clojure test files; lint clean.
- [x] Re-grep for all four old filenames repo-wide; confirm only git history
      retains them (only munera task notes reference them now).
- [x] Verify all acceptance criteria (1–7) are met against the final state.
      AC1: two profile files exist. AC2: design host → review-follow-up-design.md
      (both follow-ups); plan host + review-step → review-follow-up-steps.md.
      AC3: four per-aspect files removed; no orphans. AC4: profile bodies match
      the design table (design forbids plan/steps.md; steps profile clean of
      design-steps.md). AC5: routing/looping unchanged; all review workflows
      load/validate (tests green). AC6: workflow-definition tests updated/extended
      (14 tests, 173 assertions across compiler/core/definitions). AC7:
      doc/workflows.md "Shared review follow-up steps" section added. Also added
      an Unreleased CHANGELOG entry for the review-step predate-guard tightening.
- [x] Commit final state; close the task (`git mv` to `munera/closed/`, update
      `munera/plan.md`).

## Implementation review follow-up (2026-06-01)

- [x] R1: Broaden `review-follow-up-steps.md` so implementation-review follow-ups
      explicitly permit editing the code/tests/docs that follow-up items
      reference (e.g. "update the task's code, tests, docs, and task artifacts as
      needed"), restoring the old inline `review-step` template's broader
      "updating task artifacts as you work" scope. The current wording lists only
      `plan.md`/`steps.md` to update, which can mislead `review-step`
      (task-implementation-review) follow-ups into not editing real source files.
      Done: body now adds "When a follow-up item requires it, also update the
      code, tests, and docs the item references" and "updating the task's code,
      tests, docs, and task artifacts as you work".
- [x] R1: Update the `steps`-profile writable set in design.md and
      doc/workflows.md profile tables to reflect that, when hosting
      implementation review, the `steps` profile may write the referenced
      code/tests/docs (not just task files), so AC4's "behaviour preserved for
      implementation follow-ups" actually holds. Done: design.md profile table +
      new R1 note + AC4 broadened; doc/workflows.md profile table + note updated.
      Workflow-definitions suite green (9 tests, 111 assertions).

## Test review follow-up (2026-06-01)

- [x] T1: Add a workflow-definitions assertion that both shared follow-up step
      bodies contain the predate-exclusion guard text ("predate the preceding
      review pass"). Cover all three hosts' follow-up steps
      (`review-task-design` ambiguity/inconsistency follow-ups,
      `review-task-plan` follow-ups, `review-step` follow-up). Locks in the
      intentional `review-step` predate-guard behaviour change flagged in
      design.md so it cannot silently regress.
      Done: added predate-guard assertions to all three host follow-up body
      testing blocks (`review-task-design-test`, `review-task-plan-test`,
      `review-step-test`).
- [x] T2: Add an assertion that the `steps`-profile follow-up body
      (`review-follow-up-steps.md`, as wired into `review-task-plan` and
      `review-step` follow-up steps) permits editing referenced source — assert
      the "code, tests, and docs" clause is present. Prevents regressing the R1
      broadening (the exact AC4 implementation-follow-up scope the fix restored).
      Done: added "code, tests, and docs" assertions to `review-task-plan-test`
      and `review-step-test` follow-up body blocks.
- [x] T3: Add a regression guard for AC3 (no orphans): assert the four removed
      per-aspect follow-up filenames
      (`review-task-design-ambiguity-follow-up.md`,
      `review-task-design-inconsistency-follow-up.md`,
      `review-task-plan-ambiguity-follow-up.md`,
      `review-task-plan-inconsistency-follow-up.md`) are not referenced by the
      three rewired host `.edn`s (e.g. extend
      `review-task-prompt-artifact-targets-test` with an absent-filenames check).
      Done: extended `review-task-prompt-artifact-targets-test` with an
      absent-filenames check over the three rewired host `.edn`s.

## Test review follow-up — test-shaper (2026-06-01)

- [x] TS1: Remove or strengthen the dead positive assertion
      `(.contains text "steps.md")` in `review-task-plan-test` (~line 237) and
      `review-step-test` (~line 380). Because `"design-steps.md"` contains
      `"steps.md"`, the positive can never fail independently of the paired
      negative `(not (.contains text "design-steps.md"))` — it carries no signal
      and is redundant. Either drop the positive (the negative is the real
      discriminator) or replace it with a steps-profile-unique anchor that the
      design profile cannot satisfy (e.g. assert the steps-profile-only
      "read-only context" wording, or a plan.md/steps.md writable-set phrase),
      so a failure explains a genuine profile-contract violation.
      Done: replaced the dead `"steps.md"` positive in both
      `review-task-plan-test` and `review-step-test` with the
      steps-profile-unique anchor `"design.md as read-only context"`. The design
      profile *writes* design.md and never uses that clause (verified by grep),
      so the positive now discriminates the profiles and a failure signals a
      genuine non-steps profile wiring. Paired negative (`design-steps.md`)
      retained. Suite green (9 tests, 131 assertions, 0 failures); test ns lints
      clean.

## Test review follow-up — test-shaper second pass (2026-06-01)

- [x] TS2: Strengthen the dead positive `(.contains content "steps.md")` in
      `review-task-prompt-artifact-targets-test` (plan block). Because
      `"design-steps.md"` contains `"steps.md"`, the positive can never fail
      independently of the paired negative `(not (.contains content
      "design-steps.md"))`, so it carries no signal (the same dead-positive
      defect TS1 fixed in the deftest bodies but missed in this sibling test).
      Replace the substring positive with a standalone/word-boundary match that
      `design-steps.md` cannot satisfy (e.g. `(re-find #"(^|[^-])steps\.md"
      content)`), verified present in all three plan-family prompt files, so a
      failure signals a genuine missing `steps.md` target rather than passing
      trivially on `design-steps.md`. Run the focused suite and lint the test ns.
      Done: replaced the bare positive in the plan block with
      `(re-find #"(^|[^-])steps\.md" content)`; verified all three plan-family
      prompts (`review-task-plan-ambiguity-review.md`,
      `review-task-plan-inconsistency-review.md`, `review-follow-up-steps.md`)
      carry a standalone (space-preceded) `steps.md`, while `design-steps.md`
      cannot match the `[^-]` boundary. Focused suite green (9 tests, 131
      assertions, 0 failures — unchanged count, strengthened in place);
      `clj-kondo` on the test ns clean. Only the test file changed.

## Test review follow-up — test-shaper third pass (2026-06-01)

- [x] TS3: Add a negative assertion to `review-task-design-test`'s follow-up
      body block (the `doseq` over `[ambiguity-follow-up inconsistency-follow-up]`)
      that the design-profile body does **not** contain the steps-profile-only
      broadening clause `"code, tests, and docs"` (and optionally
      `"design.md as read-only context"`). The design profile (R1/A3) never edits
      real source and never treats design.md as read-only context; without a
      negative guard, wiring the steps-profile body into the design host — or
      broadening the design body to permit code/test/doc edits — would pass
      `review-task-design-test` silently. This makes the design-profile contract
      negative symmetric with the steps-profile tests' existing
      `(not (.contains text "design-steps.md"))` discriminator. Run the focused
      workflow-definitions suite and lint the test ns.
      Done: added two negative assertions to `review-task-design-test`'s
      design-profile body `doseq` — `(not (.contains text "code, tests, and
      docs"))` and `(not (.contains text "design.md as read-only context"))`.
      Verified the design profile body (`review-follow-up-design.md`) contains
      neither clause while the steps profile body carries them, so the negatives
      discriminate genuine profile wiring. Focused suite green (9 tests, 135
      assertions, 0 failures — +4 from TS2's 131); `clj-kondo` on the test ns
      clean. Only the test file changed.

## Code-shaper review follow-up (2026-06-01)

- [x] CS1: Make the two profile prompts' "read and update" clauses
      structurally parallel about the items file. `review-follow-up-steps.md`
      lists its items file in the writable clause ("Read and update the task's
      plan.md, steps.md, and implementation.md as needed"), but
      `review-follow-up-design.md` omits `design-steps.md` from the parallel
      clause ("Read and update the task's design.md and implementation.md as
      needed"). Both files write their items file (covered by the "mark it done
      in {items}.md" sentence), so this is behaviour-correct, but the asymmetry
      reintroduces the copy-divergence the task exists to eliminate. Fix: add
      `design-steps.md` to the design file's clause ("Read and update the task's
      design.md, design-steps.md, and implementation.md as needed") so the two
      shared-contract files are self-evidently consistent. No test change
      needed (the design-profile body assertions already reference
      `design-steps.md` and still hold).
      Done: edited `review-follow-up-design.md` writable clause to "Read and
      update the task's design.md, design-steps.md, and implementation.md as
      needed", making it structurally parallel to `review-follow-up-steps.md`.
      No test change needed — the design-profile body assertions
      (positive `design-steps.md`; negatives `code, tests, and docs` and
      `design.md as read-only context`) all still hold. Focused
      workflow-definitions suite green (9 tests, 135 assertions, 0 failures).
      Only the design prompt file changed.
