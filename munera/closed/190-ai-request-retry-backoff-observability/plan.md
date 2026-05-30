# Plan — AI request retry/backoff observability and reliability

## Approach

Implement provider-boundary retry around prepared provider request execution, not around the whole agent/session loop. The retry unit is one prepared provider request handled by `psi.turn-runtime.core/execute-prepared-request!` or the smallest adjacent seam that owns streaming and non-streaming provider execution.

Key decisions:

- Reuse and extend the existing shared provider-error classification and retry metadata helpers instead of adding a parallel retry taxonomy.
- Preserve the existing zero-based `:retry-attempt` telemetry contract: initial execution is `0`, first retry execution is `1`.
- Treat `:auto-retry-max-retries` as retry executions after the initial attempt, and `:auto-retry-enabled` as the top-level retry scheduling gate.
- Emit and retain provider lifecycle telemetry events in session state as the authoritative completed retry history:
  - `provider_request_started` once per actual provider execution attempt;
  - `provider_request_finished` once per actual provider execution attempt;
  - `provider_retry_scheduled` once per scheduled retry delay;
  - `provider_request_cancelled` for cancellation while a retry delay is pending.
- Add explicit `:provider-request-id` to new lifecycle telemetry, while retaining `:turn-id` for compatibility.
- Keep `provider_request_finished` metrics-compatible with `:status :succeeded` / `:status :failed`, adding `:final?`, `:failure-reason`, `:exhausted?`, and classification metadata rather than introducing new finished statuses.
- Derive completed retry EQL/`psi-tool` projections from retained lifecycle telemetry, not UI state or a second completed-retry ledger.
- Continue publishing active pending-backoff state into the existing session retry projection before sleeping and clearing it on resume, terminal failure, success, or cancellation, so app-runtime/TUI/Emacs retry visibility remains accurate.
- Ensure retry sleep is cancellable and suppresses the scheduled next attempt when aborted.
- Keep streaming failed-attempt partial output attempt-local; never commit failed partial text/thinking/tool-call deltas into canonical transcript assembly when retrying.
- Shape final prepared-request failures with structured retry outcome data, so downstream tests and projections do not parse prose.
- Use injectable/controlled backoff timing in tests to avoid slow sleeps.

Implementation should start with focused characterization tests around the current failing prompt lifecycle behavior, then introduce the minimal retry coordinator seam and expand coverage across telemetry, EQL, active visibility, streaming isolation, and final error shaping.

## Risks

- Streaming and non-streaming execution may have different error/result shapes; the retry boundary must normalize provider headers, HTTP status, exception data, and partial output cleanup without provider-specific ad hoc loops.
- Existing statechart retry code currently combines visibility with whole-agent-loop replay. Reusing it directly risks rerunning tools or restarting the whole prompt lifecycle.
- Existing provider lifecycle telemetry may be dispatched for metrics/logging but not retained in session state; adding retention must avoid duplicating raw HTTP request/reply captures or breaking existing metrics consumers.
- Active retry projection depends on app-runtime/TUI/Emacs status assumptions, especially `sc-phase-in`; changes must preserve the visible `:retrying` surface while not invoking old `:runtime/agent-start-loop` retry resume.
- Attempt identity is easy to regress: `:provider-request-id` groups attempts, `:retry-attempt` orders them, and `:attempt-id` must be unique per concrete execution attempt.
- Cancellation races may occur between pending backoff completion and next attempt start; final telemetry and active retry cleanup must remain coherent.
- Provider `Retry-After` parsing can produce large real delays; tests and runtime policy may need clamping or controlled delay execution to remain bounded.
- Final error metadata must stay consistent across assistant error messages, prepared-request failure results, telemetry, and EQL projections.

## Slice order

1. **Characterize current behavior and map retry seams** — confirm the canonical prompt lifecycle bypasses current statechart retry scheduling, identify streaming/non-streaming provider execution seams, and inventory existing classifier, retry metadata, telemetry, session retry projection, and EQL surfaces.
2. **Introduce provider-boundary retry coordinator** — wrap one prepared provider request execution with bounded retry classification, retry-enabled/max-retries semantics, controlled backoff calculation, fresh per-attempt execution state, and structured terminal outcome data.
3. **Preserve provider lifecycle telemetry and active retry visibility** — emit/retain lifecycle events with explicit provider request identity, per-attempt identity, retry schedule metadata, cancellation event support, and pending-backoff session retry projection updates/clears.
4. **Handle provider headers, cancellation, and streaming isolation** — carry retry-relevant headers into delay metadata, make pending backoff cancellable, avoid synthetic attempt telemetry for suppressed attempts, and discard/supersede failed-attempt streaming partial output before retries.
5. **Expose EQL/`psi-tool` retry introspection** — add session-, turn-, and provider-request-level retry summaries and attempt details projected from retained provider lifecycle telemetry.
6. **Complete focused behavior coverage** — prove retry success-after-failure, retry exhaustion, terminal non-retryable/unknown no-retry, retry-disabled vs zero max retries, active retry UI projection, cancellation, retry header delay source, streaming partial-output isolation, and preserved successful first-attempt behavior.
7. **Review and cleanup** — remove or quarantine obsolete whole-agent-loop retry behavior from the canonical path, simplify duplicated policy logic, update docs/changelog if user-visible surfaces changed, and run targeted plus broader verification.
