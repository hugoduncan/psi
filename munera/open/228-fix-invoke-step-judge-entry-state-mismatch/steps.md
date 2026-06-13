# 228 — Steps

## Slice 1 — Characterization test (red)

- [x] In `components/deterministic-operation-runtime/test/psi/deterministic_operation_runtime/core_test.clj`, add a `deftest` that builds a `state*` atom with one running run + one step-run + one attempt (`:attempt-id "attempt-1"`), mirroring the spike state shape.
- [x] Drive `runtime/invoke-operation` once for the step `:operation` (default role) against `attempt-1`; assert `:status :ok` and `:operation-handler-entry-state :entered`.
- [x] Drive `runtime/invoke-operation` a second time for the judge operation with `:operation-role :judge` against the **same** `attempt-1`; assert `:status :ok` (the post-fix behaviour).
- [x] Assert the judge op landed `:judge-operation-handler-entry-state :entered` and left the step op's `:operation-handler-entry-state :entered` untouched.
- [x] Run the suite and confirm the test **fails** pre-fix with `:reason :workflow-stopped` / `:stop-reason :handler-entry-state-mismatch` (red baseline captured in implementation.md).

## Slice 2 — Runtime phase-key namespacing

- [x] In `deterministic-operation-runtime/core`, add a private `role-phase-key` helper mapping `(role, base-key)` → namespaced keyword (`:judge` → `judge-`prefix; `:step`/nil → unchanged).
- [x] Apply the role at the **single** `transition-workflow-operation-phase!` chokepoint: read `(:operation-role invocation)` there once and rewrite the supplied `phase-opts` keys (`:phase-key`, `:timestamp-key`, `:count-key`, and each `:required-phases` entry's `:key`) via `role-phase-key` (extracted as `role-phase-opts`) before merging into `ordinary-entry/transition-latest-attempt!`. Six phase helpers unchanged.
- [x] Confirm `ordinary-entry` needs no change (already parameterized on the supplied keys).
- [x] Verify the default-role path produces byte-identical `:operation-*` keys (no behaviour change for single-operation steps).
- [x] `clj-paren-repair` the edited file; re-read to confirm coherence.
- [x] Re-run the Slice 1 test driving both ops directly with `:operation-role`; confirm the judge op now succeeds at the runtime layer.

## Slice 3 — Judge call-site role

- [x] In `components/agent-session/src/psi/agent_session/workflow_judge.clj`, add `:operation-role :judge` to the invocation map in `execute-invoke-judge!`.
- [x] In `components/workflow-runtime/.../statechart_runtime/step_execution.clj` `invoke-step-runtime-result`, leave the step `:operation` invocation with **no** `:operation-role` key (relies on absent/default ≡ `:step`); do not add an explicit `:operation-role :step` — only judge invocations annotate a role.
- [x] Confirm `invoke-operation-in` (registry) requires no change — invocation passes through unchanged.
- [x] Add/extend a step-execution or workflow-runtime test covering an invoke step with both an `:operation` and an invoke `:judge`: both deterministic operations complete and routing follows the judge outcome (no `:workflow-stopped` / `:handler-entry-state-mismatch`). Covered end-to-end by the existing `workflow-review-step-routing-test` clarity-status suites (now green).

## Slice 3b — Second defect discovered: stale-snapshot `:attempt-mismatch` on REPEAT (ψ)

- [x] Diagnosed: with the phase-key fix, the **first** clarity-status pass now routes REPEAT, exposing a **distinct** defect — the second clarity-status attempt's step `:operation` aborts with `:stop-reason :attempt-mismatch`.
- [x] Root cause: `invoke-step-runtime-result` derived `:workflow-attempt-id` from the `workflow-run` snapshot taken **before** the new attempt was appended. First attempt: snapshot empty → `nil` → task-225 equality guard skipped (worked). REPEAT: stale snapshot's latest = previous attempt id ≠ live latest → `:attempt-mismatch`.
- [x] Fix: thread the authoritative just-started `attempt-id` from the `:step/enter` caller into `invoke-step-runtime-result` (both call sites: `statechart_runtime.clj` and `step_execution.clj` `execute-actor-step!`); use it for `:workflow-attempt-id` instead of re-deriving from the snapshot.
- [x] Verified: all 11 `workflow-review-step-routing-test` tests now green (REPEAT loops + iteration-limit failures), confirming acceptance criteria #2 and #4.

## Slice 4 — Cancellation regression coverage

- [x] Add a role-aware cancellation test: a stop signal arriving before/within the judge operation yields a clean `:workflow-stopped` terminal (judge handler not invoked). — `judge-role-operation-honors-workflow-cancellation-test`.
- [x] Add/confirm a test that a stop before/within the step operation still stops cleanly (default role). — existing `invoke-operation-honors-workflow-cancellation-test` (default role) stays green.
- [x] Run existing 225 cancellation suites (`deterministic-operation-runtime`, and explicitly `workflow_statechart_runtime_call_start_cancellation_test.clj` — the suite asserting the default-role `:operation-*-state` keys the namespacing must leave unchanged) and confirm they stay green. Call-start-cancellation 14 tests/63 assertions green; judge-cancellation 8/34; statechart-runtime-cancellation green. (No dedicated `workflow-coordination` test ns exists; `ordinary-entry` is exercised via these suites.)

## Slice 5 — Workflow-level verification + close-out

- [x] Add runtime/definition-level coverage asserting the `clarity-status` invoke step with `pass-feedback-routing` judge routes REPEAT/DONE without the abort. Covered by the existing `workflow-review-step-routing-test` suite (`design-review-full-pass-routing-test`, `plan-review-full-pass-routing-test`, `review-pass-loop-iteration-limit-failure-test`), which were failing pre-fix (3 tests / 21 assertions) and are now all green (11 tests / 82 assertions).
- [x] Confirm no malli/invocation schema rejects the additive `:operation-role` key. The invocation map is open; `invoke-operation-in` passes it through unchanged; all suites green.
- [x] Add a CHANGELOG `Fixed` entry (covers both the `:handler-entry-state-mismatch` phase-key fix and the `:attempt-mismatch` stale-snapshot fix; user-visible: unblocks `task-lifecycle` / `review-task-design`).
- [x] Run `clj-kondo --lint` on all changed namespaces; 0 errors / 0 warnings.
- [x] Run the relevant Scry suites and confirm green: deterministic-operation-runtime core (5/24), workflow-statechart-runtime-call-start-cancellation (14/63), workflow-judge (17/88), workflow-judge-cancellation (8/34), workflow-statechart-runtime + cancellation + workflow-execution (34/153), workflow-runtime step-execution (10/63), workflow-review-step-routing (11/82), workflow-runtime lifecycle/state, github find-issue integration (1/13).
- [x] Update `implementation.md` with the final key-namespacing decision, the green test summary, and the discovered-second-defect deviation.

## Review follow-ups — plan/steps ambiguity (ψ 2026-06-13)

- [x] Resolve the role-injection strategy ambiguity: state explicitly whether `role-phase-key` is applied once at the central `transition-workflow-operation-phase!` chokepoint (remapping the supplied phase-opts after reading `(:operation-role invocation)`) or inside each phase helper, and update Plan §Mechanism + Slice 2 to one consistent description (`λone_way`).
  - Decided: **single chokepoint**. Applied once in `transition-workflow-operation-phase!`; six helpers unchanged. Plan §Mechanism, Slice 2, and design.md updated to the one strategy.
- [x] Correct the "five transition helpers" count: there are **six** (`reserve`, `commit-start`, `begin-call`, `commit-call`, `prepare-handler-entry`, `enter-handler`); fix design.md + plan.md (or note it is moot under the chokepoint strategy) so helper coverage is unambiguous.
  - Count corrected to six in design.md + plan.md; noted moot under chokepoint (helpers untouched).
- [x] Name the `agent-session` call-start-cancellation suite (`workflow_statechart_runtime_call_start_cancellation_test`) explicitly in the Slice 4/Slice 5 regression set, since it is the suite that directly asserts the default-role `:operation-*-state` keys that the namespacing must leave unchanged.
  - Named explicitly in plan Slice 4/5 and steps Slice 4/5.
- [x] Decide and record whether the step `:operation` call-site carries an explicit `:operation-role :step` or relies on the absent/default; remove the "only if it improves clarity" deferral so the final artifact state is determinate (Slice 3 + plan call-site threading).
  - Decided: **omit** `:operation-role` at the step call-site (absent ≡ `:step`); only judge invocations annotate a role. Deferral removed in plan + Slice 3.

## Review follow-ups — implementation review (ψ 2026-06-13)

- [x] Add a focused regression test for the second defect (`:attempt-mismatch`
  on REPEAT): pin that a re-executed invoke step's step `:operation` uses the
  just-started `attempt-id` (threaded from `:step/enter`) rather than the latest
  attempt derived from the stale `workflow-run` snapshot. Place it at the
  `step_execution` / `deterministic-operation-runtime` level so the fix has a
  localized characterization test with parity to the first defect's
  `…share-one-attempt-test`, instead of relying solely on the end-to-end
  `workflow-review-step-routing-test` REPEAT loops.
  - Added `invoke-step-re-execution-uses-just-started-attempt-not-stale-snapshot-test`
    to `workflow-runtime/.../step_execution_test.clj`. It calls
    `invoke-step-runtime-result` with a **stale** `workflow-run` snapshot (latest
    = `attempt-1`) while live `state*` and the threaded `attempt-id` are the
    just-started `attempt-2`, then asserts `:ok` + `attempt-2` driven to
    `:entered` + `attempt-1` untouched. Verified red-on-revert: deriving
    `:workflow-attempt-id` from the stale snapshot fails 3 assertions with
    `:attempt-mismatch`; green with the threaded fix. Suite: 11 tests / 67
    assertions green; clj-kondo clean.

## Review follow-ups — plan/steps inconsistency (ψ 2026-06-13)

- [x] Correct design.md Problem §2: the judge invocation passes the **explicit** latest `:workflow-attempt-id` (`(… :attempts last :attempt-id)`), not `:workflow-attempt-id nil`. Align Problem §2 with design.md "Spike outcome (confirmed)", implementation.md "Confirmed facts", and the actual code (`execute-invoke-judge!` / `invoke-step-runtime-result`), and drop the nil-based "targets the same latest attempt" reasoning (with a real id + `:attempt-id-required? false`, `ordinary-entry` asserts equality with the latest attempt).
  - Problem §2 rewritten: judge passes the explicit latest `:workflow-attempt-id`; with a real id + `:attempt-id-required? false`, `ordinary-entry` line 77 (`(or attempt-id-required? workflow-attempt-id)`) still asserts equality with the latest attempt. Verified against `workflow_judge.clj:129` and `ordinary_entry.clj:77`. Now consistent with Spike-outcome / implementation.md / code.
- [x] Remove the residual deferral in plan.md §"Slice order" Slice 3 — "(and make the step-operation default explicit if it aids clarity)" — so it matches the determinate decision in plan §"Call-site threading" and steps.md Slice 3 (omit `:operation-role` at the step call-site; only judge invocations annotate a role).
  - Parenthetical removed; Slice 3 now states the step call-site omits the key (absent ≡ `:step`), cross-referencing §Call-site threading. Determinate everywhere.

## Review follow-ups — implementation review pass 2 (ψ 2026-06-13)

- [ ] Make the judge call-site use the authoritative attempt id instead of
  re-deriving it from the `workflow-run` snapshot. In
  `agent_session/workflow_judge.clj` `execute-invoke-judge!`, replace
  `:workflow-attempt-id (some-> workflow-run (get-in [:step-runs current-step-id
  :attempts]) last :attempt-id)` (line ~129) with the `workflow-attempt-id`
  already carried in `routing-context` (destructured in `execute-judge!`,
  line ~168, and used by the cancellation guards). This aligns the judge path
  with the second-defect structural fix applied to the step `:operation`
  (`invoke-step-runtime-result` threads the authoritative just-started
  `attempt-id`), removing the same latent `:attempt-mismatch` snapshot-staleness
  failure mode (`λ consistent(code)`; `cause(structural) → redesign > patch`).
  Keep the `workflow-run` snapshot only for `resolve-invoke-args`.
- [ ] Add a focused regression test pinning the judge's attempt-id source:
  drive `execute-invoke-judge!` (or `execute-judge!`) for an invoke judge with a
  **stale** `workflow-run` snapshot while the live latest attempt and
  `routing-context`'s `workflow-attempt-id` are the just-started attempt; assert
  the judge operation targets the authoritative attempt (no `:attempt-mismatch`),
  giving parity with the step-path
  `invoke-step-re-execution-uses-just-started-attempt-not-stale-snapshot-test`.
