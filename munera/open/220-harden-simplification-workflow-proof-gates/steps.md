# 220 — Steps

Checklist derived from `plan.md`. Tick each item with the commit sha / decision
when done.

## Plan-created checklist

- [x] Created `plan.md` from stable `design.md` with approach, risks, and slice order.
- [x] Created `steps.md` with concrete implementation checklist grouped by slice.


## Plan/steps ambiguity review follow-ups

- [x] PA1: Pin the exact `PROOF_SYNC_ROUTE:` marker grammar before implementing `workflow/proof-sync-disposition-routing`: decide whether normal final-reply prose/`PASS_STATUS` lines may surround the single marker, require the marker line's route token to be exact with no trailing text, and add operation tests for valid surrounding prose versus malformed same-line route text.
- [x] PA2: Define the deterministic routing mechanism that distinguishes fixable validation-capture failures from unrecoverable terminal validation-capture failures in both simplification workflows; choose a route marker/disposition step or another explicit topology, and ensure `terminal-stop-validation-capture` is not reached via the same undifferentiated `ACTIONABLE_FEEDBACK` branch used for implementation repair.
- [x] PA3: Pin the first-writer and lifecycle contract for incidental `coverage-map.md`: decide whether `select-and-create` must create an initial scaffold or whether `coverage-review` creates it before any proof-sync can run, then update Slice 2/Slice 6 steps so the mandatory artifact's creation, updates, and content-lock tests are explicit.

## Slice 1 — Preflight and deterministic operations

- [ ] Confirm `design-steps.md` has no unchecked follow-ups and record the result in `implementation.md`.
- [ ] Re-read `.psi/workflows/reduce-incidental-complexity.edn`, `.psi/workflows/reduce-architectural-complexity.edn`, `components/agent-session/src/psi/agent_session/workflow/core.clj`, and focused workflow-loader tests to verify current routing/operation seams before editing.
- [ ] Add registered deterministic operation `workflow/proof-sync-disposition-routing` in the same built-in operation registry as `workflow/pass-status-routing` and `workflow/munera-open-task-path-routing`.
- [ ] Add registered deterministic operation `workflow/validation-capture-disposition-routing` in the same built-in operation registry before workflow EDNs invoke it.
- [ ] Implement `workflow/proof-sync-disposition-routing` to accept `{:text ...}`, allow surrounding final-reply prose/`PASS_STATUS` lines, extract exactly one whole line matching `PROOF_SYNC_ROUTE: <route>` at column 0 with exactly one space after the colon and no trailing whitespace/text, and return route data `COVERAGE_REVIEW`, `VALIDATION_RECAPTURE`, or `BOOKKEEPING_FIXED_POINT`.
- [ ] Implement `workflow/validation-capture-disposition-routing` to accept `{:text ...}`, allow surrounding final-reply prose/`PASS_STATUS` lines, extract exactly one whole line matching `VALIDATION_CAPTURE_ROUTE: <route>` at column 0 with exactly one space after the colon and no trailing whitespace/text, and return route data `IMPLEMENTATION_REPAIR` or `TERMINAL_STOP`.
- [ ] Ensure both marker-routing operations reject missing route markers with a tagged error result.
- [ ] Ensure both marker-routing operations reject duplicated route markers with a tagged error result including the duplicate lines.
- [ ] Ensure both marker-routing operations reject unsupported route tokens, malformed prefixes, leading whitespace before the prefix, trailing whitespace, or extra same-line route text with a tagged error result.
- [ ] Add runtime/operation tests covering all three valid proof-sync route markers, including valid surrounding prose and a surrounding `PASS_STATUS` line.
- [ ] Add runtime/operation tests covering both valid validation-capture route markers.
- [ ] Add runtime/operation tests covering missing, duplicated, unsupported, malformed-prefix, trailing-whitespace, and malformed same-line extra-text route markers for both operations.
- [ ] Verify existing deterministic operations still register and existing workflow routing tests remain green.
- [ ] Run focused agent-session workflow operation/routing tests and targeted lint for changed runtime/test files.
- [ ] Record Slice 1 implementation and verification notes in `implementation.md`.
- [ ] Commit Slice 1 (`⚒ workflow: add proof sync disposition routing`).

## Slice 2 — Incidental task identity boundary and selector/proof generation contracts

- [ ] Add an `extract-task-path` session step to `reduce-incidental-complexity` immediately after target-created `select-and-create` success.
- [ ] Wire incidental `select-and-create` `PASS_STATUS: REVIEW_COMPLETE` / normalized `"DONE"` route to `extract-task-path`, and keep no-target `"REPEAT"` route directly to `:done`.
- [ ] In incidental `extract-task-path`, require exactly one `munera_task_path: munera/open/NNN-slug` line and respond with only the root-relative path on success.
- [ ] Use deterministic `workflow/munera-open-task-path-routing` as the incidental `extract-task-path` judge.
- [ ] Route malformed incidental extraction to `terminal-stop-malformed-task-path` without reading task-local artifacts or inventing a task path.
- [ ] Update incidental `review-task-design`, `create-task-plan`, `review-task-plan`, `clean-baseline`, `coverage-review`, `coverage-disposition`, `coverage-fix`, `diff-gate`, `implement-task`, `review-task-implementation`, validation/proof steps, and summaries so task identity comes from `extract-task-path` yield.
- [ ] Keep the full incidental `select-and-create` handoff available only as context/evidence for downstream steps.
- [ ] Update incidental generated `design.md` prompt contract to require mandatory task-local `coverage-map.md` for every target-present task.
- [ ] Require incidental `select-and-create` to create and commit the initial `coverage-map.md` scaffold before emitting the target-present handoff; pending/unknown values must be represented explicitly rather than omitting required fields.
- [ ] In the incidental prompt, require `coverage-map.md` fields for target identity, selected row key, selector proof, top-5 guard decision, rejected essential false positives, authoritative test commands, coverage/gap dispositions, latest test/assertion counts, and relationship to `characterization-baseline.edn`.
- [ ] State the incidental `coverage-map.md` lifecycle in the relevant prompts: `coverage-review` updates coverage/test-net fields, `coverage-fix` updates it for added tests or seams, `diff-gate` records coverage-phase classification relationship to `characterization-baseline.edn`, `incidental-validation-capture` records final Gordian proof references, `proof-sync` performs final synchronization when stale, and `final-summary` reads it as proof authority.
- [ ] Update incidental generated `design.md` prompt contract to name root-relative `before-local.json`, `before-diagnose.edn`, `after-local.json`, `incidental-burden-check.edn`, `incidental-gate.edn`, `coverage-map.md`, and `characterization-baseline.edn`.
- [ ] Require `before-local.json` parse as JSON with a `units` array before baseline/selector proof claims.
- [ ] Require `before-diagnose.edn` parse as EDN before baseline/gate claims.
- [ ] Require generated incidental tasks to record top-5 guard evidence and say explicitly when no higher candidate was rejected before the chosen target.
- [ ] Require generated incidental tasks to mark marginal targets according to the design thresholds and record falsification/review questions.
- [ ] Update architecture `select-and-create` generated-design prompt, if needed, so low-confidence targets record score/confidence, actionability, falsification evidence, review questions, and scope-narrowing considerations.
- [ ] Verify both workflow EDNs parse after Slice 2 edits.
- [ ] Record Slice 2 implementation and verification notes in `implementation.md`.
- [ ] Commit Slice 2 (`⚒ workflow: harden simplification task identity`).

## Slice 3 — Parse-checked validation capture

- [ ] Strengthen architecture `validation-capture` wording so every successful validation artifact is parsed after write, not trusted from exit code alone.
- [ ] In architecture `validation-capture`, explicitly parse-check `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn` after writing.
- [ ] In architecture `validation-capture`, require exit-0 unreadable/truncated EDN to be replaced with a readable EDN failure map and routed through `validation-capture-disposition`.
- [ ] In architecture `validation-capture`, emit exactly one `VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR` marker for fixable validation failures and exactly one `VALIDATION_CAPTURE_ROUTE: TERMINAL_STOP` marker for unrecoverable capture failures; both failure paths also emit `PASS_STATUS: ACTIONABLE_FEEDBACK`.
- [ ] Add architecture `validation-capture-disposition` invoke step using operation `workflow/validation-capture-disposition-routing`; route `IMPLEMENTATION_REPAIR` to `implement-task` and `TERMINAL_STOP` to `terminal-stop-validation-capture` with the failing validation yield as context.
- [ ] Add incidental `incidental-validation-capture` after `review-task-implementation` and before `proof-sync`.
- [ ] In `incidental-validation-capture`, run `bb gordian local --json` from the worktree root and write raw stdout to `{{input}}/after-local.json`.
- [ ] In `incidental-validation-capture`, parse-check `after-local.json` as JSON and require a `units` array.
- [ ] In `incidental-validation-capture`, compute and write `incidental-burden-check.edn` containing target key, original target burden `B`, after target burden, A5 result, A2a/A2b checked row summaries, and overall pass/fail.
- [ ] In `incidental-validation-capture`, run `bb gordian gate --baseline {{input}}/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn` and write `incidental-gate.edn`.
- [ ] In `incidental-validation-capture`, parse-check `incidental-gate.edn` as EDN and treat exit-0 unreadable/truncated EDN as failure-map replacement.
- [ ] In `incidental-validation-capture`, emit exactly one `VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR` marker for fixable validation failures and exactly one `VALIDATION_CAPTURE_ROUTE: TERMINAL_STOP` marker for unrecoverable capture failures; both failure paths also emit `PASS_STATUS: ACTIONABLE_FEEDBACK`.
- [ ] Add incidental `validation-capture-disposition` invoke step using operation `workflow/validation-capture-disposition-routing`; route `IMPLEMENTATION_REPAIR` to `implement-task` and `TERMINAL_STOP` to `terminal-stop-validation-capture` with the failing validation yield as context.
- [ ] Route fixable incidental validation failures back to `implement-task` only through `validation-capture-disposition` after committing repair notes/artifacts.
- [ ] Route unrecoverable incidental capture failures to `terminal-stop-validation-capture` only through `validation-capture-disposition` with the failing validation yield as explicit context.
- [ ] Ensure no final A5/A2/A3 proof claim is allowed from unparseable JSON/EDN or uncaptured validation output.
- [ ] Verify both workflow EDNs parse after Slice 3 edits.
- [ ] Record Slice 3 implementation and verification notes in `implementation.md`.
- [ ] Commit Slice 3 (`⚒ workflow: parse-check simplification validation`).

## Slice 4 — Proof-sync fixed-point topology

- [ ] Add architecture `proof-sync` after `review-code-shape` and before `final-summary`.
- [ ] Add incidental `proof-sync` after `incidental-validation-capture` and before `final-summary`.
- [ ] In each `proof-sync` prompt, require rereading committed task-local artifacts as proof authority: `design.md`, `plan.md`, `steps.md`, `implementation.md`, `characterization-baseline.edn`, coverage artifact, and named Gordian validation artifacts.
- [ ] In architecture `proof-sync`, require reading/synchronizing `coverage-map.md`, `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn`.
- [ ] In incidental `proof-sync`, require reading/synchronizing `coverage-map.md`, `before-local.json`, `before-diagnose.edn`, `after-local.json`, `incidental-burden-check.edn`, `incidental-gate.edn`, and `characterization-baseline.edn`.
- [ ] In each `proof-sync`, return `PASS_STATUS: REVIEW_COMPLETE` only when proof artifacts are already coherent and no artifact was mutated.
- [ ] In each `proof-sync`, when stale/incomplete proof artifacts are fixed, update and commit task artifacts, return `PASS_STATUS: ACTIONABLE_FEEDBACK`, and emit exactly one `PROOF_SYNC_ROUTE: ...` marker.
- [ ] Add `proof-sync-disposition` invoke step in both workflows using operation `workflow/proof-sync-disposition-routing`.
- [ ] Route `PROOF_SYNC_ROUTE: COVERAGE_REVIEW` in architecture to `review-implementation-tests`, then continue normal architecture/test/docs/code-shape review chain before returning to proof-sync.
- [ ] Route `PROOF_SYNC_ROUTE: VALIDATION_RECAPTURE` in architecture to `validation-capture`, then rerun validation and all post-implementation review gates before returning to proof-sync.
- [ ] Route `PROOF_SYNC_ROUTE: BOOKKEEPING_FIXED_POINT` in architecture to `proof-sync-fixed-point`.
- [ ] Route `PROOF_SYNC_ROUTE: COVERAGE_REVIEW` in incidental to `review-task-implementation`, then `incidental-validation-capture`, then proof-sync.
- [ ] Route `PROOF_SYNC_ROUTE: VALIDATION_RECAPTURE` in incidental to `incidental-validation-capture`, then proof-sync.
- [ ] Route `PROOF_SYNC_ROUTE: BOOKKEEPING_FIXED_POINT` in incidental to `proof-sync-fixed-point`.
- [ ] Add read-only `proof-sync-fixed-point` in both workflows.
- [ ] In `proof-sync-fixed-point`, route `PASS_STATUS: REVIEW_COMPLETE` to `final-summary` only when the second pass is clean/no-op.
- [ ] In `proof-sync-fixed-point`, route `PASS_STATUS: ACTIONABLE_FEEDBACK` to `terminal-stop-proof-sync` when committed proof artifacts are still stale, missing, contradictory, unparseable, or contain an unresolved blocking note.
- [ ] Ensure `terminal-stop-proof-sync` is not reachable directly from `proof-sync` or `proof-sync-disposition`.
- [ ] Verify final summaries are reachable only after clean/no-op proof-sync or clean fixed-point verification.
- [ ] Verify both workflow EDNs parse after Slice 4 edits.
- [ ] Record Slice 4 implementation and verification notes in `implementation.md`.
- [ ] Commit Slice 4 (`⚒ workflow: add proof sync fixed point`).

## Slice 5 — Split terminal stops and final summaries

- [ ] Replace generic architecture `terminal-stop-summary` route targets with split terminal stop steps named `terminal-stop-malformed-task-path`, `terminal-stop-clean-baseline`, `terminal-stop-coverage-disposition`, `terminal-stop-diff-gate`, `terminal-stop-validation-capture`, and `terminal-stop-proof-sync`.
- [ ] Replace generic incidental `terminal-stop-summary` route targets with the same split terminal stop step names.
- [ ] Route malformed `extract-task-path` failures to `terminal-stop-malformed-task-path` with `select-and-create` handoff and extraction output only.
- [ ] Route `clean-baseline` failures to `terminal-stop-clean-baseline` with validated task path and failing `clean-baseline` yield.
- [ ] Route `coverage-disposition` failures or infeasible coverage to `terminal-stop-coverage-disposition` with validated task path and failing coverage/disposition yield.
- [ ] Route `diff-gate` failures to `terminal-stop-diff-gate` with validated task path and failing diff-gate yield.
- [ ] Route architecture `validation-capture` unrecoverable failures to `terminal-stop-validation-capture` when they cannot route back to implementation repair.
- [ ] Route incidental `incidental-validation-capture` unrecoverable failures to `terminal-stop-validation-capture`.
- [ ] Route only `proof-sync-fixed-point` failures to `terminal-stop-proof-sync` with validated task path, mutating proof-sync yield, and read-only fixed-point yield.
- [ ] In malformed-task-path terminal prompt, forbid reading task-local artifacts or inventing a task path.
- [ ] In post-task terminal prompts, require reading committed task artifacts and naming the durable failing artifact path where available.
- [ ] In proof-sync terminal prompt, require naming the committed proof-sync blocking note and affected proof artifact paths.
- [ ] Update final summaries in both workflows to independently read committed proof artifacts and not claim proof coherence from workflow/review prose alone.
- [ ] Verify both workflow EDNs parse after Slice 5 edits.
- [ ] Record Slice 5 implementation and verification notes in `implementation.md`.
- [ ] Commit Slice 5 (`⚒ workflow: split simplification terminal stops`).

## Slice 6 — Workflow-loader/content-lock tests

- [ ] Add a focused task-220 workflow-loader/content-lock test namespace or extend the existing task-209/task-218 tests only where the assertion naturally belongs.
- [ ] Test `reduce-incidental-complexity` loads and includes `extract-task-path` immediately after target-created selection.
- [ ] Test incidental downstream delegates/session steps consume `extract-task-path` as task identity and do not use the raw `select-and-create` handoff as `:prompt-string` input.
- [ ] Test architecture workflow still uses deterministic `workflow/munera-open-task-path-routing` for `extract-task-path`.
- [ ] Test both workflow EDNs invoke `workflow/proof-sync-disposition-routing` exactly for `proof-sync-disposition`.
- [ ] Test both workflow EDNs invoke `workflow/validation-capture-disposition-routing` exactly for `validation-capture-disposition`.
- [ ] Test both workflow EDNs route only `COVERAGE_REVIEW`, `VALIDATION_RECAPTURE`, and `BOOKKEEPING_FIXED_POINT` out of proof-sync disposition.
- [ ] Test both workflow EDNs route only `IMPLEMENTATION_REPAIR` and `TERMINAL_STOP` out of validation-capture disposition, and that `terminal-stop-validation-capture` is not reached via the same undifferentiated `ACTIONABLE_FEEDBACK` branch used for repair.
- [ ] Test proof-sync clean/no-op routing reaches `final-summary`, while mutating proof-sync routes through disposition or fixed-point before any final summary.
- [ ] Test `terminal-stop-proof-sync` is reachable only from `proof-sync-fixed-point` failure.
- [ ] Test split terminal-stop prompts include explicit `:type :source` context from the immediately failed preceding gate.
- [ ] Test malformed task-path terminal stop does not consume an extracted task path and forbids task-local artifact reads.
- [ ] Test terminal-stop prompts name their source gate and durable artifact path expectations.
- [ ] Test incidental prompt content requires mandatory `coverage-map.md` and the minimum field set from design.
- [ ] Test incidental `select-and-create` prompt content requires creating and committing the initial `coverage-map.md` scaffold before handoff.
- [ ] Test incidental coverage-review, coverage-fix, diff-gate, incidental-validation-capture, proof-sync, and final-summary prompt content follow the pinned `coverage-map.md` lifecycle.
- [ ] Test incidental prompt content names and parse-checks `before-local.json`, `before-diagnose.edn`, `after-local.json`, `incidental-burden-check.edn`, `incidental-gate.edn`, and `characterization-baseline.edn`.
- [ ] Test architecture validation prompt content parse-checks `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn` after write.
- [ ] Test exit-0 unreadable/truncated EDN/JSON wording is treated as failure-map replacement in relevant prompts.
- [ ] Test generated architecture design prompt requires low-confidence actionability/falsification/review/scope-narrowing notes.
- [ ] Test generated incidental design prompt requires top-5 guard evidence, rejected essential false positives when present, and marginal target concerns.
- [ ] Test final-summary prompts read committed task-local proof artifacts rather than relying on review prose.
- [ ] Run focused workflow-loader Scry/Kaocha tests for task-209, task-218, and task-220 affected namespaces.
- [ ] Run targeted clj-kondo over changed workflow-loader and runtime test namespaces.
- [ ] Verify workflow EDN files read as EDN after all edits.
- [ ] Record Slice 6 implementation and verification notes in `implementation.md`.
- [ ] Commit Slice 6 (`⚒ test: lock simplification proof gates`).

## Slice 7 — User-facing docs, changelog, and verification

- [ ] Update `doc/workflows.md` to document the hardened `reduce-incidental-complexity` and `reduce-architectural-complexity` proof gates, deterministic task-path boundary, parse-checked validation artifacts, proof-sync fixed point, and split terminal stops.
- [ ] Update README only if its workflow overview needs a pointer to the hardened simplification guarantees.
- [ ] Add `CHANGELOG.md` `[Unreleased]` entry under `Changed` for user-visible hardening of simplification workflows.
- [ ] Verify docs do not claim new selection algorithms, worktree creation, branch pushing, or PR creation.
- [ ] Run final focused runtime operation tests for deterministic routing.
- [ ] Run final focused workflow-loader tests covering both simplification workflows.
- [ ] Run targeted lint/format checks for changed Clojure files.
- [ ] Run `git diff --check`.
- [ ] Verify coherence across `design.md`, `plan.md`, `steps.md`, workflow EDNs, runtime operation, tests, docs, and changelog for operation id, artifact names, route labels, terminal step names, and proof-sync fixed-point semantics.
- [ ] Append final verification notes and PASS_STATUS to `implementation.md`.
- [ ] Commit Slice 7 (`⚒ doc: document hardened simplification gates`).
