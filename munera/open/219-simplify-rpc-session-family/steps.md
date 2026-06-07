# 219 — Steps

Checklist derived from `plan.md`. Tick each item with the commit sha / decision
when done.

## Plan/steps ambiguity review follow-ups

- [x] PA1: Pin the focused RPC baseline/characterization verification command(s) before Slice 1 execution: name the exact Scry/bb command(s) and namespaces/files from the design's affected test set, plus any explicitly added RPC tests, so Slice 1/2/5 rerun the same suite. Done in this follow-up: `plan.md` now pins the exact `bb clojure:test:scry --dir components/rpc/test --namespace ...` command for the eight design-listed RPC namespaces/files and requires any added characterization namespace to update the command before Slice 2 commit.
- [x] PA2: Choose one authoritative location and minimal shape for the coverage map/gap record (for example a root-relative task-local `coverage-map.edn`/`.md` or a named section in `implementation.md`) and update Slice 2/5 references to that location; avoid `implementation.md`-or-unnamed-artifact ambiguity. Done in this follow-up: `plan.md`, the new `coverage-map.md` template, and Slice 2/5 steps now use `munera/open/219-simplify-rpc-session-family/coverage-map.md` with sections for verification command, source-area coverage, behaviour coverage, and gap disposition.
- [x] PA3: Pin Slice 6 review-gate mechanics to the architecture workflow's exact review-step skill sequence/names (`task-implementation-review`, `task-test-review`, `review-implementation-architecture`, `test-shaper`, `review-task-docs`, `code-shaper`) and make the architecture gate use `review-implementation-architecture`, not the design-only architecture review. Done in this follow-up: `plan.md` and Slice 6 checklist now name the ordered review-step sequence and explicitly forbid using design-only `review-task-architecture` for the architecture implementation gate.

## Plan/steps inconsistency review follow-ups

- [ ] PI1: Align Slice 1 with the plan/design clean-baseline contract: require the recorded `git status --short --branch` to show a clean pre-refactor worktree before running the baseline suite; if there is pre-existing dirt, record it and stop or explicitly resolve it before claiming a clean baseline.
- [ ] PI2: Align Slice 2 with the focused-suite propagation contract: if coverage review adds any new RPC characterization namespace/file, update the exact focused command in `plan.md`, the relevant `steps.md` checklist text, and `characterization-baseline.edn` before committing Slice 2, so Slice 1/2/5 do not diverge on what the authoritative suite contains.
- [ ] PI3: Align the adjacent-source escape hatch across design/plan/steps: if the chosen seam requires any adjacent production file, stop and update the plan/design before editing with the exact root-relative file, why the target cannot be simplified without it, and why the change remains narrow behaviour-preserving contract alignment rather than broader TUI/Emacs/extension/workflow/app-runtime redesign.

## Slice 1 — Preflight and clean baseline

- [ ] Confirm `design-steps.md` has no unchecked follow-ups and record the result in `implementation.md`.
- [ ] Run `git status --short --branch` from the worktree root and record the pre-refactor worktree state in `implementation.md`.
- [ ] Re-read `design.md`, `architecture-targets.edn`, and `target-issues.edn`; record the selected candidate id/type and fixed target namespace/source-area list in `implementation.md`.
- [ ] Verify every target source file listed in `design.md` exists under `components/rpc/src/psi/rpc/session/`; stop if any target file is missing.
- [ ] Verify the candidate affected test files listed in `design.md` exist; note any missing or additionally relevant RPC tests in `implementation.md`.
- [ ] Run the pinned focused RPC baseline suite from `plan.md` covering command results, prompt commands, prompt streaming, navigation, RPC events, invariants, ops, and RPC end-to-end behaviour.
- [ ] Write `munera/open/219-simplify-rpc-session-family/characterization-baseline.edn` with `:git/head`, `:git/status-short`, selected candidate map, target namespaces, target source areas, affected test areas, baseline commands, and pass/fail summaries.
- [ ] If baseline tests fail for unrelated reasons that cannot be resolved locally, stop before implementation and record the failure/disposition in `implementation.md`.
- [ ] Commit Slice 1 baseline/task-artifact updates.

## Slice 2 — Coverage review and characterization gate

- [ ] Build a coverage map from each target source area to existing tests that assert observable state/output for that area.
- [ ] Cover command dispatch and command-result behaviours in the map, including prompt-path legacy result mapping and command-op result/event mapping.
- [ ] Cover picker/model/thinking command behaviours and frontend-action result behaviours in the map.
- [ ] Cover command tree/resume/session rehydration/navigation behaviours in the map, including session matching, rename, already-active, missing-session, switch, fork, and new-session cases when currently observable.
- [ ] Cover prompt/stream behaviours in the map, including slash command handling, progress event mapping, assistant message emission, footer refresh on retry/tool-result, and stop/drain semantics.
- [ ] Cover projection/emit behaviours in the map, including context/footer/session snapshot events, UI snapshot subscription, and event-driven invalidation delivery.
- [ ] Record the coverage map and identified gaps in `munera/open/219-simplify-rpc-session-family/coverage-map.md`.
- [ ] For each fixable gap, add a characterization test that proves current behaviour without asserting the future refactored structure.
- [ ] Ensure characterization tests assert emitted events, response frames, state changes, or public outputs; do not assert internal interactions or mocks for logic dependencies.
- [ ] Re-run the pinned focused RPC baseline/characterization suite from `plan.md` until green.
- [ ] If required behaviour cannot be characterized safely, stop before implementation and record the infeasible-coverage reason in `implementation.md`.
- [ ] Run `git diff --stat` and `git diff --` before production refactoring; verify the diff contains only task artifacts and characterization/test-net changes.
- [ ] Commit Slice 2 characterization-test-net updates.

## Slice 3 — Ownership seam selection

- [ ] Inspect dependencies and call flow among `commands`, `command-results`, `command-tree`, `command-resume`, `command-pickers`, `frontend-actions`, `navigation`, `emit`, `projections`, `prompt`, and `streams`.
- [ ] Identify the repeated shared decision/data-shaping surface that best explains conceptual overlap around `commands`, `command-results`, and `command-tree`.
- [ ] Decide whether the simplification seam is command/result/navigation-oriented, projection/stream/emit-oriented, or a smaller cross-cutting protocol-adaptation seam.
- [ ] Record the chosen seam, evidence, and rejected alternatives in `implementation.md` before source edits.
- [ ] Verify the chosen seam keeps RPC limited to protocol adaptation/fanout and leaves adapter-neutral semantics with existing `app-runtime`/domain owners.
- [ ] Verify the chosen seam can be implemented inside authorized target source files only.
- [ ] If the chosen seam requires an adjacent production source file, stop and update `plan.md` before editing that file.
- [ ] Commit Slice 3 seam-selection notes if task artifacts changed.

## Slice 4 — Target-local architecture simplification

- [ ] Apply the smallest production refactor for the chosen seam inside the authorized target source files.
- [ ] If extracting helpers, give them narrow names and contracts that describe RPC protocol adaptation, response/event emission, or subscriber fanout rather than adapter-neutral UI semantics.
- [ ] Keep command dispatch orchestration separate from command result/event shaping where local code permits.
- [ ] Keep navigation/result call sites clearly using existing `app-runtime` navigation/action/selector owners for adapter-neutral meaning.
- [ ] Keep projection/stream/emit delivery event-driven and recompute payloads from canonical context/state/public-model functions.
- [ ] Do not introduce RPC-local cached canonical projection snapshots, polling refresh, compatibility shims, or adapter-specific freshness models.
- [ ] Re-read every changed source file after edits and verify formatting/parentheses locally.
- [ ] Run `clj-paren-repair` or equivalent formatter on changed Clojure source/test files when needed.
- [ ] Run the focused RPC tests affected by the changed source files.
- [ ] Record implementation decisions, changed files, and focused test results in `implementation.md`.
- [ ] Commit Slice 4 production simplification and related test updates.

## Slice 5 — Focused verification and Gordian validation

- [ ] Re-run the full pinned focused RPC baseline/characterization suite from Slice 1/2 and record results in `implementation.md`.
- [ ] Run targeted `clj-kondo` over changed Clojure source/test files and record results.
- [ ] Run `git diff --check` and record the result.
- [ ] Run `bb gordian diagnose --edn > munera/open/219-simplify-rpc-session-family/after-diagnose.edn` from the worktree root.
- [ ] Run `bb gordian architecture-targets --edn > munera/open/219-simplify-rpc-session-family/after-architecture-targets.edn` from the worktree root.
- [ ] Run `bb gordian compare munera/open/219-simplify-rpc-session-family/before-diagnose.edn munera/open/219-simplify-rpc-session-family/after-diagnose.edn --edn > munera/open/219-simplify-rpc-session-family/architecture-compare.edn` from the worktree root.
- [ ] Run `bb gordian gate --baseline munera/open/219-simplify-rpc-session-family/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn > munera/open/219-simplify-rpc-session-family/architecture-gate.edn` from the worktree root.
- [ ] Verify `architecture-gate.edn` represents a successful gate; if not, add concrete repair steps and return to Slice 4.
- [ ] Inspect `architecture-compare.edn` for no new cycles, no new high findings, and zero new medium findings; recheck `coverage-map.md` for stale gaps; record any improvement or justified non-improvement in `implementation.md`.
- [ ] Commit Slice 5 validation artifacts and verification notes.

## Slice 6 — Review gates and closure

- [ ] Run `review-step` with skill `task-implementation-review` for the final diff against `design.md`, `plan.md`, and `steps.md`; append findings or `REVIEW_COMPLETE` to `implementation.md`.
- [ ] Run `review-step` with skill `task-test-review` for the characterization and regression tests; append findings or `REVIEW_COMPLETE` to `implementation.md`.
- [ ] Run `review-step` with skill `review-implementation-architecture` against the selected Gordian target, ownership constraints, validation artifacts, and blast-radius limits; append findings or `REVIEW_COMPLETE` to `implementation.md`; do not use design-only `review-task-architecture` here.
- [ ] Run `review-step` with skill `test-shaper` for clarity, signal, determinism, and absence of interaction/mock assertions; append findings or `REVIEW_COMPLETE` to `implementation.md`.
- [ ] Run `review-step` with skill `review-task-docs`; confirm no README/doc/CHANGELOG update is required for behaviour-preserving internal simplification, or update user-facing docs if observable behaviour changed.
- [ ] Run `review-step` with skill `code-shaper` for simplicity, consistency, robustness, and absence of sideways orchestration/adapter/shim complexity.
- [ ] Add any actionable review follow-ups as new unchecked checklist items under this section and execute them before closure.
- [ ] Run final focused tests, targeted lint, `git diff --check`, and any formatter checks required by changed files.
- [ ] Append final implementation verification notes and PASS_STATUS to `implementation.md`.
- [ ] Commit Slice 6 review/closure updates.

## Plan-created checklist

- [x] Created `plan.md` from stable `design.md` with approach, risks, and slice order.
- [x] Created `steps.md` with concrete implementation checklist grouped by slice.
