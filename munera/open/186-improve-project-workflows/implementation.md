## 2026-05-27 ambiguity follow-up resolution (design-steps pass 1)

Resolved all four design-steps items. Updated `design.md` with decisions:

1. **Structured output authoring path**: extend `compile-judge` to pass through `:outputs` (option a). IR already supports `judge :outputs`; this is the minimal authoring-path fix. Added as ordering step 7 in design.
2. **Schema shapes**: add `psi.workflow/judge-routing-result` (Malli `[:enum "REPEAT" "DONE"]`, JSON Schema `{:type "string" :enum ["REPEAT" "DONE"]}`) and `psi.workflow/pass-status-result` (Malli `[:map [:status [:enum "PASS" "FAIL"]] [:reason :string]]`) to `structured_output_schemas.clj`. Full spec shapes documented in design.
3. **`design-steps.md` lifecycle**: created on first write if absent, written only by `review-task-design`, never by plan/implementation review workflows. Added dedicated section to design.
4. **Loader test scope**: assert raw `:outputs` key at loader level only (option a). No `target_ir_compiler` or `workflow-ir/validate` invocation in loader tests. Updated testing approach section in design.

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
