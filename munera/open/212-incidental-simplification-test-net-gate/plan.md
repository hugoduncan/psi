# Plan — Incidental simplification pre-refactor test-net gate

## Approach

Replace the target-present `reduce-incidental-complexity` handoff from one opaque `task-lifecycle` delegate with an explicit workflow sequence that preserves the same outer selection/current-worktree contract while exposing the Phase 0/Phase 1 boundary as workflow topology.

The target-present path should become:

1. select target and create the Munera task in the invoking session's current inherited worktree;
2. review the generated design;
3. create plan/steps;
4. review plan/steps;
5. establish and iterate the characterization-test net before simplification;
6. run a workflow-level baseline/diff gate before simplification routing;
7. only then run simplification implementation and implementation review;
8. report a terminal summary.

Key decisions:

- Keep the no-target branch at the existing `select-and-create` boundary: no task, no worktree switch, no downstream lifecycle work.
- Use existing task lifecycle sub-workflows (`review-task-design`, `create-task-plan`, `review-task-plan`, `implement-task`, `review-task-implementation`) as explicit delegates instead of delegating to the whole `task-lifecycle` bundle.
- Add the pre-simplification gate as workflow-owned prompt/session steps rather than runtime special-case logic.
- Use deterministic `workflow/pass-status-routing` for gate decisions. The same `ACTIONABLE_FEEDBACK` token can route to different destinations by step: coverage review routes to a constrained coverage-fix loop, while clean-baseline or diff-gate failure routes to a terminal stop summary.
- Add a dedicated constrained characterization-fix prompt/session that may add characterization tests and explicitly justified minimal testability seams, but must not perform the simplification/refactor.
- Record the pre-characterization baseline in the task directory before characterization work starts. The baseline must include at least HEAD/status and the target/source paths identified by the generated task.
- Enforce the clean-source precondition before recording that baseline: pre-existing dirty target/source changes stop the workflow with an explicit finding; only explicitly classified task-artifact/doc changes may be carried forward.
- Classify the coverage-phase diff before simplification. Only tests, task artifacts, docs, and explicitly justified minimal testability seams may pass. Unclassified source changes, broad production edits, or simplification/refactor work stop before implementation.
- Preserve the current inherited worktree model. No new step should call `work-on`, create a worktree, or switch branches.
- Update tests as structural workflow-definition tests plus prompt-contract locks, because this invariant is primarily workflow topology and prompt behavior.
- Update `doc/workflows.md` and `CHANGELOG.md` because the delegated workflow's user-visible behavior changes.

## Risks

- `workflow/pass-status-routing` has only the existing DONE/REPEAT route vocabulary. The plan relies on per-step `:on` maps to make `ACTIONABLE_FEEDBACK` mean either "fix and re-review" or "stop with explicit finding"; tests must lock those routes so the meaning does not drift.
- The baseline/diff gate is prompt-owned and git-visible, not runtime-attested. It must be made concrete enough in prompt text and tests to avoid becoming advisory prose.
- The gate needs a reliable way to identify target/source paths from the generated task artifacts. If the generated design lacks those paths, the baseline step must stop explicitly rather than inventing them.
- A coverage-fix pass can accidentally perform simplification work. The constrained prompt and diff gate must both forbid and detect that failure mode.
- Updating the existing task-209 workflow tests for task-212 behavior may make the test namespace more crowded; keep additions focused and under the file-length guard, or split only if necessary.
- Documentation must keep saying the workflow runs in the current inherited worktree; the new gate must not reintroduce the old worktree-switching model.

## Slice order

### Slice 0 — Workflow topology orientation

Re-read the current `reduce-incidental-complexity` workflow, task-lifecycle sub-workflows, workflow grammar docs, existing workflow tests, and user docs. Confirm the concrete step names, route shapes, and prompt assets to change before editing.

### Slice 1 — Expand target-present lifecycle topology

Modify `reduce-incidental-complexity.edn` so target-present runs delegate to `review-task-design`, `create-task-plan`, and `review-task-plan` explicitly before any implementation delegate. Preserve the no-target skip from `select-and-create`.

### Slice 2 — Add the characterization-test-net gate

Add workflow prompt/session steps for clean-baseline recording, coverage review, constrained coverage fixes, and coverage-loop routing. Ensure coverage review completion is required before any simplification implementation step is reachable.

### Slice 3 — Add baseline/diff enforcement before simplification

Add the workflow-level diff classification boundary after coverage review and before `implement-task`. Route clean classification to simplification; route dirty baseline, unclassified diff, broad source edits, or premature simplification to an explicit terminal stop summary.

### Slice 4 — Lock workflow behavior in tests

Update or add workflow-loader tests to lock step order, delegate targets, deterministic routes, clean-baseline precondition text, coverage-fix constraints, baseline/diff enforcement, no-refactor-before-test-net intent, no-target early stop, current-worktree inheritance, and delegate-target co-loading.

### Slice 5 — Update user-facing docs and changelog

Update `doc/workflows.md` to describe the explicit pre-simplification characterization gate and the current inherited worktree model. Add a `CHANGELOG.md` Unreleased entry for the changed workflow behavior.

### Slice 6 — Verification and task artifacts

Run the narrow workflow-loader tests, relevant workflow definition tests, lint/format checks required by the touched files, update `implementation.md`, check completed steps, and commit the implementation.