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

## Plan/steps inconsistency review follow-ups

- [x] PI1: Align architecture `coverage-map.md` proof authority with its writer lifecycle: because plan/steps require architecture `proof-sync` and final summary to read/synchronize `coverage-map.md`, add explicit architecture first-writer/update obligations (or explicitly narrow the proof-sync artifact set) so `coverage-map.md` is created before proof-sync can require it and maintained by coverage review/fix, diff gate, validation capture, proof-sync, and final summary as appropriate. — resolved in `plan.md`: architecture `select-and-create` is first writer; lifecycle owners are coverage review/fix, diff gate, validation capture, proof-sync, and final summary.
- [x] PI2: Make low-confidence architecture selector handling mandatory rather than optional: remove the Slice 2 `if needed` escape hatch and require the architecture `select-and-create` generated-design prompt plus content-lock tests to always cover score/confidence, and for `:confidence :low` the actionability, falsification evidence, design-review questions, and scope-narrowing considerations required by `design.md` and `plan.md`. — resolved in `plan.md`/Slice 2/6 steps as mandatory architecture prompt and test obligations.
- [x] PI3: Reconcile split terminal-stop route ordering with slice boundaries: routes introduced in Slices 2-4 must not point at terminal stop steps that are only created in Slice 5. Either create the relevant split terminal steps before the first route to them, or move the routing changes into the same slice as the step definitions, and verify workflow EDNs load/compile (not only parse) after each slice that changes topology. — resolved by making split terminal-stop step definitions a Slice 2 prerequisite, leaving Slice 5 for prompt completion/final cleanup, and requiring workflow-loader/registry load verification after each topology slice.

## Slice 1 — Preflight and deterministic operations

- [x] Confirm `design-steps.md` has no unchecked follow-ups and record the result in `implementation.md`.
- [x] Re-read `.psi/workflows/reduce-incidental-complexity.edn`, `.psi/workflows/reduce-architectural-complexity.edn`, `components/agent-session/src/psi/agent_session/workflow/core.clj`, and focused workflow-loader tests to verify current routing/operation seams before editing.
- [x] Add registered deterministic operation `workflow/proof-sync-disposition-routing` in the same built-in operation registry as `workflow/pass-status-routing` and `workflow/munera-open-task-path-routing`.
- [x] Add registered deterministic operation `workflow/validation-capture-disposition-routing` in the same built-in operation registry before workflow EDNs invoke it.
- [x] Implement `workflow/proof-sync-disposition-routing` to accept `{:text ...}`, allow surrounding final-reply prose/`PASS_STATUS` lines, extract exactly one whole line matching `PROOF_SYNC_ROUTE: <route>` at column 0 with exactly one space after the colon and no trailing whitespace/text, and return route data `COVERAGE_REVIEW`, `VALIDATION_RECAPTURE`, or `BOOKKEEPING_FIXED_POINT`.
- [x] Implement `workflow/validation-capture-disposition-routing` to accept `{:text ...}`, allow surrounding final-reply prose/`PASS_STATUS` lines, extract exactly one whole line matching `VALIDATION_CAPTURE_ROUTE: <route>` at column 0 with exactly one space after the colon and no trailing whitespace/text, and return route data `IMPLEMENTATION_REPAIR` or `TERMINAL_STOP`.
- [x] Ensure both marker-routing operations reject missing route markers with a tagged error result.
- [x] Ensure both marker-routing operations reject duplicated route markers with a tagged error result including the duplicate lines.
- [x] Ensure both marker-routing operations reject unsupported route tokens, malformed prefixes, leading whitespace before the prefix, trailing whitespace, or extra same-line route text with a tagged error result.
- [x] Add runtime/operation tests covering all three valid proof-sync route markers, including valid surrounding prose and a surrounding `PASS_STATUS` line.
- [x] Add runtime/operation tests covering both valid validation-capture route markers.
- [x] Add runtime/operation tests covering missing, duplicated, unsupported, malformed-prefix, trailing-whitespace, and malformed same-line extra-text route markers for both operations.
- [x] Verify existing deterministic operations still register and existing workflow routing tests remain green.
- [x] Run focused agent-session workflow operation/routing tests and targeted lint for changed runtime/test files.
- [x] Record Slice 1 implementation and verification notes in `implementation.md`.
- [x] Commit Slice 1 (`⚒ workflow: add proof sync disposition routing`) — this commit.

## Slice 2 — Task identity boundary, terminal prerequisites, and selector/proof generation contracts

- [x] Add split terminal-stop step definitions to both workflow EDNs before adding or changing any route to them: `terminal-stop-malformed-task-path`, `terminal-stop-clean-baseline`, `terminal-stop-coverage-disposition`, `terminal-stop-diff-gate`, `terminal-stop-validation-capture`, and `terminal-stop-proof-sync`.
- [x] Keep split terminal-stop step definitions loadable with placeholder-safe prompts if needed; all routes introduced in later Slice 2-4 work must target already-defined steps, never a future Slice 5 step.
- [x] Add an `extract-task-path` session step to `reduce-incidental-complexity` immediately after target-created `select-and-create` success.
- [x] Wire incidental `select-and-create` `PASS_STATUS: REVIEW_COMPLETE` / normalized `"DONE"` route to `extract-task-path`, and keep no-target `"REPEAT"` route directly to `:done`.
- [x] In incidental `extract-task-path`, require exactly one `munera_task_path: munera/open/NNN-slug` line and respond with only the root-relative path on success.
- [x] Use deterministic `workflow/munera-open-task-path-routing` as the incidental `extract-task-path` judge.
- [x] Route malformed incidental extraction to `terminal-stop-malformed-task-path` without reading task-local artifacts or inventing a task path.
- [x] Update incidental `review-task-design`, `create-task-plan`, `review-task-plan`, `clean-baseline`, `coverage-review`, `coverage-disposition`, `coverage-fix`, `diff-gate`, `implement-task`, `review-task-implementation`, validation/proof steps, and summaries so task identity comes from `extract-task-path` yield.
- [x] Keep the full incidental `select-and-create` handoff available only as context/evidence for downstream steps.
- [x] Update incidental generated `design.md` prompt contract to require mandatory task-local `coverage-map.md` for every target-present task.
- [x] Require incidental `select-and-create` to create and commit the initial `coverage-map.md` scaffold before emitting the target-present handoff; pending/unknown values must be represented explicitly rather than omitting required fields.
- [x] In the incidental prompt, require `coverage-map.md` fields for target identity, selected row key, selector proof, top-5 guard decision, rejected essential false positives, authoritative test commands, coverage/gap dispositions, latest test/assertion counts, and relationship to `characterization-baseline.edn`.
- [x] State the incidental `coverage-map.md` lifecycle in the relevant prompts: `coverage-review` updates coverage/test-net fields, `coverage-fix` updates it for added tests or seams, `diff-gate` records coverage-phase classification relationship to `characterization-baseline.edn`, `incidental-validation-capture` records final Gordian proof references, `proof-sync` performs final synchronization when stale, and `final-summary` reads it as proof authority.
- [x] Update incidental generated `design.md` prompt contract to name root-relative `before-local.json`, `before-diagnose.edn`, `after-local.json`, `incidental-burden-check.edn`, `incidental-gate.edn`, `coverage-map.md`, and `characterization-baseline.edn`.
- [x] Require `before-local.json` parse as JSON with a `units` array before baseline/selector proof claims.
- [x] Require `before-diagnose.edn` parse as EDN before baseline/gate claims.
- [x] Require generated incidental tasks to record top-5 guard evidence and say explicitly when no higher candidate was rejected before the chosen target.
- [x] Require generated incidental tasks to mark marginal targets according to the design thresholds and record falsification/review questions.
- [x] Update architecture `select-and-create` generated-design prompt so every generated design records score and confidence, and so `:confidence :low` always records actionability despite low confidence, falsification evidence, design-review questions, and scope-narrowing considerations.
- [x] Require architecture `select-and-create` to create and commit an initial `coverage-map.md` scaffold before emitting the target-present handoff; pending/unknown values must be represented explicitly rather than omitting required fields.
- [x] In the architecture prompt, require `coverage-map.md` fields for selected candidate identity, score/confidence, target namespaces/source areas, selector proof, authoritative test commands, affected behaviours, coverage/gap dispositions, latest test/assertion counts, relationship to `characterization-baseline.edn`, and references to `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn`.
- [x] State the architecture `coverage-map.md` lifecycle in the relevant prompts: `coverage-review` updates coverage/test-net fields, `coverage-fix` updates it for added tests or seams, `diff-gate` records coverage-phase classification relationship to `characterization-baseline.edn`, `validation-capture` records final Gordian proof references, `proof-sync` performs final synchronization when stale, and `final-summary` reads it as proof authority.
- [x] Verify both workflow EDNs load/compile through the workflow-loader/registry path after Slice 2 topology edits, not only parse as EDN.
- [x] Record Slice 2 implementation and verification notes in `implementation.md`.
- [x] Commit Slice 2 (`⚒ workflow: harden simplification task identity`) — this commit.

## Slice 3 — Parse-checked validation capture

- [x] Strengthen architecture `validation-capture` wording so every successful validation artifact is parsed after write, not trusted from exit code alone.
- [x] In architecture `validation-capture`, explicitly parse-check `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn` after writing.
- [x] In architecture `validation-capture`, require exit-0 unreadable/truncated EDN to be replaced with a readable EDN failure map and routed through `validation-capture-disposition`.
- [x] In architecture `validation-capture`, emit exactly one `VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR` marker for fixable validation failures and exactly one `VALIDATION_CAPTURE_ROUTE: TERMINAL_STOP` marker for unrecoverable capture failures; both failure paths also emit `PASS_STATUS: ACTIONABLE_FEEDBACK`.
- [x] Add architecture `validation-capture-disposition` invoke step using operation `workflow/validation-capture-disposition-routing`; route `IMPLEMENTATION_REPAIR` to `implement-task` and `TERMINAL_STOP` to `terminal-stop-validation-capture` with the failing validation yield as context.
- [x] Add incidental `incidental-validation-capture` after `review-task-implementation` and before `proof-sync`.
- [x] In `incidental-validation-capture`, run `bb gordian local --json` from the worktree root and write raw stdout to `{{input}}/after-local.json`.
- [x] In `incidental-validation-capture`, parse-check `after-local.json` as JSON and require a `units` array.
- [x] In `incidental-validation-capture`, compute and write `incidental-burden-check.edn` containing target key, original target burden `B`, after target burden, A5 result, A2a/A2b checked row summaries, and overall pass/fail.
- [x] In `incidental-validation-capture`, run `bb gordian gate --baseline {{input}}/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn` and write `incidental-gate.edn`.
- [x] In `incidental-validation-capture`, parse-check `incidental-gate.edn` as EDN and treat exit-0 unreadable/truncated EDN as failure-map replacement.
- [x] In `incidental-validation-capture`, emit exactly one `VALIDATION_CAPTURE_ROUTE: IMPLEMENTATION_REPAIR` marker for fixable validation failures and exactly one `VALIDATION_CAPTURE_ROUTE: TERMINAL_STOP` marker for unrecoverable capture failures; both failure paths also emit `PASS_STATUS: ACTIONABLE_FEEDBACK`.
- [x] Add incidental `validation-capture-disposition` invoke step using operation `workflow/validation-capture-disposition-routing`; route `IMPLEMENTATION_REPAIR` to `implement-task` and `TERMINAL_STOP` to `terminal-stop-validation-capture` with the failing validation yield as context.
- [x] Route fixable incidental validation failures back to `implement-task` only through `validation-capture-disposition` after committing repair notes/artifacts.
- [x] Route unrecoverable incidental capture failures to `terminal-stop-validation-capture` only through `validation-capture-disposition` with the failing validation yield as explicit context.
- [x] Ensure no final A5/A2/A3 proof claim is allowed from unparseable JSON/EDN or uncaptured validation output.
- [x] Verify both workflow EDNs load/compile through the workflow-loader/registry path after Slice 3 topology edits, not only parse as EDN.
- [x] Record Slice 3 implementation and verification notes in `implementation.md`.
- [x] Commit Slice 3 (`⚒ workflow: parse-check simplification validation`).

## Slice 4 — Proof-sync fixed-point topology

- [x] Add architecture `proof-sync` after `review-code-shape` and before `final-summary`.
- [x] Add incidental `proof-sync` after `incidental-validation-capture` and before `final-summary`.
- [x] In each `proof-sync` prompt, require rereading committed task-local artifacts as proof authority: `design.md`, `plan.md`, `steps.md`, `implementation.md`, `characterization-baseline.edn`, coverage artifact, and named Gordian validation artifacts.
- [x] In architecture `proof-sync`, require reading/synchronizing `coverage-map.md`, `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn`.
- [x] In incidental `proof-sync`, require reading/synchronizing `coverage-map.md`, `before-local.json`, `before-diagnose.edn`, `after-local.json`, `incidental-burden-check.edn`, `incidental-gate.edn`, and `characterization-baseline.edn`.
- [x] In each `proof-sync`, return `PASS_STATUS: REVIEW_COMPLETE` only when proof artifacts are already coherent and no artifact was mutated.
- [x] In each `proof-sync`, when stale/incomplete proof artifacts are fixed, update and commit task artifacts, return `PASS_STATUS: ACTIONABLE_FEEDBACK`, and emit exactly one `PROOF_SYNC_ROUTE: ...` marker.
- [x] Add `proof-sync-disposition` invoke step in both workflows using operation `workflow/proof-sync-disposition-routing`.
- [x] Route `PROOF_SYNC_ROUTE: COVERAGE_REVIEW` in architecture to `review-implementation-tests`, then continue normal architecture/test/docs/code-shape review chain before returning to proof-sync.
- [x] Route `PROOF_SYNC_ROUTE: VALIDATION_RECAPTURE` in architecture to `validation-capture`, then rerun validation and all post-implementation review gates before returning to proof-sync.
- [x] Route `PROOF_SYNC_ROUTE: BOOKKEEPING_FIXED_POINT` in architecture to `proof-sync-fixed-point`.
- [x] Route `PROOF_SYNC_ROUTE: COVERAGE_REVIEW` in incidental to `review-task-implementation`, then `incidental-validation-capture`, then proof-sync.
- [x] Route `PROOF_SYNC_ROUTE: VALIDATION_RECAPTURE` in incidental to `incidental-validation-capture`, then proof-sync.
- [x] Route `PROOF_SYNC_ROUTE: BOOKKEEPING_FIXED_POINT` in incidental to `proof-sync-fixed-point`.
- [x] Add read-only `proof-sync-fixed-point` in both workflows.
- [x] In `proof-sync-fixed-point`, route `PASS_STATUS: REVIEW_COMPLETE` to `final-summary` only when the second pass is clean/no-op.
- [x] In `proof-sync-fixed-point`, route `PASS_STATUS: ACTIONABLE_FEEDBACK` to `terminal-stop-proof-sync` when committed proof artifacts are still stale, missing, contradictory, unparseable, or contain an unresolved blocking note.
- [x] Ensure `terminal-stop-proof-sync` is not reachable directly from `proof-sync` or `proof-sync-disposition`.
- [x] Verify final summaries are reachable only after clean/no-op proof-sync or clean fixed-point verification.
- [x] Verify both workflow EDNs load/compile through the workflow-loader/registry path after Slice 4 topology edits, not only parse as EDN.
- [x] Record Slice 4 implementation and verification notes in `implementation.md`.
- [x] Commit Slice 4 (`⚒ workflow: add proof sync fixed point`).

## Slice 5 — Split terminal stops and final summaries

- [x] Complete architecture split terminal-stop prompt content and remove any remaining generic `terminal-stop-summary` route targets now that split terminal steps were defined before first use.
- [x] Complete incidental split terminal-stop prompt content and remove any remaining generic `terminal-stop-summary` route targets now that split terminal steps were defined before first use.
- [x] Route malformed `extract-task-path` failures to `terminal-stop-malformed-task-path` with `select-and-create` handoff and extraction output only.
- [x] Route `clean-baseline` failures to `terminal-stop-clean-baseline` with validated task path and failing `clean-baseline` yield.
- [x] Route `coverage-disposition` failures or infeasible coverage to `terminal-stop-coverage-disposition` with validated task path and failing coverage/disposition yield.
- [x] Route `diff-gate` failures to `terminal-stop-diff-gate` with validated task path and failing diff-gate yield.
- [x] Route architecture `validation-capture` unrecoverable failures to `terminal-stop-validation-capture` when they cannot route back to implementation repair.
- [x] Route incidental `incidental-validation-capture` unrecoverable failures to `terminal-stop-validation-capture`.
- [x] Route only `proof-sync-fixed-point` failures to `terminal-stop-proof-sync` with validated task path, mutating proof-sync yield, and read-only fixed-point yield.
- [x] In malformed-task-path terminal prompt, forbid reading task-local artifacts or inventing a task path.
- [x] In post-task terminal prompts, require reading committed task artifacts and naming the durable failing artifact path where available.
- [x] In proof-sync terminal prompt, require naming the committed proof-sync blocking note and affected proof artifact paths.
- [x] Update final summaries in both workflows to independently read committed proof artifacts and not claim proof coherence from workflow/review prose alone.
- [x] Verify both workflow EDNs load/compile through the workflow-loader/registry path after Slice 5 topology edits, not only parse as EDN.
- [x] Record Slice 5 implementation and verification notes in `implementation.md`.
- [x] Commit Slice 5 (`⚒ workflow: split simplification terminal stops`).

## Slice 6 — Workflow-loader/content-lock tests

- [x] Add a focused task-220 workflow-loader/content-lock test namespace or extend the existing task-209/task-218 tests only where the assertion naturally belongs.
- [x] Test `reduce-incidental-complexity` loads and includes `extract-task-path` immediately after target-created selection.
- [x] Test incidental downstream delegates/session steps consume `extract-task-path` as task identity and do not use the raw `select-and-create` handoff as `:prompt-string` input.
- [x] Test architecture workflow still uses deterministic `workflow/munera-open-task-path-routing` for `extract-task-path`.
- [x] Test both workflow EDNs invoke `workflow/proof-sync-disposition-routing` exactly for `proof-sync-disposition`.
- [x] Test both workflow EDNs invoke `workflow/validation-capture-disposition-routing` exactly for `validation-capture-disposition`.
- [x] Test both workflow EDNs route only `COVERAGE_REVIEW`, `VALIDATION_RECAPTURE`, and `BOOKKEEPING_FIXED_POINT` out of proof-sync disposition.
- [x] Test both workflow EDNs route only `IMPLEMENTATION_REPAIR` and `TERMINAL_STOP` out of validation-capture disposition, and that `terminal-stop-validation-capture` is not reached via the same undifferentiated `ACTIONABLE_FEEDBACK` branch used for repair.
- [x] Test proof-sync clean/no-op routing reaches `final-summary`, while mutating proof-sync routes through disposition or fixed-point before any final summary.
- [x] Test `terminal-stop-proof-sync` is reachable only from `proof-sync-fixed-point` failure.
- [x] Test split terminal-stop prompts include explicit `:type :source` context from the immediately failed preceding gate.
- [x] Test malformed task-path terminal stop does not consume an extracted task path and forbids task-local artifact reads.
- [x] Test terminal-stop prompts name their source gate and durable artifact path expectations.
- [x] Test architecture prompt content requires mandatory `coverage-map.md`, first-writer scaffold creation, score/confidence fields, and the architecture coverage-map lifecycle through coverage review/fix, diff gate, validation capture, proof-sync, and final summary.
- [x] Test incidental prompt content requires mandatory `coverage-map.md` and the minimum field set from design.
- [x] Test incidental `select-and-create` prompt content requires creating and committing the initial `coverage-map.md` scaffold before handoff.
- [x] Test incidental coverage-review, coverage-fix, diff-gate, incidental-validation-capture, proof-sync, and final-summary prompt content follow the pinned `coverage-map.md` lifecycle.
- [x] Test incidental prompt content names and parse-checks `before-local.json`, `before-diagnose.edn`, `after-local.json`, `incidental-burden-check.edn`, `incidental-gate.edn`, and `characterization-baseline.edn`.
- [x] Test architecture validation prompt content parse-checks `after-diagnose.edn`, `after-architecture-targets.edn`, `architecture-compare.edn`, and `architecture-gate.edn` after write.
- [x] Test exit-0 unreadable/truncated EDN/JSON wording is treated as failure-map replacement in relevant prompts.
- [x] Test generated architecture design prompt requires low-confidence actionability/falsification/review/scope-narrowing notes.
- [x] Test generated incidental design prompt requires top-5 guard evidence, rejected essential false positives when present, and marginal target concerns.
- [x] Test final-summary prompts read committed task-local proof artifacts rather than relying on review prose.
- [x] Run focused workflow-loader Scry/Kaocha tests for task-209, task-218, and task-220 affected namespaces.
- [x] Run targeted clj-kondo over changed workflow-loader and runtime test namespaces.
- [x] Verify workflow EDN files read as EDN after all edits.
- [x] Record Slice 6 implementation and verification notes in `implementation.md`.
- [x] Commit Slice 6 (`⚒ test: lock simplification proof gates`).

## Slice 7 — User-facing docs, changelog, and verification

- [x] Update `doc/workflows.md` to document the hardened `reduce-incidental-complexity` and `reduce-architectural-complexity` proof gates, deterministic task-path boundary, parse-checked validation artifacts, proof-sync fixed point, and split terminal stops.
- [x] Update README only if its workflow overview needs a pointer to the hardened simplification guarantees. — no README edit needed; existing workflow overview already points to `doc/workflows.md` and names both simplification workflows without detailing guarantees.
- [x] Add `CHANGELOG.md` `[Unreleased]` entry under `Changed` for user-visible hardening of simplification workflows.
- [x] Verify docs do not claim new selection algorithms, worktree creation, branch pushing, or PR creation.
- [x] Run final focused runtime operation tests for deterministic routing.
- [x] Run final focused workflow-loader tests covering both simplification workflows.
- [x] Run targeted lint/format checks for changed Clojure files.
- [x] Run `git diff --check`.
- [x] Verify coherence across `design.md`, `plan.md`, `steps.md`, workflow EDNs, runtime operation, tests, docs, and changelog for operation id, artifact names, route labels, terminal step names, and proof-sync fixed-point semantics.
- [x] Append final verification notes and PASS_STATUS to `implementation.md`.
- [x] Commit Slice 7 (`⚒ doc: document hardened simplification gates`) — this commit.

## Implementation review follow-ups

- [x] IR1: Add `proof-sync-fixed-point` yielded source context to both simplification workflow `final-summary` steps and content-lock it in tests; final summaries can be reached from clean fixed-point verification, so they must not have to infer that final proof gate from topology or stale `proof-sync` prose. — done; both final summaries now source `proof-sync-fixed-point` yield alongside `proof-sync`, with task-220 content-lock assertions.

## Task test review follow-ups

- [x] TT1: Strengthen deterministic marker-routing operation tests to assert duplicate-marker error details include the duplicate marker lines for both `workflow/proof-sync-disposition-routing` and `workflow/validation-capture-disposition-routing`; current tests only assert `:reason :ambiguous-route-marker`, so they do not prove the design/plan diagnostic contract for duplicated markers. — done; operation tests now assert `:details :route-marker-lines` contains the exact duplicate proof-sync and validation-capture marker lines.
- [x] TT2: Extend the task-220 workflow-loader/content-lock identity tests so the post-implementation proof/final path is locked to the extracted task path, not just the pre-implementation path. At minimum assert `review-task-implementation`, `incidental-validation-capture`, `proof-sync`, `proof-sync-fixed-point`, `final-summary`, and split post-task terminal summaries consume `{:from {:step "extract-task-path" :yield :text}}` as their task-path template input where applicable; this covers the checked Slice 2 obligation that incidental validation/proof steps and summaries use the deterministic task identity boundary. — done; task-220 content-lock tests now assert incidental and architecture post-implementation validation/review/proof/final steps plus split post-task terminal summaries use the extracted Munera task path.
- [x] TT3: Strengthen deterministic `workflow/munera-open-task-path-routing` operation tests beyond the current valid-path plus one extra-line invalid smoke case. Add focused tests proving malformed/non-open/absolute/raw-handoff/multiple-path or extra-prose task-path outputs route to `REPEAT`, matching the task acceptance contract that deterministic task identity routing has valid plus extra-prose/malformed regression coverage for both simplification workflows. — done; operation tests now cover valid path plus extra-prose, PASS_STATUS-prose, multiple paths, non-open path, absolute path, raw handoff lines, malformed numeric/case/underscore/trailing-slash outputs routing to `REPEAT`.

## Docs review follow-ups

- [x] DOC1: Update `doc/workflows.md` incidental-complexity section to document that target-present generated tasks record selector justification in `design.md`/`coverage-map.md`, including top-5 guard evidence, rejected essential false positives when present, and marginal-target concerns/falsification/scope-review questions. — done; incidental workflow docs now state that target-present generated tasks record selector justification in committed `design.md`/`coverage-map.md`, including guard evidence, rejected false positives, and marginal-target review/falsification concerns.
- [x] DOC2: Correct the incidental A3 gate example in `doc/workflows.md` so it uses a worktree-root-relative task artifact path (for example `munera/open/NNN-slug/before-diagnose.edn`) or is explicitly schematic; avoid implying bare `before-diagnose.edn` resolves from the worktree root. — done; the A3 gate example now uses `munera/open/NNN-slug/before-diagnose.edn` and notes that `NNN-slug` is replaced by the generated task id.

## Code-shaper review follow-ups

- [x] CS1: Reshape deterministic workflow routing parser ownership/candidate detection: move the remaining pure routing parsers for pass-status and Munera open task path out of `workflow/core.clj` into `psi.agent-session.workflow.routing` (leaving core as registration/wiring only), and replace marker-line discovery based on `str/includes?` with an explicit line classifier that distinguishes exact markers, malformed marker attempts, and ordinary surrounding prose so allowed final-reply prose that merely mentions `PROOF_SYNC_ROUTE` or `VALIDATION_CAPTURE_ROUTE` cannot be misclassified as an ambiguous/malformed route marker. — done; pure parsers now live in `workflow.routing`, core only registers operation ids/handlers, and marker tests prove prose mentions are missing-marker ordinary prose rather than malformed/ambiguous route candidates.
- [x] CS2: Tighten marker-line classification for route markers so a line starting with the marker label but using whitespace before the colon (for example `PROOF_SYNC_ROUTE : COVERAGE_REVIEW` or `VALIDATION_CAPTURE_ROUTE : TERMINAL_STOP`) is reported as `:malformed-route-marker` rather than being treated as ordinary prose / missing marker; keep ordinary prose mentions without marker syntax classified as non-candidates, and add operation tests for both marker types. — done; marker classifier now treats whitespace-before-colon marker attempts as malformed while ordinary prose mentions remain non-candidates, with operation tests for both proof-sync and validation-capture markers.
- [ ] CS3: Reshape deterministic routing test ownership: move the pure parser/classifier edge-case coverage for `PASS_STATUS`, Munera open task path, `PROOF_SYNC_ROUTE`, and `VALIDATION_CAPTURE_ROUTE` out of the broad live delegate review test namespace into a dedicated `psi.agent-session.workflow.routing-test` (or equivalent) that exercises `psi.agent-session.workflow.routing` directly; leave the live namespace with only compact built-in operation registration/invocation smoke coverage so parser evolution is locally comprehensible and does not require the TUI/delegate live harness for every edge case.
