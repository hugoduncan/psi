# Steps — 254 Bound provider auto-retry by total elapsed time instead of attempt count

## Config & schema

- [ ] Add `:auto-retry-total-timeout-ms 600000` to `default-config` (model.clj).
- [ ] Change `:auto-retry-max-retries 3` → sentinel `nil` in `default-config`.
- [ ] Add `[:retry-deadline-ms {:optional true} [:maybe :int]]` top-level to `agent-session-schema`.

## Turn-runtime retry loop (core.clj)

- [ ] Add `now-ms` helper (injected `:now-fn`, fallback `Instant/now`).
- [ ] Resolve `budget-active?`, `explicit-cap`, `count-cap` at top of `execute-prepared-request!`
      (never `(long nil)`).
- [ ] Add `retry-deadline-for` (stale past deadline at loop entry → clear canonical field, yield nil).
- [ ] Replace `failure-reason-for` with structured `give-up-decision`
      (non-retryable / retry-disabled / count-cap / deadline / overshoot-with-`:final-sleep-ms`).
- [ ] Thread `retry-deadline-ms` through the loop `recur` binding alongside `:retry-attempt`.
- [ ] Reorder: compute `retry-metadata-for` before the give-up predicate so it sees `next-delay`.
- [ ] Window-open deadline: `(or retry-deadline-ms (when (and retryable? retry-enabled? budget-active?) (+ now total-timeout-ms)))`.
- [ ] Immediate-final branch: dispatch final `provider_request_finished` (+`:exhausted-reason`),
      `clear-active-retry!` `:clear-deadline? true`, `execution-result`.
- [ ] Final-sleep branch (`:final-sleep-ms`): non-final error-branch event, non-final path once
      with truncated metadata, sleep, then final `provider_request_finished`
      (`retry-exhausted :deadline`) + `:clear-deadline? true` + `execution-result`.
- [ ] Retry branch: `provider_retry_scheduled` + `mark-active-retry!` (persist deadline),
      full sleep, inter-attempt `clear-active-retry!` `:clear-deadline? false`, `recur`.
- [ ] `mark-active-retry!` gains `retry-deadline-ms` arg and writes it.
- [ ] `clear-active-retry!` / `retry-clear-needed?` gain `:clear-deadline?` (preserve on
      per-sleep, clear on success/final-give-up/cancel).
- [ ] Cancel path: own unconditional `clear-active-retry!` `:clear-deadline? true` in the
      `if cancelled?` branch (both retry and final-sleep cancel).
- [ ] `cancelled-retry-outcome` uses resolved `count-cap` for `:max-retries`.
- [ ] Retry-outcome carries `:exhausted-reason` + `:max-retries` = `count-cap`.

## Tests

- [ ] Verify existing retry tests (explicit caps) stay green.
- [ ] New: budget-active default drives deadline termination; `:max-retries` nil.
- [ ] New: truncated final sleep records/emits truncated delay, final event supersedes.
- [ ] New: explicit small cap hard-caps (`:exhausted-reason :count-cap`).
- [ ] New: budget-disabled count-only fallback 3.
- [ ] New: `Retry-After` respected + deadline-bounded (oversized truncated).
- [ ] New: cancellation interrupts backoff; no stale `:retry-deadline-ms` leak.
- [ ] New: stale past deadline at loop entry opens fresh window.
- [ ] New: inter-attempt clear preserves deadline; window close clears it.
- [ ] session-state model test: `valid-session?` accepts `:retry-deadline-ms`.

## Validation

- [ ] `bb test --focus psi.turn-runtime.response-mode-retry-test` green.
- [ ] `bb test --focus psi.turn-runtime.response-mode-test` green.
- [ ] `bb test --focus psi.session-state.model-test` green.
- [ ] Broader `bb test` retry/session-state/agent-session subset green.
- [ ] `clj-kondo --lint` clean on changed files.
