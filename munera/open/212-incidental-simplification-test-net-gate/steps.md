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
- [ ] Preserve the no-target `ACTIONABLE_FEEDBACK` route from `select-and-create` to workflow completion without creating or inspecting a task.
- [ ] Ensure each planning/review delegate carries appropriate `:context` from `:workflow-original` and the `select-and-create` yielded handoff.
- [ ] Remove or bypass the old target-present opaque `task-lifecycle` delegate path so it cannot hide the Phase 0/Phase 1 boundary.

## Slice 2 — Add the characterization-test-net gate

- [ ] Add a clean-baseline/session step after `review-task-plan` that reads the generated task artifacts, identifies target/source paths, verifies those paths are not already dirty, records HEAD/status/path baseline in the task directory, commits task-artifact updates when needed, and emits a single `PASS_STATUS` line.
- [ ] Route clean-baseline `REVIEW_COMPLETE` to the coverage review step and clean-baseline `ACTIONABLE_FEEDBACK` to an explicit terminal stop/summary step.
- [ ] Add a coverage review/session step using `task-test-review` and `testing-without-mocks` guidance to assess nominal, edge, and boundary coverage of the target's observable behavior before simplification.
- [ ] Make coverage review emit `PASS_STATUS: REVIEW_COMPLETE` only when the characterization net is sufficient and green against unmodified target behavior.
- [ ] Make coverage review emit `PASS_STATUS: ACTIONABLE_FEEDBACK` when characterization gaps remain or sufficient characterization is infeasible.
- [ ] Add a constrained coverage-fix/session step that executes only newly identified characterization-test work and explicitly justified minimal testability seams.
- [ ] In the coverage-fix prompt, forbid simplification/refactor work, unrelated cleanup, weakened expectations, and broad production edits.
- [ ] Route coverage-fix completion back to coverage review so the test-net gate can iterate until complete or explicitly stopped.

## Slice 3 — Add baseline/diff enforcement before simplification

- [ ] Add a pre-simplification diff-gate/session step after coverage review completion and before `implement-task`.
- [ ] In the diff-gate prompt, require comparing the current diff to the recorded pre-characterization baseline and classifying every coverage-phase change.
- [ ] Allow only characterization tests, task artifacts, docs, and explicitly justified minimal testability seams to pass the diff gate.
- [ ] Make the diff gate stop with `PASS_STATUS: ACTIONABLE_FEEDBACK` when it finds baseline-time dirty target/source paths, unclassified source/target changes, broad production edits, premature simplification/refactor work, missing baseline data, or infeasible characterization.
- [ ] Make the diff gate route `REVIEW_COMPLETE` to `implement-task` and `ACTIONABLE_FEEDBACK` to the explicit terminal stop/summary step.
- [ ] Add explicit `implement-task` and `review-task-implementation` delegate steps after the diff gate, preserving inherited current-worktree execution and task handoff context.
- [ ] Add a final summary/session step for successful target-present runs that reports the design → plan → test-net gate → simplification → review outcome.
- [ ] Add a terminal stop/summary session step that reports no-target, dirty-baseline, failed diff-gate, or infeasible-characterization outcomes without claiming simplification ran.

## Slice 4 — Lock workflow behavior in tests

- [ ] Update `components/workflow-loader/test/psi/workflow_loader/task_209_workflow_definitions_test.clj` or add a task-212-specific test namespace to assert the new `reduce-incidental-complexity` step order.
- [ ] Assert explicit delegate targets for `review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`, and `review-task-implementation`.
- [ ] Assert the old whole-task `task-lifecycle` delegate is not the target-present implementation path.
- [ ] Assert `select-and-create` still routes no-target `ACTIONABLE_FEEDBACK` to workflow completion and target-present `REVIEW_COMPLETE` into the explicit lifecycle sequence.
- [ ] Assert coverage review routes `ACTIONABLE_FEEDBACK` to coverage-fix and coverage-fix routes back to coverage review.
- [ ] Assert coverage review `REVIEW_COMPLETE` cannot route directly to `implement-task` without the pre-simplification diff gate.
- [ ] Assert the diff gate routes `REVIEW_COMPLETE` to `implement-task` and `ACTIONABLE_FEEDBACK` to the terminal stop/summary path.
- [ ] Assert prompt text locks the clean-source baseline precondition for target/source paths before baseline recording.
- [ ] Assert prompt text locks the baseline contents: HEAD/status plus target/source paths identified by the task.
- [ ] Assert prompt text locks the coverage-fix constraint to characterization tests and explicitly justified minimal testability seams.
- [ ] Assert prompt text forbids simplification/refactor work during the characterization gate.
- [ ] Assert prompt text locks diff classification categories and stop conditions for unclassified or non-minimal source/target edits.
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

- [ ] PA1: Clarify the no-target terminal route: Slice 1 says `select-and-create` `ACTIONABLE_FEEDBACK` goes directly to workflow completion, while Slice 3 says the terminal stop/summary step reports no-target; choose exactly one no-target path and lock it in tests so no-target runs do not accidentally execute later gate/summary steps or lose the existing selector final report.
- [ ] PA2: Clarify the infeasible-characterization route from coverage review: the plan says coverage review emits `ACTIONABLE_FEEDBACK` for both fixable coverage gaps and infeasible characterization, but the same route currently points to coverage-fix; specify whether infeasible characterization goes to terminal stop, how it is distinguished from fixable gaps, and what artifact note/status proves simplification will not proceed.
- [ ] PA3: Specify the characterization baseline artifact contract and diff method: name the baseline file/path in the task directory, define its minimum fields, and state whether the diff gate compares committed changes since the recorded baseline HEAD plus current worktree status so coverage-fix commits cannot make the gate see an empty `git diff`.
- [ ] PA4: Clarify workflow routing vocabulary in the plan/tests: prompts emit `PASS_STATUS: REVIEW_COMPLETE` / `PASS_STATUS: ACTIONABLE_FEEDBACK`, but `workflow/pass-status-routing` yields `DONE` / `REPEAT` for EDN `:on` maps; make the steps say which layer each token belongs to so implementers do not author unreachable `:on` keys.
