# 218 — Steps

Checklist derived from `plan.md`. Tick each item with the commit sha / decision
when done.

## Slice 1 — Preflight and dedicated review skill

- [ ] Run `bb gordian architecture-targets --edn` from the repository root and record the observed top-level `:winner` / `:candidates` envelope shape in `implementation.md`.
- [ ] Run or smoke-check `bb gordian target-issues --candidate '<candidate-id>' --edn` for a supported candidate type when available, and record the supported/unsupported behaviour in `implementation.md`.
- [ ] Create `.psi/skills/review-implementation-architecture/SKILL.md` with frontmatter name exactly `review-implementation-architecture`.
- [ ] In the skill, require reading the selected Gordian target, `architecture-targets.edn`, `target-issues.edn` or `target-issues-unavailable.edn`, validation artifacts, Munera task artifacts, and project architecture sources (`AGENTS.md`, `META.md`, `doc/architecture.md`, relevant local architecture docs).
- [ ] In the skill, require judging the implemented code change for behaviour preservation, target fit, blast-radius discipline, architectural improvement/no-regression, and absence of adapter/shim/indirection complexity.
- [ ] Verify the new skill loads through the skills registry or existing workflow-loader skill discovery seam.
- [ ] Commit Slice 1 (`⚒ skill: add implementation architecture review`).

## Slice 2 — Architecture workflow selection and task-generation shell

- [ ] Create `.psi/workflows/reduce-architectural-complexity.edn` with top-level `:name "reduce-architectural-complexity"` and a concise description distinguishing it from `reduce-incidental-complexity`.
- [ ] Add `select-and-create` as a `:session` step with tools `read`, `bash`, `edit`, and `write`, plus relevant `gordian`/review/code-shaping skills as needed.
- [ ] In `select-and-create`, require `git status --short --branch` and explicitly forbid `work-on`, worktree creation, and branch/worktree switching.
- [ ] In `select-and-create`, run `bb gordian architecture-targets --edn` from the worktree root, parse/validate the EDN envelope before task creation, and write raw EDN stdout to `munera/open/NNN-slug/architecture-targets.edn` only after all mandatory pre-task captures are verified.
- [ ] In `select-and-create`, select only the top-level `:winner` when present, eligible, and carrying interpretable `:candidate/id` and `:candidate/type`; treat missing/non-vector top-level `:candidates` as uninterpretable.
- [ ] In `select-and-create`, implement the no-target/uninterpretable branch with no task creation, no `munera_task_path:` line, final raw line `PASS_STATUS: ACTIONABLE_FEEDBACK`, judge `workflow/pass-status-routing`, and EDN route `"REPEAT"` directly to `:done`.
- [ ] In `select-and-create`, implement the target-created branch with final raw line `PASS_STATUS: REVIEW_COMPLETE`, judge normalization to `"DONE"`, and EDN route `"DONE"` to `extract-task-path`.
- [ ] In `select-and-create`, run `bb gordian target-issues --candidate '<pr-str candidate-id>' --edn` when supported and write raw stdout to `target-issues.edn`.
- [ ] In `select-and-create`, when `target-issues` is unsupported or fails for an otherwise valid selected candidate, write `target-issues-unavailable.edn` with `:candidate/id`, `:candidate/type`, `:status`, `:command`, and `:reason`, and continue task creation.
- [ ] In `select-and-create`, run `bb gordian diagnose --edn` before task creation, verify it exits successfully and emits readable EDN, and write raw stdout to root-relative `munera/open/NNN-slug/before-diagnose.edn` only after the all-or-nothing pre-task capture gate passes.
- [ ] In `select-and-create`, if any mandatory pre-task capture for an otherwise valid winner fails or emits unreadable EDN, create no partial Munera task, emit no `munera_task_path:`, include command/exit/stdout/stderr/readability details, and end with `PASS_STATUS: ACTIONABLE_FEEDBACK`.
- [ ] In `select-and-create`, resolve candidate membership for `:namespace`, `:family`, `:pair`, and `:community` exactly as specified in design.md.
- [ ] In `select-and-create`, resolve every target namespace to at least one production Clojure source file and take the no-target route if any target namespace cannot be resolved.
- [ ] Generate `munera/open/NNN-slug/design.md` containing selected candidate id/type/label, ranking evidence, target-issues framing or unavailable note, scope, non-goals, blast-radius limits, unchanged behaviours, test-net requirements, and Gordian validation criteria.
- [ ] Ensure generated task references all Gordian artifacts with worktree-root-relative paths under the generated task directory, never bare filenames.
- [ ] Ensure generated task records `:target/namespaces`, `:target/source-areas`, default/explicit `:target/allowed-adjacent-source-areas`, and `:target/affected-test-areas` semantics.
- [ ] Commit generated task creation and baseline/ranking/framing artifacts within the workflow prompt contract only after all required pre-task captures have been verified readable.
- [ ] Emit a structured handoff containing `munera_task_path:` only on the target-created path.
- [ ] Add an `extract-task-path` session step immediately after the target-created route; it reads the `select-and-create` handoff, validates exactly one root-relative `munera_task_path: munera/open/NNN-slug` line, yields only that path, routes success to `review-task-design`, and routes malformed target-present handoff to `terminal-stop-summary`.
- [ ] Add `review-task-design`, `create-task-plan`, and `review-task-plan` delegates wired with `:prompt-string {:type :map :fields {:input {:from {:step "extract-task-path" :yield :text}}}}`, while passing the full `select-and-create` handoff only as context.
- [ ] Verify the workflow EDN parses/loads after Slice 2.
- [ ] Commit Slice 2 (`⚒ workflow: add architecture target selection`).

## Slice 3 — Pre-simplification test-net gates

- [ ] Add `clean-baseline` session step after `review-task-plan`, before coverage review.
- [ ] In `clean-baseline`, read generated task artifacts and write `characterization-baseline.edn` containing `:git/head`, `:git/status-short`, selected candidate map, `:target/namespaces`, `:target/source-areas`, `:target/allowed-adjacent-source-areas`, `:target/affected-test-areas` when known, and classified pre-existing task/doc dirt.
- [ ] In `clean-baseline`, stop and route to terminal stop if target/source areas are already dirty in a way that makes current behaviour ambiguous.
- [ ] Add `coverage-review` session step that reviews affected behaviour coverage against observable state/outputs and nominal/edge/boundary cases for the recorded target/source areas.
- [ ] In `coverage-review`, record either sufficient green coverage, `CHARACTERIZATION_STATUS: FIXABLE_GAPS`, or `CHARACTERIZATION_STATUS: INFEASIBLE` in committed task artifacts.
- [ ] Add `coverage-disposition` step that routes only the immediately preceding coverage-review result: fixable gaps to `coverage-fix`, infeasible/ambiguous disposition to terminal stop.
- [ ] Add `coverage-fix` step constrained to characterization tests and explicitly justified minimal testability seams, with no architecture simplification/refactor work.
- [ ] Route `coverage-fix` back to `coverage-review` after committing characterization work.
- [ ] Add `diff-gate` step after coverage review succeeds and before `implement-task`.
- [ ] In `diff-gate`, compare committed changes since `characterization-baseline.edn` `:git/head` plus current uncommitted status/diff.
- [ ] In `diff-gate`, allow only characterization tests, task artifacts, docs, and explicitly justified minimal testability seams before implementation.
- [ ] In `diff-gate`, stop before implementation on unclassified source change, broad production edit, premature simplification/refactor, missing baseline data, or infeasible characterization.
- [ ] Wire `diff-gate` `"DONE"` route to `implement-task` and failure route to `terminal-stop-summary`.
- [ ] Verify from the EDN step order/routes that `implement-task` cannot run before clean baseline, coverage review, and diff gate succeed.
- [ ] Commit Slice 3 (`⚒ workflow: add architecture test-net gates`).

## Slice 4 — Validation capture and post-implementation review chain

- [ ] Add `implement-task` delegate targeting `implement-task`, with input from `extract-task-path` and context from the full `select-and-create` handoff plus prior gate outputs.
- [ ] Add `validation-capture` session step immediately after `implement-task` and before any post-implementation review-step delegate.
- [ ] In `validation-capture`, rerun `bb gordian diagnose --edn` and write `after-diagnose.edn` under the generated task directory.
- [ ] In `validation-capture`, rerun `bb gordian architecture-targets --edn` and write `after-architecture-targets.edn` under the generated task directory.
- [ ] In `validation-capture`, run `bb gordian compare munera/open/NNN-slug/before-diagnose.edn munera/open/NNN-slug/after-diagnose.edn --edn` and write `architecture-compare.edn`.
- [ ] In `validation-capture`, run `bb gordian gate --baseline munera/open/NNN-slug/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn` and write `architecture-gate.edn`.
- [ ] In `validation-capture`, write EDN failure maps to the same artifact paths for non-zero/unreadable validation commands, append terse failures to `implementation.md`, add plausible repair checklist items to generated `steps.md`, commit artifacts, and end with `PASS_STATUS: ACTIONABLE_FEEDBACK`.
- [ ] Route `validation-capture` `PASS_STATUS: ACTIONABLE_FEEDBACK` / normalized `"REPEAT"` back to `implement-task` for repair.
- [ ] Route `validation-capture` `PASS_STATUS: REVIEW_COMPLETE` / normalized `"DONE"` to the first post-implementation review-step gate.
- [ ] Add ordered delegate `review-implementation-correctness` targeting `review-step` with `:skill {:value "task-implementation-review"}` and `extract-task-path` output as `:input`.
- [ ] Add ordered delegate `review-implementation-tests` targeting `review-step` with `:skill {:value "task-test-review"}` and `extract-task-path` output as `:input`.
- [ ] Add ordered delegate `review-implementation-architecture` targeting `review-step` with `:skill {:value "review-implementation-architecture"}` and `extract-task-path` output as `:input`.
- [ ] Ensure the architecture review-step context includes exactly these workflow sources: `:workflow-original`, `select-and-create`, `clean-baseline`, `coverage-review`, `diff-gate`, `implement-task`, `validation-capture`, `review-implementation-correctness`, and `review-implementation-tests`; do not imply artifact file contents are automatically inlined by runtime context.
- [ ] Ensure the `review-implementation-architecture` skill requires reading selected Gordian evidence and validation artifacts from task-local root-relative paths: `architecture-targets.edn`, `target-issues.edn` or `target-issues-unavailable.edn`, `before-diagnose.edn`, `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, `architecture-gate.edn`, and Munera task artifacts.
- [ ] Add ordered delegate `review-test-shape` targeting `review-step` with `:skill {:value "test-shaper"}` and `extract-task-path` output as `:input`.
- [ ] Add ordered delegate `review-task-docs` targeting `review-step` with `:skill {:value "review-task-docs"}` and `extract-task-path` output as `:input`.
- [ ] Add ordered delegate `review-code-shape` targeting `review-step` with `:skill {:value "code-shaper"}` and `extract-task-path` output as `:input`.
- [ ] Ensure the workflow does not call the generic `review-task-implementation` workflow as a delegate target.
- [ ] Add `terminal-stop-summary` with two deterministic branches: malformed `extract-task-path` before design with no validated task path, and no-implementation gate failures after task creation with an extracted task path.
- [ ] Add `final-summary` for completed target-present runs, summarizing design → plan → test-net → diff-gate → implementation → validation-capture → six review gates.
- [ ] Verify the full workflow EDN parses/loads after Slice 4.
- [ ] Commit Slice 4 (`⚒ workflow: add architecture validation and reviews`).

## Slice 5 — Workflow-loader/content-lock tests

- [ ] Add a dedicated `components/workflow-loader/test/psi/workflow_loader/task_218_workflow_definitions_test.clj` namespace or equivalent focused test file.
- [ ] Test that `reduce-architectural-complexity.edn` loads without workflow-loader errors and registers the expected workflow name.
- [ ] Test the top-level step order includes `select-and-create`, `extract-task-path`, design/plan delegates, clean-baseline, coverage review/disposition/fix, diff-gate, implement-task, validation-capture, six review-step delegates, terminal stop summary, and final summary in the intended routes/order.
- [ ] Test `select-and-create` prompt content includes `bb gordian architecture-targets --edn`, top-level `:winner` / `:candidates`, all-or-nothing pre-task capture failure routing for `before-diagnose.edn`, and excludes JSON / `:architecture-target-ranking` selection requirements.
- [ ] Test no-target routing uses `workflow/pass-status-routing` allowed statuses `ACTIONABLE_FEEDBACK` and `REVIEW_COMPLETE`, with EDN `:on` keys only `"DONE"` and `"REPEAT"`.
- [ ] Test no-target prompt content forbids task creation, omits `munera_task_path:`, emits `PASS_STATUS: ACTIONABLE_FEEDBACK`, and routes directly to `:done`.
- [ ] Test target-present prompt content emits `munera_task_path:` and `PASS_STATUS: REVIEW_COMPLETE`.
- [ ] Test prompt content captures/writes `before-diagnose.edn`, `architecture-targets.edn`, `target-issues.edn`, `target-issues-unavailable.edn`, `characterization-baseline.edn`, `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn` with root-relative task paths, and that mandatory pre-task captures are verified before task creation.
- [ ] Test unsupported `target-issues` branch is informational and does not route to no-target.
- [ ] Test invoking-worktree constraints: no `work-on` tool and prompt forbids creating/switching worktrees.
- [ ] Test candidate membership/source-area prompt content covers namespace, family, pair, and community rules, including community-without-members as uninterpretable.
- [ ] Test `extract-task-path` is the sole downstream task-path source for review-task-design, create-task-plan, review-task-plan, implement-task, validation-capture, and all six review-step delegates, with full `select-and-create` handoff passed only as context; also test malformed extraction routes to the pre-design terminal stop without inventing a task path.
- [ ] Test clean-baseline/coverage/diff gate routing prevents `implement-task` before coverage and diff gates pass.
- [ ] Test `validation-capture` immediately follows `implement-task` in successful topology and precedes every post-implementation review-step gate.
- [ ] Test validation failure routing goes back to `implement-task` and successful validation routes to `task-implementation-review` gate.
- [ ] Test all six post-implementation review delegates target `review-step` in order with exact skill values: `task-implementation-review`, `task-test-review`, `review-implementation-architecture`, `test-shaper`, `review-task-docs`, `code-shaper`, and use `extract-task-path` as their `:input`.
- [ ] Test no post-implementation delegate target equals generic `review-task-implementation`.
- [ ] Test `.psi/skills/review-implementation-architecture/SKILL.md` is discoverable and the workflow uses exactly `:skill {:value "review-implementation-architecture"}`.
- [ ] Test `review-implementation-architecture` context sources are exactly the workflow-source list specified in plan.md and that artifact evidence is required via task-local file reads, not assumed runtime inlining.
- [ ] Add a live narrow integration shape test for `bb gordian architecture-targets --edn` when `bb`/Gordian are available, asserting only stable top-level envelope shape and candidate id/type presence; skip or fixture-fallback on unavailable/non-zero command and never assert repository-specific candidate values, scores, labels, or counts.
- [ ] Test the direct delegate set co-loads with `review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`, `review-step`, and required prompt-workflow markdown files.
- [ ] Run the focused workflow-loader Scry/Kaocha test namespace and record results in `implementation.md`.
- [ ] Run targeted `clj-kondo` over changed test files if Clojure tests were added or edited.
- [ ] Commit Slice 5 (`⚒ test: lock architecture simplification workflow`).

## Slice 6 — User-facing docs, changelog, and coherence verification

- [ ] Update `doc/workflows.md` to document `reduce-architectural-complexity`, its invoking-worktree requirement, architecture-target scope, and distinction from `reduce-incidental-complexity`.
- [ ] Update README or other workflow index documentation if it lists user-invokable workflows separately from `doc/workflows.md`.
- [ ] Add `CHANGELOG.md` `[Unreleased]` `Added` entry for the new architecture-level simplification workflow and implementation architecture review gate/skill.
- [ ] Verify docs do not claim the workflow creates a worktree, pushes a branch, or opens a PR.
- [ ] Verify coherence across `design.md`, `plan.md`, `steps.md`, skill, workflow EDN, tests, docs, and changelog for names, artifact paths, route statuses, and review-step order.
- [ ] Run final focused workflow-loader tests and targeted lint/format checks for changed files.
- [ ] Append final implementation verification notes to `implementation.md`.
- [ ] Commit Slice 6 (`⚒ doc: document architecture simplification workflow`).

## Plan/steps ambiguity follow-ups

- [x] **PA1 — Pin required pre-task baseline capture failure routing.** Update `plan.md`/Slice 2 steps to state what `select-and-create` does when a valid `architecture-targets` winner is selected but required pre-task artifact capture fails or emits unreadable EDN before task creation, especially `bb gordian diagnose --edn` for `before-diagnose.edn`: no partial Munera task, no `munera_task_path:`, final `PASS_STATUS: ACTIONABLE_FEEDBACK`, and enough command failure detail in the final response for diagnosis.
- [x] **PA2 — Pin downstream task-path threading.** Decide and record the exact workflow mechanism by which downstream delegates receive the generated task identity after the `select-and-create` session step: either pass the full `select-and-create` handoff text consistently, add an explicit path-extraction step/output, or use another supported workflow source. Update Slice 2/Slice 4 checklist items and tests so `review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`, `validation-capture`, and all six `review-step` delegates agree on the same unambiguous `:prompt-string` input shape.
- [x] **PA3 — Pin architecture review evidence context mechanics.** Update `plan.md`/Slice 4 steps to specify exactly which `:context` sources are passed to the `review-implementation-architecture` `review-step` delegate (for example `:workflow-original`, `select-and-create`, `validation-capture`, and prior review yields) versus which evidence the skill must read from task-local files by path. Tests should lock those sources and avoid implying the workflow runtime inlines artifact file contents automatically.
- [x] **PA4 — Pin the real Gordian envelope test shape.** Update Slice 5 to specify whether the `architecture-targets --edn` envelope coverage is a live narrow integration test, a fixture/content-lock test, or both. If live, assert only stable envelope structure (`:winner` map when present, top-level `:candidates` vector, candidate id/type shape) with skip/fallback behaviour when `bb gordian` is unavailable, not repository-specific candidate values or scores.

## Plan/steps inconsistency follow-ups

- [x] **PI1 — Synchronize selector success topology across task files.** `design.md`'s selector routing contract still says `select-and-create` normalizes `PASS_STATUS: REVIEW_COMPLETE` to `"DONE"` and routes directly to `review-task-design`, while `plan.md`/`steps.md` after PA2 require `"DONE"` to route first to `extract-task-path`, then to `review-task-design`, with every downstream task consumer using the extracted path. Update the task files and planned tests so there is exactly one target-present topology and no delegate can bypass `extract-task-path`.
- [x] **PI2 — Reconcile malformed `extract-task-path` stop handling with terminal summary scope.** PA2 says a malformed target-present handoff from `extract-task-path` routes to `terminal-stop-summary`, but Slice 4 currently describes `terminal-stop-summary` only for no-implementation gate failures after task creation and after a task identity is available. Update the plan/steps/workflow prompt contract so `terminal-stop-summary` explicitly handles this pre-design, no-validated-task-path stop case from the full `select-and-create` handoff, or choose a different deterministic stop route.
- [x] **PI3 — Synchronize architecture-review evidence delivery wording.** `design.md` still says the `review-implementation-architecture` review-step invocation must receive Gordian evidence/artifacts "in context", while PA3 in `plan.md`/`steps.md` says runtime context sources are limited to workflow yields and artifact contents are not inlined; the skill must read task-local root-relative files. Update the design/acceptance wording and tests to use the same path-read boundary and avoid implying artifact-content inlining.
