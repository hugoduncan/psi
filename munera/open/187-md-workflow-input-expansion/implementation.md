## 2026-05-28 ambiguity follow-up pass 1

Executed all five design-steps from ambiguity review pass 1. All resolved in design.md.

1. **`vars:` parsing → EDN string**: chose option (b). `vars:` value is a scalar string
   read via `clojure.edn/read-string`. No changes to `parse-yaml-frontmatter` required.
   Design updated with EDN syntax example and parser rationale.

2. **Implementation step 4 corrected**: design now states `compile-prompt-workflow-step`
   must pass `(:vars referenced)` to `markdown-body->contribution`, and that
   `markdown-body->contribution` must accept an optional vars argument.

3. **`{{original}}` → `:workflow-original`**: changed from `:workflow-input :path [:original]`
   to `{:from :workflow-original}` for consistency with `.edn` usage and to preserve the
   `resolve-source-ref` fallback (avoids `nil` when `:original` absent from workflow-input).
   Rationale documented in design.

4. **Test update noted**: acceptance criterion 7 added — existing
   `compiler_target_authoring_test.clj` `:framing-prompt` assertion must be updated to
   assert absence. Also added to the scope's test list.

5. **Wiring gap targets enumerated**: scope now explicitly lists the four `.edn` files and
   their step counts. `review-step.edn` exclusion noted. Desired outcome updated to name
   the four files.

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
