# 186 improve project workflows — Steps

## Slice 1 — Rename `review-implementation` → `review-task-implementation`

- [ ] Audit all `.edn` workflow files for references to `"review-implementation"` (`:target`, `:description`, etc.)
- [ ] Rename `.psi/workflows/review-implementation.edn` → `.psi/workflows/review-task-implementation.edn`
- [ ] Update `:name` field inside the EDN to `"review-task-implementation"`
- [ ] Update `:description` field to reflect the new name
- [ ] Update `review-implementation-in-worktree.edn`: change `:target "review-implementation"` → `"review-task-implementation"` and update `:description` string
- [ ] Update `AGENTS.md` workflow listing (`review-implementation` → `review-task-implementation`, `review-implementation-in-worktree` description)
- [ ] Verify workflow loads without error (loader smoke: `bb tasks` or focused loader test)
- [ ] Commit: `⚒ rename review-implementation → review-task-implementation`

## Slice 2 — Rename `review-task-until-clear` → `review-task-plan`; narrow scope

- [ ] Rename `.psi/workflows/review-task-until-clear.edn` → `.psi/workflows/review-task-plan.edn`
- [ ] Update `:name` to `"review-task-plan"`
- [ ] Update `:description` to "Repeatedly review a Munera task plan and steps for ambiguities and inconsistencies, record terse notes, execute follow-up steps, and loop until no actionable feedback remains"
- [ ] `ambiguity-review` step prompt: remove `design.md` references; change scope to `plan.md` and `steps.md` only
- [ ] `ambiguity-follow-up` step prompt: change `design-steps.md` → `steps.md`; remove `design.md` references
- [ ] `inconsistency-review` step prompt: remove `design.md` references; change scope to `plan.md` and `steps.md` only
- [ ] `inconsistency-follow-up` step prompt: change `design-steps.md` → `steps.md`; remove `design.md` references
- [ ] `clarity-status` step prompt (actor + judge): remove `design.md` references; change scope to `plan.md` and `steps.md` only
- [ ] Update `AGENTS.md` workflow listing (`review-task-until-clear` → `review-task-plan`)
- [ ] Verify workflow loads without error
- [ ] Commit: `⚒ rename review-task-until-clear → review-task-plan, narrow to plan/steps`

## Slice 3 — Extract inline prompts to `.md` files

- [ ] **`review-task-plan`**: extract each of the 5 step prompts to `.md` files:
  - [ ] `review-task-plan-ambiguity-review.md`
  - [ ] `review-task-plan-ambiguity-follow-up.md`
  - [ ] `review-task-plan-inconsistency-review.md`
  - [ ] `review-task-plan-inconsistency-follow-up.md`
  - [ ] `review-task-plan-clarity-status.md`
  - [ ] Update `review-task-plan.edn` steps to use `:prompt-workflow` for each
- [ ] **`review-step`**: extract the 2 step prompts to `.md` files:
  - [ ] `review-step-review.md`
  - [ ] `review-step-follow-up.md`
  - [ ] Update `review-step.edn` steps to use `:prompt-workflow` for each
- [ ] **`implement-task`**: extract the 2 step prompts to `.md` files:
  - [ ] `implement-task-implement-pass.md`
  - [ ] `implement-task-final-summary.md`
  - [ ] Update `implement-task.edn` steps to use `:prompt-workflow` for each
- [ ] Verify all three workflows load without error after extraction
- [ ] Commit: `⚒ extract inline prompts to .md files (review-task-plan, review-step, implement-task)`

## Slice 4 — Add `review-task-docs` skill; wire into `review-task-implementation`

- [ ] Create `.psi/skills/review-task-docs/SKILL.md` with frontmatter and review lambda
- [ ] Add `review-task-docs` step to `review-task-implementation.edn` between `review-test-shape` and `review-code-shape`
  - Step delegates to `review-step` with `{:skill "review-task-docs"}`
  - Include prior step context sources
- [ ] Update `AGENTS.md` skills listing to include `review-task-docs`
- [ ] Verify `review-task-implementation` loads without error
- [ ] Commit: `⚒ add review-task-docs skill and wire into review-task-implementation`

## Slice 5 — Create `review-task-design` workflow

- [ ] Create prompt `.md` files for each step:
  - [ ] `review-task-design-ambiguity-review.md` — scope: `design.md` only; follow-ups to `design-steps.md`
  - [ ] `review-task-design-ambiguity-follow-up.md` — execute `design-steps.md` ambiguity follow-ups
  - [ ] `review-task-design-inconsistency-review.md` — scope: `design.md` only; follow-ups to `design-steps.md`
  - [ ] `review-task-design-inconsistency-follow-up.md` — execute `design-steps.md` inconsistency follow-ups
  - [ ] `review-task-design-clarity-status.md` — judge: inspect `design.md` and `design-steps.md` only
  - [ ] `review-task-design-final-summary.md` — user-facing summary
- [ ] Create `review-task-design.edn` with loop structure: ambiguity-review → ambiguity-follow-up → inconsistency-review → inconsistency-follow-up → clarity-status (REPEAT/DONE) → final-summary
  - All steps reference their `.md` prompt files via `:prompt-workflow`
  - `clarity-status` has judge step routing `REPEAT` → `ambiguity-review`, `DONE` → `final-summary`
- [ ] Update `AGENTS.md` workflow listing to include `review-task-design`
- [ ] Verify workflow loads without error
- [ ] Commit: `⚒ create review-task-design workflow`

## Slice 6 — Create `create-task-plan` workflow

- [ ] Create `create-task-plan.md` prompt file — single step: read `design.md`, create `plan.md` and `steps.md`, commit, summarize
- [ ] Create `create-task-plan.edn` as a single-step workflow referencing `create-task-plan.md`
  - Tools: `read`, `bash`, `edit`, `write`
  - Skills: `work-independently`, `task-design`
- [ ] Update `AGENTS.md` workflow listing to include `create-task-plan`
- [ ] Verify workflow loads without error
- [ ] Commit: `⚒ create create-task-plan workflow`

## Slice 7 — Extend `compile-judge`; add schema ids

- [ ] Locate `compile-judge` in `target_ir_compiler.clj` (or equivalent)
- [ ] Extend `compile-judge` to pass through `:outputs` from judge specs (if present)
- [ ] Add `psi.workflow/judge-routing-result` schema to `structured_output_schemas.clj`:
  - Malli: `[:enum "REPEAT" "DONE"]`
  - JSON Schema: `{:type "string" :enum ["REPEAT" "DONE"]}`
- [ ] Add `psi.workflow/pass-status-result` schema to `structured_output_schemas.clj`:
  - Malli: `[:map [:status [:enum "PASS" "FAIL"]] [:reason :string]]`
  - JSON Schema: `{:type "object" :properties {:status {:type "string" :enum ["PASS" "FAIL"]} :reason {:type "string"}} :required ["status" "reason"]}`
- [ ] Add focused test: judge step with `:outputs` compiles to IR with `:outputs` present
- [ ] Run focused tests green
- [ ] Commit: `⚒ compile-judge passes through :outputs; add judge-routing-result and pass-status-result schemas`

## Slice 8 — Adopt structured output schemas

- [ ] `review-task-design` — add `:outputs` to `clarity-status` judge step (schema: `judge-routing-result`)
- [ ] `review-task-plan` — add `:outputs` to `clarity-status` judge step (schema: `judge-routing-result`)
- [ ] `review-step` — add `:outputs` to `review-status` judge step (schema: `judge-routing-result`)
- [ ] `implement-task` — add `:outputs` to `implement-pass` judge step (schema: `judge-routing-result`)
- [ ] `review-task-implementation` — evaluate whether any step emits a machine-readable signal warranting `pass-status-result`; add if applicable
- [ ] Verify all affected workflows load without error
- [ ] Commit: `⚒ adopt structured output schemas on judge steps`

## Slice 9 — Loader/compiler tests

- [ ] Identify or create test file in `components/workflow-loader/test/` for built-in workflow definitions
- [ ] For each new/renamed workflow (`review-task-design`, `review-task-plan`, `review-task-implementation`, `create-task-plan`):
  - [ ] Assert: loads without error
  - [ ] Assert: correct step count
  - [ ] Assert: correct step names
  - [ ] Assert: correct step types
  - [ ] Assert: `:prompt-workflow` references resolve (for `.md`-backed steps)
- [ ] For workflows with judge steps: assert compiled judge step has expected `:on` routing keys
- [ ] For workflows with `:outputs` on judge steps: assert raw `:outputs` key is present in compiled EDN step
- [ ] Run `bb test` (or focused loader tests) green
- [ ] Commit: `⚒ add loader/compiler tests for new and renamed workflows`

## Final verification

- [ ] `bb test` green
- [ ] `bb lint` clean (or pre-existing warnings only)
- [ ] All acceptance criteria in `design.md` checked off
