# Steps

## Slice 1 — Inspect current workflow consumers and test seams

- [ ] Search workflow definitions and tests for all `review-step` delegate targets/usages and record whether any non-implementation-review consumer would be harmed by changing the shared loop limit.
- [ ] Inspect existing workflow-definition tests for `review-step`, `review-task-design`, and `review-task-plan` and list assertions that encode the old limits or old topology.
- [ ] Inspect existing workflow runtime/review routing tests for reusable helpers that can exercise pass loops and iteration-limit failures without adding broad new harness code.

## Slice 2 — Implementation-review limit

- [ ] Update `.psi/workflows/review-step.edn` so `follow-up` routes back to `review` with `:max-iterations 10`.
- [ ] Update workflow-definition coverage that currently expects `:max-iterations 6` on the `review-step` loop to expect 10 total target-step entries.
- [ ] Add or update a focused runtime test proving the `review` step may be entered 10 total times and that an attempted 11th entry fails through the workflow iteration-limit path.
- [ ] Verify `review-task-implementation.edn` delegates still target the shared `review-step` workflow for all implementation/test/docs/shape review profiles.

## Slice 3 — Design-review full-pass loop

- [x] PA1: Decide what happens to the existing `.psi/workflows/review-task-design-clarity-status.md` and `.psi/workflows/review-task-plan-clarity-status.md` prompt files when pass completion is encoded by deterministic topology instead of artifact re-reading: either remove/stop referencing them, or update their wording/tests so no stale file instructs the old re-read-after-follow-up behavior.
- [ ] Rework `.psi/workflows/review-task-design.edn` so a pass always runs `architecture-review`, `ambiguity-review`, and `inconsistency-review` in that order.
- [ ] Preserve conditional follow-up behavior for each design phase: `ACTIONABLE_FEEDBACK` runs that phase's follow-up, while `REVIEW_COMPLETE` skips it.
- [ ] Encode explicit pass-level feedback memory so any actionable architecture, ambiguity, or inconsistency result in the completed pass routes to a restart decision after the inconsistency phase.
- [ ] Ensure the restart decision loops to `architecture-review` with `:max-iterations 6`, where the initial architecture entry is pass 1 and loop-backs can start passes 2 through 6.
- [ ] Ensure a clean completed design pass routes to `final-summary` without another pass.
- [ ] Keep final-summary context sources coherent with any renamed or added route/status steps.

## Slice 4 — Plan-review full-pass loop

- [ ] Rework `.psi/workflows/review-task-plan.edn` so a pass always runs `ambiguity-review` and `inconsistency-review` in that order.
- [ ] Preserve conditional follow-up behavior for each plan phase: `ACTIONABLE_FEEDBACK` runs that phase's follow-up, while `REVIEW_COMPLETE` skips it.
- [ ] Encode explicit pass-level feedback memory so any actionable ambiguity or inconsistency result in the completed pass routes to a restart decision after the inconsistency phase.
- [ ] Ensure the restart decision loops to `ambiguity-review` with `:max-iterations 5`, where the initial ambiguity entry is pass 1 and loop-backs can start passes 2 through 5.
- [ ] Ensure a clean completed plan pass routes to `final-summary` without another pass.
- [ ] Keep final-summary context sources coherent with any renamed or added route/status steps.

## Slice 5 — Runtime and definition coverage

- [ ] Update workflow-loader definition tests to assert the authored `review-step`, design-review, and plan-review `:max-iterations` values and loop-back targets.
- [ ] Add or update design-review runtime tests proving actionable feedback in an earlier phase still completes later phases before restarting at architecture review.
- [ ] Add or update design-review runtime tests proving pass-level feedback memory survives follow-up execution and causes restart even when follow-up files would be clear.
- [ ] Add or update design-review runtime tests proving pass 6 with actionable feedback fails on attempted pass 7 through the iteration-limit path.
- [ ] Add or update plan-review runtime tests proving actionable ambiguity feedback still completes inconsistency review before restarting at ambiguity review.
- [ ] Add or update plan-review runtime tests proving pass-level feedback memory survives follow-up execution and causes restart even when follow-up files would be clear.
- [ ] Add or update plan-review runtime tests proving pass 5 with actionable feedback fails on attempted pass 6 through the iteration-limit path.
- [ ] Keep tests behavior/state focused; avoid asserting incidental prompt wording except where content-locks already exist for workflow definitions.

## Slice 6 — Docs and changelog

- [ ] Search `README.md`, `doc/`, and `CHANGELOG.md` for review workflow repetition/limit descriptions.
- [ ] Update user-facing workflow docs if they describe old single-pass behavior, old one-follow-up-per-phase behavior, or old review-loop limits.
- [ ] Add a `[Unreleased]` `CHANGELOG.md` entry if the changed review-loop limits/repetition are user-visible.

## Slice 7 — Verification and coherence

- [ ] Run focused workflow-loader tests covering workflow definitions.
- [ ] Run focused workflow runtime/review routing tests covering the new loop behavior and iteration-limit failures.
- [ ] Run targeted `clj-kondo` on touched Clojure test/source files.
- [ ] Run `clj-paren-repair` or formatter on touched Clojure files if edits change Clojure structure/formatting.
- [ ] Re-read changed workflow definitions, tests, docs, `plan.md`, and `steps.md` to verify coherence with `design.md` acceptance criteria.
- [ ] Append implementation notes with verification results to `implementation.md`.
