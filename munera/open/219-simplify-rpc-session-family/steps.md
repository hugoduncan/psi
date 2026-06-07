# 219 — Steps

Checklist derived from `plan.md`. Tick each item with the commit sha / decision
when done.

## Plan/steps ambiguity review follow-ups

- [x] PA1: Pin the focused RPC baseline/characterization verification command(s) before Slice 1 execution: name the exact Scry/bb command(s) and namespaces/files from the design's affected test set, plus any explicitly added RPC tests, so Slice 1/2/5 rerun the same suite. Done in this follow-up: `plan.md` now pins the exact `bb clojure:test:scry --dir components/rpc/test --namespace ...` command for the eight design-listed RPC namespaces/files and requires any added characterization namespace to update the command before Slice 2 commit.
- [x] PA2: Choose one authoritative location and minimal shape for the coverage map/gap record (for example a root-relative task-local `coverage-map.edn`/`.md` or a named section in `implementation.md`) and update Slice 2/5 references to that location; avoid `implementation.md`-or-unnamed-artifact ambiguity. Done in this follow-up: `plan.md`, the new `coverage-map.md` template, and Slice 2/5 steps now use `munera/open/219-simplify-rpc-session-family/coverage-map.md` with sections for verification command, source-area coverage, behaviour coverage, and gap disposition.
- [x] PA3: Pin Slice 6 review-gate mechanics to the architecture workflow's exact review-step skill sequence/names (`task-implementation-review`, `task-test-review`, `review-implementation-architecture`, `test-shaper`, `review-task-docs`, `code-shaper`) and make the architecture gate use `review-implementation-architecture`, not the design-only architecture review. Done in this follow-up: `plan.md` and Slice 6 checklist now name the ordered review-step sequence and explicitly forbid using design-only `review-task-architecture` for the architecture implementation gate.

## Plan/steps inconsistency review follow-ups

- [x] PI1: Align Slice 1 with the plan/design clean-baseline contract: require the recorded `git status --short --branch` to show a clean pre-refactor worktree before running the baseline suite; if there is pre-existing dirt, record it and stop or explicitly resolve it before claiming a clean baseline. Done in this follow-up: `plan.md` and Slice 1 steps now require `git status --short --branch` to show only the branch header/no short-status entries before running or claiming a clean baseline; pre-existing dirt must be recorded and stopped/resolved first.
- [x] PI2: Align Slice 2 with the focused-suite propagation contract: if coverage review adds any new RPC characterization namespace/file, update the exact focused command in `plan.md`, the relevant `steps.md` checklist text, and `characterization-baseline.edn` before committing Slice 2, so Slice 1/2/5 do not diverge on what the authoritative suite contains. Done in this follow-up: `plan.md` now requires `plan.md`, `steps.md`, `coverage-map.md`, and `characterization-baseline.edn` to share one authoritative command/namespace set across Slices 1/2/5 after any new characterization namespace; Slice 2 checklist now requires that propagation before committing the characterization gate.
- [x] PI3: Align the adjacent-source escape hatch across design/plan/steps: if the chosen seam requires any adjacent production file, stop and update the plan/design before editing with the exact root-relative file, why the target cannot be simplified without it, and why the change remains narrow behaviour-preserving contract alignment rather than broader TUI/Emacs/extension/workflow/app-runtime redesign. Done in this follow-up: `plan.md` and Slice 3 steps now require stopping before adjacent production edits and synchronizing `design.md`/`plan.md` with the exact root-relative file, necessity, and narrow behaviour-preserving contract-alignment rationale, explicitly excluding broader redesign.

## Slice 1 — Preflight and clean baseline

- [ ] Confirm `design-steps.md` has no unchecked follow-ups and record the result in `implementation.md`.
- [ ] Run `git status --short --branch` from the worktree root and record the pre-refactor worktree state in `implementation.md`; require the output to show only the branch header and no short-status entries before running or claiming the clean baseline. If there is pre-existing dirt, record it and stop or explicitly resolve it before continuing.
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
- [ ] For each fixable gap, add a characterization test that proves current behaviour without asserting the future refactored structure; if adding a new RPC characterization namespace/file, first update the exact focused command in `plan.md`, this checklist's verification wording, and `characterization-baseline.edn` so Slices 1/2/5 share one authoritative suite.
- [ ] Ensure characterization tests assert emitted events, response frames, state changes, or public outputs; do not assert internal interactions or mocks for logic dependencies.
- [ ] Re-run the pinned focused RPC baseline/characterization suite from `plan.md` until green, verifying it includes every characterization namespace/file recorded in `coverage-map.md` and `characterization-baseline.edn`.
- [ ] If required behaviour cannot be characterized safely, stop before implementation and record the infeasible-coverage reason in `implementation.md`.
- [ ] Run `git diff --stat` and `git diff --` before production refactoring; verify the diff contains only task artifacts and characterization/test-net changes.
- [ ] Commit Slice 2 characterization-test-net updates.

## Characterization fix passes

- [x] 2026-06-07 pass: Filled `coverage-map.md` with source-area/behaviour coverage, added `psi.rpc-test/rpc-model-and-thinking-picker-frontend-actions-test` for `/model`, `/thinking`, submitted `select-model`, and submitted `select-thinking-level` RPC behaviour, updated `characterization-baseline.edn` with the focused command/result summary, and reran the pinned focused suite green (55 tests / 422 assertions). No production changes and no testability seam.
- [x] 2026-06-07 pass 2: Added `psi.rpc-session-navigation-test/rpc-tree-command-edge-behaviour-test` for `/tree` already-active, missing-session, rename, and unique-prefix switch behaviours; added `psi.rpc-test/rpc-frontend-action-cancelled-and-failed-result-test` for cancelled/failed `frontend_action_result` payloads and no snapshot emission; updated `coverage-map.md` and `characterization-baseline.edn`; reran the pinned focused suite green (57 tests / 439 assertions). No production changes and no testability seam.

## Slice 3 — Ownership seam selection

- [x] Inspect dependencies and call flow among `commands`, `command-results`, `command-tree`, `command-resume`, `command-pickers`, `frontend-actions`, `navigation`, `emit`, `projections`, `prompt`, and `streams`. Done in Slice 3 seam-selection notes: command/result/navigation duplication and app-runtime navigation ownership were inspected before source edits.
- [x] Identify the repeated shared decision/data-shaping surface that best explains conceptual overlap around `commands`, `command-results`, and `command-tree`. Done in Slice 3: duplicated command-result-driven rehydration and manual resume/tree-switch navigation emission were selected as the shared RPC adaptation surface.
- [x] Decide whether the simplification seam is command/result/navigation-oriented, projection/stream/emit-oriented, or a smaller cross-cutting protocol-adaptation seam. Done in Slice 3: chose a command/result/navigation-oriented RPC protocol-adaptation seam.
- [x] Record the chosen seam, evidence, and rejected alternatives in `implementation.md` before source edits. Done in Slice 3 implementation note dated 2026-06-07.
- [x] Verify the chosen seam keeps RPC limited to protocol adaptation/fanout and leaves adapter-neutral semantics with existing `app-runtime`/domain owners. Done in Slice 3: app-runtime navigation/messages remain authoritative; RPC emits protocol frames/events only.
- [x] Verify the chosen seam can be implemented inside authorized target source files only. Done in Slice 3: planned edits are limited to target RPC session source files.
- [x] If the chosen seam requires an adjacent production source file, stop and update `design.md` and `plan.md` before editing that file with the exact root-relative file, why the target cannot be simplified without it, and why the change remains narrow behaviour-preserving contract alignment rather than broader TUI/Emacs/extension/workflow/app-runtime redesign. Not needed: Slice 3 chose a target-local seam using existing app-runtime contracts.
- [x] Commit Slice 3 seam-selection notes if task artifacts changed. Done in the Slice 3 seam-selection commit.

## Slice 4 — Target-local architecture simplification

- [x] Apply the smallest production refactor for the chosen seam inside the authorized target source files. Done in Slice 4: centralized duplicated `/new` command-result rehydration shaping in `commands.clj`; attempted resume/tree navigation routing was reverted before commit after Gordian gate failure.
- [x] If extracting helpers, give them narrow names and contracts that describe RPC protocol adaptation, response/event emission, or subscriber fanout rather than adapter-neutral UI semantics. Done: `commands/new-session-command-payload`, `commands/focus-new-session-command!`, and `commands/emit-new-session-command!` describe RPC command-result emission/adaptation.
- [x] Keep command dispatch orchestration separate from command result/event shaping where local code permits. Done: command/prompt dispatch call the shared command-path helper rather than shaping `/new` rehydration inline.
- [x] Keep navigation/result call sites clearly using existing `app-runtime` navigation/action/selector owners for adapter-neutral meaning. Done/verified: committed change did not move adapter-neutral navigation semantics; `/resume` and `/tree` behaviour remains with existing owners, while `/new` command rehydration shaping is a narrow RPC command-result adapter.
- [x] Keep projection/stream/emit delivery event-driven and recompute payloads from canonical context/state/public-model functions. Done: no projection/stream changes; emit still recomputes payloads from canonical context/state helpers.
- [x] Do not introduce RPC-local cached canonical projection snapshots, polling refresh, compatibility shims, or adapter-specific freshness models. Done: no cache, polling, shim, or adapter-specific freshness model added.
- [x] Re-read every changed source file after edits and verify formatting/parentheses locally. Done after editing/reverting candidates and re-reading the committed changed source files `commands.clj` and `prompt.clj`.
- [x] Run `clj-paren-repair` or equivalent formatter on changed Clojure source/test files when needed. Done on all changed Clojure source files.
- [x] Run the focused RPC tests affected by the changed source files. Done: pinned focused RPC suite green, 57 tests / 439 assertions.
- [x] Record implementation decisions, changed files, and focused test results in `implementation.md`. Done in Slice 4 implementation note dated 2026-06-07.
- [x] Commit Slice 4 production simplification and related test updates. Done in the Slice 4 production simplification commit.

## Slice 5 — Focused verification and Gordian validation

- [x] Re-run the full pinned focused RPC baseline/characterization suite from Slice 1/2 and record results in `implementation.md`. Done in Slice 5: green, 57 tests / 439 assertions.
- [x] Run targeted `clj-kondo` over changed Clojure source/test files and record results. Done in Slice 5: changed source files linted with 0 errors / 0 warnings.
- [x] Run `git diff --check` and record the result. Done in Slice 5: clean.
- [x] Run `bb gordian diagnose --edn > munera/open/219-simplify-rpc-session-family/after-diagnose.edn` from the worktree root. Done in Slice 5.
- [x] Run `bb gordian architecture-targets --edn > munera/open/219-simplify-rpc-session-family/after-architecture-targets.edn` from the worktree root. Done in Slice 5.
- [x] Run `bb gordian compare munera/open/219-simplify-rpc-session-family/before-diagnose.edn munera/open/219-simplify-rpc-session-family/after-diagnose.edn --edn > munera/open/219-simplify-rpc-session-family/architecture-compare.edn` from the worktree root. Done in Slice 5.
- [x] Run `bb gordian gate --baseline munera/open/219-simplify-rpc-session-family/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn > munera/open/219-simplify-rpc-session-family/architecture-gate.edn` from the worktree root. Done in Slice 5; the exact command exited 0 but emitted truncated EDN, so the committed artifact was regenerated through the same Gordian gate functions with unbounded EDN printing and parsed successfully.
- [x] Verify `architecture-gate.edn` represents a successful gate; if not, add concrete repair steps and return to Slice 4. Done in Slice 5: parsed `:result :pass`, 0 new cycles, 0 new high findings, 0 new medium findings.
- [x] Inspect `architecture-compare.edn` for no new cycles, no new high findings, and zero new medium findings; recheck `coverage-map.md` for stale gaps; record any improvement or justified non-improvement in `implementation.md`. Done in Slice 5: no new cycles/high/medium, one new low outside target, no stale coverage gaps, target score improved 102.89 → 102.33.
- [x] Commit Slice 5 validation artifacts and verification notes. Done in the Slice 5 validation commit.

## Slice 6 — Review gates and closure

- [x] Run `review-step` with skill `task-implementation-review` for the final diff against `design.md`, `plan.md`, and `steps.md`; append findings or `REVIEW_COMPLETE` to `implementation.md`. Done in Slice 6 review note: no actionable feedback.
- [x] Run `review-step` with skill `task-test-review` for the characterization and regression tests; append findings or `REVIEW_COMPLETE` to `implementation.md`. Done in Slice 6 review note: no actionable feedback.
- [x] Run `review-step` with skill `review-implementation-architecture` against the selected Gordian target, ownership constraints, validation artifacts, and blast-radius limits; append findings or `REVIEW_COMPLETE` to `implementation.md`; do not use design-only `review-task-architecture` here. Done in Slice 6 architecture review note: no actionable feedback.
- [x] Run `review-step` with skill `test-shaper` for clarity, signal, determinism, and absence of interaction/mock assertions; append findings or `REVIEW_COMPLETE` to `implementation.md`. Done in Slice 6 test-shaper note: no actionable feedback.
- [x] Run `review-step` with skill `review-task-docs`; confirm no README/doc/CHANGELOG update is required for behaviour-preserving internal simplification, or update user-facing docs if observable behaviour changed. Done in Slice 6 docs review note: no user-facing docs/changelog update required.
- [x] Run `review-step` with skill `code-shaper` for simplicity, consistency, robustness, and absence of sideways orchestration/adapter/shim complexity. Done in Slice 6 code-shaper note: no actionable feedback.
- [x] Add any actionable review follow-ups as new unchecked checklist items under this section and execute them before closure. Done: no actionable review follow-ups were found.
- [x] Run final focused tests, targeted lint, `git diff --check`, and any formatter checks required by changed files. Done in Slice 6 final verification: formatter no-op, focused RPC suite green 57/439, targeted clj-kondo 0/0, `git diff --check` clean.
- [x] Append final implementation verification notes and PASS_STATUS to `implementation.md`. Done in Slice 6 final note.
- [x] Commit Slice 6 review/closure updates. Done in the Slice 6 closure commit.

## Plan-created checklist

- [x] Created `plan.md` from stable `design.md` with approach, risks, and slice order.
- [x] Created `steps.md` with concrete implementation checklist grouped by slice.

## Pre-simplification baseline/diff gate

- [x] 2026-06-07 gate: verified baseline data and clean baseline target/source status, compared committed changes from baseline HEAD `c513da4bb7e195956689cfa6455262f565e806ee` through current HEAD plus empty uncommitted status/diff, classified all coverage-phase changes as characterization tests, task artifacts, or documentation/working-memory note with no source implementation changes or testability seams, reran the pinned focused RPC suite green (57 tests / 439 assertions), and recorded no revert/split/closure/retry required.

## Task-test-review follow-ups

- [x] TT1: Add a focused RPC test for the prompt-op slash-command `/new` path after the shared command-helper refactor. Done 2026-06-07: added `psi.rpc-prompt-command-test/rpc-prompt-new-slash-command-rehydrates-without-agent-loop-test`, driving an actual prompt request with message `/new` through the RPC/session harness, asserting the agent loop is not invoked, and locking prompt response acceptance, `session/resumed`, `session/rehydrated`, RPC focus, prompt-path assistant confirmation, and session/footer snapshot outputs. Focused prompt-command test and pinned RPC suite are green (58 tests / 449 assertions).
- [x] TT2: Add focused RPC coverage for slash-command `/new` when the runtime provides an `:on-new-session!` callback. Done 2026-06-07: added `psi.rpc-prompt-command-test/rpc-prompt-new-slash-command-uses-callback-rehydrate-payload-test`, driving prompt `/new` with a runtime `:on-new-session!` callback through the existing RPC/session harness and proving callback source-session id, agent-loop suppression, callback-created rehydrated session, callback-supplied startup transcript, tool metadata/order, RPC focus movement, and prompt-path confirmation. Verification green: targeted TT2 1 test / 10 assertions, pinned RPC suite 59 tests / 459 assertions, targeted clj-kondo 0/0, `git diff --check` clean.
- [x] TT3: Add focused RPC command-op `/new` coverage when the runtime provides an `:on-new-session!` callback, proving the command request path threads the callback into slash resolution and emits callback-created rehydration/startup transcript/tool metadata plus focus movement and command-result output through the shared helper. Done 2026-06-07: added callback-backed command-op `/new` coverage in `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test`, asserting callback source-session id, accepted command response, callback-created rehydration, startup transcript, tool metadata/order, RPC focus movement, and `new_session` command-result output. Verification green: targeted navigation test 1 test / 42 assertions, pinned RPC suite 59 tests / 469 assertions, targeted clj-kondo 0/0, `git diff --check` clean.
- [x] TT4: Update `coverage-map.md` after TT3 so the authoritative coverage artifact records callback-backed command-op `/new` coverage in `psi.rpc-session-navigation-test/rpc-session-resume-and-rehydrate-events-test` and the latest focused suite count (59 tests / 469 assertions), rather than the stale 59 / 459 assertion count and prompt-only callback gap disposition. Done 2026-06-07: refreshed `coverage-map.md` latest count to 59 tests / 469 assertions, recorded callback-backed command-op `/new` coverage under `commands.clj` and command/result behaviour coverage, and added the gap disposition for callback-backed command-op `/new` through `rpc-session-resume-and-rehydrate-events-test`.

## Post-implementation validation recapture failures

- [x] REPAIR-VALIDATION-1: Repair the Gordian gate EDN output path so `bb gordian gate --baseline munera/open/219-simplify-rpc-session-family/before-diagnose.edn --fail-on new-cycles,new-high-findings --max-new-medium-findings 0 --edn` emits complete parseable EDN to stdout without truncation, then rerun the full post-implementation validation-capture procedure before any review-step gate accepts the validation artifacts. Done 2026-06-07: fixed local Gordian CLI process-exit flushing (`42f1daa` in `/Users/duncan/projects/hugoduncan/gordian/gordian-master`), reran diagnose/architecture-targets/compare/gate capture from the psi worktree, and verified all four validation artifacts parse; `architecture-gate.edn` is raw complete EDN with `:result :pass`.
