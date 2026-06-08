# 220 — Harden Simplification Workflow Proof Gates

## Intent

Harden the simplification workflows so they carry task identity, proof artifacts, validation artifacts, and terminal-stop reasons through explicit deterministic boundaries rather than relying on handoff prose, stale task notes, or implicit review context.

This task covers both:

- `.psi/workflows/reduce-incidental-complexity.edn`
- `.psi/workflows/reduce-architectural-complexity.edn`

The goal is not to change what either workflow simplifies. The goal is to make both workflows more reliable after real-world runs exposed weak proof/routing boundaries.

## Why

The first real `reduce-architectural-complexity` run and the completed task `219-simplify-rpc-session-family` exposed several workflow hardening lessons:

1. **Task identity should cross workflow steps through a deterministic boundary.** `reduce-architectural-complexity` already gained `extract-task-path` plus deterministic `workflow/munera-open-task-path-routing` after an LLM judge misrouted a valid path. `reduce-incidental-complexity` still sends the full `select-and-create` handoff blob as downstream `:input`, so every downstream step must interpret prose instead of receiving a validated task path.
2. **Generated proof artifacts can go stale after review follow-ups.** In task 219, review follow-ups added characterization coverage, but `coverage-map.md` was stale until task-test-review caught it. A green suite plus stale coverage/proof artifact is an incoherent test net.
3. **Machine-readable validation artifacts must be parse-checked, not trusted by exit code alone.** In task 219, `bb gordian gate ... --edn` exited 0 while stdout EDN was truncated. The architecture workflow correctly routed failure after unreadable validation, but the contract should be locked more explicitly and mirrored where incidental workflow uses Gordian JSON/EDN artifacts.
4. **Terminal stop summaries should explain the actual failing gate.** A single terminal summary that infers from missing artifacts can mislead. Stop summaries should receive or record the immediate failing gate result.
5. **Selector confidence/justification should be explicit.** Architecture selection has `:confidence`; incidental selection has a human/skill guard for essential-vs-incidental false positives. Both workflows should record target uncertainty/justification so generated tasks do not blindly trust weak or marginal selectors.

## Scope

### In scope

- Add or reuse deterministic task-path extraction/routing for `reduce-incidental-complexity`.
- Preserve or strengthen deterministic task-path routing in `reduce-architectural-complexity`.
- Add a proof-artifact synchronization/verification gate after implementation/review follow-ups where proof artifacts may become stale.
- Strengthen parse-check requirements for Gordian baseline and validation artifacts in both workflows.
- Improve terminal-stop summaries so they receive or durably reference the actual failing gate output.
- Require explicit selector confidence/justification in generated task designs:
  - architecture: record low-confidence target handling and why the target remains actionable,
  - incidental: record essential-vs-incidental guard evidence, rejected false positives when relevant, and marginal qualification concerns.
- Update workflow-loader/content-lock tests to lock the new routing/proof/validation contracts.
- Update user-facing docs if workflow behaviour or guarantees change.
- Add CHANGELOG `[Unreleased]` entry if the hardening changes user-visible workflow behaviour.

### Out of scope

- Changing the selection algorithms themselves:
  - no new Gordian `architecture-targets` logic,
  - no new incidental gap/threshold method,
  - no persistent skip list unless implementation proves it is required to avoid deterministic no-progress.
- Changing what a valid simplification target is.
- Changing task lifecycle review workflows globally unless the change is required by both simplification workflows and is explicitly justified.
- Reworking `task-lifecycle` generally.
- Opening PRs or pushing branches.

## Required improvements

### 1. Deterministic task identity boundary for incidental workflow

`reduce-incidental-complexity` should stop passing the full `select-and-create` handoff blob as the downstream task identity.

Required shape:

- Add an `extract-task-path` step after target-created `select-and-create`, mirroring the architecture workflow pattern.
- The step reads the full `select-and-create` handoff, extracts exactly one `munera_task_path: munera/open/NNN-slug` line, and yields only the root-relative task path.
- Route valid output through deterministic `workflow/munera-open-task-path-routing`, not an LLM judge.
- Downstream steps consume `{:from {:step "extract-task-path" :yield :text}}` for task identity.
- The full selection handoff remains available as context only.
- Malformed extraction routes to a terminal stop that does not invent a task path.

`reduce-architectural-complexity` already has this shape; this task should verify it remains locked and add any missing tests/docs after the runtime fix.

### 2. Proof-artifact synchronization gate

Both simplification workflows should prevent final success when task-local proof artifacts are stale after review follow-ups.

Required shape:

- Add a workflow-local proof synchronization gate near the end of each workflow, after implementation/review follow-ups that may add/change tests and before final summary.
- The gate must inspect task artifacts and recorded review outputs to determine whether characterization/proof artifacts still match the final test net.
- It must at least verify:
  - authoritative test command(s),
  - latest test/assertion counts when recorded,
  - newly added characterization tests from review follow-ups,
  - coverage/gap dispositions,
  - `characterization-baseline.edn` and any coverage artifact are coherent with the final test set.
- For `reduce-architectural-complexity`, `coverage-map.md` is expected and should be synchronized.
- For `reduce-incidental-complexity`, choose a lightweight standard proof shape:
  - either require `coverage-map.md` when coverage spans multiple source/test areas,
  - or require an explicit coverage/proof section in `implementation.md` plus `characterization-baseline.edn` updates.
  The choice must be explicit and tested.
- If proof artifacts are stale, the gate must update task artifacts, commit them, and route appropriately rather than finalizing with stale proof.

### 3. Parse-check Gordian artifacts

Both workflows should state and test that successful Gordian machine artifacts are parseable, not merely produced by exit-0 commands.

Architecture workflow:

- Strengthen validation-capture contract and tests so every artifact is parsed after write:
  - `after-diagnose.edn`
  - `after-architecture-targets.edn`
  - `architecture-compare.edn`
  - `architecture-gate.edn`
- Exit 0 with unreadable/truncated EDN must write a failure map and route back to repair.

Incidental workflow:

- Strengthen selection/baseline capture and generated acceptance contracts so these artifacts are read/parse checked:
  - `before-local.json`
  - `before-diagnose.edn`
  - after `bb gordian local --json` used for A5/A2 checks,
  - `bb gordian gate --baseline ...` output when captured or claimed.
- If a generated task asks the agent to compare JSON/EDN, the workflow prompt must require parse checking the involved files before making pass/fail claims.

### 4. Terminal-stop summaries with failing-gate context

Terminal summaries should not infer failure causes from missing artifacts when the immediately preceding gate already produced a result.

Required shape:

- Split terminal summaries by stop source, or pass the failing step yield into a single terminal summary with enough context to name the failing gate.
- At minimum, terminal summaries should distinguish:
  - malformed/missing task path before design,
  - clean-baseline failure,
  - coverage-disposition/infeasible coverage,
  - diff-gate failure,
  - validation-capture failure for architecture workflow,
  - proof-sync failure when added.
- Prompts should report the actual failed gate and its recorded durable task artifact path when available.
- Tests should lock that terminal-stop prompts include the relevant preceding gate context.

### 5. Selector confidence and justification recording

Generated tasks should not blindly trust a weak or marginal selector.

Architecture workflow:

- Generated `design.md` must record selected candidate `:confidence` and score.
- If `:confidence` is `:low`, the design must explicitly state:
  - why the target remains actionable,
  - what evidence would falsify the target,
  - review questions for architecture/design review,
  - whether the implementation scope should be narrowed.
- The workflow should not automatically reject low-confidence targets unless the design review concludes they are not actionable.

Incidental workflow:

- Generated `design.md` must record why the chosen target passed the essential-vs-incidental guard.
- It should record top candidates considered or at least the candidates rejected as essential false positives when that occurred.
- If the target barely passes thresholds or evidence is uncertain, the generated design must call that out for review rather than treating it as high-confidence.

## Acceptance criteria

- `reduce-incidental-complexity` uses a deterministic `extract-task-path` boundary for downstream task identity, and tests prove downstream delegates no longer consume raw `select-and-create` handoff as `:input`.
- `reduce-architectural-complexity` retains deterministic task-path routing via `workflow/munera-open-task-path-routing`, with regression tests covering valid path and extra-prose/malformed path routing.
- Both workflows include a proof-artifact synchronization/check gate before final success, or the design records a justified narrower equivalent for incidental tasks.
- Workflow prompts/tests require parse-checking relevant Gordian JSON/EDN artifacts before pass claims; exit-0 unreadable machine output is treated as failure.
- Terminal-stop summaries receive or durably reference the immediately failing gate output and name the stop source precisely.
- Generated architecture tasks record low-confidence target handling when applicable.
- Generated incidental tasks record essential-vs-incidental selector justification and marginal-target uncertainty when applicable.
- Focused workflow-loader tests cover the changed workflow topology, prompt content locks, deterministic routing, proof-sync gate ordering, terminal-stop context, and parse-check contracts.
- Runtime or operation-level tests cover `workflow/munera-open-task-path-routing` if not already sufficiently covered.
- Docs and CHANGELOG are updated if user-visible workflow guarantees or behaviour changed.

## Open design questions

1. Should the proof-sync gate be a shared small workflow/prompt reused by both simplification workflows, or two workflow-local steps with different artifact expectations?
2. For incidental tasks, should `coverage-map.md` become mandatory, optional based on scope size, or should `characterization-baseline.edn` plus `implementation.md` be the canonical proof shape?
3. Should terminal-stop summaries be split into multiple dedicated steps, or should a single terminal summary accept a `stop_source`/previous-yield map from each failing route?
4. Should low-confidence architecture targets ever stop automatically, or always proceed to design review with explicit uncertainty?
