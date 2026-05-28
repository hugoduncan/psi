## 2026-05-28 ambiguity review pass 1

Reviewed `design.md` against `components/workflow-loader/src/psi/workflow_loader/compiler.clj`,
`parser.clj`, `components/prompt-assets/src/psi/prompt_assets/prompt_templates.clj`
(`parse-yaml-frontmatter`), `components/workflow-step-materialization/src/psi/workflow_step_materialization/source_resolution.clj`,
`components/workflow-step-session-config/src/psi/workflow_step_session_config/core.clj`,
`components/workflow-loader/test/psi/workflow_loader/compiler_target_authoring_test.clj`,
and the current `.psi/workflows/` `.edn`/`.md` files.

Found five actionable ambiguities. Added follow-up items to `design-steps.md`.

1. **`vars:` frontmatter YAML parsing is unspecified**: `parse-yaml-frontmatter` supports
   only scalars and block sequences — it cannot parse nested maps. The design's `vars:`
   example requires nested map syntax (`my-var:\n  from: workflow-input\n  path: [some-field]`).
   The design says "update the parser" but does not specify the parsing approach (extend
   the YAML parser, use EDN syntax for the value, inline-map syntax, etc.).

2. **Implementation step 4 is incorrect — `:prompt-workflow` path needs a change**:
   `compile-prompt-workflow-step` calls `(markdown-body->contribution (:body referenced))`.
   After step 1 adds `:vars` to the parsed result, `compile-prompt-workflow-step` must
   also pass `(:vars referenced)` to `markdown-body->contribution`. The design states
   "no additional change needed there" — this is wrong.

3. **`{{original}}` source spec choice is unexplained**: Design specifies `{{original}}` →
   `{:from :workflow-input :path [:original]}`. Existing `.edn` workflows use
   `{:from :workflow-original}` or `{:from :workflow-original :path [:original]}`.
   `resolve-source-ref` for `:workflow-original` has special fallback logic that diverges
   from `:workflow-input :path [:original]` when `:workflow-original` is explicitly set
   on the run. The design doesn't explain the choice or whether `:workflow-original`
   should be used instead.

4. **Existing test `compiler_target_authoring_test.clj` will break**: Line 19 asserts
   `(get-in definition [:workflow-file-meta :framing-prompt])` equals `"Frame it."`.
   Removing `:framing-prompt` from `compile-markdown-workflow-file` breaks this test.
   The design doesn't mention updating it.

5. **Task 186 wiring gap scope is unenumerated**: Design says "update all affected `.edn`
   workflows" without listing them. Based on orphaned `.md` files in `.psi/workflows/`:
   `review-task-plan.edn` (5 steps), `implement-task.edn` (2 steps),
   `review-task-design.edn` (6 steps), `create-task-plan.edn` (1 step).
   (`review-step.edn` was intentionally left inline per task 186 impl notes.)
