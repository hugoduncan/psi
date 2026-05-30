# Steps

## Slice 1 — Inspect current workflow and prompt topology

- [x] Read `.psi/workflows/review-task-design.edn` and identify the current ambiguity, inconsistency, clarity-status, and final-summary step transitions.
- [x] Read `.psi/workflows/review-task-plan.edn` and identify the current ambiguity, inconsistency, clarity-status, and final-summary step transitions.
- [x] Read the design-review review/follow-up prompts and confirm all follow-up instructions currently target `design-steps.md`.
- [x] Read the plan-review review/follow-up prompts and list every reference to `design-steps.md` or `steps.md`.
- [x] Inspect existing `workflow/pass-status-routing` tests and `review-step` workflow tests to identify the preferred test helper pattern.

## Slice 2 — Update design-review workflow routing

- [x] Modify `review-task-design.edn` so `ambiguity-review` routes `ACTIONABLE_FEEDBACK` to `ambiguity-follow-up` and `REVIEW_COMPLETE` to `inconsistency-review`.
- [x] Modify `review-task-design.edn` so `ambiguity-follow-up` continues to `inconsistency-review` after executing.
- [x] Modify `review-task-design.edn` so `inconsistency-review` routes `ACTIONABLE_FEEDBACK` to `inconsistency-follow-up` and `REVIEW_COMPLETE` to `clarity-status`.
- [x] Modify `review-task-design.edn` so `inconsistency-follow-up` continues to `clarity-status` after executing.
- [x] Verify `clarity-status` still routes `REPEAT` to `ambiguity-review` and `DONE` to `final-summary`.

## Slice 3 — Update plan-review workflow routing

- [x] Modify `review-task-plan.edn` so `ambiguity-review` routes `ACTIONABLE_FEEDBACK` to `ambiguity-follow-up` and `REVIEW_COMPLETE` to `inconsistency-review`.
- [x] Modify `review-task-plan.edn` so `ambiguity-follow-up` continues to `inconsistency-review` after executing.
- [x] Modify `review-task-plan.edn` so `inconsistency-review` routes `ACTIONABLE_FEEDBACK` to `inconsistency-follow-up` and `REVIEW_COMPLETE` to `clarity-status`.
- [x] Modify `review-task-plan.edn` so `inconsistency-follow-up` continues to `clarity-status` after executing.
- [x] Verify `clarity-status` still routes `REPEAT` to `ambiguity-review` and `DONE` to `final-summary`.

## Slice 4 — Correct prompt artifact targets

- [x] Update plan-review ambiguity review prompt text so new follow-up items are written to `steps.md`, not `design-steps.md`.
- [x] Update plan-review ambiguity follow-up prompt text so it reads and checks `steps.md`, not `design-steps.md`.
- [x] Update plan-review inconsistency review prompt text so new follow-up items are written to `steps.md`, not `design-steps.md`.
- [x] Update plan-review inconsistency follow-up prompt text so it reads and checks `steps.md`, not `design-steps.md`.
- [x] Confirm design-review ambiguity/inconsistency review prompts still instruct follow-ups via `design-steps.md`.
- [x] Confirm design-review ambiguity/inconsistency follow-up prompts still read and check `design-steps.md`.

## Slice 5 — Test design-review conditional follow-ups

- [x] Add or update a focused test where design ambiguity review returns `REVIEW_COMPLETE`, ambiguity follow-up is skipped, and inconsistency review still runs.
- [x] Add or update a focused test where design inconsistency review returns `REVIEW_COMPLETE`, inconsistency follow-up is skipped, and clarity-status still runs.
- [x] Add or update a focused test where design ambiguity review returns `ACTIONABLE_FEEDBACK` and only ambiguity follow-up runs before inconsistency review.
- [x] Add or update a focused test where design inconsistency review returns `ACTIONABLE_FEEDBACK` and inconsistency follow-up runs before clarity-status.

## Slice 6 — Test plan-review conditional follow-ups and artifact targets

- [x] Add or update a focused test where plan ambiguity review returns `REVIEW_COMPLETE`, ambiguity follow-up is skipped, and inconsistency review still runs.
- [x] Add or update a focused test where plan inconsistency review returns `REVIEW_COMPLETE`, inconsistency follow-up is skipped, and clarity-status still runs.
- [x] Add or update a focused test where plan ambiguity review returns `ACTIONABLE_FEEDBACK` and only ambiguity follow-up runs before inconsistency review.
- [x] Add or update a focused test where plan inconsistency review returns `ACTIONABLE_FEEDBACK` and inconsistency follow-up runs before clarity-status.
- [x] Add or update a focused prompt/definition assertion that plan-review prompts reference `steps.md` and do not reference `design-steps.md`.
- [x] Add or update a focused prompt/definition assertion that design-review prompts reference `design-steps.md`.

## Slice 7 — Verify

- [x] Run the focused workflow definition/runtime tests covering `review-task-design`, `review-task-plan`, and `workflow/pass-status-routing`.
- [x] Run targeted `clj-kondo` on changed Clojure test/source paths, if any Clojure files changed.
- [x] Re-read changed workflow EDN and prompt files to verify topology and artifact wording match the design.

## Slice 8 — Record implementation notes

- [x] Append implementation decisions, test commands, and any discovered trade-offs to `implementation.md`.
- [x] Check `git status --short` and ensure only intended workflow, prompt, test, and Munera task files changed.

## Test-shaper follow-up

- [x] Strengthen `review-task-design` and `review-task-plan` workflow definition tests to assert each per-reviewer `workflow/pass-status-routing` judge sources `:text` from that same step's own `:final-llm-reply` output, not just the operation id and route table.
- [x] Strengthen `review-task-design` and `review-task-plan` workflow definition tests to assert each follow-up step uses the full `workflow/constant-routing` judge shape with `:args {:route "DONE"}`, not just the operation id and `:on` route table.
- [x] Strengthen `review-task-design` and `review-task-plan` workflow definition tests to assert the full actual `clarity-status` `:on` map: `REPEAT` goes to `ambiguity-review` with `:max-iterations 6`, and `DONE` goes to `final-summary`.

## Docs review follow-up

- [x] Update CHANGELOG.md [Unreleased] to mention the user-visible `review-task-design` and `review-task-plan` conditional per-reviewer follow-up behavior, including that plan-review follow-ups now target `steps.md` instead of `design-steps.md`.
- [x] Update doc/workflow-ir.md so its invoke-judge runtime support note reflects the current executed runtime support used by the review workflows, rather than saying invoke judges are only a documented future shape.
