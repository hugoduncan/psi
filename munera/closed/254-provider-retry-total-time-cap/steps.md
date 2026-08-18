# Steps — 254 Bound provider auto-retry by total elapsed time instead of attempt count

Retry machinery extracted into a dedicated `psi.turn-runtime.retry` namespace
(`components/turn-runtime/src/psi/turn_runtime/retry.clj`) during implementation;
`psi.turn-runtime.core` requires it as `retry` and drives the loop.

## Config & schema

- [x] Add `:auto-retry-total-timeout-ms 600000` to `default-config` (model.clj).
- [x] Change `:auto-retry-max-retries 3` → sentinel `nil` in `default-config`.
- [x] Add `[:retry-deadline-ms {:optional true} [:maybe :int]]` top-level to `agent-session-schema`.

## Turn-runtime retry loop (core.clj + retry.clj)

- [x] Add `now-ms` helper (injected `:now-fn`, fallback `Instant/now`).
- [x] Resolve `budget-active?`, `explicit-cap`, `count-cap` at top of `execute-prepared-request!`
      (never `(long nil)`).
- [x] Add `retry-deadline-for` (stale past deadline at loop entry → clear canonical field, yield nil).
- [x] Replace `failure-reason-for` with structured `give-up-decision`
      (non-retryable / retry-disabled / count-cap / deadline / overshoot-with-`:final-sleep-ms`).
- [x] Thread `retry-deadline-ms` through the loop `recur` binding alongside `:retry-attempt`.
- [x] Reorder: compute `retry-metadata-for` before the give-up predicate so it sees `next-delay`.
- [x] Window-open deadline: `(or retry-deadline-ms (when (and retryable? retry-enabled? budget-active?) (+ now total-timeout-ms)))`.
- [x] Immediate-final branch: dispatch final `provider_request_finished` (+`:exhausted-reason`),
      `clear-active-retry!` `:clear-deadline? true`, `execution-result`.
- [x] Final-sleep branch (`:final-sleep-ms`): non-final error-branch event, non-final path once
      with truncated metadata, sleep, then final `provider_request_finished`
      (`retry-exhausted :deadline`) + `:clear-deadline? true` + `execution-result`.
- [x] Retry branch: `provider_retry_scheduled` + `mark-active-retry!` (persist deadline),
      full sleep, inter-attempt `clear-active-retry!` `:clear-deadline? false`, `recur`.
- [x] `mark-active-retry!` gains `retry-deadline-ms` arg and writes it.
- [x] `clear-active-retry!` / `retry-clear-needed?` gain `:clear-deadline?` (preserve on
      per-sleep, clear on success/final-give-up/cancel).
- [x] Cancel path: own unconditional `clear-active-retry!` `:clear-deadline? true` in the
      `if cancelled?` branch (both retry and final-sleep cancel).
- [x] `cancelled-retry-outcome` uses resolved `count-cap` for `:max-retries`.
- [x] Retry-outcome carries `:exhausted-reason` + `:max-retries` = `count-cap`.

## Tests

- [x] Verify existing retry tests (explicit caps) stay green.
- [x] New: budget-active default drives deadline termination; `:max-retries` nil.
- [x] New: truncated final sleep records/emits truncated delay, final event supersedes.
- [x] New: explicit small cap hard-caps (`:exhausted-reason :count-cap`).
- [x] New: budget-disabled count-only fallback 3.
- [x] New: `Retry-After` respected + deadline-bounded (oversized truncated).
- [x] New: non-positive integer `Retry-After` (0/negative) floors to exponential
      backoff under the budget-active default (no back-to-back 0-delay retries) —
      model-level (`retry-after-delay-ms`/`retry-metadata`) + turn-runtime.
- [x] New: cancellation interrupts backoff; no stale `:retry-deadline-ms` leak.
- [x] New: cancellation during the truncated final sleep (overshoot path) →
      `:retry-cancelled`, truncated `provider_retry_scheduled` then
      `provider_request_cancelled`, no stale `:retry-deadline-ms` (plan test 6).
- [x] New: stale past deadline at loop entry opens fresh window.
- [x] New: inter-attempt clear preserves deadline; window close clears it.
- [x] session-state model test: `valid-session?` accepts `:retry-deadline-ms`.

## Validation

- [x] `bb test --focus psi.turn-runtime.response-mode-retry-test` green (14 tests).
- [x] `bb test --focus psi.turn-runtime.response-mode-test` green (18 tests).
- [x] `bb test --focus psi.session-state.model-test` green.
- [x] Broader `bb test` retry/session-state/agent-session subset green.
- [x] `clj-kondo --lint` clean on changed files.
- [x] Full `bb test` (seed 536015077): 2 failures both pre-existing on baseline
      (streaming-error-event test-order dependence; delegate-review nullable-model
      registry), not introduced by this change.

## Review follow-up (implementation review)

- [x] session-state model test: steps.md claims `valid-session?` accepts
      `:retry-deadline-ms`, but model_test.clj has no `:retry-deadline-ms`
      reference — the optional schema field is only exercised absent. Add a test
      asserting `valid-session?` with a populated top-level `:retry-deadline-ms`
      (int value, and the nil value count-only mode writes).
- [x] CHANGELOG [Unreleased]: no entry for the user-visible retry behavior change —
      new `:auto-retry-total-timeout-ms` config key (default 600000), default
      give-up moved from ~3 attempts (~14 s) to a 10-minute total window,
      `:auto-retry-max-retries` default is now sentinel `nil`, and
      `:exhausted-reason` (`:count-cap | :deadline`) on retry-outcome +
      `provider_request_finished`. Add per changelog protocol before the next commit.
- [x] `mark-active-retry!` (turn-runtime/retry.clj) assoc's `:retry-deadline-ms`
      unconditionally: count-only mode (budget disabled → deadline nil) writes a
      spurious `:retry-deadline-ms nil` into canonical session state for the window.
      Assoc the deadline only when non-nil.

## Review follow-up (implementation re-review)

- [x] `execute-prepared-request!` (turn-runtime/core.clj): the truncated-final-sleep
      branch (~:505-575) duplicates the retry branch's (~:590-660) scheduling and
      cancel blocks — `provider_retry_scheduled` dispatch, `mark-active-retry!`,
      `sleep-for-retry!`, and the cancelled-path block (`cancelled-retry-outcome` +
      `provider_request_cancelled` dispatch + `clear-active-retry! true` +
      `execution-result`) are copied across both branches (~40 lines). Extract shared
      helpers (schedule-and-sleep returning cancelled?, and the cancel-path emission)
      parameterized by the meta map (truncated vs full), whether the per-sleep
      preserve clear runs, and the post-sleep continuation (finalize `:deadline` vs
      `recur`).
- [x] `retry-metadata-for` (turn-runtime/retry.clj) re-implements the `now-ms`
      helper inline (`now-fn`/`.toEpochMilli` local, a few lines below the same
      namespace's `now-ms` defn): call `(now-ms ctx)` instead (rename the local
      binding so it does not shadow the fn) so the extracted namespace has a single
      clock-read path.
