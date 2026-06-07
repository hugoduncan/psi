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
2. Run `bb gordian architecture-targets --edn` from the worktree root. JSON is not part of this workflow contract; the selector and tests consume the authoritative EDN envelope.
3. Select the deterministic winner from the top-level `:winner` map when it is present, eligible, and has an interpretable candidate id/type. The top-level `:candidates` vector is retained as ranking context and evidence for tests/review, but selection is the top-level `:winner`. The selected target is based on `architecture-targets`, not on what `target-issues` can describe.
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


### Selector routing contract

The `select-and-create` step owns the only no-target / uninterpretable-output decision. Its final response must use exactly one raw `PASS_STATUS` line, and the workflow EDN must route only through the normalized `workflow/pass-status-routing` result:

- target selected and task created: final line exactly `PASS_STATUS: REVIEW_COMPLETE`; `workflow/pass-status-routing` is invoked with `:allowed-statuses ["ACTIONABLE_FEEDBACK" "REVIEW_COMPLETE"]`, normalizes this to `"DONE"`, and the `select-and-create` `:on` map routes `"DONE"` to `review-task-design`,
- no eligible top-level `:winner`, missing or non-vector top-level `:candidates`, missing candidate id/type on the winner, unsupported candidate shape needed for membership resolution, or otherwise uninterpretable `architecture-targets --edn` envelope: final line exactly `PASS_STATUS: ACTIONABLE_FEEDBACK`; the same judge normalizes this to `"REPEAT"`, and the `select-and-create` `:on` map routes `"REPEAT"` directly to `:done`,
- the no-target / uninterpretable response must not emit a `munera_task_path:` line and must not create a Munera task or baseline artifact,
- raw `PASS_STATUS` tokens must never appear as EDN `:on` keys; EDN routes consume only `"DONE"` and `"REPEAT"`.

The no-target route completes the workflow immediately. It must not run `review-task-design`, `create-task-plan`, `review-task-plan`, `clean-baseline`, coverage review/fix/disposition, `diff-gate`, `implement-task`, post-implementation reviews, `terminal-stop-summary`, or final target-present summary steps.

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


### Candidate membership and target/source area contract

The generated task must record a single authoritative candidate map copied from the selected top-level `:winner` in `architecture-targets.edn`: at minimum `:candidate/id`, `:candidate/type`, `:candidate/label`, `:members` when present, `:score`, `:confidence`, and the ranking evidence summary. The top-level `:candidates` vector remains supporting ranking context, not a second selection envelope. Every later clean-baseline check, coverage review, diff-gate classification, blast-radius decision, validation capture, and implementation architecture review must use the same recorded target/source area derived from that candidate.

Candidate membership is resolved as follows:

- `:namespace` candidate — target namespaces are exactly the namespace named by the candidate id, for example `[:namespace "psi.foo.bar"]`. If the winner also carries `:members`, it must equal that singleton for membership purposes; any additional attached findings are evidence only.
- `:family` candidate — target namespaces are the winner's `:members` vector when present. If `:members` is absent, target namespaces are all Gordian-discovered production namespaces whose name is exactly the family prefix from `[:family "prefix"]` or starts with `prefix.` at the time the ranking artifact was captured. Do not recompute family membership later from a changed worktree.
- `:pair` candidate — target namespaces are exactly the two namespaces in `[:pair "ns-a" "ns-b"]` or the equivalent two-element `:members` vector. No family expansion is implied.
- `:community` candidate — target namespaces are exactly the winner's `:members` vector. A bare community id such as `[:community 3]` is not sufficient for a generated task; if a community winner lacks `:members`, treat the selector output as uninterpretable and take the no-target route.

For every target namespace, resolve the target source file(s) immediately during task creation and record root-relative paths. Resolution should use the namespace declaration in Clojure source files discovered by Gordian (`.clj`, `.cljc`, `.cljs` under production source roots); a conventional path mapping may be used only as a fallback and must be recorded. If any target namespace cannot be resolved to at least one root-relative source file, treat the selector output as uninterpretable and take the no-target route rather than creating an unverifiable task.

The generated task and later `characterization-baseline.edn` must distinguish:

- `:target/namespaces` — the resolved namespaces from the selected candidate,
- `:target/source-areas` — root-relative production source files defining those namespaces,
- `:target/allowed-adjacent-source-areas` — optional root-relative production files outside the candidate membership that the generated design explicitly names as necessary boundary seams; absent by default and never inferred from attached findings,
- `:target/affected-test-areas` — existing or newly created tests used to characterize affected behaviour.

Attached findings whose `:location` is `:touching` and external namespaces mentioned by evidence are context for review, not target membership. Editing outside `:target/source-areas` is out of blast radius unless the generated design records the file under `:target/allowed-adjacent-source-areas` with a terse reason before implementation. The clean-baseline and diff-gate steps must inspect the union of `:target/source-areas` and `:target/allowed-adjacent-source-areas` for source dirt and scope violations, while treating `:target/affected-test-areas` as coverage proof rather than source membership.

### Task-local Gordian artifact contract

All generated-task artifact references must be worktree-root-relative paths under the generated task directory; prompts and designs must not rely on bare filenames resolving from the task directory.

At task creation, `select-and-create` must write these artifacts:

- `munera/open/NNN-slug/before-diagnose.edn` — raw EDN stdout from `bb gordian diagnose --edn` run at the worktree root; this is the baseline passed to `bb gordian gate --baseline munera/open/NNN-slug/before-diagnose.edn`,
- `munera/open/NNN-slug/architecture-targets.edn` — raw EDN stdout from `bb gordian architecture-targets --edn` run at the worktree root; this is the authoritative ranking/selection evidence and must contain the top-level `:winner` / `:candidates` envelope consumed by the selector,
- `munera/open/NNN-slug/target-issues.edn` — raw EDN stdout from `bb gordian target-issues --candidate '<pr-str candidate-id>' --edn` when the command succeeds for the selected candidate,
- `munera/open/NNN-slug/target-issues-unavailable.edn` — EDN map written instead of `target-issues.edn` when the selected candidate is valid but `target-issues` cannot describe it, shaped at least as `{:candidate/id ..., :candidate/type ..., :status :unsupported | :failed, :command ..., :reason ...}`. This file records missing supplemental framing; it is not a no-target condition.

During the clean-baseline gate, the workflow must write:

- `munera/open/NNN-slug/characterization-baseline.edn` — EDN map containing at least `:git/head`, `:git/status-short`, the recorded candidate map, `:target/namespaces`, `:target/source-areas`, `:target/allowed-adjacent-source-areas`, `:target/affected-test-areas` when known, and any classified pre-existing task-artifact/doc dirt.

After implementation, before any post-implementation review-step gate runs, a workflow-local `validation-capture` session gate must produce or update the validation artifacts below. `implement-task` is not responsible for producing them before yielding; the architecture workflow owns this producer-before-review boundary explicitly.

- `munera/open/NNN-slug/after-diagnose.edn` — raw EDN stdout from rerunning `bb gordian diagnose --edn`,
- `munera/open/NNN-slug/after-architecture-targets.edn` — raw EDN stdout from rerunning `bb gordian architecture-targets --edn`, used to interpret whether the selected target's ranking/evidence improved, disappeared, or requires explanation,
- `munera/open/NNN-slug/architecture-compare.edn` — raw EDN stdout from `bb gordian compare munera/open/NNN-slug/before-diagnose.edn munera/open/NNN-slug/after-diagnose.edn --edn`,
- `munera/open/NNN-slug/architecture-gate.edn` — raw EDN stdout from `bb gordian gate --baseline munera/open/NNN-slug/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn`.

For each validation command, successful artifacts are raw EDN stdout. If a command exits non-zero or cannot emit readable EDN, `validation-capture` must write an EDN failure map to the same worktree-root-relative artifact path, shaped at least as `{:status :failed, :command ..., :exit ..., :stdout-summary ..., :stderr-summary ...}`, append the same terse failure to `implementation.md`, add a concrete unchecked repair item to the generated task's `steps.md` when repair is plausible, commit the artifact update, and end with `PASS_STATUS: ACTIONABLE_FEEDBACK`. That status routes back to `implement-task` for repair before validation is retried. Only `PASS_STATUS: REVIEW_COMPLETE` from `validation-capture` may route to the post-implementation review-step chain. Missing, unreadable, failed, or explicitly inapplicable-without-a-pre-implementation-design-reason validation artifacts must never be silently accepted by later review gates.

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

These reviews must reuse the existing shared review-loop architecture rather than introducing a bespoke loop. This task must introduce the separate implementation architecture review skill at exactly `.psi/skills/review-implementation-architecture/SKILL.md`, with skill identity `review-implementation-architecture`, and route it through the existing `review-step` workflow. The workflow's `review-step` delegate for this gate must pass exactly `:skill {:value "review-implementation-architecture"}`. It must not use `review-task-architecture`, any candidate/alias skill name, or an inline prompt as a substitute. Actionable findings from this skill are written to the generated task's `steps.md` and executed by the shared `review-follow-up-steps` machinery until the skill reports no actionable feedback. The `review-step` invocation for this skill must receive the selected Gordian architecture target, the `architecture-targets` evidence, any `target-issues` framing or `target-issues-unavailable.edn`, before/after Gordian validation artifacts, and the Munera task artifacts in context.

The architecture-specific gate is workflow-local to `reduce-architectural-complexity`. It must not be implemented as a custom review/follow-up loop, and it must not broaden the generic `review-task-implementation` workflow for unrelated tasks unless a separate general review-design decision explicitly chooses that broader policy.

Post-implementation review sequence is explicit and workflow-local for this architecture workflow rather than a single delegate to the generic `review-task-implementation` wrapper. After `implement-task`, `reduce-architectural-complexity` must first run the workflow-local `validation-capture` gate described above. Only after that gate succeeds may it run these `review-step` delegates in order, each with the generated task path as `:input` and the named skill as `:skill`:

1. `task-implementation-review` — included; verifies implementation correctness/completeness against the task,
2. `task-test-review` — included; verifies implementation test coverage and behaviour proof,
3. `review-implementation-architecture` — included as the architecture-specific gate using `.psi/skills/review-implementation-architecture/SKILL.md`,
4. `test-shaper` — included; verifies the resulting characterization/affected-area tests are clear, robust, and focused after any architecture-review follow-ups,
5. `review-task-docs` — included; verifies README/doc/CHANGELOG synchronization when the task changed user-facing docs or workflow surfaces,
6. `code-shaper` — included; final shape gate for simplicity, consistency, robustness, and absence of adapter/shim complexity.

The generic `review-task-implementation` workflow is intentionally not called by `reduce-architectural-complexity` in this sequence, because its fixed internal order has no slot for the architecture-specific gate and broadening it would affect unrelated tasks. The architecture workflow instead reuses the same underlying `review-step` / `review-follow-up-steps` machinery explicitly. `implement-task` output is not considered complete for this workflow until `validation-capture` has produced successful before/after validation artifacts and all six review-step gates above have resolved with no actionable feedback.

## Relationship to existing workflows

- `reduce-incidental-complexity` remains the function-level workflow.
- The new workflow is the architecture-level workflow.
- Both workflows should share the same safety principle: no behaviour-preserving simplification without a green test net.
- Reuse existing review/design/plan/implementation workflows where possible instead of inventing a parallel lifecycle.
- Reuse or mirror the existing `clean-baseline`, `coverage-review`, `coverage-disposition`, `coverage-fix`, `diff-gate`, and `implement-task` routing pattern from `reduce-incidental-complexity` when it remains appropriate.
- Mirror the generic implementation-review workflow's underlying `review-step` gate pattern, but do not delegate to the fixed `review-task-implementation` wrapper; the architecture workflow owns its explicit post-implementation review-step sequence.
- Insert an explicit workflow-local `validation-capture` gate after `implement-task` and before the review-step sequence; it writes the after/compare/gate artifacts, commits them, and routes failed or missing validation back to `implement-task` repair before reviews can run.
- Add post-implementation review routing so task-implementation-review, task-test-review, review-implementation-architecture, test-shaper, review-task-docs, and code-shaper are explicit gates for this architecture-level workflow.

## Acceptance criteria for this task

- A new architecture-level simplification workflow exists as `.psi/workflows/reduce-architectural-complexity.edn` and loads through the normal workflow loader.
- The workflow uses `bb gordian architecture-targets` for target ranking/selection and `bb gordian target-issues` only for post-selection issue framing when supported.
- Unsupported `target-issues` candidate types do not change selection and do not force a no-target stop; generated tasks record missing supplemental framing and proceed from `architecture-targets` evidence. Missing or uninterpretable `architecture-targets` output still stops before task creation.
- Generated Munera tasks include architecture-target evidence, target-issues framing when available, behaviour-preservation constraints, test-net gate requirements, and Gordian validation criteria.
- The workflow includes a mandatory pre-simplification test-net gate equivalent in strength to `reduce-incidental-complexity` for this higher-level target type.
- `implement-task` cannot run unless the characterization coverage review and baseline/diff gate pass.
- Tests lock workflow parsing/loading, command/prompt contracts, the real `architecture-targets --edn` top-level `:winner` / `:candidates` envelope, pass-status routing, no-target routing, unsupported-`target-issues` informational routing, generated-task prompt content, test-net gate ordering, invoking-worktree constraints, validation-capture producer-before-review ordering, and all six ordered post-implementation review-step gates (`task-implementation-review`, `task-test-review`, `review-implementation-architecture`, `test-shaper`, `review-task-docs`, `code-shaper`) with their required contexts.
- User-facing docs mention the new workflow and distinguish it from `reduce-incidental-complexity`.
- CHANGELOG `[Unreleased]` records the new workflow.

## Locked decisions

1. Workflow name is `reduce-architectural-complexity`.
2. The workflow runs in the invoking worktree. It must not call `work-on`, create another worktree, or switch branches.
3. Selection is based on `architecture-targets`. `target-issues` is post-selection information only; unsupported `target-issues` candidate types do not cause the workflow to choose a different target.
4. Post-implementation completion requires a successful workflow-local `validation-capture` gate followed by explicit workflow-local `review-step` gates for task-implementation-review, task-test-review, review-implementation-architecture, test-shaper, review-task-docs, and code-shaper. The sequence is in the repeat-until-clean style of the generic implementation-review workflow but is not a delegate to that wrapper, because numeric Gordian validation alone cannot capture all architectural-fit concerns and the architecture gate needs a fixed slot.
5. The implementation architecture review skill is distinct from the existing design-oriented `review-task-architecture` skill. It verifies the actual implemented code change against the selected Gordian target, the project's broader existing architecture, and the intended architecture direction.
6. The implementation architecture review skill identity is exactly `review-implementation-architecture` at `.psi/skills/review-implementation-architecture/SKILL.md`.
