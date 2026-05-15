2026-05-14 ambiguity review
- Ambiguity: task lacks `plan.md`, so the implementation approach/order/risk surface is not reviewable against the design.
- Ambiguity: task lacks `design-steps.md`, so there is no canonical place to record and track design follow-up items required by the review workflow.
- Ambiguity: design assigns failed terminal `provider_request_finished` ownership to `:on-agent-done`, but does not state the exact predicate/data path that proves the pending agent-end event is the unretried terminal provider failure rather than another terminal path; the emission guard is therefore underspecified.
- Ambiguity: design says `:attempt-id` can reuse prepared request / turn id because retries create a new prepared request / new turn id in the existing flow, but it does not cite or require proof of that invariant at the retry owner boundary, so the uniqueness contract for retry attempts is asserted but not anchored.

2026-05-14 ambiguity follow-up execution
- Added missing `plan.md` with implementation slices, ordering, decisions, risks, and focused verification owners.
- Kept `design-steps.md` as the canonical design follow-up surface and marked all newly added ambiguity items done.
- Refined `design.md` with an explicit `:on-agent-done` terminal-failure emission guard: evaluate statechart-carried `:pending-agent-event`, require `:agent-end` plus assistant `:stop-reason :error`, exclude non-provider terminal paths, and treat handler-boundary access to that pending event as a task requirement.
- Refined `design.md` to anchor `:attempt-id == prepared-request/turn-id` to an explicit retry-flow invariant plus focused verification expectation proving retries execute with a fresh prepared request / fresh turn id.
- Did not touch `steps.md` execution items per task instruction; this pass only resolved the newly added ambiguity design follow-ups.

2026-05-14 inconsistency review
- Inconsistency: `design.md` says terminal failed `provider_request_finished` emission at `:on-agent-done` should read the statechart-carried `:pending-agent-event` from the handler boundary data, but `plan.md` simultaneously records that the current `:on-agent-done` handler receives only `session-id`; the task files do not reconcile this required handler-boundary input change with the implementation approach or execution checklist.
- Inconsistency: `design.md` makes a fresh retry prepared-request/turn-id proof an explicit invariant and verification expectation for the `:attempt-id` choice, and `plan.md` repeats that proof requirement, but `steps.md` has no explicit step to add or run the retry-flow proof; the execution checklist therefore omits a task-critical acceptance dependency already required by design/plan.

2026-05-14 inconsistency follow-up execution
- Reconciled the `:on-agent-done` handler-boundary contract across task artifacts.
- Refined `design.md` to state explicitly that current code registers `:on-agent-done` with `session-id` only, so this task includes a small handler-boundary change to preserve/pass `data` or `:pending-agent-event` before terminal failed provider telemetry is emitted there.
- Updated `plan.md` ordering and risks to include the handler-boundary input slice explicitly instead of leaving it implicit.
- Added an explicit unchecked retry-flow proof item to `steps.md` covering fresh prepared-request / turn-id creation across `:on-retry-triggered` → `:on-retry-resume` → next execution.
- Marked the two newly added inconsistency follow-up items done in `design-steps.md`.
- Did not execute `steps.md` implementation items per task instruction.

2026-05-14 implementation
- Added shared `psi.session-state.model/provider-error-kind` canonical classifier with focused mapping proofs for auth, rate-limit, timeout, overloaded, invalid-request, provider-unavailable, transport, unknown, and non-error nil.
- Added turn-runtime provider telemetry start/success finish emission in `psi.turn-runtime.core/execute-prepared-request!`, normalizing provider/model ids and using `retry-attempt` from session state.
- Added statechart-owned failed finish and retry-scheduled telemetry in `psi.agent-session.dispatch-handlers.statechart-actions`, with terminal failures emitted from `:on-agent-done` and retrying failures emitted from `:on-retry-triggered`.
- Extended `psi.agent-session.turn.handlers/prompt-finish-base-result` to pass `:pending-agent-event` through the `:on-agent-done` dispatch effect so terminal provider failure telemetry can use statechart-carried data rather than reconstruction alone.
- Extended metrics schema/counters/extension/persistence tests to carry top-level `:providers` plus nested per-model aggregates.
- Added provider summary and provider-model summary sections to `/metrics` output.
- Focused verification green:
  - `clojure -M:test --focus psi.session-state.model-test --focus psi.turn-runtime.response-mode-test --focus psi.agent-session.statechart-actions-test --focus psi.agent-session.turn.handlers-test --focus psi.metrics.schema-test --focus psi.metrics.counters-test --focus psi.metrics.persistence-test --focus psi.metrics.extension-test`
  - result: `23 tests, 132 assertions, 0 failures`
- Remaining follow-up: the explicit retry-flow proof for fresh prepared-request / turn id across retry resume is still open in `steps.md`.

2026-05-14 implementation review
- Actionable: acceptance/design still require a focused retry-flow proof that retries execute with a fresh prepared-request / turn id, but `steps.md` keeps that item open and the referenced focused verification list/results do not show a dedicated retry-flow test discharging it; the `:attempt-id == prepared-request/turn-id` uniqueness claim across retries therefore remains unproved.

2026-05-14 review follow-up execution
- Added owner-local retry-flow proof in `components/agent-session/test/psi/agent_session/statechart_actions_test.clj` covering `:on-retry-triggered` → `:on-retry-resume` → next prepared request creation.
- The proof observes distinct prepared-request ids across the failed and resumed attempts while session id remains stable, discharging the `:attempt-id == prepared-request/turn-id` retry uniqueness requirement in task design/plan.
- Focused verification green: `clojure -M:test --focus psi.agent-session.statechart-actions-test` → `8 tests, 43 assertions, 0 failures`.
