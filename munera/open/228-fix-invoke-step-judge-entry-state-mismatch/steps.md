# 228 — Steps

## Slice 1 — Characterization test (red)

- [ ] In `components/deterministic-operation-runtime/test/psi/deterministic_operation_runtime/core_test.clj`, add a `deftest` that builds a `state*` atom with one running run + one step-run + one attempt (`:attempt-id "attempt-1"`), mirroring the spike state shape.
- [ ] Drive `runtime/invoke-operation` once for the step `:operation` (default role) against `attempt-1`; assert `:status :ok` and `:operation-handler-entry-state :entered`.
- [ ] Drive `runtime/invoke-operation` a second time for the judge operation with `:operation-role :judge` against the **same** `attempt-1`; assert `:status :ok` (the post-fix behaviour).
- [ ] Assert the judge op landed `:judge-operation-handler-entry-state :entered` and left the step op's `:operation-handler-entry-state :entered` untouched.
- [ ] Run the suite and confirm the test **fails** pre-fix with `:reason :workflow-stopped` / `:stop-reason :handler-entry-state-mismatch` (red baseline captured in implementation.md).

## Slice 2 — Runtime phase-key namespacing

- [ ] In `deterministic-operation-runtime/core`, add a private `role-phase-key` helper mapping `(role, base-key)` → namespaced keyword (`:judge` → `judge-`prefix; `:step`/nil → unchanged).
- [ ] Apply the role at the **single** `transition-workflow-operation-phase!` chokepoint: read `(:operation-role invocation)` there once and rewrite the supplied `phase-opts` keys (`:phase-key`, `:timestamp-key`, `:count-key`, and each `:required-phases` entry's `:key`) via `role-phase-key` before merging into `ordinary-entry/transition-latest-attempt!`. Leave the six phase helpers (`reserve`, `commit-start`, `begin-call`, `commit-call`, `prepare-handler-entry`, `enter-handler`) unchanged.
- [ ] Confirm `ordinary-entry` needs no change (already parameterized on the supplied keys).
- [ ] Verify the default-role path produces byte-identical `:operation-*` keys (no behaviour change for single-operation steps).
- [ ] `clj-paren-repair` the edited file; re-read to confirm coherence.
- [ ] Re-run the Slice 1 test driving both ops directly with `:operation-role`; confirm the judge op now succeeds at the runtime layer.

## Slice 3 — Judge call-site role

- [ ] In `components/agent-session/src/psi/agent_session/workflow_judge.clj`, add `:operation-role :judge` to the invocation map in `execute-invoke-judge!`.
- [ ] In `components/workflow-runtime/.../statechart_runtime/step_execution.clj` `invoke-step-runtime-result`, leave the step `:operation` invocation with **no** `:operation-role` key (relies on absent/default ≡ `:step`); do not add an explicit `:operation-role :step` — only judge invocations annotate a role.
- [ ] Confirm `invoke-operation-in` (registry) requires no change — invocation passes through unchanged.
- [ ] Add/extend a step-execution or workflow-runtime test covering an invoke step with both an `:operation` and an invoke `:judge`: both deterministic operations complete and routing follows the judge outcome (no `:workflow-stopped` / `:handler-entry-state-mismatch`).

## Slice 4 — Cancellation regression coverage

- [ ] Add a role-aware cancellation test: a stop signal arriving before/within the judge operation yields a clean `:workflow-stopped` terminal (judge handler not invoked).
- [ ] Add/confirm a test that a stop before/within the step operation still stops cleanly (default role).
- [ ] Run existing 225 cancellation suites (`deterministic-operation-runtime`, `workflow-coordination`, and explicitly `components/agent-session/test/.../workflow_statechart_runtime_call_start_cancellation_test.clj` — the suite asserting the default-role `:operation-*-state` keys the namespacing must leave unchanged) and confirm they stay green.

## Slice 5 — Workflow-level verification + close-out

- [ ] Add runtime/definition-level coverage (or extend an existing review-task-design / workflow-definition test) asserting the `clarity-status` invoke step with `pass-feedback-routing` judge routes REPEAT/DONE without the abort.
- [ ] Confirm no malli/invocation schema rejects the additive `:operation-role` key; adjust if a schema exists.
- [ ] Add a CHANGELOG `Fixed` entry (user-visible: unblocks `task-lifecycle` / `review-task-design` design review).
- [ ] Run `clj-kondo --lint` on all changed namespaces; resolve any findings.
- [ ] Run the relevant Scry suites (`deterministic-operation-runtime`, `workflow-coordination`, `agent-session` `workflow_statechart_runtime_call_start_cancellation_test` + workflow-judge, `workflow-runtime` step-execution) and confirm green.
- [ ] Update `implementation.md` with the final key-namespacing decision, the green test summary, and any deviations.

## Review follow-ups — plan/steps ambiguity (ψ 2026-06-13)

- [x] Resolve the role-injection strategy ambiguity: state explicitly whether `role-phase-key` is applied once at the central `transition-workflow-operation-phase!` chokepoint (remapping the supplied phase-opts after reading `(:operation-role invocation)`) or inside each phase helper, and update Plan §Mechanism + Slice 2 to one consistent description (`λone_way`).
  - Decided: **single chokepoint**. Applied once in `transition-workflow-operation-phase!`; six helpers unchanged. Plan §Mechanism, Slice 2, and design.md updated to the one strategy.
- [x] Correct the "five transition helpers" count: there are **six** (`reserve`, `commit-start`, `begin-call`, `commit-call`, `prepare-handler-entry`, `enter-handler`); fix design.md + plan.md (or note it is moot under the chokepoint strategy) so helper coverage is unambiguous.
  - Count corrected to six in design.md + plan.md; noted moot under chokepoint (helpers untouched).
- [x] Name the `agent-session` call-start-cancellation suite (`workflow_statechart_runtime_call_start_cancellation_test`) explicitly in the Slice 4/Slice 5 regression set, since it is the suite that directly asserts the default-role `:operation-*-state` keys that the namespacing must leave unchanged.
  - Named explicitly in plan Slice 4/5 and steps Slice 4/5.
- [x] Decide and record whether the step `:operation` call-site carries an explicit `:operation-role :step` or relies on the absent/default; remove the "only if it improves clarity" deferral so the final artifact state is determinate (Slice 3 + plan call-site threading).
  - Decided: **omit** `:operation-role` at the step call-site (absent ≡ `:step`); only judge invocations annotate a role. Deferral removed in plan + Slice 3.
