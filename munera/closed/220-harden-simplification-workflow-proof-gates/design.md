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
- For `reduce-incidental-complexity`, require a lightweight task-local `coverage-map.md` for every target-present task. The map is mandatory even for single-area targets so proof-sync has one authoritative coverage/proof artifact to read. The required fields are defined in the ambiguity follow-up decisions below and must be locked by workflow-loader/content-lock tests.
- If proof artifacts are stale, the gate must update task artifacts, commit them, and route appropriately rather than finalizing with stale proof.
- Final success must only follow a clean/no-op proof-sync pass. A proof-sync pass that mutates artifacts must return `ACTIONABLE_FEEDBACK` and route back through the relevant proof/review gate or repeat proof-sync until a later pass verifies a fixed point.
- Committed task-local artifacts are the authoritative proof-sync inputs and outputs. Review/workflow yields are context only and cannot establish final proof coherence by themselves.

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

- Split terminal summaries by stop source, or pass the failing step yield/source envelope into a single terminal summary with enough explicit context to name the failing gate. This must be represented in workflow topology/data-flow, not inferred from hidden runtime state, cross-step introspection, or missing artifacts.
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


## Architecture follow-up decisions

### Terminal-stop topology and failing-gate context

Terminal-stop handling must stay inside explicit workflow topology and data-flow. Do not solve failing-gate context by adding hidden runtime state, cross-step introspection, or inference from missing artifacts. The implementation should use one of these explicit shapes, in priority order:

1. **Split terminal-stop summary steps by stop source.** Each failing route goes to a dedicated summary step such as `terminal-stop-malformed-task-path`, `terminal-stop-clean-baseline`, `terminal-stop-coverage-disposition`, `terminal-stop-diff-gate`, `terminal-stop-validation-capture`, or `terminal-stop-proof-sync`. The step contribution includes the validated task path when one exists and the immediately preceding failing step yield for that stop source.
2. **Single terminal-stop step with explicit source envelope.** If a single summary step is kept, each failing route must pass an explicit `stop_source` / `failed_gate` map or equivalent prior-step source through existing workflow source/judge data-flow. The prompt must name the stop source from that explicit value, not from absent artifacts or prose reconstruction.

For both simplification workflows, terminal summaries must report the actual gate that failed and the committed task-local artifact path where the gate recorded its durable finding, when such an artifact exists. Pre-design malformed task-path stops must not read task-local artifacts or invent a path. Post-task stops must read committed task artifacts to summarize durable state, using the failed step yield only as context for which artifact/gate to inspect.

### Proof synchronization authority and fixed point

The proof-artifact synchronization gate is a **verifying fixed-point gate**, not a final mutating writer. Its authoritative inputs and outputs are committed task-local artifacts: `design.md`, `plan.md`, `steps.md`, `implementation.md`, `characterization-baseline.edn`, `coverage-map.md` when required, Gordian validation artifacts, and any explicitly named task-local proof files. Workflow yields and review child-session prose may identify likely stale files or recent follow-up context, but they are not proof authority across session/workflow boundaries.

Required proof-sync routing:

- If proof artifacts are already coherent, the gate performs no mutation, records no new proof changes, and ends with `PASS_STATUS: REVIEW_COMPLETE`; only this clean/no-op pass may route to `final-summary`.
- If proof artifacts are stale or incomplete, the gate updates the committed task-local artifacts, commits those updates, and ends with `PASS_STATUS: ACTIONABLE_FEEDBACK`; it must not route directly to final success from the same mutating pass.
- After a mutating proof-sync pass, the workflow must route back through the relevant proof/review gate or repeat proof-sync until a later clean/no-op proof-sync pass proves a fixed point. Examples: stale coverage proof routes through coverage/test review before proof-sync is retried; stale validation proof routes through validation capture or architecture review as appropriate; pure bookkeeping proof updates may repeat proof-sync and require the next pass to be clean before final summary.

`final-summary` must independently read the committed task-local proof artifacts and may use prior yields only as context. It must not claim proof coherence from ephemeral review prose, child-session summaries, or the mere existence of previous workflow yields.


## Ambiguity follow-up decisions

### Incidental proof-artifact standard

`reduce-incidental-complexity` generated tasks must include a mandatory, lightweight `coverage-map.md` in every target-present task directory. `implementation.md` remains the chronological work log; it is not the canonical coverage/proof map. `coverage-map.md` is required because proof-sync needs one durable task-local artifact to compare against the final test net after review follow-ups.

Minimum `coverage-map.md` fields for incidental tasks:

- target identity: namespace, var, arity, line range, source path, and the selected row's `(ns, var, arity, line)` key;
- selector proof: `lcc-total`, `cc`, `gap`, top-5 guard decision for the chosen target, and any rejected essential false positives before the chosen target;
- authoritative test commands and focused test namespaces/files used as the characterization net;
- existing coverage and newly added characterization tests, grouped by nominal/edge/boundary observable behaviour;
- coverage-gap dispositions, including `FIXABLE_GAPS`, `INFEASIBLE`, or reviewed-complete notes when they occur;
- latest recorded test/assertion counts when the test runner reports them, plus the command/output artifact or transcript reference used to support the count;
- the relationship to `characterization-baseline.edn` and whether any coverage-phase production seam was introduced.

Workflow-loader/content-lock tests must lock that the incidental selection/generated-design prompt requires `coverage-map.md`, that coverage-review/proof-sync prompts read and update it, and that final-summary reads it rather than relying on review prose.

### Incidental parse-checked proof artifacts

Incidental tasks must name and parse-check these task-local proof artifacts before any A5/A2/A3 or final proof claim:

- `before-local.json` — raw `bb gordian local --json` baseline captured at task creation; must parse as JSON and contain a `units` array.
- `before-diagnose.edn` — raw `bb gordian diagnose --edn` baseline captured at task creation; must parse as EDN.
- `after-local.json` — raw post-implementation `bb gordian local --json` output captured from the worktree root; must parse as JSON and contain a `units` array.
- `incidental-burden-check.edn` — an EDN map recording the deterministic A5 target-reduction result and A2 relocation-guard result computed from `before-local.json` and `after-local.json`. It must include the target key, original target burden `B`, target after burden, A5 pass/fail, A2a/A2b checked row summaries, and overall pass/fail.
- `incidental-gate.edn` — raw `bb gordian gate --baseline munera/open/NNN-slug/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn` output. It must parse as EDN; exit 0 with unreadable/truncated EDN is failure and must be replaced by a readable EDN failure map.
- `coverage-map.md` and `characterization-baseline.edn` — read by proof-sync as task-local proof authority. `coverage-map.md` is Markdown but must include the required named fields above; `characterization-baseline.edn` must parse as EDN.

A generated incidental `design.md` must reference these root-relative artifact names. The workflow may add `after-diagnose.edn` as supporting context, but final A3 gate claims are made from the parse-checked `incidental-gate.edn` result.

Architecture tasks continue to use the existing task-local validation artifact names and must parse-check all of them after write: `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn`.

### Proof-sync topology and routing

Both simplification workflows use the same fixed-point topology shape, with workflow-local prompt content specialized to each artifact set. `proof-sync` emits an explicit durable route marker when it mutates artifacts so routing is deterministic rather than inferred from prose. The route marker is one of `PROOF_SYNC_ROUTE: COVERAGE_REVIEW`, `PROOF_SYNC_ROUTE: VALIDATION_RECAPTURE`, or `PROOF_SYNC_ROUTE: BOOKKEEPING_FIXED_POINT`. There is no direct proof-sync terminal marker: every proof-sync terminal stop must pass through `proof-sync-fixed-point` so the stop has both the mutating `proof-sync` context and the read-only fixed-point context.

`proof-sync-disposition` is a `:type :invoke` step backed by the registered deterministic operation `workflow/proof-sync-disposition-routing`, added through the same built-in operation registry as `workflow/pass-status-routing` and `workflow/munera-open-task-path-routing`. It is not a workflow-local EDN operation definition. The operation accepts `{:text ...}` from the mutating `proof-sync` final reply, extracts exactly one `PROOF_SYNC_ROUTE: ...` line, and returns the route label `COVERAGE_REVIEW`, `VALIDATION_RECAPTURE`, or `BOOKKEEPING_FIXED_POINT`. Missing, duplicated, or unsupported markers return a tagged error. Runtime operation tests must cover each valid marker plus malformed/missing/duplicated marker errors; workflow-loader/content-lock tests must assert both simplification workflow EDNs invoke this exact registered operation id and route only those three labels.

Architecture workflow order after post-implementation review gates:

1. `review-code-shape`
2. `proof-sync`
3. clean/no-op `proof-sync` -> `final-summary`
4. mutating `proof-sync` -> `proof-sync-disposition` -> one of:
   - `COVERAGE_REVIEW` -> `review-implementation-tests`, after which the normal architecture review chain continues through architecture/test/docs/code-shape and returns to `proof-sync`;
   - `VALIDATION_RECAPTURE` -> `validation-capture`, after which validation and all post-implementation review gates rerun before `proof-sync`;
   - `BOOKKEEPING_FIXED_POINT` -> `proof-sync-fixed-point`.

Incidental workflow order after implementation review:

1. `review-task-implementation`
2. `incidental-validation-capture`
3. `proof-sync`
4. clean/no-op `proof-sync` -> `final-summary`
5. mutating `proof-sync` -> `proof-sync-disposition` -> one of:
   - `COVERAGE_REVIEW` -> `review-task-implementation`, after which `incidental-validation-capture` and `proof-sync` rerun;
   - `VALIDATION_RECAPTURE` -> `incidental-validation-capture`, then `proof-sync`;
   - `BOOKKEEPING_FIXED_POINT` -> `proof-sync-fixed-point`.

`incidental-validation-capture` captures and parse-checks `after-local.json`, `incidental-burden-check.edn`, and `incidental-gate.edn` before proof-sync runs. If it fails to capture parseable proof or records a failing A5/A2/A3 result, it appends a durable repair/stop finding to task artifacts, commits it, and routes back to `implement-task` for fixable implementation/validation failures; unrecoverable capture failures route to `terminal-stop-validation-capture`.

`proof-sync` PASS_STATUS mapping:

- `PASS_STATUS: REVIEW_COMPLETE` only when proof artifacts are already coherent and no task artifact was mutated. Route `DONE -> final-summary`.
- `PASS_STATUS: ACTIONABLE_FEEDBACK` when it updates and commits stale/incomplete proof artifacts. Route `REPEAT -> proof-sync-disposition`, whose deterministic marker mapping selects coverage review, validation recapture, or bookkeeping fixed-point verification. A proof-sync pass that detects a potentially terminal proof problem must still record the durable finding as a bookkeeping proof update and emit `PROOF_SYNC_ROUTE: BOOKKEEPING_FIXED_POINT`; terminal stopping is decided only by the subsequent read-only fixed-point check.

`proof-sync-fixed-point` is read-only and is used after pure bookkeeping proof updates, including proof-sync updates that recorded a durable terminal-looking proof finding. It rereads committed task-local artifacts after the mutating pass:

- `PASS_STATUS: REVIEW_COMPLETE` when the second pass is clean/no-op. Route `DONE -> final-summary`.
- `PASS_STATUS: ACTIONABLE_FEEDBACK` when proof artifacts are still stale, missing, contradictory, unparseable, or contain a durable proof-sync blocking note that cannot be resolved by coverage review or validation recapture. Route `REPEAT -> terminal-stop-proof-sync` without mutating any task artifact. The blocking note must already have been written and committed by the preceding mutating `proof-sync` pass; `proof-sync-fixed-point` may only cite that existing note and affected artifact paths in its final reply.

This topology guarantees final success never follows directly from a mutating proof-sync pass. Stale coverage/test proof goes back through the relevant review gate, stale validation/Gordian proof goes back through validation capture, and pure bookkeeping or terminal-looking proof updates require the read-only fixed-point check. It also avoids hidden runtime state: routing uses the registered `workflow/proof-sync-disposition-routing` operation, explicit route marker, committed task-local files, and explicit prior-step source context only.

### Terminal-stop workflow shape

Use split terminal-stop summary steps rather than a single source-envelope step. This is the selected shape for both workflows because it makes stop-source context explicit in EDN topology and avoids prompt inference from absent artifacts.

Required terminal steps and routes:

- `terminal-stop-malformed-task-path`: from `extract-task-path` failure. Inputs are the `select-and-create` handoff and `extract-task-path` output only. It must not read task-local artifacts or invent a task path.
- `terminal-stop-clean-baseline`: from `clean-baseline` failure. Inputs include the validated task path and the failing `clean-baseline` yield; summary reads the committed dirty/missing-baseline finding from task artifacts.
- `terminal-stop-coverage-disposition`: from `coverage-disposition` failure or infeasible coverage. Inputs include the validated task path and the failing coverage/disposition yield; summary names the latest committed `CHARACTERIZATION_STATUS` artifact.
- `terminal-stop-diff-gate`: from `diff-gate` failure. Inputs include the validated task path and failing diff-gate yield; summary names the durable diff-gate classification/failure artifact.
- `terminal-stop-validation-capture`: from architecture `validation-capture` or incidental `incidental-validation-capture` unrecoverable failure. Inputs include the validated task path and failing validation yield; summary names the parse-checked failure map artifact, for example `architecture-gate.edn` or `incidental-gate.edn`.
- `terminal-stop-proof-sync`: only from `proof-sync-fixed-point` failure. Inputs include the validated task path, the mutating `proof-sync` yield, and the read-only fixed-point yield; summary names the committed proof-sync blocking note and affected proof artifacts. It must not be reachable directly from `proof-sync-disposition`.

Workflow-loader/content-lock tests must prove terminal-stop prompts include the relevant preceding gate context (`:type :source` from the failed step) and that malformed task-path terminal handling does not consume a task path.

### Selector uncertainty semantics

Architecture selector uncertainty does not auto-stop solely because `:confidence` is `:low`. A low-confidence architecture winner still creates a task when it is otherwise interpretable, has source membership, and mandatory pre-task captures are parseable. The generated `design.md` must record candidate score and confidence. For `:confidence :low`, it must also record:

- why the target remains actionable despite low confidence;
- evidence that would falsify the target during design/architecture review;
- review questions that design review must answer before planning;
- whether implementation scope should be narrowed to a smaller namespace/family/pair/community slice.

Design review may reject or narrow a low-confidence target; the selector workflow itself does not automatically reject it.

Incidental selector uncertainty is recorded in generated `design.md` and `coverage-map.md`. A target is **marginal** when any of these holds:

- `lcc-total` is below `5.5` (within 10% of the `5.0` threshold);
- `gap` is below `2.2` (within 10% of the `2.0` threshold);
- the chosen target was not the first top-5 candidate because at least one higher-gap candidate was rejected as essential;
- the guard rationale depends on weak evidence, ambiguous local findings, or uncertain coverage hints.

The incidental generated design must include a top-5 guard-evidence table when any qualifying candidates exist. Minimum fields are ns, var, arity, line, `lcc-total`, `cc`, `gap`, guard decision (`accepted-incidental`, `rejected-essential`, or `rejected-uncertain`), and one-sentence rationale. If no candidates were rejected before the chosen target, the design must say so explicitly. For marginal accepted targets, the design must state the concern, what review evidence would falsify the target, and which scope/coverage questions the design review should answer before planning.

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

## Resolved design questions

1. Proof-sync is implemented as workflow-local steps initially, because the two workflows have different artifact expectations. Shared helper extraction is allowed later only if it preserves the same fixed-point/no-op success contract.
2. Incidental target-present tasks require `coverage-map.md` as the canonical coverage/proof artifact, alongside `characterization-baseline.edn` and named parse-checked Gordian proof artifacts.
3. Terminal-stop handling uses split terminal summary steps per stop source. A single source-envelope terminal step is no longer an allowed implementation choice for this task.
4. Low-confidence architecture targets do not stop automatically; they proceed to generated design only when otherwise interpretable and parse-checked, with explicit uncertainty/falsification/review questions for design review.
