# 218 — Architecture Simplification Workflow

## Intent

Add a workflow that simplifies code **above the function/executable-unit level** by using Gordian's architecture-targeting commands to select and frame a high-value architectural refactor target, then driving a constrained Munera task through design, planning, test-net gating, implementation, and review.

This is the architectural sibling of `reduce-incidental-complexity`: instead of selecting a single high-burden function/unit, it selects a namespace, family, pair, or community-scale target from `bb gordian architecture-targets`. After selection, `bb gordian target-issues` provides supporting problem framing when it can describe the selected target.

## Why

`reduce-incidental-complexity` is intentionally scoped to function-level incidental complexity. It is not the right tool for architecture-level issues such as cycles, god modules, unstable shared namespaces, cross-lens hidden coupling, missing abstractions, or family/community boundaries that need to be reshaped.

Gordian now has higher-level commands for that job:

- `bb gordian architecture-targets` deterministically ranks above-unit refactor candidates.
- `bb gordian target-issues` turns a selected candidate into a structured issue description with observed signals, hypotheses, refactoring directions, review questions, success signals, and recommended next steps.

Psi should expose those commands through a repeatable workflow that creates a safe, reviewed simplification task rather than relying on ad hoc manual triage.

## Scope

### In scope

- A new user-invokable workflow for architecture-level simplification.
- Selection using `bb gordian architecture-targets` rather than manual diagnosis triage.
- Target selection based on `bb gordian architecture-targets`; `target-issues` is informational framing after the target is selected, not a selector or eligibility gate.
- Generated Munera task design for a behaviour-preserving architectural simplification.
- A mandatory pre-simplification test-net gate, similar in spirit to `reduce-incidental-complexity`:
  - baseline clean-source recording,
  - coverage review,
  - characterization-test fix loop when coverage gaps are fixable,
  - terminal stop when characterization is infeasible or the baseline/diff gate fails,
  - no simplification until tests are in place and green.
- Objective before/after architectural validation using Gordian snapshots/compare/gate.
- Workflow-loader/parser/compiler tests that lock the workflow shape, routing, and prompt contracts.
- User-facing documentation and changelog entry for the new workflow.

### Out of scope

- Replacing `reduce-incidental-complexity` or changing its function-level selector.
- Implementing new Gordian analysis commands; this task consumes the existing `architecture-targets` and `target-issues` commands.
- Automatically pushing a branch or opening a PR.
- Broad, multi-target architecture programs. Each workflow run handles one selected target and one Munera task.
- A persistent skip list for rejected targets unless implementation discovers it is required for deterministic no-progress avoidance.

## Workflow shape

Create a new `.psi/workflows/reduce-architectural-complexity.edn` orchestration workflow. The workflow name is fixed as `reduce-architectural-complexity`, clearly distinguishing architecture-level simplification from the function-level `reduce-incidental-complexity`.

The workflow should follow the proven shape of `reduce-incidental-complexity` where it applies, especially the post-selection test-net gate. The new workflow does not need to copy every incidental-complexity numeric acceptance rule, because its target type and validation lens are different. It runs in the invoking worktree, matching the current `reduce-incidental-complexity` model; no worktree wrapper is part of this task.

### Step 1 — select and create architecture task

A `:session` step should:

1. Confirm it is running in the intended invoking worktree. It must not call `work-on`, create another worktree, or switch branches; the caller is responsible for invoking the workflow from the intended isolated branch/worktree.
2. Run `bb gordian architecture-targets --edn` or `--json` from the worktree root.
3. Select the deterministic winner from the emitted `:architecture-target-ranking` unless there is no eligible/actionable target. The selected target is based on `architecture-targets`, not on what `target-issues` can describe.
4. Run `bb gordian target-issues --candidate '<candidate-id>'` for the selected candidate when `target-issues` supports that candidate type.
   - `target-issues` is informational framing after selection. It must not change the selected target and must not cause the workflow to skip a valid `architecture-targets` winner.
   - If `target-issues` cannot describe the selected candidate type, the workflow should still create the task from the `architecture-targets` evidence and record that no `target-issues` framing was available for this candidate. The generated task should then require the implementation architecture review to compensate by checking the broader project architecture sources and relevant Gordian evidence directly.
   - The supported and unsupported `target-issues` paths must both be explicit and tested.
5. Capture baseline artifacts in the generated task directory, including at least:
   - a Gordian diagnose snapshot suitable for `bb gordian gate --baseline`,
   - the architecture-targets ranking output,
   - the target-issues output for the selected candidate when available.
6. Create a Munera task under `munera/open/NNN-slug/design.md` describing the selected architecture target, observed signals, hypotheses, refactoring directions, scope, non-goals, and acceptance criteria.
7. Commit the task creation and baseline artifacts.
8. Emit a structured handoff containing `munera_task_path:` for downstream steps.

### Early stop behaviour

If no target is eligible from `architecture-targets`, or the command outputs cannot be interpreted safely, the workflow must stop before task creation. Lack of `target-issues` support for the selected candidate is not a no-target condition; it is recorded as missing supplemental framing. The workflow must not fabricate a target or create an empty task.

The no-target response should be deterministic and should route the workflow to completion without running design/plan/test/implementation steps.

### Generated architecture task contract

The generated task is a behaviour-preserving architectural simplification. Its `design.md` must state:

- the selected candidate id and type,
- why this target was selected, citing `architecture-targets` ranking evidence,
- the `target-issues` problem framing when available, preserving the boundary between observations, hypotheses, directions, and review questions; when unavailable, an explicit note that selection is based solely on `architecture-targets` evidence,
- intended simplification scope,
- explicit non-goals and blast-radius limits,
- the existing behaviour that must remain unchanged,
- test-net requirements before refactoring,
- Gordian before/after validation requirements.

The generated task should not prescribe exact implementation mechanics beyond the level needed to keep the refactor constrained and behaviour-preserving.

## Test-net gate requirement

The workflow must gate architectural simplification on tests being in place, similar to the hardened `reduce-incidental-complexity` workflow.

Required gate semantics:

1. **Clean baseline before characterization work**
   - Record git `HEAD`, `git status --short`, target/source areas, and any classified pre-existing dirt in a task-local baseline artifact.
   - Stop before simplification if the target/source area is already dirty in a way that would make current behaviour ambiguous.

2. **Coverage review before simplification**
   - Review existing tests for behaviour affected by the architecture target.
   - Coverage must be judged against observable state/outputs and relevant nominal, edge, and boundary behaviour.
   - For architecture-level targets, coverage may need to be subsystem or integration-level; the workflow must allow that without defaulting to broad, slow, unfocused tests.

3. **Characterization fix loop**
   - If coverage gaps are fixable, add characterization tests for current behaviour before simplification.
   - Minimal production seams are allowed only when needed to observe behaviour, and must be recorded as such.
   - Tests must be green against the pre-simplification behaviour.

4. **Infeasible coverage stop**
   - If the target cannot be characterized safely, record the finding in task artifacts and stop. Do not refactor.

5. **Diff/baseline gate before implementation**
   - After characterization work and before simplification, compare changes since the clean baseline.
   - Only characterization tests, task artifacts, docs, and explicitly justified minimal testability seams are allowed.
   - Any premature architecture simplification, broad production edit, or unclassified source change stops the workflow before implementation.

6. **Implementation only after gate pass**
   - `implement-task` may run only after the coverage review and diff/baseline gate both pass.

## Architectural validation and review after implementation

The generated task acceptance criteria must include Gordian validation appropriate for above-function-level simplification:

- rerun `bb gordian diagnose --edn` after the change,
- run `bb gordian compare <before> <after>` or an equivalent before/after comparison when artifact format permits,
- run `bb gordian gate --baseline <before-diagnose>` with explicit failure conditions for new cycles and new high findings, and no new medium findings unless the final design intentionally chooses a different stricter gate,
- when `target-issues` framing is available, verify the selected target's stated success signals were improved or explicitly explain why a signal is not expected to move in the first slice; when unavailable, verify success against the `architecture-targets` evidence, Gordian before/after output, and the implementation architecture review findings,
- keep all characterization and affected-area tests green.

Acceptance must not rely only on subjective “architecture looks better” judgement. Because quantitative architectural success can be difficult to encode completely, the workflow output must also go through explicit review gates after implementation:

- implementation architecture review: use a new dedicated skill to verify the implemented change against the selected architecture target, any `target-issues` framing, Gordian evidence, and the project's broader existing architecture (`AGENTS.md`, `META.md`, `doc/architecture.md`, and relevant local architecture docs). This is distinct from `review-task-architecture`, which reviews design fit before implementation. The implementation architecture review must judge whether the actual code change preserves/improves the intended architecture without broadening scope, fighting established boundaries, or introducing worse coupling,
- test-shaper review: judge whether the characterization/affected-area tests are clear, robust, focused, and sufficient for the behaviour-preserving architecture change,
- code-shaper review: judge whether the implementation is simple, consistent, robust, and genuinely simplifies the architecture rather than adding adapters/shims/indirection.

These reviews must reuse the existing shared review-loop architecture rather than introducing a bespoke loop. This task should introduce a separate implementation architecture review skill (candidate name: `review-implementation-architecture`) and route it through the existing `review-step` workflow, so actionable findings are written to the generated task's `steps.md` and executed by the shared `review-follow-up-steps` machinery until the skill reports no actionable feedback. The `review-step` invocation for this skill must receive the selected Gordian architecture target, the `architecture-targets` evidence, any `target-issues` framing, before/after Gordian validation artifacts, and the Munera task artifacts in context.

The architecture-specific gate is workflow-local to `reduce-architectural-complexity`: it should be inserted explicitly into that workflow's post-implementation review sequence before or alongside the existing test-shaper and code-shaper gates. It must not be implemented as a custom review/follow-up loop, and it must not broaden the generic `review-task-implementation` workflow for unrelated tasks unless a separate general review-design decision explicitly chooses that broader policy. `implement-task` output is not considered complete for this workflow until implementation architecture, test-shaper, and code-shaper review feedback has been resolved.

## Relationship to existing workflows

- `reduce-incidental-complexity` remains the function-level workflow.
- The new workflow is the architecture-level workflow.
- Both workflows should share the same safety principle: no behaviour-preserving simplification without a green test net.
- Reuse existing review/design/plan/implementation workflows where possible instead of inventing a parallel lifecycle.
- Reuse or mirror the existing `clean-baseline`, `coverage-review`, `coverage-disposition`, `coverage-fix`, `diff-gate`, `implement-task`, and `review-task-implementation` routing pattern from `reduce-incidental-complexity` when it remains appropriate.
- Add post-implementation review routing so the new implementation architecture review skill, test-shaper, and code-shaper reviews are explicit gates for this architecture-level workflow.

## Acceptance criteria for this task

- A new architecture-level simplification workflow exists as `.psi/workflows/reduce-architectural-complexity.edn` and loads through the normal workflow loader.
- The workflow uses `bb gordian architecture-targets` for target ranking/selection and `bb gordian target-issues` only for post-selection issue framing when supported.
- Unsupported `target-issues` candidate types do not change selection and do not force a no-target stop; generated tasks record missing supplemental framing and proceed from `architecture-targets` evidence. Missing or uninterpretable `architecture-targets` output still stops before task creation.
- Generated Munera tasks include architecture-target evidence, target-issues framing when available, behaviour-preservation constraints, test-net gate requirements, and Gordian validation criteria.
- The workflow includes a mandatory pre-simplification test-net gate equivalent in strength to `reduce-incidental-complexity` for this higher-level target type.
- `implement-task` cannot run unless the characterization coverage review and baseline/diff gate pass.
- Tests lock workflow parsing/loading, command/prompt contracts, pass-status routing, no-target routing, unsupported-`target-issues` informational routing, generated-task prompt content, test-net gate ordering, invoking-worktree constraints, and post-implementation implementation-architecture/test-shaper/code-shaper review gates.
- User-facing docs mention the new workflow and distinguish it from `reduce-incidental-complexity`.
- CHANGELOG `[Unreleased]` records the new workflow.

## Locked decisions

1. Workflow name is `reduce-architectural-complexity`.
2. The workflow runs in the invoking worktree. It must not call `work-on`, create another worktree, or switch branches.
3. Selection is based on `architecture-targets`. `target-issues` is post-selection information only; unsupported `target-issues` candidate types do not cause the workflow to choose a different target.
4. Post-implementation completion requires a separate implementation architecture review skill plus test-shaper and code-shaper review gates, in the repeat-until-clean style of `review-task-implementation`, because numeric Gordian validation alone cannot capture all architectural-fit concerns.
5. The implementation architecture review skill is distinct from the existing design-oriented `review-task-architecture` skill. It verifies the actual implemented code change against the selected Gordian target, the project's broader existing architecture, and the intended architecture direction.
