# 186 improve project workflows

## Intent

Improve the set of project-workflow and review workflows to be more focused, more composable, better structured, and testable. This means:

- splitting review workflows by lifecycle phase (design, plan, implementation)
- adding a docs review skill and wiring it into the implementation review chain
- renaming workflows for clarity
- migrating inline prompt text to referenced `.md` prompt files
- adopting structured output (schemas) where the workflow consumes machine-readable step results
- establishing a testing approach for workflow definitions

## Problem

The current review and project workflows have several friction points:

1. **`review-task-until-clear` conflates design review with plan/steps review.** It reviews `design.md`, `plan.md`, and `steps.md` together. A new workflow is needed that reviews only `design.md` (before a plan exists), and the existing workflow should be scoped to plan/steps review after a design is stable.

2. **No `create-task-plan` workflow.** Given a stable `design.md`, creating `plan.md` and `steps.md` is a common step that currently has no dedicated workflow — it falls inside `implement-task` or is done ad-hoc.

3. **`review-implementation` has no docs review.** User-facing documentation changes are not reviewed as part of the implementation review chain. A `review-task-docs` skill and corresponding workflow step are needed.

4. **Naming is inconsistent.** `review-implementation` should be `review-task-implementation` to match naming conventions. `review-task-until-clear` should be `review-task-plan` to reflect its scoped focus.

5. **Inline prompt text in `.edn` workflows is hard to read and maintain.** Now that `.md` step prompts are supported (task 184), inline prompt strings in multi-step `.edn` workflows should be extracted to referenced `.md` files.

6. **Structured output is underused.** Workflows that consume machine-readable signals (PASS_STATUS, REPEAT/DONE) rely on free-text parsing. Where the workflow IR supports schemas, step outputs should be declared with structured output.

7. **No workflow testing approach.** There is no established pattern for testing that workflow `.edn` + `.md` definitions load, compile, and produce correct step shapes. This leaves workflow authoring errors invisible until runtime.

## Scope

### In scope

- Create `review-task-design` workflow: reviews only `design.md` for ambiguities and inconsistencies, loops until clear.
- Create `create-task-plan` workflow: given a stable `design.md`, creates `plan.md` and `steps.md`.
- Rename `review-task-until-clear` → `review-task-plan`: scope narrowed to `plan.md` and `steps.md` review (post-design).
- Rename `review-implementation` → `review-task-implementation`.
- Update `review-implementation-in-worktree` to call `review-task-implementation`.
- Add `review-task-docs` skill: reviews user-facing documentation for an implemented task.
- Add `review-task-docs` step to the `review-task-implementation` workflow chain.
- Extract inline prompt text from `.edn` workflows into referenced `.md` prompt files where the prompt is substantial and reusable.
- Adopt structured output schemas for step results where the workflow consumes a machine-readable signal (judge routing, PASS_STATUS).
- Define and implement a workflow testing approach: loader/compiler unit tests for the new `.edn`+`.md` workflow definitions.

### Out of scope

- Migrating all existing `.psi/workflows/*.md` multi-step workflows to `.edn` (deferred follow-on from task 184).
- Redesigning `implement-task` workflow.
- Changing workflow runtime semantics beyond what structured output adoption requires.
- Adding new review skills beyond `review-task-docs`.
- Changing `review-step` or `review-design-turn` internals.

## Desired outcome

After this task:

- `review-task-design` exists: reviews `design.md` only, loops until no actionable feedback.
- `create-task-plan` exists: creates `plan.md` and `steps.md` from a stable `design.md`.
- `review-task-plan` exists (renamed from `review-task-until-clear`): reviews `plan.md` and `steps.md` only.
- `review-task-implementation` exists (renamed from `review-implementation`): includes docs review step.
- `review-task-docs` skill exists and is wired into `review-task-implementation`.
- `review-implementation-in-worktree` delegates to `review-task-implementation`.
- Substantial inline prompts in `.edn` workflows are extracted to `.md` files.
- Structured output schemas are used where step results are machine-readable signals.
- Focused loader/compiler tests cover the new workflow definitions.

## Workflow descriptions

### `review-task-design`

Scope: `design.md` only (plus any referenced concepts/code needed to evaluate the design).

Loop: ambiguity-review → ambiguity-follow-up → inconsistency-review → inconsistency-follow-up → clarity-status → (REPEAT | DONE).

Follow-up items written to `design-steps.md` (created on first write if absent). Does not touch `plan.md` or `steps.md`.

Terminates when no new actionable ambiguity or inconsistency feedback remains.

### `create-task-plan`

Input: Munera task path with a stable, complete `design.md`.

Behavior: reads `design.md`, creates or updates `plan.md` (approach, decisions, risks) and `steps.md` (implementation checklist). Commits.

Single pass — not a loop. Returns a summary of what was created.

### `review-task-plan` (renamed from `review-task-until-clear`)

Scope: `plan.md` and `steps.md` (assumes `design.md` is already stable).

Same loop structure as current `review-task-until-clear` but constrained to plan/steps artifacts. Follow-up items written to `steps.md` rather than `design-steps.md`.

#### Concrete prompt changes required

The following prompt-string changes must be made when renaming and narrowing `review-task-until-clear` → `review-task-plan`:

1. **`ambiguity-review` step prompt**: Remove references to `design.md`. Change "review the task design, plan and steps for ambiguities" → "review the task plan and steps for ambiguities". Change "especially design.md, plan.md, steps.md" → "especially plan.md and steps.md". Remove any instruction to read `design.md`.

2. **`ambiguity-follow-up` step prompt**: Remove references to `design.md`. Change "add unchecked follow-up items to design-steps.md" → "add unchecked follow-up items to steps.md". Change "Read and update the task's design.md, plan.md, steps.md, and implementation.md" → "Read and update the task's plan.md, steps.md, and implementation.md". Remove any instruction to write `design-steps.md`.

3. **`inconsistency-review` step prompt**: Remove references to `design.md`. Change "review the task design, plan and steps for inconsistencies" → "review the task plan and steps for inconsistencies". Change "especially design.md, plan.md, steps.md" → "especially plan.md and steps.md". Remove any instruction to read `design.md`.

4. **`inconsistency-follow-up` step prompt**: Remove references to `design-steps.md`. Change "add unchecked follow-up items to design-steps.md" → "add unchecked follow-up items to steps.md". Change "Read and update the task's steps.md, implementation.md, design.md, and plan.md" → "Read and update the task's steps.md, implementation.md, and plan.md". Remove any instruction to write `design-steps.md`.

5. **`clarity-status` step prompt**: Remove references to `design.md`. Change "especially design.md, plan.md, steps.md" → "especially plan.md and steps.md".

6. **`:name` field**: Change `"review-task-until-clear"` → `"review-task-plan"`.

7. **`:description` field**: Update to reflect the narrowed scope, e.g. "Repeatedly review a Munera task plan and steps for ambiguities and inconsistencies, record terse notes, execute follow-up steps, and loop until no actionable feedback remains".

### `review-task-implementation` (renamed from `review-implementation`)

Chain: task-implementation-review → task-test-review → test-shaper → **review-task-docs** → code-shaper.

Each step delegates to `review-step`.

### `review-task-docs` skill

Reviews user-facing documentation changes for an implemented task:
- Are all new/changed behaviours reflected in `README.md` and `doc/`?
- Are removed behaviours cleaned up from docs?
- Is the changelog updated if required?
- Are examples accurate?

## Structured output adoption

Workflows that use judge steps with `REPEAT`/`DONE` or `PASS_STATUS: ...` signals are candidates for structured output schemas. The adoption should:

- declare a `:structured-output` schema on judge steps where the judge emits a fixed enum signal
- declare a `:structured-output` schema on actor steps where the result is consumed by a downstream step as machine-readable data (e.g. PASS_STATUS)
- not change workflow behavior, only make the output contract explicit and machine-verifiable

Affected workflows: `review-task-design`, `review-task-plan`, `review-task-implementation`, `review-step`, `implement-task`.

### Authoring path for judge step structured output

`compile-judge` in `target_ir_compiler.clj` does not currently pass through `:outputs` from judge specs. The IR already supports `judge :outputs` (validated in `ir.clj`). **Decision: extend `compile-judge` to pass through `:outputs` so structured output can be declared directly on judge steps in the authoring format.** This is the minimal change that aligns the authoring path with the IR capability.

### Schema shapes

Structured output specs in the IR require `:schema-id`, `:schema-version`, `:schema` (Malli), and optionally `:json-schema` (JSON object). For judge routing signals:

- Add a new reusable schema id `psi.workflow/judge-routing-result` (version 1) to `structured_output_schemas.clj`.
- Malli shape: `[:enum "REPEAT" "DONE"]`
- JSON Schema shape: `{:type "string" :enum ["REPEAT" "DONE"]}`
- Use `{:source :judge/structured-output :mode :structured :schema-id :psi.workflow/judge-routing-result :schema-version 1 :schema [:enum "REPEAT" "DONE"] :json-schema {:type "string" :enum ["REPEAT" "DONE"]}}` as the output spec on judge steps that emit routing signals.
- Actor steps emitting `PASS_STATUS` use `{:source :session/structured-output ...}` with an appropriate schema (e.g. `[:map [:status [:enum "PASS" "FAIL"]] [:reason :string]]`); a `psi.workflow/pass-status-result` schema id should be added alongside `judge-routing-result`.
- The existing `psi.workflow/judge-review-result` schema (a complex map with `:decision`, `:issues`, `:confidence`) is **retained as-is** and is not deprecated or superseded by the new ids. `judge-routing-result` is a distinct, narrower schema for binary `REPEAT`/`DONE` judge routing signals; `judge-review-result` is a richer schema for structured review output. They are additive — both coexist in `structured_output_schemas.clj`.

## `.md` prompt extraction

Steps with substantial inline prompt text (>10 lines) in `.edn` workflows should be extracted to referenced `.md` files using `:prompt-workflow`. Priority targets:

- `review-task-design` (new): author prompts directly as `.md` files from the start
- `review-task-plan` (renamed): extract existing inline prompts
- `review-step`: extract review and follow-up prompts
- `implement-task`: extract implement-pass and final-summary prompts

The extracted `.md` files live alongside the `.edn` workflow in `.psi/workflows/`.

## Workflow testing approach

The testing approach for workflow definitions is:

- **Loader/compiler unit tests**: load each `.edn` + `.md` workflow definition through `workflow-loader.core/load-workflow-definitions` and assert: no load errors, correct step count, correct step names, correct step types, correct prompt-workflow references resolve.
- **Step shape tests**: for workflows with judge steps, assert the compiled judge step has the expected `:on` routing keys.
- **Structured output tests**: for steps with `:structured-output`, assert the raw `:outputs` key is present and has the expected shape in the compiled EDN step (i.e. at the workflow-loader level, not the IR level). The workflow-loader compiler passes `:outputs` through as-is; IR structured output validation runs in `target_ir_compiler` at runtime. Loader tests do not invoke `target_ir_compiler` or `workflow-ir/validate`.
- Tests live in `components/workflow-loader/test/` using existing test infrastructure.

**Loading mechanism**: tests use temp-dir fixtures matching the existing `core_test.clj` pattern — write `.edn` and `.md` files to a temp directory, call `load-workflow-definitions` with the temp dir (using `with-redefs` to override `global-workflow-dirs` and `project-workflow-dir`). Tests do not depend on the real project root or any installed workflow files. Individual `.md` prompt files referenced by `:prompt-workflow` are written into the same temp dir alongside their `.edn` workflow file.

This does not require running workflows end-to-end; it validates the authoring artifacts compile correctly at the loader level.

## `design-steps.md` artifact lifecycle

`design-steps.md` is a design-phase-only artifact created and written exclusively by the `review-task-design` workflow. It is created on first write if absent — the workflow does not require it to pre-exist. It is never written by `review-task-plan`, `review-task-implementation`, or any other review workflow. Follow-up items from plan/steps review are written to `steps.md`; follow-up items from implementation review are written to `implementation.md`.

## Ordering rationale

Refactors before new features, to avoid doing the same work twice:

1. **Rename `review-implementation` → `review-task-implementation`** and update `review-implementation-in-worktree`. Pure rename, no behavior change. Do first so subsequent steps use the final name.
2. **Rename `review-task-until-clear` → `review-task-plan`**, narrow scope to `plan.md`/`steps.md`. Rename + scope change together.
3. **Extract inline prompts to `.md` files** for `review-task-plan`, `review-step`, `implement-task`. Do before creating new workflows so the extraction pattern is established.
4. **Add `review-task-docs` skill** and wire into `review-task-implementation` as a new `review-step` delegation.
5. **Create `review-task-design` workflow**, using `.md` prompt files from the start.
6. **Create `create-task-plan` workflow**, using `.md` prompt files from the start.
7. **Extend `compile-judge` to pass through `:outputs`** in `target_ir_compiler.clj`. Prerequisite for judge step structured output authoring. Add `psi.workflow/judge-routing-result` and `psi.workflow/pass-status-result` schema ids to `structured_output_schemas.clj`.
8. **Adopt structured output schemas** across affected workflows.
9. **Add workflow loader/compiler tests** for all new and renamed workflows.

## Acceptance criteria

1. `review-task-design` workflow exists, reviews `design.md` only, loops until clear, uses `.md` prompt files.
2. `create-task-plan` workflow exists, creates `plan.md` and `steps.md` from `design.md`, single pass.
3. `review-task-plan` workflow exists (renamed from `review-task-until-clear`), scoped to `plan.md`/`steps.md`.
4. `review-task-implementation` workflow exists (renamed from `review-implementation`), includes docs review step.
5. `review-implementation-in-worktree` delegates to `review-task-implementation`: both the `:target` reference (`"review-implementation"` → `"review-task-implementation"`) and the `:description` string (`"…via the review-implementation workflow"` → `"…via the review-task-implementation workflow"`) are updated. The `summary` step body is also updated to reflect 5 review passes and to include `review-task-docs` in the named list (`task-implementation-review, task-test-review, test-shaper, review-task-docs, code-shaper`).
6. `review-task-docs` skill exists with a clear review lambda.
7. Substantial inline prompts in `review-task-plan`, `review-step`, `implement-task` are extracted to `.md` files.
8. `compile-judge` passes through `:outputs`; `psi.workflow/judge-routing-result` and `psi.workflow/pass-status-result` schema ids exist in `structured_output_schemas.clj`.
9. Structured output schemas are declared on judge steps and machine-readable actor steps in affected workflows.
10. Loader/compiler tests pass for all new and renamed workflow definitions; structured output assertions check the raw `:outputs` key at the loader level only.
11. `bb test` is green after all changes.
