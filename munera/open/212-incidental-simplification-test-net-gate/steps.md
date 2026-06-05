# Steps — Incidental simplification pre-refactor test-net gate

## Slice 0 — Workflow topology orientation

- [x] Read `.psi/workflows/reduce-incidental-complexity.edn` and record the current `select-and-create` → `lifecycle` routing contract in `implementation.md`.
- [x] Read `.psi/workflows/task-lifecycle.edn` and note the sub-workflow order that must be exposed in `reduce-incidental-complexity`.
- [x] Read `.psi/workflows/review-task-design.edn`, `.psi/workflows/create-task-plan.edn`, `.psi/workflows/review-task-plan.edn`, `.psi/workflows/implement-task.edn`, and `.psi/workflows/review-task-implementation.edn` to confirm delegate input/context shapes.
- [x] Read `doc/workflow-grammar.md` and `doc/workflow-grammar-concepts.md` for delegate/session routing grammar relevant to the new topology.
- [x] Read `components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj` and identify existing assertions that must change from whole-lifecycle delegation to explicit phased delegation.
- [x] Record any implementation constraints or non-blocking discoveries in `implementation.md`.

## Slice 1 — Expand target-present lifecycle topology

- [x] Edit `.psi/workflows/reduce-incidental-complexity.edn` so `select-and-create` routes target-present `REVIEW_COMPLETE` to `review-task-design` instead of directly to a whole `task-lifecycle` delegate.
- [x] Add explicit `:delegate` steps for `review-task-design`, `create-task-plan`, and `review-task-plan` using `{:type :map :fields {:input {:from {:step "select-and-create" :yield :text}}}}` or an equivalent task-path handoff that existing delegated workflows can consume.
- [x] Preserve the no-target route at the `select-and-create` boundary: selector `PASS_STATUS: ACTIONABLE_FEEDBACK` normalizes through `workflow/pass-status-routing` to `"REPEAT"` and routes directly to `:done`, without creating/inspecting a task or running any downstream terminal-summary step.
- [x] Ensure each planning/review delegate carries appropriate `:context` from `:workflow-original` and the `select-and-create` yielded handoff.
- [x] Remove or bypass the old target-present opaque `task-lifecycle` delegate path so it cannot hide the Phase 0/Phase 1 boundary.

## Slice 2 — Add the characterization-test-net gate

- [x] Add a clean-baseline/session step after `review-task-plan` that reads the generated task artifacts, identifies target/source paths, verifies those paths are not already dirty, writes `characterization-baseline.edn` in the task directory with HEAD/status/target-source-paths/explicitly-classified pre-existing task-artifact-or-doc dirt, commits task-artifact updates when needed, and emits a single `PASS_STATUS` line.
- [x] Route clean-baseline raw `PASS_STATUS: REVIEW_COMPLETE` → normalized `"DONE"` to the coverage review step and raw `PASS_STATUS: ACTIONABLE_FEEDBACK` → normalized `"REPEAT"` to an explicit terminal stop/summary step.
- [x] Add a coverage review/session step using `task-test-review` and `testing-without-mocks` guidance to assess nominal, edge, and boundary coverage of the target's observable behavior before simplification.
- [x] Make coverage review emit `PASS_STATUS: REVIEW_COMPLETE` only when the characterization net is sufficient and green against unmodified target behavior.
- [x] Make coverage review emit `PASS_STATUS: ACTIONABLE_FEEDBACK` when characterization gaps remain or sufficient characterization is infeasible, and require it to record exactly one task-artifact marker: `CHARACTERIZATION_STATUS: FIXABLE_GAPS` for fixable gaps or `CHARACTERIZATION_STATUS: INFEASIBLE` for infeasible characterization.
- [x] Add a coverage-disposition/session or deterministic step after coverage-review `"REPEAT"` that routes `FIXABLE_GAPS` to coverage-fix and `INFEASIBLE` to the terminal stop summary without running coverage-fix.
- [x] Add a constrained coverage-fix/session step that executes only newly identified characterization-test work and explicitly justified minimal testability seams.
- [x] In the coverage-fix prompt, forbid simplification/refactor work, unrelated cleanup, weakened expectations, and broad production edits.
- [x] Route coverage-fix completion back to coverage review so the test-net gate can iterate until complete or explicitly stopped.

## Slice 3 — Add baseline/diff enforcement before simplification

- [x] Add a pre-simplification diff-gate/session step after coverage review completion and before `implement-task`.
- [x] In the diff-gate prompt, require comparing both committed changes since `characterization-baseline.edn`'s recorded HEAD and current uncommitted worktree status/diff to the recorded pre-characterization baseline, then classifying every coverage-phase change.
- [x] Allow only characterization tests, task artifacts, docs, and explicitly justified minimal testability seams to pass the diff gate.
- [x] Make the diff gate stop with `PASS_STATUS: ACTIONABLE_FEEDBACK` when it finds baseline-time dirty target/source paths, unclassified source/target changes, broad production edits, premature simplification/refactor work, missing `characterization-baseline.edn` data, or `CHARACTERIZATION_STATUS: INFEASIBLE`.
- [x] Make the diff gate route raw `PASS_STATUS: REVIEW_COMPLETE` → normalized `"DONE"` to `implement-task` and raw `PASS_STATUS: ACTIONABLE_FEEDBACK` → normalized `"REPEAT"` to the explicit terminal stop/summary step.
- [x] Add explicit `implement-task` and `review-task-implementation` delegate steps after the diff gate, preserving inherited current-worktree execution and task handoff context.
- [x] Add a final summary/session step for successful target-present runs that reports the design → plan → test-net gate → simplification → review outcome.
- [x] Add a terminal stop/summary session step for target-present gate failures that reports dirty-baseline, failed diff-gate, or infeasible-characterization outcomes without claiming simplification ran; no-target remains the selector's direct `:done` route and must not run this step.

## Slice 4 — Lock workflow behavior in tests

- [x] Update `components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj` or add a task-212-specific test namespace to assert the new `reduce-incidental-complexity` step order.
- [x] Assert explicit delegate targets for `review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`, and `review-task-implementation`.
- [x] Assert the old whole-task `task-lifecycle` delegate is not the target-present implementation path.
- [x] Assert `select-and-create` still routes no-target raw `PASS_STATUS: ACTIONABLE_FEEDBACK` → normalized `"REPEAT"` → `:done` with no downstream terminal summary, and target-present raw `PASS_STATUS: REVIEW_COMPLETE` → normalized `"DONE"` into the explicit lifecycle sequence.
- [x] Assert coverage review raw `PASS_STATUS: ACTIONABLE_FEEDBACK` normalizes to `"REPEAT"` and routes first to coverage-disposition; assert disposition sends `CHARACTERIZATION_STATUS: FIXABLE_GAPS` to coverage-fix and `CHARACTERIZATION_STATUS: INFEASIBLE` to terminal stop, and coverage-fix routes back to coverage review.
- [x] Assert coverage review `REVIEW_COMPLETE` cannot route directly to `implement-task` without the pre-simplification diff gate.
- [x] Assert the diff gate raw `PASS_STATUS: REVIEW_COMPLETE` normalizes to `"DONE"` and routes to `implement-task`, while raw `PASS_STATUS: ACTIONABLE_FEEDBACK` normalizes to `"REPEAT"` and routes to the terminal stop/summary path.
- [x] Assert prompt text locks the clean-source baseline precondition for target/source paths before baseline recording.
- [x] Assert prompt text locks the baseline artifact path and contents: `characterization-baseline.edn` with HEAD/status plus target/source paths identified by the task and any explicitly classified pre-existing task-artifact/doc dirt.
- [x] Assert prompt text locks the coverage-fix constraint to characterization tests and explicitly justified minimal testability seams.
- [x] Assert prompt text forbids simplification/refactor work during the characterization gate.
- [x] Assert prompt text locks the diff method (committed changes since recorded baseline HEAD plus current uncommitted status/diff), classification categories, and stop conditions for unclassified or non-minimal source/target edits.
- [x] Assert prompt text preserves current inherited worktree execution and forbids `work-on`/worktree switching.
- [x] Update delegate co-loading tests so all directly referenced workflows in the new sequence resolve when loaded together.

## Slice 5 — Update user-facing docs and changelog

- [x] Update `doc/workflows.md` to describe `reduce-incidental-complexity` as running an explicit pre-simplification characterization-test-net gate before simplification implementation.
- [x] Document that the gate loops on coverage feedback and stops before simplification when characterization is infeasible, the baseline is dirty, or the coverage-phase diff includes unclassified/non-minimal source changes.
- [x] Ensure `doc/workflows.md` still states that `reduce-incidental-complexity` runs in the invoking session's current inherited worktree and does not call `work-on`.
- [x] Add a `CHANGELOG.md` `[Unreleased]` entry describing the changed `reduce-incidental-complexity` workflow behavior.

## Slice 6 — Verification and task artifacts

- [x] Run the focused workflow-loader test namespace that covers `reduce-incidental-complexity` and record the result in `implementation.md`.
- [x] Run the broader relevant workflow definition tests if the focused test does not cover all touched workflow definitions, and record the result in `implementation.md`.
- [x] Run `bb lint` or the narrow lint command covering changed Clojure test files and record the result in `implementation.md`.
- [x] Run `bb fmt:check` or format verification for changed Clojure/EDN files and record the result in `implementation.md`.
- [x] Run `bb commit-check:file-lengths` and record the result in `implementation.md`.
- [x] Review `git diff` to confirm changes are limited to workflow definitions/prompts, workflow tests, docs/changelog, and task artifacts.
- [x] Mark completed checklist items in `steps.md` and append final implementation status to `implementation.md`.
- [x] Commit implementation, docs, tests, and task artifact updates with a symbolized Munera commit message.

## Plan ambiguity review follow-ups

- [x] PA1: Clarify the no-target terminal route: Slice 1 says `select-and-create` `ACTIONABLE_FEEDBACK` goes directly to workflow completion, while Slice 3 says the terminal stop/summary step reports no-target; choose exactly one no-target path and lock it in tests so no-target runs do not accidentally execute later gate/summary steps or lose the existing selector final report.
- [x] PA2: Clarify the infeasible-characterization route from coverage review: the plan says coverage review emits `ACTIONABLE_FEEDBACK` for both fixable coverage gaps and infeasible characterization, but the same route currently points to coverage-fix; specify whether infeasible characterization goes to terminal stop, how it is distinguished from fixable gaps, and what artifact note/status proves simplification will not proceed.
- [x] PA3: Specify the characterization baseline artifact contract and diff method: name the baseline file/path in the task directory, define its minimum fields, and state whether the diff gate compares committed changes since the recorded baseline HEAD plus current worktree status so coverage-fix commits cannot make the gate see an empty `git diff`.
- [x] PA4: Clarify workflow routing vocabulary in the plan/tests: prompts emit `PASS_STATUS: REVIEW_COMPLETE` / `PASS_STATUS: ACTIONABLE_FEEDBACK`, but `workflow/pass-status-routing` yields `DONE` / `REPEAT` for EDN `:on` maps; make the steps say which layer each token belongs to so implementers do not author unreachable `:on` keys.
## Implementation review follow-ups

- [x] IR1: Make artifact-recording gate steps writable: `coverage-review` and `diff-gate` require recording coverage status, reviewed coverage, diff classification, and stop findings in task artifacts, so either add `edit`/`write` (and commit/update wording as needed) to those session steps or move the recording to an explicit writable step; add workflow-definition tests that lock the chosen write contract.
- [x] IR2: Make `clean-baseline` failure records explicit and durable: because `terminal-stop-summary` relies on task artifacts rather than conditional gate-output context, `clean-baseline` must append/commit a task-artifact finding before `PASS_STATUS: ACTIONABLE_FEEDBACK` when target/source paths are missing or dirty, and tests should lock the failure-path record contract.
- [x] IR3: Make `coverage-disposition` route from the latest characterization status: because task artifacts are append-only and `coverage-review` can run multiple times, disposition must use the immediately preceding coverage-review output or the latest committed task-artifact `CHARACTERIZATION_STATUS`, not any historical marker; update prompt/tests so stale `FIXABLE_GAPS` or `INFEASIBLE` records cannot override the latest status.
- [x] IR4: Make `coverage-disposition` terminal-stop failures durable: when disposition stops because the latest characterization status is ambiguous, missing, has both markers, or only stale historical markers are available, it must append/commit a task-artifact stop finding (or terminal summary must source that disposition output) so `terminal-stop-summary` can explain the stop without relying on ephemeral child-session output; add workflow-definition tests for the chosen contract.
- [x] IR5: Reconcile the `CHANGELOG.md` Unreleased `Added` entry for `reduce-incidental-complexity`: it still says target-present runs "drive through the full `task-lifecycle`", contradicting the implemented explicit-phase topology and the `Changed` entry; update the user-facing changelog wording (or otherwise remove the contradiction) so docs/changelog match the workflow.

## Test review follow-ups

- [x] TT5: Lock the target-present gate-failure terminal route: add workflow-definition assertions that `terminal-stop-summary` is terminal (no `:judge`/`:on` continuation, or only an explicit route to `:done`) so dirty-baseline, infeasible-characterization, and failed-diff paths cannot regress into `implement-task`, `final-summary`, or any other downstream step while existing route/prompt assertions stay green.

- [x] TT4: Lock the successful target-present final-summary route: add workflow-definition assertions that `review-task-implementation` advances to `final-summary` by default (no `:judge`/`:on` shortcut to `:done`) and that the final summary remains the terminal successful path, so a regression cannot keep step-order/prompt tests green while skipping the user-facing design → plan → test-net → diff-gate → simplification → review summary.

- [x] TT3: Lock the gate-step `:judge` contracts, not just `:on` maps and prompt text: add workflow-definition assertions that `clean-baseline`, `coverage-review`, `coverage-disposition`, `coverage-fix`, and `diff-gate` all invoke `workflow/pass-status-routing` from their own `:output :final-llm-reply` with the intended allowed statuses, so a regression to constant routing or the wrong source step cannot keep the focused task-212 tests green while bypassing the raw `PASS_STATUS` gate decisions.

- [x] TT2: Lock the transitive post-implementation test-review gate: add workflow-definition coverage proving `review-task-implementation` includes a `review-task-tests` delegate to `review-step` with `:skill "task-test-review"` (or equivalent), so the final reduce-incidental-complexity implementation-review phase cannot silently lose or retarget the task-test-review pass while focused task-212 tests remain green.

- [x] TT1: Strengthen the `reduce-incidental-complexity` delegate co-loading test to load the real directly referenced workflow files (`review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`, `review-task-implementation`) plus their required prompt-workflow markdown dependencies, rather than synthetic stub workflows, so the explicit-phase delegate chain is proven against the actual workflow corpus.
