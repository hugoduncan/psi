# Steps — Incidental simplification pre-refactor test-net gate

## Slice 0 — Workflow topology orientation

- [ ] Read `.psi/workflows/reduce-incidental-complexity.edn` and record the current `select-and-create` → `lifecycle` routing contract in `implementation.md`.
- [ ] Read `.psi/workflows/task-lifecycle.edn` and note the sub-workflow order that must be exposed in `reduce-incidental-complexity`.
- [ ] Read `.psi/workflows/review-task-design.edn`, `.psi/workflows/create-task-plan.edn`, `.psi/workflows/review-task-plan.edn`, `.psi/workflows/implement-task.edn`, and `.psi/workflows/review-task-implementation.edn` to confirm delegate input/context shapes.
- [ ] Read `doc/workflow-grammar.md` and `doc/workflow-grammar-concepts.md` for delegate/session routing grammar relevant to the new topology.
- [ ] Read `components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj` and identify existing assertions that must change from whole-lifecycle delegation to explicit phased delegation.
- [ ] Record any implementation constraints or non-blocking discoveries in `implementation.md`.

## Slice 1 — Expand target-present lifecycle topology

- [ ] Edit `.psi/workflows/reduce-incidental-complexity.edn` so `select-and-create` routes target-present `REVIEW_COMPLETE` to `review-task-design` instead of directly to a whole `task-lifecycle` delegate.
- [ ] Add explicit `:delegate` steps for `review-task-design`, `create-task-plan`, and `review-task-plan` using `{:type :map :fields {:input {:from {:step "select-and-create" :yield :text}}}}` or an equivalent task-path handoff that existing delegated workflows can consume.
- [ ] Preserve the no-target route at the `select-and-create` boundary: selector `PASS_STATUS: ACTIONABLE_FEEDBACK` normalizes through `workflow/pass-status-routing` to `"REPEAT"` and routes directly to `:done`, without creating/inspecting a task or running any downstream terminal-summary step.
- [ ] Ensure each planning/review delegate carries appropriate `:context` from `:workflow-original` and the `select-and-create` yielded handoff.
- [ ] Remove or bypass the old target-present opaque `task-lifecycle` delegate path so it cannot hide the Phase 0/Phase 1 boundary.

## Slice 2 — Add the characterization-test-net gate

- [ ] Add a clean-baseline/session step after `review-task-plan` that reads the generated task artifacts, identifies target/source paths, verifies those paths are not already dirty, writes `characterization-baseline.edn` in the task directory with HEAD/status/target-source-paths/explicitly-classified pre-existing task-artifact-or-doc dirt, commits task-artifact updates when needed, and emits a single `PASS_STATUS` line.
- [ ] Route clean-baseline raw `PASS_STATUS: REVIEW_COMPLETE` → normalized `"DONE"` to the coverage review step and raw `PASS_STATUS: ACTIONABLE_FEEDBACK` → normalized `"REPEAT"` to an explicit terminal stop/summary step.
- [ ] Add a coverage review/session step using `task-test-review` and `testing-without-mocks` guidance to assess nominal, edge, and boundary coverage of the target's observable behavior before simplification.
- [ ] Make coverage review emit `PASS_STATUS: REVIEW_COMPLETE` only when the characterization net is sufficient and green against unmodified target behavior.
- [ ] Make coverage review emit `PASS_STATUS: ACTIONABLE_FEEDBACK` when characterization gaps remain or sufficient characterization is infeasible, and require it to record exactly one task-artifact marker: `CHARACTERIZATION_STATUS: FIXABLE_GAPS` for fixable gaps or `CHARACTERIZATION_STATUS: INFEASIBLE` for infeasible characterization.
- [ ] Add a coverage-disposition/session or deterministic step after coverage-review `"REPEAT"` that routes `FIXABLE_GAPS` to coverage-fix and `INFEASIBLE` to the terminal stop summary without running coverage-fix.
- [ ] Add a constrained coverage-fix/session step that executes only newly identified characterization-test work and explicitly justified minimal testability seams.
- [ ] In the coverage-fix prompt, forbid simplification/refactor work, unrelated cleanup, weakened expectations, and broad production edits.
- [ ] Route coverage-fix completion back to coverage review so the test-net gate can iterate until complete or explicitly stopped.

## Slice 3 — Add baseline/diff enforcement before simplification

- [ ] Add a pre-simplification diff-gate/session step after coverage review completion and before `implement-task`.
- [ ] In the diff-gate prompt, require comparing both committed changes since `characterization-baseline.edn`'s recorded HEAD and current uncommitted worktree status/diff to the recorded pre-characterization baseline, then classifying every coverage-phase change.
- [ ] Allow only characterization tests, task artifacts, docs, and explicitly justified minimal testability seams to pass the diff gate.
- [ ] Make the diff gate stop with `PASS_STATUS: ACTIONABLE_FEEDBACK` when it finds baseline-time dirty target/source paths, unclassified source/target changes, broad production edits, premature simplification/refactor work, missing `characterization-baseline.edn` data, or `CHARACTERIZATION_STATUS: INFEASIBLE`.
- [ ] Make the diff gate route raw `PASS_STATUS: REVIEW_COMPLETE` → normalized `"DONE"` to `implement-task` and raw `PASS_STATUS: ACTIONABLE_FEEDBACK` → normalized `"REPEAT"` to the explicit terminal stop/summary step.
- [ ] Add explicit `implement-task` and `review-task-implementation` delegate steps after the diff gate, preserving inherited current-worktree execution and task handoff context.
- [ ] Add a final summary/session step for successful target-present runs that reports the design → plan → test-net gate → simplification → review outcome.
- [ ] Add a terminal stop/summary session step for target-present gate failures that reports dirty-baseline, failed diff-gate, or infeasible-characterization outcomes without claiming simplification ran; no-target remains the selector's direct `:done` route and must not run this step.

## Slice 4 — Lock workflow behavior in tests

- [ ] Update `components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj` or add a task-212-specific test namespace to assert the new `reduce-incidental-complexity` step order.
- [ ] Assert explicit delegate targets for `review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`, and `review-task-implementation`.
- [ ] Assert the old whole-task `task-lifecycle` delegate is not the target-present implementation path.
- [ ] Assert `select-and-create` still routes no-target raw `PASS_STATUS: ACTIONABLE_FEEDBACK` → normalized `"REPEAT"` → `:done` with no downstream terminal summary, and target-present raw `PASS_STATUS: REVIEW_COMPLETE` → normalized `"DONE"` into the explicit lifecycle sequence.
- [ ] Assert coverage review raw `PASS_STATUS: ACTIONABLE_FEEDBACK` normalizes to `"REPEAT"` and routes first to coverage-disposition; assert disposition sends `CHARACTERIZATION_STATUS: FIXABLE_GAPS` to coverage-fix and `CHARACTERIZATION_STATUS: INFEASIBLE` to terminal stop, and coverage-fix routes back to coverage review.
- [ ] Assert coverage review `REVIEW_COMPLETE` cannot route directly to `implement-task` without the pre-simplification diff gate.
- [ ] Assert the diff gate raw `PASS_STATUS: REVIEW_COMPLETE` normalizes to `"DONE"` and routes to `implement-task`, while raw `PASS_STATUS: ACTIONABLE_FEEDBACK` normalizes to `"REPEAT"` and routes to the terminal stop/summary path.
- [ ] Assert prompt text locks the clean-source baseline precondition for target/source paths before baseline recording.
- [ ] Assert prompt text locks the baseline artifact path and contents: `characterization-baseline.edn` with HEAD/status plus target/source paths identified by the task and any explicitly classified pre-existing task-artifact/doc dirt.
- [ ] Assert prompt text locks the coverage-fix constraint to characterization tests and explicitly justified minimal testability seams.
- [ ] Assert prompt text forbids simplification/refactor work during the characterization gate.
- [ ] Assert prompt text locks the diff method (committed changes since recorded baseline HEAD plus current uncommitted status/diff), classification categories, and stop conditions for unclassified or non-minimal source/target edits.
- [ ] Assert prompt text preserves current inherited worktree execution and forbids `work-on`/worktree switching.
- [ ] Update delegate co-loading tests so all directly referenced workflows in the new sequence resolve when loaded together.

## Slice 5 — Update user-facing docs and changelog

- [ ] Update `doc/workflows.md` to describe `reduce-incidental-complexity` as running an explicit pre-simplification characterization-test-net gate before simplification implementation.
- [ ] Document that the gate loops on coverage feedback and stops before simplification when characterization is infeasible, the baseline is dirty, or the coverage-phase diff includes unclassified/non-minimal source changes.
- [ ] Ensure `doc/workflows.md` still states that `reduce-incidental-complexity` runs in the invoking session's current inherited worktree and does not call `work-on`.
- [ ] Add a `CHANGELOG.md` `[Unreleased]` entry describing the changed `reduce-incidental-complexity` workflow behavior.

## Slice 6 — Verification and task artifacts

- [ ] Run the focused workflow-loader test namespace that covers `reduce-incidental-complexity` and record the result in `implementation.md`.
- [ ] Run the broader relevant workflow definition tests if the focused test does not cover all touched workflow definitions, and record the result in `implementation.md`.
- [ ] Run `bb lint` or the narrow lint command covering changed Clojure test files and record the result in `implementation.md`.
- [ ] Run `bb fmt:check` or format verification for changed Clojure/EDN files and record the result in `implementation.md`.
- [ ] Run `bb commit-check:file-lengths` and record the result in `implementation.md`.
- [ ] Review `git diff` to confirm changes are limited to workflow definitions/prompts, workflow tests, docs/changelog, and task artifacts.
- [ ] Mark completed checklist items in `steps.md` and append final implementation status to `implementation.md`.
- [ ] Commit implementation, docs, tests, and task artifact updates with a symbolized Munera commit message.

## Plan ambiguity review follow-ups

- [x] PA1: Clarify the no-target terminal route: Slice 1 says `select-and-create` `ACTIONABLE_FEEDBACK` goes directly to workflow completion, while Slice 3 says the terminal stop/summary step reports no-target; choose exactly one no-target path and lock it in tests so no-target runs do not accidentally execute later gate/summary steps or lose the existing selector final report.
- [x] PA2: Clarify the infeasible-characterization route from coverage review: the plan says coverage review emits `ACTIONABLE_FEEDBACK` for both fixable coverage gaps and infeasible characterization, but the same route currently points to coverage-fix; specify whether infeasible characterization goes to terminal stop, how it is distinguished from fixable gaps, and what artifact note/status proves simplification will not proceed.
- [x] PA3: Specify the characterization baseline artifact contract and diff method: name the baseline file/path in the task directory, define its minimum fields, and state whether the diff gate compares committed changes since the recorded baseline HEAD plus current worktree status so coverage-fix commits cannot make the gate see an empty `git diff`.
- [x] PA4: Clarify workflow routing vocabulary in the plan/tests: prompts emit `PASS_STATUS: REVIEW_COMPLETE` / `PASS_STATUS: ACTIONABLE_FEEDBACK`, but `workflow/pass-status-routing` yields `DONE` / `REPEAT` for EDN `:on` maps; make the steps say which layer each token belongs to so implementers do not author unreachable `:on` keys.
