## 2026-05-27 implementation pass — all 9 slices complete

All 9 ordered slices executed. Key deviations from initial design:

1. **`:prompt-workflow` cannot combine with `:contributions`** — compiler rejects dual prompt sources. Steps that needed prior step context sources (e.g. `ambiguity-follow-up`) had their prompts updated to instruct the agent to read task files independently rather than relying on preloaded context. The `final-summary` steps that need prior step outputs kept inline contributions.

2. **`create-task-plan.md` naming collision** — naming the step prompt file `create-task-plan.md` caused a mixed-kind name collision (both `.md` and `.edn` define `create-task-plan`). Renamed to `create-task-plan-create-plan.md` following the `<workflow>-<step>.md` convention.

3. **`review-task-implementation` structured output** — delegate steps don't emit machine-readable judge signals so no `pass-status-result` schema was warranted. Evaluation was done and documented.

4. **Test isolation** — `review-task-plan` final-summary step uses inline contributions (not `:prompt-workflow`), so it doesn't require a `.md` file in the temp dir test fixture.

`bb test` green, `bb lint` clean.

## 2026-05-27 inconsistency follow-up pass 2

Executed the one newly added design-step from inconsistency review pass 2:

- **Removed 5 spurious "Update AGENTS.md" steps from steps.md**: slices 1, 2, 4, 5, and 6 each had an "Update AGENTS.md workflow/skills listing" item with no basis in design or plan. The workflow and skills listings are dynamically generated at runtime — no static AGENTS.md listing exists. Removed all 5 items; design-step marked done.

## 2026-05-27 inconsistency review pass 2

Reviewed design.md, plan.md, steps.md, design-steps.md, implementation.md, and referenced workflow files (review-implementation.edn, review-implementation-in-worktree.edn, review-task-until-clear.edn, review-step.edn, implement-task.edn), workflow-loader core/compiler source and tests, and structured_output_schemas.clj.

Found one actionable inconsistency. Added follow-up item to design-steps.md.

1. **`steps.md` has 5 "Update AGENTS.md workflow/skills listing" items with no basis in design or plan, and no corresponding target**: design.md and plan.md make no mention of updating AGENTS.md. The workflow listing shown in the agent system prompt is dynamically generated at runtime by the workflow-loader extension from the loaded `.psi/workflows/` definitions — there is no static listing in AGENTS.md to update. Similarly, the skills listing is dynamically generated from `.psi/skills/`. These 5 steps.md items (slices 1, 2, 4, 5, 6) are inconsistent with how the system works and will waste implementer time.

## 2026-05-27 ambiguity follow-up pass 2

Resolved both design-steps items from pass 2:

1. **`review-implementation-in-worktree` summary step stale**: Extended AC 5 in design.md to explicitly require the `summary` step body be updated to "five review passes" with the full named list including `review-task-docs`. Added corresponding steps.md item to slice 4.

2. **Loader test loading mechanism**: Corrected function name `load-workflows` → `load-workflow-definitions` in design testing approach section. Added explicit loading mechanism paragraph specifying temp-dir fixtures matching `core_test.clj` pattern (with-redefs, no real project root dependency, `.md` files written alongside `.edn` in temp dir).

Both design-steps items marked done.

## 2026-05-27 ambiguity review pass 2

Reviewed design.md, plan.md, steps.md, design-steps.md, implementation.md, and referenced workflow files (review-implementation.edn, review-implementation-in-worktree.edn, review-task-until-clear.edn, review-step.edn, implement-task.edn) plus workflow-loader compiler source and tests.

Found two new actionable ambiguities. Added follow-up items to design-steps.md.

1. **`review-implementation-in-worktree` summary step will be stale after review-task-docs addition**: The `summary` step prompt hard-codes "four review passes" and names them explicitly (`task-implementation-review, task-test-review, test-shaper, code-shaper`). After slice 4 inserts `review-task-docs`, the chain becomes 5 passes. Acceptance criterion 5 only covers the top-level `:target` and `:description` fields — the `summary` step body is not mentioned in design, plan, or steps.

2. **Loader test loading mechanism is unspecified**: The design says "load through `workflow-loader.core/load-workflows`" but the actual public function is `load-workflow-definitions`. More critically, the design does not specify whether tests call `load-workflow-definitions` with the real project root (environment-dependent), parse individual files via `compile-workflow-file`, or use temp-dir fixtures matching existing test patterns.

## 2026-05-27 ambiguity follow-up resolution (design-steps pass 1)

Resolved all four design-steps items. Updated `design.md` with decisions:

1. **Structured output authoring path**: extend `compile-judge` to pass through `:outputs` (option a). IR already supports `judge :outputs`; this is the minimal authoring-path fix. Added as ordering step 7 in design.
2. **Schema shapes**: add `psi.workflow/judge-routing-result` (Malli `[:enum "REPEAT" "DONE"]`, JSON Schema `{:type "string" :enum ["REPEAT" "DONE"]}`) and `psi.workflow/pass-status-result` (Malli `[:map [:status [:enum "PASS" "FAIL"]] [:reason :string]]`) to `structured_output_schemas.clj`. Full spec shapes documented in design.
3. **`design-steps.md` lifecycle**: created on first write if absent, written only by `review-task-design`, never by plan/implementation review workflows. Added dedicated section to design.
4. **Loader test scope**: assert raw `:outputs` key at loader level only (option a). No `target_ir_compiler` or `workflow-ir/validate` invocation in loader tests. Updated testing approach section in design.

## 2026-05-27 inconsistency follow-up pass 1

Resolved all four inconsistency design-steps items. Updated `design.md` with decisions:

1. **`judge-review-result` schema relationship**: retained as-is, additive alongside new `judge-routing-result` and `pass-status-result`. Distinct purpose — richer review output vs binary routing signal. Added explicit clarification paragraph to schema shapes section.

2. **`review-task-plan` prompt text changes**: added "Concrete prompt changes required" subsection to the `review-task-plan` workflow description in design. Enumerates all 7 changes needed across the 5 steps (`ambiguity-review`, `ambiguity-follow-up`, `inconsistency-review`, `inconsistency-follow-up`, `clarity-status`) plus `:name` and `:description` fields. Covers `design-steps.md` → `steps.md` redirection and `design.md` removal.

3. **Prompt extraction scope coverage**: the concrete prompt changes in item 2 above make the `design-steps.md` → `steps.md` redirection explicit for the extraction step. No separate design section needed since the per-step enumeration already specifies the narrowed content.

4. **`review-implementation-in-worktree` description**: updated acceptance criterion 5 to explicitly require both `:target` and `:description` string updates.

All four design-steps marked done.

## 2026-05-27 inconsistency review pass 1

Reviewed `design.md`, `design-steps.md`, and referenced workflow files (`review-task-until-clear.edn`, `review-implementation.edn`, `review-implementation-in-worktree.edn`, `review-step.edn`, `implement-task.edn`) and `structured_output_schemas.clj`.

Found four actionable inconsistencies. Added follow-up items to `design-steps.md`.

1. **Existing `judge-review-result` schema conflicts with planned schema ids**: `structured_output_schemas.clj` already defines `psi.workflow/judge-review-result` (a complex map schema). The design specifies adding `psi.workflow/judge-routing-result` and `psi.workflow/pass-status-result` but is silent on the existing schema. The relationship is unspecified — are they additive? Does `judge-review-result` overlap with the intended use of `judge-routing-result`?

2. **`review-task-until-clear.edn` writes follow-up items to `design-steps.md` — conflicts with lifecycle rule**: The design states `design-steps.md` is written exclusively by `review-task-design`. But the existing `review-task-until-clear.edn` (to be renamed `review-task-plan`) writes follow-up items to `design-steps.md` in its inconsistency-follow-up step. Renaming alone does not fix this; the prompt text must also be updated to target `steps.md`.

3. **Prompt text changes for `review-task-plan` scope narrowing are unspecified**: The design says rename `review-task-until-clear` → `review-task-plan` and "constrain to plan/steps artifacts." But the existing prompts in the five steps reference `design.md`, `plan.md`, and `steps.md` together. The design doesn't specify which prompt strings need changing to enforce the narrowed scope.

4. **`review-implementation-in-worktree` description string will be stale after rename**: The workflow description says "…via the review-implementation workflow." After renaming to `review-task-implementation`, this description will be stale. The design calls for updating the `:target` reference but doesn't mention updating the description string.

## 2026-05-27 ambiguity review pass 1

Reviewed `design.md` plus referenced workflow files (`.psi/workflows/review-task-until-clear.edn`, `review-implementation.edn`, `review-step.edn`, `implement-task.edn`, `review-design-turn.edn`, `review-implementation-in-worktree.edn`) and workflow runtime/loader components (`components/workflow-runtime/src/psi/workflow_runtime/target_ir_compiler.clj`, `ir.clj`, `structured_output.clj`, `structured_output_schemas.clj`, `components/workflow-loader/src/psi/workflow_loader/compiler.clj`, `core.clj`).

Found four actionable ambiguities. Added follow-up items to `design-steps.md`.

1. **Structured output authoring gap**: `compile-judge` in `target_ir_compiler.clj` does not handle `:outputs` on judge specs; the IR supports `judge :outputs` for structured output but the authoring-to-IR compiler does not pass it through. The design says "declare `:structured-output` schema on judge steps" but doesn't address whether this requires extending `compile-judge` or whether structured output should instead live on the session step's `:outputs`.

2. **JSON Schema vs Malli**: The design says use `{:type :string :enum ["REPEAT" "DONE"]}` JSON Schema shapes, but the IR `structured-output-spec-schema` requires both `:schema` (Malli) and optionally `:json-schema` (JSON object). The design doesn't specify the Malli schema shape or whether a new reusable schema id is needed (like `psi.workflow/judge-routing-result`).

3. **`design-steps.md` artifact status**: `review-task-design` writes follow-up items to `design-steps.md`, but this file is not a canonical Munera artifact (protocol lists `design.md`, `plan.md`, `steps.md`, `implementation.md`). The design doesn't specify who creates it, whether it is created on first write, or what happens when `review-task-design` runs on a task that has no `design-steps.md` yet.

4. **Workflow loader test scope for structured output**: The design says loader tests should "assert the schema is present and correct in the compiled step." The workflow-loader compiler (`compile-edn-workflow-file`) passes `:outputs` through as-is without IR validation — IR structured output validation runs in `target_ir_compiler` at runtime. The design doesn't clarify whether loader tests should also invoke `target_ir_compiler` or only assert on the EDN authoring form.
