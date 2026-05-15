# Plan

## Approach

Implement in narrow slices that match the discovered canonical owners and keep the telemetry surface minimal.

1. Core event-surface slice
   - Add provider telemetry shaping helpers at the existing canonical owners.
   - Emit `provider_request_started` from `components/turn-runtime/src/psi/turn_runtime/core.clj` `execute-prepared-request!`.
   - Emit `provider_retry_scheduled` from `components/agent-session/src/psi/agent_session/dispatch_handlers/statechart_actions.clj` `:on-retry-triggered`.
   - Emit `provider_request_finished` with split ownership:
     - succeeded attempts from `turn-runtime.core/execute-prepared-request!`
     - failed retrying attempts from `:on-retry-triggered`
     - failed terminal attempts from `:on-agent-done` only when the pending agent-end event proves a terminal provider failure path

2. Canonical classification slice
   - Add one small shared `psi.session-state.model/provider-error-kind` helper beside existing retry heuristics.
   - Reuse it when shaping failed finish events and retry-scheduled events.

3. Metrics aggregation slice
   - Extend `psi.metrics.schema`, `psi.metrics.counters`, and `psi.metrics.extension` for provider/provider-model aggregates.
   - Preserve the existing persistence/load/save path and expose the new `:providers` branch unchanged in `metrics/summary`.

4. Summary rendering slice
   - Extend `/metrics` rendering with a provider table and, if still readable, a per-model table.

## Ordering

1. Lock down design follow-up clarifications in `design.md`.
2. Add focused owner-side tests that pin the telemetry contracts and retry/terminal split.
3. Implement the core events and classifier.
4. Implement metrics schema/counters/extension/persistence/rendering.
5. Run focused verification for owner tests and metrics tests.

## Key decisions

- Canonical request counting unit remains one provider execution attempt.
- `:attempt-id` remains the prepared-request / turn id, but implementation must prove the retry-flow invariant that every retry executes a fresh prepared request with a fresh `:prepared-request/id`.
- Failed terminal finish emission is owned by `:on-agent-done`, but only when the pending statechart data still carries the terminal `:agent-end` provider error event that was not routed through retry.
- Non-provider terminal paths must be excluded from failed terminal provider telemetry emission.

## Risks

- The current `:on-agent-done` handler receives only `session-id`; terminal-failure emission may require reading statechart-carried pending event data or narrowing the event-shaping helper boundary so the terminal provider failure proof is explicit.
- Retry-flow uniqueness for `:attempt-id` is currently an inferred runtime property; implementation must pin it with focused proof rather than relying on prose.
- Metrics rendering can grow noisy if provider and model tables are both unconditional; keep rendering conditional on data presence.

## Focused verification

- `components/turn-runtime/test/psi/turn_runtime/core_test.clj`
  - started event emits once per execution attempt
  - successful finished event emits once with normalized provider/model identity
- `components/agent-session/test/psi/agent_session/statechart_actions_test.clj`
  - retry-scheduled event includes actual `:delay-ms`, `:delay-source`, and retry-attempt being scheduled
  - retrying failed finish emits `:final? false`
  - terminal failed finish emits `:final? true` only for the proved terminal provider failure path
- retry-flow proof
  - focused test(s) pin that a retry executes with a fresh prepared request / turn id, making `:attempt-id == :prepared-request/id` safe for retries
- `components/session-state/test/psi/session_state/model_test.clj`
  - provider-error-kind canonical mappings
- `extensions/metrics/test/psi/metrics/{schema,counters,persistence,extension}_test.clj`
  - schema acceptance
  - provider aggregate updates
  - persistence round-trip
  - summary/persisted update behavior
- rendering test(s) for `/metrics` provider section
