## 2026-05-28 plan ambiguity follow-up pass 1

Executed both follow-up items from plan ambiguity review pass 1.

1. **Final-summary wiring decision**: chose option (a) — exclude final-summary steps from
   wiring in `implement-task.edn`, `review-task-plan.edn`, and `review-task-design.edn`.
   Rationale: these steps carry `:source` contributions with step-output yield refs
   (`{:from {:step "X" :yield :text}}`) which are out of scope for `.md` frontmatter vars.
   Wiring would silently drop them. The three `.md` final-summary files exist but are
   intentionally not referenced by their parent `.edn` workflows. `create-task-plan.edn`'s
   single step has no source contributions and is wired normally.
   Updated: `plan.md` (Key decisions + Risks), `steps.md` Slice 3 wiring instructions.

2. **Test fixture gap**: documented in `plan.md` Risks. Updated Slice 3 test step in
   `steps.md` to explicitly require updating (not just adding to) the four existing
   `deftest` blocks to use `with-workflow-dir` with both the `.edn` and all referenced
   `.md` files.

## 2026-05-28 plan ambiguity review pass 1

Reviewed `plan.md` and `steps.md` against `compiler.clj`, `workflow_definitions_test.clj`,
and the `.psi/workflows/` `.edn`/`.md` corpus. Found two actionable ambiguities.

1. **Final-summary steps have `:source` contributions that would be silently dropped by wiring.**
   `implement-task.edn`, `review-task-plan.edn`, and `review-task-design.edn` final-summary steps
   each carry `{:type :source :from :workflow-original}` and `{:type :source :from {:step "X" :yield :text}}`
   contributions alongside the template. `compile-prompt-workflow-step` replaces ALL `:contributions`
   with the `.md` body. The `.md` final-summary files (`implement-task-final-summary.md`,
   `review-task-plan-final-summary.md`, `review-task-design-final-summary.md`) contain only `{{input}}`
   — no source references. Wiring as planned silently drops the step-output context that these
   sessions depend on. The plan/steps are silent on this. Must decide: (a) exclude final-summary
   steps from wiring (keep inline, like review-step.edn), (b) express the sources in the `.md` files
   somehow, or (c) explicitly accept the behavior change. `create-task-plan.edn` is unaffected
   (its single step has only a template contribution).

2. **Existing `workflow_definitions_test.clj` tests use `load-edn-only` and will break after wiring.**
   All four affected workflows (`review-task-design`, `review-task-plan`, `implement-task`,
   `create-task-plan`) already have `deftest` blocks using `load-edn-only`, which copies only the
   `.edn` file to a temp dir. After wiring, the `.edn` steps reference `.md` files via
   `:prompt-workflow`; `compile-prompt-workflow-step` resolves them relative to the `.edn` path and
   returns an error if not found. The existing tests would fail immediately. `steps.md` says
   "add loader tests" but the existing tests must also be updated to use `with-workflow-dir` with
   both the `.edn` and all referenced `.md` files. This is not mentioned anywhere in the plan.

## 2026-05-28 ambiguity review pass 2

Reviewed design.md against compiler.clj, parser.clj, source_resolution.clj, and the .psi/workflows/ corpus.
Found three actionable ambiguities. Added follow-up items to design-steps.md.

1. **`vars:` valid `:from` values unspecified**: design says "supported `:from` values match the same
   source-spec grammar used in `.edn` :vars" but `.edn` :vars support step-output/step-yield refs
   (map `:from` values) which are explicitly out-of-scope for `.md` frontmatter vars. The design
   contradicts itself. Validation must restrict `:from` to keyword-only values
   (`:workflow-input`, `:workflow-original`, etc.) but the allowed set is never enumerated.

2. **`{{varname}}` token scanning pattern unspecified**: `markdown-body->contribution` is described
   as scanning for `{{varname}}` tokens but the character set valid in `varname` is never stated.
   This affects both auto-wiring and the unknown-var error detection regex/pattern.

3. **Unknown-var compile-time error propagation unspecified**: `compile-markdown-workflow-file`
   currently has no error-return path — it always returns `{:definition ...}`. If
   `markdown-body->contribution` needs to signal an unknown-var error, the design doesn't specify
   the mechanism (throw `ex-info`, return `{:error ...}`, etc.) or how callers handle it.
   The `:prompt-workflow` path already has `{:error ...}` handling; the standalone `.md` path does not.

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

## 2026-05-28 inconsistency follow-up pass 1

Executed all three design-steps from inconsistency review pass 1. All resolved in design.md.

1. **`review-task-plan.edn` step count → 6**: chose option (a). Created
   `.psi/workflows/review-task-plan-final-summary.md` (parallel to
   `review-task-design-final-summary.md`). Updated design scope step count from 5 to 6.
   The `.md` file is now part of the task 186 wiring corpus for `review-task-plan.edn`.

2. **`allowed-md-frontmatter-keys` added to implementation step 1**: updated the
   implementation path to explicitly name adding `:vars` to `allowed-md-frontmatter-keys`
   as a prerequisite sub-step within step 1.

3. **`parse-markdown-workflow-file` return shape specified**: implementation step 1 now
   explicitly documents the updated return shape:
   `{:workflow-kind :single-step-markdown :name string :description string
     :session-config map :body string :vars map-or-nil}`.

## 2026-05-28 inconsistency review pass 1

Reviewed `design.md` against `compiler.clj`, `parser.clj`,
`compiler_target_authoring_test.clj`, and the current `.psi/workflows/` corpus.
Found three actionable inconsistencies. Added follow-up items to `design-steps.md`.

1. **`review-task-plan.edn` step count wrong**: design scope says 5 steps
   (ambiguity-review, ambiguity-follow-up, inconsistency-review,
   inconsistency-follow-up, clarity-status), but the actual file has 6 steps
   including `final-summary`. No `review-task-plan-final-summary.md` exists.
   The desired outcome says "all task 186 extracted `.md` files are referenced"
   — yet `final-summary` in `review-task-plan.edn` has no `.md` counterpart and
   is unaddressed. Inconsistency between the step count, the `.md` corpus, and
   the desired outcome.

2. **`allowed-md-frontmatter-keys` not updated in implementation path**: the
   parser rejects unknown frontmatter keys via `unsupported-frontmatter-error`.
   Adding `vars:` support requires adding `:vars` to `allowed-md-frontmatter-keys`
   in `parser.clj`. Implementation step 1 says "read and validate the `vars:`
   frontmatter key" but omits this prerequisite change. Without it, any `.md`
   file using `vars:` will fail with an unsupported-key error before the new
   parsing logic is reached.

3. **Parsed result shape for `vars:` not specified**: implementation step 4
   references `(:vars referenced)` on the parsed result, implying `parse-markdown-workflow-file`
   must return a `:vars` key. The design never updates the documented return
   shape (`{:workflow-kind :name :description :session-config :body}`). This
   creates a gap between step 1 (parser) and step 4 (compiler): the compiler
   assumes a `:vars` key that the parser contract does not define.

## 2026-05-28 ambiguity follow-up pass 2

Executed all three design-steps from ambiguity review pass 2. All resolved in design.md.

1. **Valid `:from` values for `vars:` frontmatter**: restricted to `:workflow-input` and
   `:workflow-original` only. These are the only keyword `:from` values handled by
   `resolve-source-ref` / `apply-source-spec`. `:workflow-runtime` is handled only by
   `resolve-binding-ref` (a different code path) and is therefore invalid here. Step-output
   and step-yield map `:from` refs are already out of scope per the design. The `vars:`
   frontmatter section now states the exact allowed set and the rationale. Validation
   must reject any other `:from` value.

2. **`{{varname}}` token scanning pattern**: specified as `\{\{([a-zA-Z][a-zA-Z0-9_-]*)\}\}`.
   Leading letter required; subsequent chars may be letters, digits, underscores, or hyphens.
   Tokens not matching this pattern pass through literally and are not subject to unknown-var
   errors. Added to implementation path step 2a.

3. **Unknown-var error propagation**: chose option (a) — throw `ex-info` from
   `markdown-body->contribution`. `compile-workflow-file` already wraps all compilation in
   `(catch clojure.lang.ExceptionInfo e {:error (.getMessage e)})`, so no error-return path
   needs to be added to `compile-markdown-workflow-file`. Implementation path steps 2d and 3
   updated to document this mechanism explicitly.
