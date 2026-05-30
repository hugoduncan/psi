# 186 improve project workflows — Steps

## Slice 1 — Rename `review-implementation` → `review-task-implementation`

- [x] Audit all `.edn` workflow files for references to `"review-implementation"` (`:target`, `:description`, etc.)
- [x] Rename `.psi/workflows/review-implementation.edn` → `.psi/workflows/review-task-implementation.edn`
- [x] Update `:name` field inside the EDN to `"review-task-implementation"`
- [x] Update `:description` field to reflect the new name
- [x] Update `review-implementation-in-worktree.edn`: change `:target "review-implementation"` → `"review-task-implementation"` and update `:description` string
- [x] Verify workflow loads without error (loader smoke: `bb tasks` or focused loader test)
- [x] Commit: `⚒ rename review-implementation → review-task-implementation`

## Slice 2 — Rename `review-task-until-clear` → `review-task-plan`; narrow scope

- [x] Rename `.psi/workflows/review-task-until-clear.edn` → `.psi/workflows/review-task-plan.edn`
- [x] Update `:name` to `"review-task-plan"`
- [x] Update `:description` to "Repeatedly review a Munera task plan and steps for ambiguities and inconsistencies, record terse notes, execute follow-up steps, and loop until no actionable feedback remains"
- [x] `ambiguity-review` step prompt: remove `design.md` references; change scope to `plan.md` and `steps.md` only
- [x] `ambiguity-follow-up` step prompt: change `design-steps.md` → `steps.md`; remove `design.md` references
- [x] `inconsistency-review` step prompt: remove `design.md` references; change scope to `plan.md` and `steps.md` only
- [x] `inconsistency-follow-up` step prompt: change `design-steps.md` → `steps.md`; remove `design.md` references
- [x] `clarity-status` step prompt (actor + judge): remove `design.md` references; change scope to `plan.md` and `steps.md` only
- [x] Verify workflow loads without error
- [x] Commit: `⚒ rename review-task-until-clear → review-task-plan, narrow to plan/steps`

## Slice 3 — Extract inline prompts to `.md` files

- [x] **`review-task-plan`**: extract each of the 5 step prompts to `.md` files:
  - [x] `review-task-plan-ambiguity-review.md`
  - [x] `review-task-plan-ambiguity-follow-up.md`
  - [x] `review-task-plan-inconsistency-review.md`
  - [x] `review-task-plan-inconsistency-follow-up.md`
  - [x] `review-task-plan-clarity-status.md`
  - [x] Update `review-task-plan.edn` steps to use `:prompt-workflow` for each
- [x] **`review-step`**: extract the 2 step prompts to `.md` files:
  - [x] `review-step-review.md`
  - [x] `review-step-follow-up.md`
  - [x] Update `review-step.edn` steps to use `:prompt-workflow` for each
- [x] **`implement-task`**: extract the 2 step prompts to `.md` files:
  - [x] `implement-task-implement-pass.md`
  - [x] `implement-task-final-summary.md`
  - [x] Update `implement-task.edn` steps to use `:prompt-workflow` for each
- [x] Verify all three workflows load without error after extraction
- [x] Commit: `⚒ extract inline prompts to .md files (review-task-plan, review-step, implement-task)`

## Slice 4 — Add `review-task-docs` skill; wire into `review-task-implementation`

- [x] Create `.psi/skills/review-task-docs/SKILL.md` with frontmatter and review lambda
- [x] Add `review-task-docs` step to `review-task-implementation.edn` between `review-test-shape` and `review-code-shape`
  - Step delegates to `review-step` with `{:skill "review-task-docs"}`
  - Include prior step context sources
- [x] Update `review-implementation-in-worktree.edn` `summary` step body: change "four review passes" → "five review passes"; update named list to `task-implementation-review, task-test-review, test-shaper, review-task-docs, code-shaper`
- [x] Verify `review-task-implementation` loads without error
- [x] Commit: `⚒ add review-task-docs skill and wire into review-task-implementation`

## Slice 5 — Create `review-task-design` workflow

- [x] Create prompt `.md` files for each step:
  - [x] `review-task-design-ambiguity-review.md` — scope: `design.md` only; follow-ups to `design-steps.md`
  - [x] `review-task-design-ambiguity-follow-up.md` — execute `design-steps.md` ambiguity follow-ups
  - [x] `review-task-design-inconsistency-review.md` — scope: `design.md` only; follow-ups to `design-steps.md`
  - [x] `review-task-design-inconsistency-follow-up.md` — execute `design-steps.md` inconsistency follow-ups
  - [x] `review-task-design-clarity-status.md` — judge: inspect `design.md` and `design-steps.md` only
  - [x] `review-task-design-final-summary.md` — user-facing summary
- [x] Create `review-task-design.edn` with loop structure: ambiguity-review → ambiguity-follow-up → inconsistency-review → inconsistency-follow-up → clarity-status (REPEAT/DONE) → final-summary
  - All steps reference their `.md` prompt files via `:prompt-workflow`
  - `clarity-status` has judge step routing `REPEAT` → `ambiguity-review`, `DONE` → `final-summary`
- [x] Verify workflow loads without error
- [x] Commit: `⚒ create review-task-design workflow`

## Slice 6 — Create `create-task-plan` workflow

- [x] Create `create-task-plan-create-plan.md` prompt file — single step: read `design.md`, create `plan.md` and `steps.md`, commit, summarize
- [x] Create `create-task-plan.edn` as a single-step workflow referencing `create-task-plan-create-plan.md`
  - Tools: `read`, `bash`, `edit`, `write`
  - Skills: `work-independently`, `task-design`
- [x] Verify workflow loads without error
- [x] Commit: `⚒ create create-task-plan workflow`

## Slice 7 — Extend `compile-judge`; add schema ids

- [x] Locate `compile-judge` in `target_ir_compiler.clj` (or equivalent)
- [x] Extend `compile-judge` to pass through `:outputs` from judge specs (if present)
- [x] Add `psi.workflow/judge-routing-result` schema to `structured_output_schemas.clj`:
  - Malli: `[:enum "REPEAT" "DONE"]`
  - JSON Schema: `{:type "string" :enum ["REPEAT" "DONE"]}`
- [x] Add `psi.workflow/pass-status-result` schema to `structured_output_schemas.clj`:
  - Malli: `[:map [:status [:enum "PASS" "FAIL"]] [:reason :string]]`
  - JSON Schema: `{:type "object" :properties {:status {:type "string" :enum ["PASS" "FAIL"]} :reason {:type "string"}} :required ["status" "reason"]}`
- [x] Add focused test: judge step with `:outputs` compiles to IR with `:outputs` present
- [x] Run focused tests green
- [x] Commit: `⚒ compile-judge passes through :outputs; add judge-routing-result and pass-status-result schemas`

## Slice 8 — Adopt structured output schemas

- [x] `review-task-design` — add `:outputs` to `clarity-status` judge step (schema: `judge-routing-result`)
- [x] `review-task-plan` — add `:outputs` to `clarity-status` judge step (schema: `judge-routing-result`)
- [x] `review-step` — add `:outputs` to `review-status` judge step (schema: `judge-routing-result`)
- [x] `implement-task` — add `:outputs` to `implement-pass` judge step (schema: `judge-routing-result`)
- [x] `review-task-implementation` — evaluated: delegate steps do not emit machine-readable judge signals; no `pass-status-result` warranted
- [x] Verify all affected workflows load without error
- [x] Commit: `⚒ adopt structured output schemas on judge steps`

## Slice 9 — Loader/compiler tests

- [x] Identify or create test file in `components/workflow-loader/test/` for built-in workflow definitions
- [x] For each new/renamed workflow (`review-task-design`, `review-task-plan`, `review-task-implementation`, `create-task-plan`):
  - [x] Assert: loads without error
  - [x] Assert: correct step count
  - [x] Assert: correct step names
  - [x] Assert: correct step types
  - [x] Assert: `:prompt-workflow` references resolve (for `.md`-backed steps)
- [x] For workflows with judge steps: assert compiled judge step has expected `:on` routing keys
- [x] For workflows with `:outputs` on judge steps: assert raw `:outputs` key is present in compiled EDN step
- [x] Run `bb test` (or focused loader tests) green
- [x] Commit: `⚒ add loader/compiler tests for new and renamed workflows`

## Follow-up — task-test-review pass

- [x] Add `reusable-pass-status-result-schema-test` to `structured_output_test.clj`: use `output-result` with `pass-status-result-schema-id/version/schema` and representative JSON `{"status":"PASS","reason":"all checks green"}` → assert `:valid`, `:status :PASS`, `:reason "all checks green"`. Also assert invalid input (missing `:reason`) → `:invalid`. Run `bb test` green.
- [x] Add loader test for `review-implementation-in-worktree` to `workflow_definitions_test.clj`: loads without error, contains definition `"review-implementation-in-worktree"`, has a delegate step with `:target "review-task-implementation"`, and the summary step body contains `"review-task-docs"`. Run `bb test` green.

## Final verification

- [x] `bb test` green (3 pre-existing skill-discovery failures, no new failures)
- [x] `bb lint` clean (0 errors, 0 warnings)
- [x] All acceptance criteria in `design.md` checked off

## Follow-up — task-implementation-review pass

- [x] Fix `{{input}}` unsubstituted in `:prompt-workflow` steps: `review-task-design`, `review-task-plan` actor steps, and `create-task-plan` all compile to `:vars {}` so `{{input}}` is rendered literally at runtime. Fix by switching these steps from `:prompt-workflow` to inline `:contributions` with `:vars {"input" {:from :workflow-input :path [:input]}}` (same fix as `review-step` commit `7d6b848e`), or extend the workflow-loader compiler to support a `:vars` override alongside `:prompt-workflow`.
- [x] Remove orphaned `.md` files `review-step-review.md` and `review-step-follow-up.md` — extracted in slice 3 but abandoned when `review-step.edn` was fixed to use inline contributions; now unreferenced dead artifacts.
- [x] Add loader test assertion that `{{input}}`-bearing steps have `:vars` wired to `:workflow-input` (not `:vars {}`) to prevent silent regression.

## Follow-up — task-implementation-review pass 4

- [x] Add CHANGELOG `[Unreleased]` entries for user-visible changes: new workflows `review-task-design` (Added) and `create-task-plan` (Added); renamed workflows `review-task-implementation` (Changed, from `review-implementation`) and `review-task-plan` (Changed, from `review-task-until-clear`); new `review-task-docs` step in `review-task-implementation` chain (Changed).

## Follow-up — task-implementation-review pass 3

- [x] Add loader tests for `review-step` in `workflow_definitions_test.clj` covering the post-structural-fix shape: loads without error, 2 steps (`review`, `follow-up`), correct step types (`:session`, `:session`), judge on `follow-up` has REPEAT/DONE `:on` routing, judge `:outputs` has `judge-routing-result` schema-id. Run focused loader tests green and commit.

## Follow-up — task-implementation-review pass 11

- [x] Fix `execute-judge-missing-turn-result-structured-output-fails-test` in `workflow_judge_test.clj`: commit `d1a81113` changed `parse-json-value` to plain-text fallback (always `{:ok? true}`) and changed `workflow_judge.clj` to use `output-result` instead of `missing-ai-structured-output-result`. The test still asserts the old "fail if no structured-output metadata" contract. Update the test to assert the new contract: when turn result has no `:structured-output` metadata but assistant text is valid JSON matching the schema, the judge routes successfully (`:action :complete`). Run focused `workflow-judge-test` green, then `bb test` green.

## Follow-up — task-implementation-review pass 7

- [x] Fix `structured-output-envelope-invalid-json-test` name and comment: `"not json"` no longer triggers a parse error (plain-text fallback returns `{:ok? true}` with value `"not json"`); the test now exercises the malli validation-error path, not a parse-error path. Update the test docstring/comment to reflect this. Verify `bb test` still green.
- [x] Address dead `{:ok? false}` branch in `structured-output-envelope` / `validation-input`: `parse-json-value` now always returns `{:ok? true}`, making the `ok? false` branch unreachable for the `raw-output` path. Either remove the dead branch or add a comment that it is only reachable via the `:payload`-absent path in `ai-structured-output`.

## Follow-up — test-shaper review pass

- [x] Consolidate `workflow_definitions_test.clj` per-workflow `deftest` explosion: merge each workflow's 4–6 separate `deftest` forms (loads, step-count, step-names-and-types, input-vars-wired, judge-routing, judge-outputs) into one `deftest` per workflow with `testing` blocks, one `load-edn-only` call per workflow. Preserves all assertions, eliminates 28+ redundant fixture setups. Run `bb test` green.
- [x] Fix `review-task-implementation` fixture inconsistency in `workflow_definitions_test.clj`: replace the inline `with-workflow-dir` call with `load-edn-only` to match the pattern used by all other workflows.
- [x] Fix `reusable-pass-status-result-schema-test` in `structured_output_test.clj`: change `:source :judge/structured-output` → `:source :session/structured-output` to match the design-specified usage of `pass-status-result` as an actor-step schema. Run `bb test` green.

## Follow-up — code-shaper review pass

- [x] Remove dead `:prompt` field from `implement-task.edn` judge: `compile-judge` in `target_ir_compiler.clj` silently drops `:prompt` (not in the `select-keys` list). The field has no runtime effect. Remove it from the judge map in `implement-task.edn`. Verify `bb test` green and `bb lint` clean.
