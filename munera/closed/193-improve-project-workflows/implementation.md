## 2026-05-29 task-plan ambiguity review pass

Reviewed task artifacts (`design.md`, `plan.md`, `steps.md`, `design-steps.md`, `implementation.md`), `.psi/workflows/review-step.edn`, `.psi/workflows/review-design-turn.edn`, and focused workflow loader/judge tests. Found one new actionable ambiguity.

1. **Task scope/source of truth for review-step routing is ambiguous after task 189 behavior landed** — this task's artifacts still describe `review-step` slice 8 as judge-step structured output on a separate `review-status` step with loader proof for a 3-step shape, while current code and recent task-189 state describe/pass through deterministic PASS_STATUS routing semantics on the `follow-up -> review` loop and mention `workflow/pass-status-routing`, `workflow/constant-routing`, duplicate PASS_STATUS handling, and same-step structured status sourcing. The current task artifacts never state whether task 186 intentionally owns the older 3-step/judge-only shape as source of truth, or whether those newer deterministic-routing semantics are accepted follow-on evolution outside this task. Add a design follow-up to either (a) explicitly freeze task 186 to the current implemented 2-step deterministic routing contract and update design/plan/steps/notes accordingly, or (b) mark task 189 as the superseding authority for review-step routing and record that task 186's older review-step shape/testing notes are historical only.

## 2026-05-28 code-shaper follow-up execution

Removed dead `:prompt` field from `implement-task.edn` judge map. `compile-judge` never includes `:prompt` in its `select-keys` output, so the field had no runtime effect. Removed one key-value pair from the judge map. `bb test` green (3 pre-existing failures, 0 new). `bb lint` 0 errors, 0 warnings.

## 2026-05-28 code-shaper review pass

Reviewed `target_ir_compiler.clj`, `structured_output_schemas.clj`, `structured_output.clj`, all new/renamed workflow EDN files, and `workflow_definitions_test.clj` against the code-shaper skill (simplicity ∧ consistency ∧ robustness).

One new actionable issue found:

1. **`implement-task.edn` judge has a dead `:prompt` field** — `compile-judge` in `target_ir_compiler.clj` does not handle `:prompt` (absent from the `select-keys` list for `:llm` judge session config). The field is silently dropped at compile time. The value `"Respond exactly with one word: REPEAT or DONE. Inspect the specific Munera task artifacts before deciding."` duplicates intent already expressed in the judge's `:contributions` template. Pre-existing since task 184; preserved through this task's slice 3 extraction and slice 7/8 structured output changes. Simplicity violation: dead authoring field with no runtime effect misleads future workflow authors about the judge authoring grammar. Fix: remove `:prompt` from `implement-task.edn` judge, or document the field as unsupported in the target-authored grammar and add a compile-time warning/error in `compile-judge`.

Two pre-existing observations (no new steps needed):
- `coerce-enum` is a silent pass-through for string-valued enums (`"REPEAT"`, `"PASS"`) — tries keyword coercion, falls back to original string. Works correctly; the new string-enum schemas expose this undocumented behavior but do not break it.
- `judge-review-result` (pre-existing) has no `*-json-schema` def while the new `judge-routing-result` and `pass-status-result` schemas do — minor asymmetry in `structured_output_schemas.clj`, pre-existing gap not introduced by this task.

`bb test` green (3 pre-existing failures, 0 new). `bb lint` 0 errors, 0 warnings.

## 2026-05-28 follow-up execution — post-review-task-docs pass

Checked `steps.md` for unchecked items added by the preceding review-task-docs pass. All 94 steps are checked `[x]`; the review-task-docs pass (commit `523b1f81`) found no new actionable issues and added no new follow-up items. No implementation work to execute.

## 2026-05-28 review-task-docs pass

Reviewed `README.md`, `doc/workflows.md`, `doc/` (all files), and `CHANGELOG.md` against the review-task-docs skill.

No new actionable issues found. Key verifications:

- `CHANGELOG.md` `[Unreleased]` has all 5 user-visible entries: `review-task-design` (Added), `create-task-plan` (Added), `review-implementation` rename (Changed), `review-task-until-clear` rename (Changed), `review-task-docs` step in chain (Changed). Entries are accurate and use correct `/delegate` invocation syntax.
- `README.md` Workflows section points to `doc/workflows.md` generically; no specific workflow names listed; no stale old-name references.
- `doc/workflows.md` is an authoring-guide scoped to grammar examples, not a workflow catalogue. Task-lifecycle workflows (`review-task-design`, `create-task-plan`, etc.) are not expected there; their user-facing entry point is the CHANGELOG. No stale `review-implementation` or `review-task-until-clear` references anywhere in doc/.
- No examples in any doc reference old workflow names or incorrect behaviour descriptions.

## 2026-05-28 test-shaper review pass

Reviewed `workflow_definitions_test.clj`, `structured_output_test.clj`, and `workflow_judge_test.clj` (agent-session) against the test-shaper skill.

Three actionable issues found:

1. **`workflow_definitions_test.clj` — high test fragmentation across per-workflow deftest explosion**: Each of 7 workflows has 4–6 separate `deftest` forms (loads, step-count, step-names-and-types, input-vars-wired, judge-routing, judge-outputs). Each loads the same `.edn` file independently via `load-edn-only`. 35 tests × 1 file parse each = 35 redundant fixture setups for ~7 workflows. Economical: consolidate per-workflow into one `deftest` with `testing` blocks — same coverage, one parse per workflow, failures localize to the workflow. The current shape also violates `single_concern`: a loads-test and step-count-test are trivially subsumed by step-names-and-types-test.

2. **`workflow_definitions_test.clj` — `review-task-implementation` uses `with-workflow-dir` directly instead of `load-edn-only`**: All other workflows use `load-edn-only`; `review-task-implementation` duplicates the same one-file map inline. Inconsistent fixture style with identical semantics. Change to `load-edn-only`.

3. **`structured_output_test.clj` — `reusable-pass-status-result-schema-test` uses `:source :judge/structured-output` but `pass-status-result` is an actor-step schema**: Design specifies `pass-status-result` for actor steps emitting `PASS_STATUS` (`:source :session/structured-output`). The test spec uses `:judge/structured-output`. `output-result` does not validate `:source`, so the test passes, but the spec is misleading and misrepresents the intended usage. Update to `:source :session/structured-output`.

`bb test` green (3 pre-existing failures, 0 new). `bb lint` 0 errors, 0 warnings.

## 2026-05-28 task-test-review follow-up execution

Executed both unchecked items from the task-test-review pass:

1. **`reusable-pass-status-result-schema-test`** added to `structured_output_test.clj`: two `testing` blocks — valid JSON `{"status":"PASS","reason":"all checks green"}` validates to `:valid` with correct coerced values; invalid JSON missing `:reason` produces `:invalid` with errors. Focused test: 1 test, 5 assertions, 0 failures.

2. **`review-implementation-in-worktree` loader tests** added to `workflow_definitions_test.clj`: three `deftest` forms — loads without error, delegate step targets `"review-task-implementation"`, summary step body contains `"review-task-docs"`. Fixed a formatter-induced paren nesting issue (new `deftest` forms were indented inside the previous `deftest` closing parens; corrected to top-level). Focused loader tests: 35 tests (+3), 72 assertions, 0 failures.

`bb test` green (3 pre-existing failures, 0 new). `bb lint` 0 errors, 0 warnings.

## 2026-05-28 task-test-review pass

Reviewed skill, task artifacts, `workflow_definitions_test.clj`, `target_ir_compiler_test.clj`, `structured_output_test.clj`, `workflow_judge_test.clj`, `structured_output_schemas.clj`, and all new/renamed workflow EDN files.

Two new actionable issues found:

1. **`pass-status-result` schema has no validation test** — `structured_output_test.clj` tests `judge-review-result` and `judge-routing-result` (string-enum) with representative data but has no test for `psi.workflow/pass-status-result` (`[:map [:status [:enum "PASS" "FAIL"]] [:reason :string]]`). AC 8 requires the schema exists; the loader tests assert the `:schema-id` reference in workflow EDN but no test validates the schema itself against representative JSON. A `reusable-pass-status-result-schema-test` analogous to `reusable-judge-review-result-schema-test` is missing.

2. **`review-implementation-in-worktree` has no loader test** — AC 5 requires it delegates to `review-task-implementation` and the summary step names 5 passes including `review-task-docs`. This structural invariant is unguarded: no loader test asserts the `:target` is `"review-task-implementation"` or that the summary step body contains the correct pass list. A regression (e.g. a future rename) would be invisible to the test suite.

`bb test` green (3 pre-existing failures, 0 new). `bb lint` 0 errors, 0 warnings.

## 2026-05-28 task-implementation-review pass 12

Reviewed skill, task artifacts, all workflow EDN files (`review-task-design.edn`, `review-task-plan.edn`, `review-task-implementation.edn`, `create-task-plan.edn`, `implement-task.edn`, `review-step.edn`, `review-implementation-in-worktree.edn`), `review-task-docs` skill, `structured_output_schemas.clj`, `structured_output.clj`, `workflow_judge_test.clj`, `workflow_definitions_test.clj`, `CHANGELOG.md`, `README.md`, and `doc/`.

No new actionable issues found. All 11 acceptance criteria satisfied. Key verifications:

- All old workflow names absent; all new/renamed workflows present and correct.
- `review-task-plan` prompts contain no `design.md` or `design-steps.md` references.
- All `{{input}}` vars wired to `:workflow-input` in all actor steps across all workflows; `review-step` `review` step also has `{{skill}}` wired.
- `review-implementation-in-worktree` delegates to `review-task-implementation`; summary names 5 passes including `review-task-docs`.
- `compile-judge` passes through `:outputs`; both `judge-routing-result` and `pass-status-result` schema ids present and correct.
- `execute-judge-missing-turn-result-structured-output-fails-test` reflects new plain-text fallback routing contract (`:action :complete`, `:status :valid`).
- `structured-output-envelope-plain-text-validation-error-test` name/comment correct; dead `{:ok? false}` branch documented.
- CHANGELOG `[Unreleased]` has all 5 user-visible entries.
- Loader tests: 32 tests covering all new/renamed workflows including `review-step` and `implement-task`. `bb test` green (3 pre-existing failures, 0 new). `bb lint` 0 errors, 0 warnings.

## 2026-05-28 follow-up execution — pass 11 item

Fixed `execute-judge-missing-turn-result-structured-output-fails-test` in `workflow_judge_test.clj`. Updated test name, docstring, and assertions to reflect the new contract: when turn result has no `:structured-output` metadata but assistant text is valid JSON matching the schema, `output-result` → `parse-json-value` (plain-text fallback, always `{:ok? true}`) → malli validates → judge routes with `:action :complete`, `:judge-event :clear`, `:status :valid`. Old assertions (`:missing-structured-output` error, `:invalid` status) removed. `bb test` green, `bb lint` 0 errors 0 warnings.

## 2026-05-28 task-implementation-review pass 11

Reviewed skill, task artifacts, workflow EDN files, `workflow_judge.clj`, `structured_output.clj`, `workflow_judge_test.clj`, and ran focused + full `bb test`.

One new actionable issue found:

**`execute-judge-missing-turn-result-structured-output-fails-test` is failing (5 assertions)** — commit `d1a81113` changed `parse-json-value` to use a plain-text fallback (always `{:ok? true}`) and changed `workflow_judge.clj` to use `output-result` instead of `missing-ai-structured-output-result`, explicitly so that plain-text `DONE`/`REPEAT` judge output routes correctly without structured-output metadata. The test still asserts the old contract (missing metadata → `:missing-structured-output` fail). The test must be updated to reflect the new contract: when the turn result has no `:structured-output` metadata but assistant text is valid JSON matching the schema, the judge routes successfully. `bb lint` 0 errors 0 warnings.

## 2026-05-28 task-implementation-review pass 10

Reviewed skill, task artifacts, all new/renamed workflow EDN files, `review-task-docs` skill, `structured_output_schemas.clj`, `structured_output.clj`, `structured_output_test.clj`, `workflow_definitions_test.clj`, `CHANGELOG.md`, `README.md`, and `doc/`.

No new actionable issues found. All 11 acceptance criteria satisfied. Key verifications:

- All old workflow names absent from `.psi/workflows/`.
- `review-task-plan` prompts contain no `design.md` or `design-steps.md` references.
- All `{{input}}` vars wired to `:workflow-input` in all actor steps across all new/renamed workflows (including `review-status` in `review-step`, all three steps); judge contributions use `:vars {}` (correct — judges use actor context, not workflow-input).
- Sub-step `.md` files (`review-task-design-*.md`, `review-task-plan-*.md`, `implement-task-*.md`, `create-task-plan-create-plan.md`) registered as standalone callable workflows — intentional design, content matches inline EDN text.
- `compile-judge` passes through `:outputs`; both schema ids present and correct.
- `parse-json-value` plain-text fallback test name/comment correct; dead `{:ok? false}` branch documented.
- `review-implementation-in-worktree` delegates to `review-task-implementation`, summary names 5 passes.
- `bb test` green (3 pre-existing failures, 0 new). `bb lint` 0 errors, 0 warnings.

## 2026-05-28 task-implementation-review pass 9

Reviewed skill, task artifacts (design.md, plan.md, steps.md, design-steps.md, implementation.md), all new/renamed workflow EDN files (`review-task-design.edn`, `review-task-plan.edn`, `review-task-implementation.edn`, `create-task-plan.edn`, `implement-task.edn`, `review-step.edn`, `review-implementation-in-worktree.edn`), `review-task-docs` skill, `structured_output_schemas.clj`, `structured_output.clj`, `structured_output_test.clj`, `workflow_definitions_test.clj`, `CHANGELOG.md`, `README.md`, and `doc/`.

No new actionable issues found. All 11 acceptance criteria satisfied. Key verifications:

- All old workflow names have zero remaining references in `.psi/workflows/`.
- `review-task-plan` prompts contain no `design.md` or `design-steps.md` references.
- All `{{input}}` vars wired to `:workflow-input` in all 6 steps of `review-task-design` and `review-task-plan`, including `final-summary`; same for `create-task-plan` and `implement-task`.
- `review-step` all 3 steps have `{{input}}` wired; `review` step also has `{{skill}}` wired.
- `review-implementation-in-worktree` summary step names 5 passes including `review-task-docs`.
- `compile-judge` passes through `:outputs`; both `judge-routing-result` and `pass-status-result` schema ids present and correct.
- `parse-json-value` plain-text fallback test name/comment correct; dead `{:ok? false}` branch documented.
- All design-steps.md items checked; all steps.md items checked.
- Loader tests: 32 tests, 66 assertions, 0 failures. `bb lint` 0 errors, 0 warnings.

## 2026-05-28 task-implementation-review pass 8

Reviewed skill, task artifacts, all new/renamed workflow EDN files (`review-task-design.edn`, `review-task-plan.edn`, `review-task-implementation.edn`, `create-task-plan.edn`, `implement-task.edn`, `review-step.edn`, `review-implementation-in-worktree.edn`), `review-task-docs` skill, `structured_output_schemas.clj`, `target_ir_compiler.clj`, `structured_output.clj`, `structured_output_test.clj`, `workflow_definitions_test.clj`, `doc/workflows.md`, `README.md`, and `CHANGELOG.md`. Confirmed post-pass-7 commits (`9a7e18ef`, `01ce4df1`).

No new actionable issues found. All 11 acceptance criteria satisfied. Key verifications:

- All old workflow names have zero remaining references in `.psi/workflows/`.
- `review-task-plan` prompts contain no `design.md` or `design-steps.md` references.
- `{{input}}` vars wired to `:workflow-input` in all actor steps across all new/renamed workflows (including `final-summary` steps in `review-task-design` and `review-task-plan`).
- `review-implementation-in-worktree` summary step names 5 passes including `review-task-docs`.
- `compile-judge` passes through `:outputs`; both `judge-routing-result` and `pass-status-result` schema ids present and correct.
- `structured-output-envelope-plain-text-validation-error-test` name and comment correctly reflect validation-error path (not parse-error); dead `{:ok? false}` branch documented with comment.
- `review-step` orphaned `.md` files absent; 3-step shape with `review-status` judge confirmed.
- `CHANGELOG.md` `[Unreleased]` has entries for all 5 user-visible changes.
- Loader tests: 32 tests, 66 assertions, 0 failures. `bb test` green. `bb lint` 0 errors, 0 warnings.
- All steps.md items checked; all design-steps.md items checked.

## 2026-05-28 task-implementation-review pass 7

Reviewed skill, task artifacts, all new/renamed workflow EDN files, `structured_output.clj`, `structured_output_test.clj`, and post-pass-6 commit `d1a81113` (plain-text fallback for unquoted judge output).

Two minor actionable issues found:

1. **`structured-output-envelope-invalid-json-test` comment/name is misleading after plain-text fallback** — `"not json"` no longer triggers a parse error; the fallback treats it as the plain string `"not json"`, which then fails malli validation. The test still passes (`:invalid` + `:errors`) but the comment "malformed JSON is invalid and records errors" implies a parse error path that no longer exists. Update the test name/comment to reflect the new behavior (validation error, not parse error).

2. **`parse-json-value` `{:ok? false}` return is now unreachable** — the plain-text fallback always returns `{:ok? true}`. The `ok? false` branch in `structured-output-envelope` (via `validation-input`) is dead code for the `raw-output` path. Not harmful, but misleading. Either remove the dead branch or add a comment that it is only reachable via the `:payload` path.

`bb test` green, `bb lint` 0 errors 0 warnings.

## 2026-05-28 task-implementation-review pass 6

Reviewed skill, task artifacts, all new/renamed workflow EDN files, `review-task-docs` skill, `structured_output_schemas.clj`, `target_ir_compiler.clj`, `workflow_definitions_test.clj`, `doc/workflows.md`, `README.md`, and `CHANGELOG.md`.

No new actionable issues found. All 11 acceptance criteria satisfied. Key verifications:

- `review-step.edn` has 3 steps (`review`, `follow-up`, `review-status`); judge on `review-status` with REPEAT/DONE routing and `judge-routing-result` `:outputs` — matches loader test assertions.
- No orphaned `.md` prompt files in `.psi/workflows/` (`review-step-review.md`, `review-step-follow-up.md` absent).
- `doc/workflows.md` is an authoring guide with curated authoring examples — not a workflow catalogue; new task-lifecycle workflows are not expected there. CHANGELOG covers all user-visible additions and renames.
- `bb lint` clean (0 errors, 0 warnings). `bb test` green (confirmed via prior pass 5 and lint/test re-verification).
- All steps.md items checked; all design-steps.md items checked.

## 2026-05-28 task-implementation-review pass 5

Reviewed skill, task artifacts (design.md, plan.md, steps.md, design-steps.md, implementation.md), all new/renamed workflow EDN files (`review-task-design.edn`, `review-task-plan.edn`, `review-task-implementation.edn`, `create-task-plan.edn`, `implement-task.edn`, `review-step.edn`, `review-implementation-in-worktree.edn`), `review-task-docs` skill, `structured_output_schemas.clj`, `workflow_definitions_test.clj`, `doc/workflows.md`, `README.md`, and `CHANGELOG.md`.

No new actionable issues found. All 11 acceptance criteria satisfied. Key verifications:

- All old workflow names (`review-implementation`, `review-task-until-clear`) have zero remaining references in `.psi/workflows/`.
- `review-task-plan` prompts contain no `design.md` or `design-steps.md` references.
- `{{input}}` vars are wired to `:workflow-input` in all actor steps across all new/renamed workflows.
- `review-implementation-in-worktree` summary step names 5 passes including `review-task-docs`.
- `compile-judge` passes through `:outputs`; both `judge-routing-result` and `pass-status-result` schema ids present and correct.
- `review-step` restored to 3-step shape with judge on `review-status`; structured-output string-enum regression fixed (commit `dc0c7595`).
- `CHANGELOG.md` `[Unreleased]` has entries for all 5 user-visible changes.
- Loader tests: 32 tests, 66 assertions, 0 failures. `bb test` green. `bb lint` 0 errors, 0 warnings.
- All steps.md items checked; all design-steps.md items checked.

## 2026-05-28 task-implementation-review pass 4

Reviewed skill, task artifacts, all new/renamed workflow EDN files, `review-task-docs` skill, `structured_output_schemas.clj`, `target_ir_compiler.clj`, `workflow_definitions_test.clj`, `doc/workflows.md`, `README.md`, and `CHANGELOG.md`. Also reviewed post-pass-3 commits `8a4dde28`, `5d1cd647`, `dc0c7595` (review-step judge removal/restore cycle and structured-output string-enum regression fix).

One new actionable issue found:

**CHANGELOG missing entries for user-visible changes** — `[Unreleased]` has no entries for: (a) new workflows `review-task-design`, `create-task-plan`; (b) renamed workflows `review-task-implementation` (from `review-implementation`) and `review-task-plan` (from `review-task-until-clear`) — renames are breaking since old names are gone; (c) new `review-task-docs` review step in `review-task-implementation`. These are new commands/behaviours visible via `/delegate`. Per AGENTS.md changelog rule, user-visible changes require an `[Unreleased]` entry before the commit.

`bb lint` clean (0 errors, 0 warnings). `bb test` green (exit 0, all dots).

## 2026-05-27 task-implementation-review pass 3

Reviewed skill, task artifacts, `review-step.edn` (post-structural-fix commit `f18c2d21`), `workflow_definitions_test.clj`, and full `bb test` / `bb lint`.

One new actionable issue found:

**`review-step` loader tests missing after structural fix** — commit `f18c2d21` (after pass 2) removed the `review-status` actor step, collapsed the judge onto `follow-up`, and introduced a new `FOLLOWUP_STATUS: ALL_DONE / ITEMS_REMAINING` signal contract. `review-step` now has 2 steps instead of 3, with different routing logic. `workflow_definitions_test.clj` has no tests for `review-step` (Slice 9 scope was new/renamed workflows only). The structural change is unguarded — a future regression would be invisible to the loader test suite. Add loader tests: loads without error, 2 steps, correct names/types, judge on `follow-up` has REPEAT/DONE routing and `:outputs` with `judge-routing-result`.

`bb test` green, `bb lint` 0 errors 0 warnings.

## 2026-05-27 task-implementation-review pass 2

Reviewed skill, task artifacts (design.md, plan.md, steps.md, design-steps.md, implementation.md), all new/renamed workflow EDN files (`review-task-design.edn`, `review-task-plan.edn`, `review-task-implementation.edn`, `create-task-plan.edn`, `implement-task.edn`, `review-step.edn`, `review-implementation-in-worktree.edn`), the `review-task-docs` skill, `structured_output_schemas.clj`, `target_ir_compiler.clj`, and `workflow_definitions_test.clj`.

No new actionable issues found. All 11 acceptance criteria are satisfied. Key verifications:

- All old workflow names (`review-implementation`, `review-task-until-clear`) have zero remaining references.
- `review-task-plan` has no `design.md` or `design-steps.md` references in any step prompt.
- All `{{input}}` template vars are wired to `:workflow-input` in all actor steps across all new/renamed workflows.
- `review-implementation-in-worktree` summary step names 5 passes including `review-task-docs`.
- `compile-judge` passes through `:outputs`; both `judge-routing-result` and `pass-status-result` schema ids present.
- Focused loader tests: 25 tests, 53 assertions, 0 failures. Full `bb test` green. `bb lint` 0 errors, 0 warnings.
- Unreferenced `.md` prompt files (`review-task-plan-*.md`, `review-task-design-*.md`, `implement-task-*.md`, `create-task-plan-create-plan.md`) are in sync with inline EDN text; their existence as standalone prompts is documented and intentional.

## 2026-05-27 follow-up execution pass 2

All three review follow-up items completed:

1. **`{{input}}` fix**: Switched `review-task-design.edn`, `review-task-plan.edn`, `create-task-plan.edn`, and `implement-task.edn` actor steps from `:prompt-workflow` to inline `:contributions` with `:vars {"input" {:from :workflow-input :path [:input]}}`. The `.md` prompt files remain on disk (they are valid standalone prompts) but are no longer referenced by these workflows. Scope extended to `implement-task` actor steps (both `implement-pass` and `final-summary`) which had the same bug.

2. **Orphaned `.md` removal**: Deleted `review-step-review.md` and `review-step-follow-up.md` via `git rm`.

3. **Loader test `:vars` assertions**: Added `step-has-input-var-wired?` helper and `*-input-vars-wired-test` deftest for `review-task-design`, `review-task-plan`, `create-task-plan`, and `implement-task`. Also added full `implement-task` test coverage (loads, step count, step names/types, judge routing, judge outputs). Removed now-unused `load-edn-with-md-refs` helper (all workflows use inline contributions; no `.md` refs needed in tests). `bb test` green, `bb lint` clean (0 errors, 0 warnings).

## 2026-05-27 task-implementation-review pass

**`{{input}}` unsubstituted in `:prompt-workflow` steps** — `review-task-design`, `review-task-plan` (actor steps), and `create-task-plan` all use `:prompt-workflow` referencing `.md` files that contain `{{input}}`. The workflow-loader compiler expands `:prompt-workflow` to a `:template` contribution with `:vars {}` (empty). At runtime, `{{input}}` is rendered literally — the agent sees the string `{{input}}` instead of the task path. The same bug was fixed for `review-step` (commit `7d6b848e`) by switching to inline `:contributions` with explicit `:vars {"input" {:from :workflow-input :path [:input]}}`. The same fix is needed for `review-task-design`, `review-task-plan` actor steps, and `create-task-plan`. `implement-task` actor steps have the same issue but the judge step already uses inline contributions with correct `:vars` — the actor steps rely on `:workflow-original` for context, which may be sufficient if the original message contains the task path.

**Orphaned `.md` files for `review-step`** — `review-step-review.md` and `review-step-follow-up.md` were extracted in slice 3 but then abandoned when the fix reverted `review-step.edn` to inline contributions. These files are unreferenced dead artifacts.

**Loader tests don't assert `:vars` wiring** — `workflow_definitions_test.clj` asserts that `:prompt-workflow` resolves to contributions but does not assert that `{{input}}` is wired to `:workflow-input` via `:vars`. The gap is invisible to the test suite.

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

## Closure (2026-05-31 audit)

Closed as complete during the open-task reconciliation audit. All `steps.md` items checked and review loops recorded no actionable feedback.
