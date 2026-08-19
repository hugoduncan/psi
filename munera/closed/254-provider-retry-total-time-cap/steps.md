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

## Review follow-up (implementation review, third turn)

- [x] `execute-prepared-request!` (turn-runtime/core.clj): the failed-attempt
      `provider_request_finished` dispatch (~:560) and the truncated-final-sleep
      finalize dispatch (~:610) build ~18-line identical payloads (session-id,
      turn-id, provider-request-id, attempt-id, provider, model-id,
      retry-attempt, :status :failed, retryable?, error-kind, stop-reason,
      error-message, http-status `cond->`); only `:final?` and the failure
      fields (`:failure-reason`/`:exhausted?`/`:exhausted-reason`) differ. The
      re-review extracted `schedule-and-sleep!`/`cancelled-retry-path!` but left
      this finalize event duplication inline. Extract a shared failed-attempt
      terminal-event builder parameterized by `final?` + failure fields and use
      it from both branches.
      → Done: private `failed-attempt-finished-event` (core.clj) builds the base
      payload from `error-fields` + `final?` + failure-fields; both the
      immediate-final error-branch dispatch and the truncated-final-sleep
      finalize dispatch call it. Outer error-fields destructuring in
      `execute-prepared-request!` trimmed to `retryable?`/`error-message` (the
      builder destructures the rest) — clj-kondo clean.
- [x] `retry-deadline-for` (turn-runtime/retry.clj) stale branch dissocs only
      `:retry-deadline-ms`. In the design's own "session persisted mid-window
      and rehydrated after the deadline has passed (process death / close)"
      scenario, a process death during a retry sleep leaves canonical
      `:retry-attempt` (> 0) and `:retry` (stale `:resume-at`) in session state;
      the stale-deadline clear then opens a "fresh window" that resumes the
      backoff at attempt N (e.g. 4 s instead of 2 s) and keeps the stale
      `:retry` map visible until the first `mark-active-retry!`. Reset
      `:retry-attempt`/`:retry` alongside the stale-deadline dissoc (the same
      cleanup the three terminal clears do) so a stale window's fresh window
      starts at attempt 0 with no stale retry metadata.
      → Done: `retry-deadline-for` stale branch now assoc's `:retry-attempt 0`
      + `:retry nil` alongside the deadline dissoc. Loop bindings in
      `execute-prepared-request!` reordered to read `retry-deadline-for` first
      (its stale branch resets attempt state), so the `retry-attempt-for`
      read-back observes the fresh-window state; matching `recur` order
      (`deadline-ms` then `next-attempt`). Test extended: stale-deadline test
      seeds `:retry-deadline-ms 1000` (past) + `:retry-attempt 3` + stale
      `:retry` map and asserts the fresh window starts at attempt 0 (2 attempts,
      delays [2000 3000], resume-at [7000 10000]) with no stale retry metadata
      after the run.

## Review follow-up (implementation review, fourth turn)

- [x] Test-seam hot-loop hazard under the budget-active default: with
      `:provider-retry-sleep? false` (no real sleeps), no injected `:now-fn`
      (falls back to `Instant/now` wall clock), and no explicit
      `:auto-retry-max-retries` (sentinel-nil default), a persistent retryable
      failure now loops until the REAL wall-clock deadline — 10 minutes with
      the default `:auto-retry-total-timeout-ms 600000` — because the
      budget-active `count-cap` is nil and `now` advances only by wall time.
      Pre-change, the same test-seam misconfiguration terminated after the
      default 3 attempts. All current tests are safe (explicit caps,
      success-terminating stubs, or injected `:now-fn`), so nothing hangs
      today, but the failure mode of the seam regressed silently: a future
      retry test that omits `:now-fn` + an explicit cap hot-loops ~10 min
      instead of failing fast. Guard or document: e.g. at loop entry, treat
      `:provider-retry-sleep? false` + budget-active + nil count-cap + no
      injected `:now-fn` as a test-config error (fail fast), or document that
      the seam requires an advancing `:now-fn` whenever the budget is active.
      → Done: behavioral fail-fast guard. `retry/assert-test-seam-no-hot-loop!`
      (retry.clj) fires at the retry-scheduling point (the `:else` branch of
      `execute-prepared-request!`) when `:provider-retry-sleep? false` +
      budget-active + nil count-cap and the clock advanced < 1000 ms between
      two consecutive scheduled retries — the loop cannot reach its deadline,
      so a persistent failure would spin until the real 10-min wall-clock
      deadline. Static "no injected `:now-fn`" detection is impossible (every
      session ctx supplies the default wall-clock `:now-fn java.time.Instant/now`
      via callback-fns, as a fresh fn instance per ctx), so the guard is
      behavioral: it also catches a constant injected clock. Fires on the 2nd
      scheduled retry (needs two consecutive clock reads) — still fail-fast.
      The loop threads `last-retry-now` through `recur` (new 3rd binding).
      Two new tests: the guard throws `Test-seam misconfiguration` (2
      attempts, no hang); an injected advancing atom-backed clock still drives
      the window to `:deadline` (guard bypassed). Note: `:provider-retry-sleep? false`
      passed via `create-session-context` opts is NOT propagated to the ctx
      (`create-context*` ignores it — verified empirically), so the seam keys
      must be assoc'd onto the ctx directly, as the new tests do. All current
      tests unaffected: explicit caps, budget-disabled, injected advancing
      `:now-fn`, or success-terminating stubs that schedule at most one retry
      (the guard needs a 2nd retry-scheduling to compare clock reads).
      Validation: retry-test 16, response-mode-test 18, model-test 12,
      eql-provider-retry-test 3, core-test 16, prompt-lifecycle retry vars 2
      — all green; clj-kondo clean.
