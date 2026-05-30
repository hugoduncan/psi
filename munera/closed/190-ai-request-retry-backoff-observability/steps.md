# Steps — AI request retry/backoff observability and reliability

## Slice 1 — Characterize current behavior and map retry seams

- [x] Run the existing focused failing proof for canonical prompt lifecycle retry scheduling and record the current failure in `implementation.md`.
- [x] Inspect `psi.turn-runtime.core/execute-prepared-request!` and identify the smallest common seam for streaming and non-streaming prepared provider request execution.
- [x] Inspect existing provider-error classification and retry metadata helpers, including HTTP status, exception data, stop reason, message, and retry header handling.
- [x] Inspect existing provider lifecycle telemetry emission and metrics consumers for `provider_request_started`, `provider_retry_scheduled`, and `provider_request_finished`.
- [x] Inspect existing session retry state/projection and app-runtime/TUI/Emacs-facing retry status derivation, including `sc-phase-in`.
- [x] Inspect existing provider request/reply telemetry captures and EQL/`psi-tool` resolver conventions for session-, turn-, and provider-request-level data.
- [x] Record the concrete root cause category in `implementation.md`: missing classification, scheduling, execution, stale attempt state, invisible telemetry, statechart bypass, or another cause.

## Slice 2 — Introduce provider-boundary retry coordinator

- [x] Add a focused unit seam for executing one prepared provider request through a controlled provider function that can fail retryably, fail terminally, or succeed by attempt.
- [x] Implement or extract a retry coordinator around prepared provider request execution that retries only the current prepared request and never reruns local tools or the whole agent loop.
- [x] Preserve fresh per-attempt provider execution state for each retry, including consumed streams and accumulation buffers.
- [x] Apply shared provider-error classification so explicit retryable kinds can retry and `:unknown` defaults to terminal non-retryable.
- [x] Implement `:auto-retry-enabled` gate semantics: classify and emit single-attempt lifecycle data, but do not schedule retry when disabled.
- [x] Implement `:auto-retry-max-retries` as retry-execution count after initial attempt, with zero-based `:retry-attempt` coordinates.
- [x] Return structured final failure metadata for non-retryable, retry-disabled, and retry-exhausted outcomes; retry-cancelled remains pending with cancellation support.
- [x] Add focused tests for retry success-after-failure, retry exhaustion, terminal non-retryable no-retry, unknown no-retry, retry-disabled, and zero-max-retries exhaustion.

## Slice 3 — Preserve provider lifecycle telemetry and active retry visibility

- [x] Add explicit `:provider-request-id` to new provider lifecycle telemetry events while retaining `:turn-id`.
- [x] Ensure every actual provider execution attempt emits `provider_request_started` with zero-based `:retry-attempt` and a unique `:attempt-id`.
- [x] Ensure every actual provider execution attempt emits exactly one `provider_request_finished` with metrics-compatible `:status :succeeded` or `:status :failed`.
- [x] Ensure final failed attempts include `:final?`, `:failure-reason`, `:retryable?`, `:error-kind`, optional `:http-status`, and exhaustion metadata where applicable.
- [x] Ensure each scheduled retry emits `provider_retry_scheduled` with failed attempt, next `:retry-attempt`, error classification, delay ms, delay source, resume-at, and retry header metadata.
- [x] Retain provider lifecycle telemetry captures under session state as a distinct stream from raw provider HTTP request/reply captures.
- [x] Keep existing `/ext/provider-telemetry` dispatch behavior for metrics/log consumers unless a focused compatibility test proves a required adjustment.
- [x] Publish active retry state into existing session retry fields before pending backoff sleep begins.
- [x] Clear active retry fields when retry delay completes, the request succeeds, or the request fails terminally/exhausted/disabled; cancellation clearing remains pending with cancellation support.
- [x] Add focused tests proving app-runtime/TUI/Emacs-facing phase/status reports retrying while provider-boundary backoff is pending.
- [x] Add focused tests proving provider-boundary retry resume does not dispatch `:runtime/agent-start-loop` or rerun local tools.

## Slice 4 — Handle provider headers, cancellation, and streaming isolation

- [x] Normalize streaming and non-streaming provider failure values so retry-relevant headers are available to retry metadata calculation, for example under `:provider-error/headers`.
- [x] Honor parseable `Retry-After` / `X-Retry-After` as the scheduled delay with `:delay-source :retry-after`.
- [x] Preserve rate-limit reset/limit/remaining header metadata in active retry projection, telemetry, and EQL-ready lifecycle captures.
- [x] Fall back to configured exponential backoff with `:delay-source :exponential-backoff` when retry headers are absent or invalid.
- [x] Make pending retry backoff cancellable through the active session/turn abort signal or an equivalent coordinator-owned cancellable delay.
- [x] On pending-backoff cancellation, suppress the next provider execution attempt and clear active retry projection fields.
- [x] Emit `provider_request_cancelled` for pending-backoff cancellation with request identity, suppressed next attempt, last failed attempt, `:failure-reason :retry-cancelled`, and last error classification/cause.
- [x] Verify cancellation does not emit synthetic `provider_request_started` or `provider_request_finished` for the suppressed next attempt.
- [x] Ensure failed streaming attempt partial text/thinking/tool-call deltas remain attempt-local and are not committed to canonical transcript/message assembly when retrying.
- [x] Ensure a successful later streaming retry produces final assistant content without mixing or duplicating failed-attempt partial output.
- [x] Add focused tests for pending-backoff cancellation and streaming partial-output isolation.
- [x] Add focused tests for retry header delay source and invalid-header fallback.

## Slice 5 — Expose EQL/psi-tool retry introspection

- [x] Add or extend resolvers for session-level provider retry count and retried provider-request count projected from retained lifecycle telemetry.
- [x] Add or extend resolvers for grouped session provider retry summaries keyed by explicit `:provider-request-id`.
- [x] Add or extend turn/request-level resolvers for retry count, retry attempts, final status, and final error classification.
- [x] Add retry-attempt detail projection fields for attempt number, error kind, error message, HTTP status, delay ms, delay source, resume-at, and final marker where applicable.
- [x] Preserve compatibility fallback from `:turn-id` only for older retained telemetry lacking explicit `:provider-request-id`.
- [x] Add focused EQL/`psi-tool` tests proving session, turn, and provider-request retry questions are answerable after request completion.

## Slice 6 — Complete focused behavior coverage

- [x] Add or update an integration-style prompt lifecycle test proving a representative retryable connection/request failure is retried before the turn fails.
- [x] Add or update an integration-style prompt lifecycle test proving retryable failure followed by provider success returns success to the caller.
- [x] Add or update a test proving repeated retryable failures through the maximum retry count return structured retry-exhausted failure preserving the last cause.
- [x] Add or update a test proving terminal provider/client errors are not retried and expose `:failure-reason :non-retryable`.
- [x] Add or update a tool-result-post test proving provider retry does not rerun a local tool whose result was already recorded.
- [x] Add or update a test proving first-attempt successful provider requests preserve existing behavior and telemetry.
- [x] Ensure tests use controlled provider/request seams and injectable/controlled backoff timing instead of real network calls or slow sleeps.

## Slice 7 — Review and cleanup

- [x] Remove, disable, or clearly quarantine obsolete whole-agent-loop retry behavior from the canonical prompt lifecycle path so it cannot duplicate provider-boundary retries.
- [x] Simplify duplicated retry policy code after provider-boundary retry is authoritative.
- [x] Update `implementation.md` with implementation decisions, discovered runtime seams, and verification commands/results.
- [x] Update user-facing docs and changelog if retry behavior, metrics, EQL/`psi-tool`, TUI, or Emacs-visible surfaces changed.
- [x] Run targeted retry, turn-runtime, provider telemetry, EQL, app-runtime/TUI/Emacs projection, and prompt lifecycle tests.
- [x] Run targeted `clj-kondo` over changed Clojure source and tests.
- [x] Run the broad project verification command appropriate for the final edit set, or record why it was not run.

## Implementation review follow-up

- [x] Wire provider-boundary pending-backoff cancellation to the real session/turn abort or shutdown signal and make production retry delay interruptible/polling rather than a single uninterruptible `Thread/sleep`.
- [x] Add direct EQL/`psi-tool` provider retry resolvers and tests for callers querying by `turn-id` or explicit `provider-request-id`, not only nested session-level retry summaries.
- [x] Add focused EQL coverage for `:psi.provider-retry/final?` and fix provider retry attempt final-marker semantics so final/cancelled lifecycle events can mark the corresponding retry attempt detail when applicable.
- [x] Add focused canonical prompt-lifecycle coverage proving terminal provider failures emit exactly one `provider_request_finished` lifecycle event to `/ext/provider-telemetry`, then remove or gate the legacy `:on-agent-done` compatibility emission so provider-boundary final telemetry is not duplicated for current prepared-request execution.
