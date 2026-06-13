# 228 — Steps

## Slice 1 — Characterization test (red)

- [ ] In `components/deterministic-operation-runtime/test/psi/deterministic_operation_runtime/core_test.clj`, add a `deftest` that builds a `state*` atom with one running run + one step-run + one attempt (`:attempt-id "attempt-1"`), mirroring the spike state shape.
- [ ] Drive `runtime/invoke-operation` once for the step `:operation` (default role) against `attempt-1`; assert `:status :ok` and `:operation-handler-entry-state :entered`.
- [ ] Drive `runtime/invoke-operation` a second time for the judge operation with `:operation-role :judge` against the **same** `attempt-1`; assert `:status :ok` (the post-fix behaviour).
- [ ] Assert the judge op landed `:judge-operation-handler-entry-state :entered` and left the step op's `:operation-handler-entry-state :entered` untouched.
- [ ] Run the suite and confirm the test **fails** pre-fix with `:reason :workflow-stopped` / `:stop-reason :handler-entry-state-mismatch` (red baseline captured in implementation.md).

## Slice 2 — Runtime phase-key namespacing

- [ ] In `deterministic-operation-runtime/core`, add a private `role-phase-key` helper mapping `(role, base-key)` → namespaced keyword (`:judge` → `judge-`prefix; `:step`/nil → unchanged).
- [ ] Read the role once per invocation (`(:operation-role invocation)`) and thread it into `transition-workflow-operation-phase!` / the five phase helpers so every `:phase-key`, `:timestamp-key`, `:count-key`, and `:required-phases` `:key` is produced via `role-phase-key`.
- [ ] Confirm `ordinary-entry` needs no change (already parameterized on the supplied keys).
- [ ] Verify the default-role path produces byte-identical `:operation-*` keys (no behaviour change for single-operation steps).
- [ ] `clj-paren-repair` the edited file; re-read to confirm coherence.
- [ ] Re-run the Slice 1 test driving both ops directly with `:operation-role`; confirm the judge op now succeeds at the runtime layer.

## Slice 3 — Judge call-site role

- [ ] In `components/agent-session/src/psi/agent_session/workflow_judge.clj`, add `:operation-role :judge` to the invocation map in `execute-invoke-judge!`.
- [ ] In `components/workflow-runtime/.../statechart_runtime/step_execution.clj` `invoke-step-runtime-result`, leave the step `:operation` on the default role (add an explicit `:operation-role :step` only if it improves clarity).
- [ ] Confirm `invoke-operation-in` (registry) requires no change — invocation passes through unchanged.
- [ ] Add/extend a step-execution or workflow-runtime test covering an invoke step with both an `:operation` and an invoke `:judge`: both deterministic operations complete and routing follows the judge outcome (no `:workflow-stopped` / `:handler-entry-state-mismatch`).

## Slice 4 — Cancellation regression coverage

- [ ] Add a role-aware cancellation test: a stop signal arriving before/within the judge operation yields a clean `:workflow-stopped` terminal (judge handler not invoked).
- [ ] Add/confirm a test that a stop before/within the step operation still stops cleanly (default role).
- [ ] Run existing 225 cancellation suites (`deterministic-operation-runtime`, `workflow-coordination`) and confirm they stay green.

## Slice 5 — Workflow-level verification + close-out

- [ ] Add runtime/definition-level coverage (or extend an existing review-task-design / workflow-definition test) asserting the `clarity-status` invoke step with `pass-feedback-routing` judge routes REPEAT/DONE without the abort.
- [ ] Confirm no malli/invocation schema rejects the additive `:operation-role` key; adjust if a schema exists.
- [ ] Add a CHANGELOG `Fixed` entry (user-visible: unblocks `task-lifecycle` / `review-task-design` design review).
- [ ] Run `clj-kondo --lint` on all changed namespaces; resolve any findings.
- [ ] Run the relevant Scry suites (`deterministic-operation-runtime`, `workflow-coordination`, `agent-session` workflow-judge, `workflow-runtime` step-execution) and confirm green.
- [ ] Update `implementation.md` with the final key-namespacing decision, the green test summary, and any deviations.
