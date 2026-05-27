## 2026-05-27 ambiguity follow-up resolution (design-steps pass 1)

Resolved all four design-steps items. Updated `design.md` with decisions:

1. **Structured output authoring path**: extend `compile-judge` to pass through `:outputs` (option a). IR already supports `judge :outputs`; this is the minimal authoring-path fix. Added as ordering step 7 in design.
2. **Schema shapes**: add `psi.workflow/judge-routing-result` (Malli `[:enum "REPEAT" "DONE"]`, JSON Schema `{:type "string" :enum ["REPEAT" "DONE"]}`) and `psi.workflow/pass-status-result` (Malli `[:map [:status [:enum "PASS" "FAIL"]] [:reason :string]]`) to `structured_output_schemas.clj`. Full spec shapes documented in design.
3. **`design-steps.md` lifecycle**: created on first write if absent, written only by `review-task-design`, never by plan/implementation review workflows. Added dedicated section to design.
4. **Loader test scope**: assert raw `:outputs` key at loader level only (option a). No `target_ir_compiler` or `workflow-ir/validate` invocation in loader tests. Updated testing approach section in design.

## 2026-05-27 ambiguity review pass 1

Reviewed `design.md` plus referenced workflow files (`.psi/workflows/review-task-until-clear.edn`, `review-implementation.edn`, `review-step.edn`, `implement-task.edn`, `review-design-turn.edn`, `review-implementation-in-worktree.edn`) and workflow runtime/loader components (`components/workflow-runtime/src/psi/workflow_runtime/target_ir_compiler.clj`, `ir.clj`, `structured_output.clj`, `structured_output_schemas.clj`, `components/workflow-loader/src/psi/workflow_loader/compiler.clj`, `core.clj`).

Found four actionable ambiguities. Added follow-up items to `design-steps.md`.

1. **Structured output authoring gap**: `compile-judge` in `target_ir_compiler.clj` does not handle `:outputs` on judge specs; the IR supports `judge :outputs` for structured output but the authoring-to-IR compiler does not pass it through. The design says "declare `:structured-output` schema on judge steps" but doesn't address whether this requires extending `compile-judge` or whether structured output should instead live on the session step's `:outputs`.

2. **JSON Schema vs Malli**: The design says use `{:type :string :enum ["REPEAT" "DONE"]}` JSON Schema shapes, but the IR `structured-output-spec-schema` requires both `:schema` (Malli) and optionally `:json-schema` (JSON object). The design doesn't specify the Malli schema shape or whether a new reusable schema id is needed (like `psi.workflow/judge-routing-result`).

3. **`design-steps.md` artifact status**: `review-task-design` writes follow-up items to `design-steps.md`, but this file is not a canonical Munera artifact (protocol lists `design.md`, `plan.md`, `steps.md`, `implementation.md`). The design doesn't specify who creates it, whether it is created on first write, or what happens when `review-task-design` runs on a task that has no `design-steps.md` yet.

4. **Workflow loader test scope for structured output**: The design says loader tests should "assert the schema is present and correct in the compiled step." The workflow-loader compiler (`compile-edn-workflow-file`) passes `:outputs` through as-is without IR validation — IR structured output validation runs in `target_ir_compiler` at runtime. The design doesn't clarify whether loader tests should also invoke `target_ir_compiler` or only assert on the EDN authoring form.
