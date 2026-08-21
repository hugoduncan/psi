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

## Review follow-up (implementation review, fifth turn)

- [x] New retry tests real-sleep because the `:provider-retry-sleep?` seam flag
      passed via `create-session-context` opts is INERT (`create-context*` in
      agent-session/context.clj does not propagate it to the ctx — established
      empirically in the 4th-turn follow-up):
      `execute-prepared-request-explicit-count-cap-still-bounds-test` (~2 s real
      sleep) and `execute-prepared-request-count-only-fallback-three-test`
      (~14 s: 2 s + 4 s + 8 s) sleep against the real wall clock every suite run
      (measured vs the no-sleep hot-loop guard test: 13.2 s / 25.0 s vs 11.0 s).
      Assoc `:provider-retry-sleep? false` directly onto the ctx (the pattern
      the two hot-loop tests already use) or inject a no-op
      `:provider-retry-sleep-fn`, so the new tests stop paying real backoff time.
      → Done: both tests now create ctx0 via `create-session-context` (without
      the inert opts flag) and `(assoc ctx0 :provider-retry-sleep? false)`
      directly onto the ctx, matching the hot-loop tests. Retry suite drops from
      ~25.0 s to 13.2 s.
- [x] `assert-test-seam-no-hot-loop!` (retry.clj) fires only when
      `(= false (:provider-retry-sleep? ctx))`, but its docstring claims to
      guard "with :provider-retry-sleep? false (no real sleeps /
      :provider-retry-sleep-fn)". A test that injects a no-op
      `:provider-retry-sleep-fn` WITHOUT the `:provider-retry-sleep? false`
      flag — budget active, nil count-cap, non-advancing clock, persistent
      failure — still spins to the real 10-min wall-clock deadline undetected
      (flag nil → `(= false nil)` false → guard skipped; sleeps are no-ops via
      the sleep-fn). Broaden the condition to
      `(or (= false (:provider-retry-sleep? ctx)) (some? (:provider-retry-sleep-fn ctx)))`
      (with a matching ex-info message) and add a test. Safe for all current
      tests: advancing-clock tests advance >= 2000 ms; the non-advancing
      sleep-fn tests schedule at most one retry (the guard needs two
      consecutive clock reads).
      → Done: guard condition broadened to
      `(or (= false (:provider-retry-sleep? ctx)) (some? (:provider-retry-sleep-fn ctx)))`
      with docstring + ex-info message updated to name both seams and
      `:provider-retry-sleep-fn` presence added to the ex-data. New test
      `execute-prepared-request-sleep-fn-seam-guard-test` injects a no-op
      sleep-fn without the flag (budget active, nil count-cap, default
      wall-clock `:now-fn`, persistent failure) and asserts the guard throws
      at the 2nd scheduled retry. All current sleep-fn tests unaffected
      (verified): advancing-clock tests advance >= 2000 ms; non-advancing
      sleep-fn tests (`deadline-preserved-inter-attempt`, `cancel-*`,
      `clears-active-retry-state`) schedule at most one retry or carry an
      explicit count cap.

## Review follow-up (implementation review, sixth turn)

- [x] Complete the 5th-turn inert-flag real-sleep fix across the remaining
      retry tests. The seam discovery — `:provider-retry-sleep? false` passed
      via `create-session-context` opts is NOT propagated to the ctx
      (`create-context*` destructures known keys only) — was applied only to
      the two NEW tests. Seven OTHER retry tests still pass the flag via opts
      and real-sleep on every suite run (measured with test-var timing):
      - `response_mode_test.clj`:
        `execute-prepared-request-retry-exhaustion-preserves-last-cause-test`
        (~6 s: 2 s + 4 s),
        `execute-prepared-request-retry-after-header-drives-delay-test`
        (~5 s `Retry-After`),
        `execute-prepared-request-streaming-error-event-provider-headers-drive-retry-test`
        (~4 s `Retry-After`),
        `execute-prepared-request-streaming-exception-preserves-retry-headers-test`
        (~2 s `Retry-After`);
      - `response_mode_retry_test.clj`:
        `execute-prepared-request-streaming-retry-discards-failed-partial-output-test`
        (~2 s);
      - `prompt_lifecycle_test.clj`:
        `prompt-execution-result-retryable-error-enters-retrying-and-schedules-retry-test`
        (~2 s),
        `prompt-provider-retry-after-tool-result-does-not-rerun-tool-test`
        (~2 s).
      Fix: drop the inert opts flag and `(assoc ctx0 :provider-retry-sleep? false)`
      directly onto the ctx (or inject a no-op `:provider-retry-sleep-fn`), matching
      the 5th-turn pattern. Do NOT touch
      `execute-prepared-request-production-backoff-observes-active-turn-abort-test`
      (response_mode_test.clj) — it intentionally real-sleeps through the
      interruptible poll seam to test active-turn abort and carries no flag.
      (~23 s of needless real sleep per full-suite run.)
      → Done: all seven tests now drop the inert opts flag and assoc
      `:provider-retry-sleep? false` directly onto the ctx (the
      `retry-after-header-drives-delay` / `streaming-exception-preserves-retry-headers`
      / `streaming-error-event-provider-headers-drive-retry` tests add the key to
      their existing `now-fn` assoc). All carry an explicit
      `:auto-retry-max-retries` (or terminate on success after one retry), so the
      hot-loop guard never fires. `production-backoff-observes-active-turn-abort`
      untouched. Suite timing: response-mode-test drops from ~25 s to ~10.9 s,
      retry suite ~13.2 s → ~11.0 s; the two prompt-lifecycle retry vars no
      longer pay their ~2 s sleeps.
- [x] Budget-disabled (count-only) mode can still be deadline-bounded by a
      leftover FUTURE canonical `:retry-deadline-ms` from a prior budget-active
      window. `retry-deadline-for` (retry.clj:27) returns a future deadline
      regardless of `budget-active?`, and the loop's
      `deadline-ms (or retry-deadline-ms (when (and retryable? retry-enabled?
      budget-active?) (+ now budget-timeout-ms)))` (core.clj:550) binds it in
      count-only mode → `:exhausted-reason :deadline` (while `:max-retries`
      reports the count-only fallback 3) instead of count-cap-only give-up,
      contradicting design Approach 1 disable semantics ("no deadline is
      computed, the give-up predicate evaluates only the count cap"). Reachable
      when a session persisted mid-window (deadline still future) is rehydrated
      with `:auto-retry-total-timeout-ms` nil/absent/`<= 0` (config change /
      process restart); the existing stale-past entry check handles only
      expired deadlines. Fix: gate the loop-entry seed on `budget-active?` —
      e.g. `retry-deadline-for` takes `budget-active?` and clears the canonical
      deadline + yields nil when disabled, or the `deadline-ms` resolution drops
      the seed when `budget-active?` is false — and add a test: session seeded
      with a future persisted `:retry-deadline-ms`, budget disabled, no
      explicit cap → gives up only on the count-only fallback 3, never
      `:exhausted-reason :deadline`.
      → Done: `retry-deadline-for` now takes `budget-active?`; its first branch
      (budget disabled + a canonical deadline present) dissocs the deadline and
      yields nil, so count-only mode never binds a leftover future deadline —
      the `deadline-ms` resolution then evaluates only the count-cap path.
      The stale-past branch is unchanged (budget-active only). Loop entry in
      `execute-prepared-request!` passes `budget-active?` (computed above the
      loop). New `execute-prepared-request-budget-disabled-ignores-leftover-future-deadline-test`
      (retry_test.clj): seeds a future-but-close `:retry-deadline-ms 1000`
      under `:auto-retry-total-timeout-ms 0` + injected clock at 0 (without
      the gate, the 2000 ms first backoff overshoots the 1000 ms window →
      `:deadline` give-up after 1 attempt) and asserts 4 attempts, `:count-cap`,
      `:max-retries 3`, and the leftover deadline cleared. retry suite 17 → 18.

## Review follow-up (implementation review, seventh turn)

- [x] `create-context*` (agent-session/context.clj) silently drops the retry
      test-seam keys passed via `create-session-context` opts: the
      destructured opts list does not include `:provider-retry-sleep?`,
      `:provider-retry-sleep-fn`, `:now-fn`, or `:provider-retry-cancelled?`,
      so a test passing them the natural way (opts) real-sleeps / misses the
      cancellation seam with no warning. The 4th/5th/6th-turn follow-ups
      worked around this (assoc the keys directly onto the ctx "established
      empirically"), but the root cause is unfixed — every future retry test
      author hits the same silent trap. Propagate the seam keys through
      `create-context*` (they are inert outside the retry seam, so
      production behavior is unchanged) or fail fast when they are passed via
      opts and ignored; then the direct-assoc workarounds in the retry /
      response-mode / prompt-lifecycle tests can revert to the natural opts
      API.
      → Done: `create-context*` now destructures and propagates
      `:provider-retry-sleep?` / `:provider-retry-sleep-fn` /
      `:provider-retry-cancelled?` / `:now-fn` onto the ctx (assoc'd after
      the callback-fns merge, so `:now-fn` overrides the default wall-clock).
      All direct-assoc workarounds reverted to the natural opts API in
      response_mode_retry_test.clj (11 sites), response_mode_test.clj (6),
      prompt_lifecycle_test.clj (2); the 3 sites whose seam fn must close
      over the ctx to read session state keep a minimal assoc with an updated
      comment (opts are evaluated before the ctx exists). The 4 pre-existing
      tests that already passed the flag via opts (previously inert) now
      actually disable sleeps. clj-kondo clean.

- [x] `assert-test-seam-no-hot-loop!` (retry.clj) uses the hardcoded
      `min-retry-clock-advance-ms` 1000 ms threshold: an injected clock that
      advances by less than 1000 ms between scheduled retries trips the guard
      even though the loop terminates at the injected deadline — a FALSE
      positive. Sub-second base delays are already legitimate in this suite
      (`execute-prepared-request-retry-after-header-drives-delay-test` uses
      `:auto-retry-base-delay-ms 10`; it only avoids the guard because it
      carries an explicit cap), so a budget-active, cap-free test with a
      small base delay (e.g. 10/100/500 ms) and the standard advancing-clock
      pattern (`(fn [delay-ms] (swap! clock + (long delay-ms)))`) throws
      `Test-seam misconfiguration` at the 2nd retry despite a correctly
      advancing clock. The discriminator only needs to separate a constant
      clock (advance exactly 0) / wall-clock jitter from delay-driven
      advances: derive the threshold from the configured delays (e.g.
      `(min :auto-retry-base-delay-ms :auto-retry-max-delay-ms)` or a small
      jitter bound), make it configurable, or document a minimum-delay
      constraint.
      → Done: the hardcoded 1000 ms constant is replaced by
      `retry-min-clock-advance-ms` (retry.clj), derived from the configured
      delays — `(max 1 (min :auto-retry-base-delay-ms :auto-retry-max-delay-ms))`
      — and overridable per-test via `:retry-min-clock-advance-ms` on the
      ctx. Docstring + ex-info updated (message now reports the derived
      threshold in ex-data). New
      `execute-prepared-request-small-base-delay-advancing-clock-not-guarded-test`
      (retry_test.clj): budget-active, cap-free, base delay 10, standard
      delay-driven advancing clock → no guard throw, window runs to
      `:deadline` (> 2 attempts; the old 1000 ms threshold would have thrown
      at the 2nd retry). retry suite 18 → 19.
- [x] The new top-level `:retry-deadline-ms` session field is absent from
      every surface that already exposes its siblings `:retry-attempt` /
      `:retry`: the EQL session resolver `agent-session-retry-compact`
      (agent-session/resolvers/session.clj ~270), the session introspection
      map (agent-session/introspection.clj ~45), and the RPC
      `session/updated` projection (rpc/events.clj ~54, ~141). A session
      retrying inside a 10-minute window is undebuggable via EQL/RPC: the
      window deadline is invisible (only the per-attempt `:resume-at` shows),
      so an observer cannot tell when the window closes. Add
      `:retry-deadline-ms` to the three surfaces, or explicitly document it
      as intentionally internal.
      → Done: `:retry-deadline-ms` added to all three surfaces —
      `agent-session-retry-compact` resolver output/values
      (`:psi.agent-session/retry-deadline-ms`), `diagnostics-in`
      (`:retry-deadline-ms`), and the `session/updated` projection
      (session_summary.clj summary + rpc/events.clj select-keys +
      required-event-payload-keys). Tests: `retry-compact-eql-introspection-test`
      (queries the resolver with a seeded window, nil without),
      `diagnostics-test` contains? check (config_compaction_test.clj), and
      `session-updated-payload-includes-retry-contract-test` seeds/asserts
      the field (rpc_events_test.clj, key-set + value), fixture
      `session-scoped-event-data` updated for the new required key.

## Review follow-up (implementation review, eighth turn)

- [x] `create-context*` (agent-session/context.clj) still silently drops
      `:retry-min-clock-advance-ms` passed via `create-session-context` opts:
      the 7th-turn seam-key propagation covers `:provider-retry-sleep?` /
      `:provider-retry-sleep-fn` / `:provider-retry-cancelled?` / `:now-fn`
      but not the hot-loop guard threshold override that
      `retry-min-clock-advance-ms` (turn-runtime/retry.clj) documents as
      "Overridable per-test via :retry-min-clock-advance-ms on the ctx" — the
      same silent-drop trap for a future cap-free budget-active test whose
      smallest delay is a provider `Retry-After` below the configured base
      (the exact case the docstring names). No current test passes it, so
      nothing misbehaves today. Propagate the key through `create-context*`
      alongside the other four seam keys, and add a test that passes it via
      opts and asserts the guard threshold uses it.
      → Done: `create-context*` now destructures and propagates
      `:retry-min-clock-advance-ms` alongside the other four seam keys
      (cond-> guarded by `contains? opts`, after the callback-fns merge);
      comment updated. New
      `execute-prepared-request-retry-min-clock-advance-opts-propagation-test`
      (response_mode_retry_test.clj) passes the key via
      `create-session-context` opts (budget-active default, cap-free,
      `:provider-retry-sleep? false`) and asserts the guard fires at the 2nd
      retry with `:min-retry-clock-advance-ms 12345` in ex-data — the default
      derivation would be 2000, so the assertion fails if `create-context*`
      still drops the key.
- [x] `retry-deadline-for` (turn-runtime/retry.clj) budget-disabled branch
      dissoc's only `:retry-deadline-ms`, leaving the stale `:retry-attempt` /
      `:retry` residue from a prior budget-active window — unlike the stale-past
      branch, which resets `:retry-attempt 0` + `:retry nil` alongside the
      deadline dissoc (3rd-turn fix). A session persisted mid-window (deadline
      still future) and rehydrated with `:auto-retry-total-timeout-ms`
      nil/absent/`<= 0` gives up at the FIRST failure with 0 retries when the
      stale attempt >= the count-only fallback 3 (verified empirically: seeded
      future deadline 5000 + attempt 3 + stale `:retry` map under timeout 0 →
      1 attempt, `:exhausted-reason :count-cap`, `:max-retries 3`, vs 4
      attempts for a clean count-only session), or resumes the backoff
      mid-sequence with a stale `:retry` map visible when the stale attempt
      < 3. The 6th-turn leftover-future-deadline test seeded only the
      deadline, so the residue was never exercised. Reset
      `:retry-attempt`/`:retry` in the budget-disabled branch (mirror the
      stale-past branch) and extend the test to seed attempt + retry map.
      → Done: `retry-deadline-for` budget-disabled branch now assoc's
      `:retry-attempt 0` + `:retry nil` alongside the deadline dissoc
      (mirrors the stale-past branch). The
      `execute-prepared-request-budget-disabled-ignores-leftover-future-deadline-test`
      seed extended with `:retry-attempt 3` + stale `:retry` map and now
      asserts the fresh window runs 4 attempts (`:count-cap`, `:max-retries 3`)
      with `:retry-attempt` zeroed and `:retry` nil after the run — the residue
      is not honored and not visible.

## Review follow-up (implementation review, ninth turn)

- [x] `bb commit-check:file-lengths` fails: the 7th-turn follow-up grew
      `components/agent-session/test/psi/agent_session/eql_introspection_test.clj`
      to 829 lines (800 limit) by adding
      `retry-compact-eql-introspection-test` (+33 lines) to it. The provider /
      request-shape / retry-compact tests (233 lines:
      `provider-capture-eql-introspection-test`,
      `current-request-shape-test`, `retry-compact-eql-introspection-test`)
      are split into a new logically-consistent file
      `components/agent-session/test/psi/agent_session/provider_introspection_test.clj`
      (ns `psi.agent-session.provider-introspection-test`), each with its own
      private copies of the shared helpers (no forwarding vars); the original
      file drops to 586 lines, loses the now-unused
      `make-user-msg`/`make-assistant-msg` helpers, and its ns docstring no
      longer claims provider captures. Both files lint clean (clj-kondo) and
      the focused suite (8 tests, 162 assertions across both namespaces) is
      green; `bb commit-check:file-lengths` passes.

## Review follow-up (implementation review, tenth turn)

- [x] `retry-after-delay-ms` (session-state/model.clj) integer branch parses
      `Retry-After` with `(Long/parseLong raw)` WITHOUT the try/catch the
      RFC-date `:else` branch has: a numeric string outside Long range (e.g.
      `Retry-After: 99999999999999999999`) throws an uncaught
      `NumberFormatException` instead of falling back to the exponential
      backoff (verified empirically: `retry-after-delay-ms` and
      `retry-metadata` both throw). The single-predicate reorder
      (`retry-metadata-for` runs unconditionally BEFORE `give-up-decision`)
      means this now executes on EVERY failed attempt — including
      non-retryable errors that previously never computed retry metadata —
      so one malformed oversized header crashes the whole turn instead of a
      graceful classification/fallback. The invalid-Retry-After fallback
      test covers non-numeric garbage only (RFC-date catch branch), not an
      oversized integer. Fix: wrap the integer branch in try/catch → nil
      (exponential fallback), mirroring the `:else` branch; add a model-level
      test (`retry-after-delay-ms`/`retry-metadata` with a 20-digit value →
      nil/exponential) and a turn-runtime test (budget-active default,
      cap-free, persistent retryable failure + oversized `Retry-After` →
      floors to backoff, window runs to `:deadline`, no throw).
      → Done: integer branch now parses via the existing `parse-long-safe`
      helper (`(some-> raw parse-long-safe (* 1000))`), so an out-of-Long-range
      integer yields nil → `(when (and delay-ms (pos? delay-ms)) ...)` floors
      to the exponential backoff; comment updated. New
      `retry-after-oversized-integer-floors-to-exponential-test` (model_test.clj:
      `retry-after-delay-ms` 20-digit → nil; `retry-metadata` → exponential
      2000, `:delay-source :exponential-backoff`) and
      `execute-prepared-request-oversized-retry-after-floors-to-backoff-test`
      (retry_test.clj: budget-active default — timeout/cap keys omitted so
      default-config 600000/nil apply — persistent retryable failure with a
      20-digit `Retry-After` → no throw, first scheduled delay exponential
      2000, window runs to `:deadline`, `:max-retries nil`).
- [x] `retry-deadline-for` (turn-runtime/retry.clj) has two textually identical
      cond-branch bodies (budget-disabled leftover deadline and stale-past
      deadline): both assoc `:retry-attempt 0` + `:retry nil`, dissoc
      `:retry-deadline-ms`, and yield nil. Merge into one branch
      (`(and (some? deadline) (or (not budget-active?) (< deadline (now-ms ctx))))`)
      with the two intents documented in the existing docstring; behavior
      unchanged.
      → Done: merged into a single branch with the combined predicate
      `(and (some? deadline) (or (not budget-active?) (< deadline (now-ms ctx))))`;
      docstring gains a sentence noting both intents share one branch (same
      cleanup, differing only in the predicate). Behavior unchanged; all
      deadline tests green.

## Review follow-up (implementation review, thirteenth turn)

- [x] Truncated-final attempt-number mismatch breaks the EQL `provider-retries`
      final marker. The truncated final sleep's `provider_retry_scheduled`
      reports `:retry-attempt N+1` (`next-attempt`, via `schedule-and-sleep!`),
      but the authoritative terminal `provider_request_finished` it supersedes
      reports `:retry-attempt N` (the pre-sleep failed attempt, via
      `failed-attempt-finished-event` with the loop's `retry-attempt` binding).
      The event-based EQL resolver `provider-retry-summary->eql`
      (agent-session/resolvers/provider_retries.clj) marks a schedule final by
      `(= (:retry-attempt schedule) (:retry-attempt final))`, so for a
      truncated-final window the LAST schedule — the truncated one whose
      `:resume-at` equals the deadline — is displayed
      `:psi.provider-retry/final? false`, while the second-to-last (full
      backoff) schedule is `final? true`. Verified empirically with the real
      flow (budget 5000 / base 2000, injected clock + sleep-fn): events
      finished(0,false) → scheduled(1, delay 2000 / resume 2000) →
      finished(1,false) → scheduled(2, delay 3000 / resume 5000) →
      finished(1,true, `:retry-exhausted :deadline`); EQL retry-attempts =
      attempt 1 (2000/2000) `final? true`, attempt 2 (3000/5000)
      `final? false`. The cancel path is consistent
      (`provider_request_cancelled` reports N+1, matching its truncated
      schedule), so only the deadline-finalize path is off. Fix: align the
      truncated-final terminal event's `:retry-attempt` with the truncated
      schedule it supersedes (report `next-attempt` on the finalize dispatch,
      keeping the retry-outcome `:retry-attempt`/`:attempt-count` per design),
      or change the resolver's final-marker rule to mark the last schedule of a
      provider-request-id as final; add a regression test
      (eql_provider_retry_test.clj — either the real truncated-final flow from
      `execute-prepared-request-total-time-window-governs-termination-test`
      queried through `:psi.agent-session/provider-retries`, or a hand-built
      finished(1,false) → scheduled(2) → finished(1,true) sequence) asserting
      the truncated schedule carries `final? true`.
      → Done: chose the resolver-side fix — `provider-retry-summary->eql`
      (agent-session/resolvers/provider_retries.clj) now marks a schedule
      final by `(= (:retry-attempt %) (some-> (last schedules) :retry-attempt))`
      (the LAST schedule of the provider request) instead of matching the
      terminal event's `:retry-attempt`. The terminal `provider_request_finished`
      keeps reporting the pre-sleep failed attempt N (the actually-executed
      attempt, consistent with its `:attempt-id`); the marker is a projection
      concern and "last schedule" is correct for every terminal path (success /
      count-cap / deadline / cancel — the cancel event reports the scheduled
      attempt N+1). New
      `provider-retry-truncated-final-schedule-marker-test`
      (eql_provider_retry_test.clj) hand-builds the verified truncated-final
      sequence — finished(0,false) → scheduled(1, 2000/2000) →
      finished(1,false) → scheduled(2, 3000/5000) → finished(1,true,
      `:retry-exhausted :deadline`) — and asserts the truncated schedule
      (attempt 2) carries `final? true` while the full backoff (attempt 1)
      carries `final? false`. Verified the test fails against the pre-fix
      resolver (14 pass / 1 fail) and passes with it. eql-provider-retry-test
      3 → 4; the three pre-existing marker tests (success / count-cap /
      cancel) unchanged. clj-kondo clean; `bb commit-check:file-lengths`
      passes; CHANGELOG [Unreleased] Fixed entry added.

## Review follow-up (implementation review, fourteenth turn)

- [x] `:exhausted-reason` (`:count-cap | :deadline`) is dropped by the EQL
      `provider-retries` introspection surface. `provider-retry-summary->eql`
      (agent-session/resolvers/provider_retries.clj) projects the terminal
      event's `:failure-reason` into `:psi.provider-request/final-status` and
      its `:error-kind`, but not the sibling `:exhausted-reason` — and
      `provider-retries` is the ONLY consumer of the retained provider events
      (no raw-event EQL resolver; RPC does not subscribe to provider retry
      events; `grep exhausted-reason components/*/src` matches only
      turn-runtime core.clj/retry.clj). The design's stated purpose of the
      field — "Telemetry and UI can therefore tell whether the window was
      bounded by time or by an explicit count cap" — is therefore unreachable
      through any EQL/UI read path today: an observer querying
      `:psi.agent-session/provider-retries` sees `final-status :retry-exhausted`
      but cannot tell which boundary fired, exactly the debuggability gap the
      7th-turn `:retry-deadline-ms`-on-all-surfaces step closed for the window
      deadline. Fix: add `:psi.provider-request/exhausted-reason
      (:exhausted-reason final)` to `provider-retry-summary->eql` and the three
      resolver output key lists (by-request-id / by-turn-id / provider-retries),
      and assert it — `provider-retry-truncated-final-schedule-marker-test`
      (eql_provider_retry_test.clj:262) already seeds
      `:exhausted-reason :deadline` on the final event and would fail without
      the projection; add a `:count-cap` case alongside.
      → Done: `:psi.provider-request/exhausted-reason (:exhausted-reason final)`
      added to `provider-retry-summary->eql` and all three resolver output key
      lists (by-request-id / by-turn-id / provider-retries). The
      `provider-retry-truncated-final-schedule-marker-test` now asserts
      `:exhausted-reason :deadline`, and a new
      `provider-retry-count-cap-exhausted-reason-test` seeds a count-cap final
      and asserts `:exhausted-reason :count-cap` (mirrors the deadline case;
      the existing direct/`provider-retries` queries gained the key). eql
      provider-retry-test 4 → 5; clj-kondo clean;
      `bb commit-check:file-lengths` passes.
- [x] CHANGELOG [Unreleased] is missing a Fixed entry for the oversized-integer
      `Retry-After` crash fix (11th-turn follow-up). A provider-sent numeric
      `Retry-After` outside Long range (e.g. 20 digits) previously threw an
      uncaught `NumberFormatException` from `retry-after-delay-ms`
      (session-state/model.clj) — and because `retry-metadata-for` now runs on
      EVERY failed attempt before `give-up-decision`, one malformed oversized
      header crashed the whole turn instead of flooring to the exponential
      backoff; the fix parses via `parse-long-safe` and yields nil →
      exponential fallback. bug_fix is user-visible per the changelog protocol
      (the 13th-turn EQL-marker fix got a Fixed entry for the same reason), and
      the 12th-turn docs review verified only the behaviour-change entry (line
      49), not this crash fix. Add a `### Fixed` entry (e.g. "A provider
      `Retry-After` outside Long range now floors to the exponential backoff
      instead of crashing the turn with an uncaught `NumberFormatException`")
      before the next commit.
      → Done: CHANGELOG [Unreleased] `### Fixed` entry added (top of the Fixed
      section, above the 13th-turn EQL-marker entry): names the uncaught
      `NumberFormatException` crash path (integer branch lacked the RFC-date
      try/catch; retry metadata runs on every failed attempt pre-decision) and
      the `parse-long-safe` → nil → exponential-floor fix.

## Review follow-up (implementation review, fifteenth turn)

- [x] `retry-after-delay-ms` / `retry-metadata` (session-state/model.clj) +
      `give-up-decision` (turn-runtime/retry.clj): the 10th-turn oversized
      `Retry-After` fix covers only integers OUTSIDE Long range (20 digits →
      `parse-long-safe` nil → exponential fallback). A PARSEABLE near-Long/MAX
      integer (16 digits, seconds ≥ 9223372036854775) still crashes the whole
      turn with `ArithmeticException: long overflow` — verified against the
      real functions:
      - `(retry-after-delay-ms "9223372036854776" now)` THROWS: the integer
        branch's `(some-> raw parse-long-safe (* 1000))` applies `* 1000`
        OUTSIDE `parse-long-safe`'s try/catch, so a seconds value in
        [9223372036854776, 9223372036854775807] overflows the ms conversion
        with no fallback.
      - `(retry-metadata {:retry-after "9223372036854775"} 0 2000 now)` THROWS:
        `:resume-at (+ (long now-ms) delay-ms)` overflows for the largest
        seconds value whose ×1000 fits (delay 9223372036854775000, now ~1.7e12).
      - `give-up-decision`'s overshoot comparison `(> (+ now next-delay-ms)
        deadline-ms)` (retry.clj, new code) has the same overflow at the same
        delay and would throw once the two sites above are guarded.
      Because `retry-metadata-for` now runs on EVERY failed attempt before
      `give-up-decision` (the single-predicate reorder), one malformed
      near-Long/MAX header aborts the whole turn — the exact crash class the
      10th-turn step intended to close, one digit shorter than its 20-digit
      test input. The existing tests
      (`retry-after-oversized-integer-floors-to-exponential-test` /
      `execute-prepared-request-oversized-retry-after-floors-to-backoff-test`)
      cover only the out-of-range 20-digit value. Fix: cap the integer
      branch's accepted seconds (e.g. `(when (< n (quot Long/MAX_VALUE 1000))
      (* 1000 n))` → nil → exponential floor, or try/catch the `* 1000`
      mirroring the RFC-date branch), and make the overshoot comparison
      subtraction-based (`(> next-delay-ms (- deadline-ms now))` — cannot
      overflow). Add model-level tests (`retry-after-delay-ms` /
      `retry-metadata` with `Retry-After: 9223372036854775` and
      `9223372036854776` → nil/exponential, no throw) and a turn-runtime test
      (budget-active default, cap-free, persistent retryable failure +
      16-digit `Retry-After` → floors to backoff, window runs to `:deadline`,
      no throw).
      → Done: `retry-after-delay-ms` integer branch now caps the accepted
      seconds — strictly below `(quot Long/MAX_VALUE 1000)` (the `* 1000`
      overflow boundary) AND below the value whose delay-ms would overflow
      `retry-metadata`'s `:resume-at` `(+ now-ms delay-ms)` — so both
      `Retry-After: 9223372036854775` and `9223372036854776` yield nil →
      exponential floor, no throw (a fitting large value like
      `9223372036854774` at now-ms 0 is still honored with a non-overflowing
      `:resume-at`). `give-up-decision`'s overshoot comparison is now
      subtraction-based (`(> next-delay-ms (- deadline-ms now))` — cannot
      overflow). New model tests
      `retry-after-near-long-integer-floors-to-exponential-test` (both 16-digit
      values → nil/exponential + fitting-boundary `:resume-at` no-overflow
      case) and turn-runtime
      `execute-prepared-request-near-long-retry-after-floors-to-backoff-test`
      (budget-active default, cap-free, persistent retryable failure +
      16-digit `Retry-After` → floors to backoff, window runs to `:deadline`,
      no throw). CHANGELOG [Unreleased] Fixed entry extended to name the
      near-Long `ArithmeticException` class. Validation: model-test 14,
      retry-test 22, response-mode-test 18, eql-provider-retry-test 5,
      core-test 16, prompt-lifecycle-test 23 — all green; clj-kondo clean;
      commit-check:file-lengths / changelog:check /
      commit-check:dispatch-architecture pass.

## Review follow-up (implementation review, sixteenth turn)

- [x] Zero `:auto-retry-base-delay-ms` (or `:auto-retry-max-delay-ms`) with the
      budget active hot-loops in PRODUCTION. `exponential-backoff-ms` yields 0
      for a 0 base (or max), `sleep-for-retry!` skips non-positive delays, and
      `assert-test-seam-no-hot-loop!` fires only under the sleep-disabled test
      seams (`:provider-retry-sleep? false` / `:provider-retry-sleep-fn` —
      never set on a production ctx), so a persistent retryable failure retries
      back-to-back with zero delay until the REAL wall-clock deadline (10 min
      default) — pre-change the default count cap 3 bounded the same
      misconfiguration to 4 instant attempts. This also breaks design Approach
      5's guarantee that "the budget-active default never retries back-to-back
      with an immediate 0-delay until the deadline": the non-positive
      `Retry-After` floor (`(when (pos? delay-ms) ...)` → exponential) lands on
      0 when the exponential itself is 0. Fix: floor the per-attempt delay to a
      positive minimum (clamp base/max to >= 1, or `(max 1 delay-ms)` at the
      point the slept delay is chosen) so the budget-active default always
      sleeps between attempts — and/or extend the hot-loop guard to the
      production path (zero delay + active budget + nil cap is always a
      misconfiguration). Add a turn-runtime test: budget active, cap-free,
      base 0 → first scheduled `:delay-ms` positive and the loop sleeps, never
      zero-delay back-to-back.
      → Done: `retry-metadata` now floors the per-attempt delay to a positive
      minimum — `(max 1 (long (or retry-after-ms exponential-delay-ms)))` — so
      a 0 base/max exponential yields 1 ms (not 0), `sleep-for-retry!` always
      sleeps between attempts, and the budget-active default can never retry
      back-to-back with an immediate 0-delay until the deadline (design
      Approach 5 guarantee restored). New model test
      `retry-metadata-floors-zero-exponential-to-positive-delay-test` (zero
      exponential → `:delay-ms 1`, positive `Retry-After` still wins) and
      turn-runtime
      `execute-prepared-request-zero-base-delay-floored-to-positive-sleep-test`
      (doseq over base-0 and max-0 configs, budget active 3 ms, cap-free:
      every scheduled `:delay-ms` is the positive floor 1, the loop sleeps
      [1 1 1], give-up `:deadline` at 4 attempts — no hot loop). CHANGELOG
      [Unreleased] Fixed entry added. Validation: model-test 15,
      retry-test 23, response-mode-test 18, eql-provider-retry-test 5,
      provider-introspection-test 3, session-test 9, retry-headers-test 3,
      rpc-prompt/rpc-events green — all green; clj-kondo clean.
- [x] `mementum/knowledge/provider-retry-total-time-window.md` is stale: it was
      written at the first closure (2026-08-18), BEFORE the 4th–15th-turn
      follow-ups, and omits post-closure facts a future AI session needs — the
      `assert-test-seam-no-hot-loop!` behavioral guard + derived/overridable
      `:retry-min-clock-advance-ms` threshold, the `create-context*` seam-key
      propagation (retry seam keys now flow through `create-session-context`
      opts, replacing the direct-assoc workaround), the `:retry-deadline-ms`
      EQL/RPC/introspection surfaces, the `:exhausted-reason` EQL
      provider-retries projection, and the oversized/near-Long `Retry-After`
      floor fixes. Refresh the page (status stays done) per mementum's
      stale-knowledge protocol before closing.
      → Done: page refreshed in place (status stays `done`) with the 4th–15th
      and 16th-turn follow-up facts: the `assert-test-seam-no-hot-loop!`
      behavioral guard + derived/overridable `:retry-min-clock-advance-ms`
      threshold, the `create-context*` seam-key propagation (all five retry
      seam keys now flow through `create-session-context` opts), the
      `:retry-deadline-ms` EQL/RPC/introspection surfaces (`session/updated`
      payload + `:psi.agent-session/retry-deadline-ms` resolver), the
      `:exhausted-reason` EQL provider-retries projection (incl. the
      last-schedule `final?` keying rule), the oversized/near-Long `Retry-After`
      floor fixes, and the 16th-turn positive-delay floor (zero base/max config
      never hot-loops).

## Review follow-up (implementation review, seventeenth turn)

- [x] Replace the 1 ms production fallback for a zero base/max retry delay with a
      floor that actually prevents a provider-request storm, or reject the invalid
      configuration before entering the retry loop. `retry-metadata`
      (session-state/model.clj) currently applies `(max 1 ...)`; under the default
      600000 ms cap-free window, a persistent error can therefore issue roughly
      1000 provider attempts per second / 600000 attempts over ten minutes. This
      avoids a literal zero-delay loop but does not resolve the structural
      performance failure described by the 16th-turn follow-up. Pin a safe minimum
      or validation rule and add a runtime test proving the misconfiguration is
      bounded without relying on a 3 ms synthetic budget.
      → Done: `retry/validate-retry-config!` rejects non-positive base/max delays
      before the first provider request; `retry-metadata` no longer masks invalid
      input with a 1 ms fallback. The runtime test covers both invalid keys and
      asserts zero provider attempts.
- [x] Make retry-window deadline construction overflow-safe for large positive
      `:auto-retry-total-timeout-ms` values. `execute-prepared-request!`
      (turn-runtime/core.clj) computes `(+ now budget-timeout-ms)` with checked long
      arithmetic; an operator value near `Long/MAX_VALUE` therefore throws
      `ArithmeticException` on the first retryable failure instead of opening a
      bounded window or rejecting the config. Validate/cap the timeout (or use a
      subtraction/saturating deadline calculation) and add a focused runtime test
      covering the overflow boundary.
      → Done: `retry/deadline-ms` performs subtraction-guarded saturating addition;
      overflow yields `Long/MAX_VALUE`. A focused runtime test opens the boundary
      window and verifies normal count-cap termination without an exception.

## Review follow-up (implementation review, eighteenth turn)

- [x] Gate `retry/validate-retry-config!` on `retry-enabled?` (or defer it until a
      retry is actually eligible). `execute-prepared-request!` currently validates
      `:auto-retry-base-delay-ms` / `:auto-retry-max-delay-ms` unconditionally before
      the initial provider attempt, so a session with `:auto-retry-enabled false`
      and an otherwise irrelevant legacy/non-positive retry delay now throws
      `Invalid retry configuration` and issues zero provider requests instead of
      executing once and returning `:failure-reason :retry-disabled` on a retryable
      failure. This violates design Approach 1's contract that
      `:auto-retry-enabled` is the master on/off for retries as a whole. Add a
      regression test combining disabled auto-retry with a non-positive delay and
      assert one provider attempt plus the normal retry-disabled outcome.
      → Done: delay validation now runs only when `retry-enabled?`; the existing
      retry-disabled regression test now supplies a zero base delay and still
      asserts one provider attempt plus the normal `:retry-disabled` outcome.

## Review follow-up (implementation review, nineteenth turn)

- [x] Defer `retry/validate-retry-config!` until an enabled, retryable provider
      failure is actually eligible to enter retry scheduling. The eighteenth-turn
      fix gates validation only on session-level `retry-enabled?`, but
      `execute-prepared-request!` still validates before the initial provider
      attempt whenever retries are enabled. Consequently a non-positive retry
      delay prevents an otherwise successful request, or a terminal
      non-retryable failure such as HTTP 401, from executing even though no retry
      delay can be used. This is the same inactive-config problem as the fixed
      retry-disabled case at a narrower eligibility boundary. Add regression
      coverage proving invalid delay settings do not block a successful initial
      request or a non-retryable failure, while a retryable failure still rejects
      the configuration before scheduling any retry.
      → Done: validation now runs only when the give-up decision will enter a
      full or truncated retry-scheduling branch. New retry-config tests
      prove success and HTTP 401 execute normally with an invalid inactive delay,
      while retryable transport failure executes once then rejects before emitting
      `provider_retry_scheduled` or writing retry state. The prior base/max test
      moved into the new namespace and now asserts this scheduling boundary.

## Review follow-up (implementation review, twentieth turn)

- [x] Preserve a terminal provider-event lifecycle when retry-delay validation
      rejects a retryable failure. `execute-prepared-request!` currently dispatches
      `provider_request_finished` with `:final? false` before calling
      `retry/validate-retry-config!`; validation then throws before either
      `provider_retry_scheduled` or a final/cancelled provider event is emitted.
      The new `retryable-failure-validates-before-scheduling-test` asserts only the
      event type sequence, so it accepts this stranded non-final request; EQL
      `provider-retries` consequently has no final event/status for the failed
      provider request. Reorder or explicitly finalize the validation-error path
      so the completed provider attempt has an authoritative terminal event while
      still rejecting before retry state/scheduling, and assert the terminal
      event fields (including `:final?`) in the regression test.
      → Done: retry-delay validation now runs before the ordinary non-final failed
      event; an invalid config emits one final failed `provider_request_finished`
      event and then rethrows, without scheduling or retry-state writes. The
      regression test asserts the terminal event's lifecycle and error fields.

## Review follow-up (implementation review, twenty-first turn)

- [x] Clear canonical retry-window state when retry-delay validation rejects a
      retryable failure. The new validation-error catch in
      `execute-prepared-request!` emits a terminal `provider_request_finished`
      event and immediately rethrows, but unlike every other terminal path it
      never calls `retry/clear-active-retry!` with deadline clearing enabled. A
      session rehydrated mid-window can therefore enter this path with persisted
      `:retry-attempt`, `:retry`, and `:retry-deadline-ms`; after the terminal
      configuration error those fields remain visible and can contaminate the
      next turn. Clear the terminal retry state before rethrowing, and extend
      `retryable-failure-validates-before-scheduling-test` with seeded active
      retry/deadline state that is absent/reset after the exception.
      → Done: the validation-error catch now clears active retry state with
      deadline clearing enabled before rethrowing. The regression test seeds a
      future active retry window and asserts `:retry-attempt` resets to zero,
      `:retry` becomes nil, and `:retry-deadline-ms` is absent.

## Review follow-up (test review)

- [x] Replace the task-added retry tests' `with-redefs` of
      `psi.turn-runtime.core/execute-live-turn!` with an injected nullable
      provider that returns the same response sequences through the real
      provider execution boundary. The current tests inject behavior by
      replacing the logic under test (including all tests in
      `retry_config_test.clj` and the total-window/count-cap/deadline/
      `Retry-After`/cancellation cases in `response_mode_retry_test.clj`), which
      violates the task-test-review `¬mock`/`¬stub` rule and the design
      constraint to drive retries through the provider seam. Keep injected
      state-only clock/sleep/cancellation seams and preserve the existing
      outcome, event, retry-state, and attempt-count assertions.
      → Done: added shared `retry-provider-test-support` with a scripted
      nullable streaming provider and migrated all 25 direct
      `execute-live-turn!` replacements in `retry_config_test.clj` and
      `response_mode_retry_test.clj` through the real provider execution
      boundary. Clock/sleep/cancellation seams and state-based assertions are
      unchanged. Focused suites: 26 tests, 151 assertions, all green; changed
      files lint clean and commit checks pass.

## Review follow-up (test-shaper review)

- [x] Migrate
      `execute-prepared-request-streaming-retry-discards-failed-partial-output-test`
      off its remaining `with-redefs` of `psi.turn-runtime.core/do-stream!`.
      It replaces runtime logic rather than driving the real provider boundary,
      unlike every other retry test migrated to `retry-provider-test-support`,
      so the suite is inconsistent and the test can miss integration regressions
      between provider streaming and failed-attempt output isolation. Extend the
      scripted nullable provider support to accept explicit per-attempt stream
      event sequences (partial text followed by error, then successful text),
      and preserve the existing final-content and provider-event assertions.
      → Done: `retry-provider-test-support` now accepts explicit `:stream-events`
      per scripted response. The test drives partial-text/error then successful-text
      attempts through the nullable provider's real streaming boundary, with its
      final-content isolation and provider-event lifecycle assertions preserved.
      Focused retry suite: 23 tests, 135 assertions, all green; changed tests lint
      clean.

## Review follow-up (test-shaper re-review)

- [x] Make `retry-provider-test-support/response->events` fail fast when a
      scripted `response-fn` is exhausted or returns a malformed response.
      It currently destructures `nil` (or a map containing neither
      `:stream-events` nor `:assistant-message`) and falls through to
      `assistant-message->events nil`, fabricating `:start` plus `:done` events
      with a nil reason. An unexpected extra provider attempt therefore produces
      a misleading downstream outcome instead of a meaningful script-boundary
      failure. Validate that each response explicitly contains one supported
      shape, throw an informative `ex-info` otherwise, and add focused helper
      coverage for exhausted/malformed scripts.
      → Done: `response->events` now requires exactly one non-nil supported
      response shape and throws `Invalid scripted provider response` with the
      response and supported shapes in ex-data for exhausted, malformed, or
      ambiguous scripts. Focused helper coverage exercises each invalid boundary;
      helper and retry suites pass, and changed tests lint clean.

## Review follow-up (test-shaper second re-review)

- [x] Validate the selected scripted response payload, not only the outer shape
      discriminator, in `retry-provider-test-support/response->events`. A non-nil
      `{:assistant-message {}}` currently passes the new guard and is converted
      into synthetic `:start` / `:done` events with a nil stop reason; malformed
      `:stream-events` values likewise escape the helper's boundary validation.
      Such scripts can still produce misleading downstream failures instead of
      the promised `Invalid scripted provider response`. Define the minimal valid
      contract for each supported payload (including a non-nil assistant-message
      `:stop-reason`) and add focused malformed-payload cases that assert the
      informative boundary error.
      → Done: `response->events` now validates the selected payload before
      conversion. Assistant messages require a recognized stop reason plus
      sequential content (success) or an error message (error); explicit streams
      require a non-empty sequence of supported, minimally valid events ending in
      `:done` or `:error`. Focused tests cover malformed assistant messages,
      collection shape, event payloads/types, and terminal events. Helper and both
      consuming retry suites pass; changed tests lint clean.

## Review follow-up (test-shaper third re-review)

- [x] Exercise malformed scripted responses through the public
      `nullable-provider-context` provider boundary instead of dereferencing the
      private `response->events` var in every helper test. The current tests are
      coupled to the helper's internal decomposition, so a behavior-preserving
      rename/extraction breaks them while the actual contract — invoking the
      nullable provider's stream with a bad scripted response throws the
      informative boundary error — is not directly proved. Keep a narrow public
      boundary assertion for the error message/ex-data and retain table-driven
      malformed cases without reaching through `#'` private-var access.
- [x] Tighten scripted payload validation to reject structurally malformed but
      currently accepted sequences. A successful `:assistant-message` accepts any
      sequential `:content` (for example `[nil]` or an unsupported content map),
      which `assistant-message->events` silently drops into an empty successful
      response. Explicit streams accept a terminal event before the last event,
      duplicate/misplaced `:start` events, and events before `:start`, because only
      individual event shapes and the final event are checked. Define the minimal
      content-item and stream-topology invariants used by this test provider, then
      add representative malformed cases proving each invalid partition fails at
      the scripted-provider boundary with a meaningful error.
      → Done: helper tests now invoke the provider returned by
      `nullable-provider-context` and assert the boundary error message/ex-data;
      no private-var access remains. Successful assistant content is restricted
      to text items with string text, and explicit streams require one leading
      `:start`, only text deltas in the body, and exactly one final terminal event.
      Table-driven cases cover malformed content, events before start, duplicate /
      misplaced starts, and early / duplicate terminals. Helper plus consuming
      retry suites pass (28 tests, 193 assertions); changed files lint clean.

## Review follow-up (documentation review)

- [x] Make the retry policy's operator configuration surface real and document it.
      `CHANGELOG.md` presents `:auto-retry-total-timeout-ms` and
      `:auto-retry-max-retries` as configurable controls, but
      `doc/configuration.md` omits all `:auto-retry-*` settings and the documented
      user/project `:agent-session` config path does not currently reach the retry
      runtime: `shared-config/resolution.clj` resolves no retry keys,
      `app-runtime/create-runtime-session-context` passes only its separate
      `session-config`, and `main/session-runtime-config-from-args` supplies only
      `:llm-stream-idle-timeout-ms`. Either plumb the retry keys through the
      documented config precedence and add their types/defaults/disable semantics
      to `doc/configuration.md`, or identify the actually supported operator surface
      and correct the changelog to name it. Include the existing base/max-delay
      controls and their positive-value validation so the settings form one
      coherent reference.
      → Done: shared-config now projects the retry runtime keys from resolved
      user/project `:agent-session` configuration, and app-runtime merges those
      settings into the agent context before runtime session overrides (which
      remain highest precedence). `:auto-retry-enabled` is applied as a
      presence-aware initial session preference. `doc/configuration.md` now
      documents the master switch, count cap, base/max delays, total-time budget,
      defaults, disable/count-only semantics, positive delay validation, and an
      EDN example. Shared-config precedence and real project-config → app-runtime
      propagation tests pass; focused suites, lint, file-length, changelog, and
      dispatch-architecture checks are green.

## Review follow-up (documentation re-review)

- [x] Summarize the new default provider retry policy in both required overview
      surfaces. `README.md` currently links to `doc/configuration.md` without
      mentioning that retryable provider failures now use a configurable
      10-minute total-time window instead of the former default attempt cap, and
      `ramora/IMPLEMENTED.md` says only "provider-boundary retry/backoff
      observability" without recording the total-time limiter or operator config
      surface. Add concise overview text to both, linking the README detail to
      `doc/configuration.md`; keep the full key-by-key reference there.
      → Done: both overview surfaces now summarize the configurable 10-minute
      total-time default; README links directly to the detailed retry policy.

## Review follow-up (code-shaper review)

- [x] Introduce one typed retry-policy resolution boundary instead of coercing raw operator config ad hoc in `execute-prepared-request!` / `validate-retry-config!`. `resolved-session-runtime-config` currently passes file values through unchanged, then core eagerly calls `long` on `:auto-retry-total-timeout-ms`, retry validation calls `long` on the delay values, and `give-up-decision` compares the raw `:auto-retry-max-retries`. A string/map value therefore leaks a `ClassCastException` (the timeout can block even a successful request before the provider boundary), fractional numbers are silently truncated despite the documented integer contract, and a negative explicit retry cap is accepted despite the documented non-negative contract. Parse/validate the four retry settings into one consistent data shape with informative `ex-info`, while preserving the established rule that inactive delay settings do not block successful/non-retryable requests; add boundary tests for wrong types, fractions, and negative caps.
- [x] Make configured exponential-delay metadata overflow-safe before it is constructed. Positive base/max values currently pass `validate-retry-config!`, but `exponential-backoff-ms` uses floating-point multiplication/coercion and `retry-metadata` performs checked `(+ now-ms delay-ms)`; a near-`Long/MAX_VALUE` configured delay can therefore overflow `:resume-at` before validation is reached (metadata is built before `validate-retry-config!`), including on a non-retryable failure where delay config should be inactive. Validate an upper bound or use saturating/subtraction-safe arithmetic for the configured delay path, defer metadata construction until retry eligibility is known, and cover a near-Long delay on both retryable and non-retryable failures.
- [x] Replace `clear-active-retry!`'s optional positional `clear-deadline?` argument with an explicit required close mode (for example `:window-close` / `:between-attempts`, or a required options map). Omitting the fourth argument currently silently means “preserve deadline”, which makes the window-lifecycle invariant unenforceable and allows a future terminal call site to leak `:retry-deadline-ms`; all current callers already choose explicitly. Shape `retry-clear-needed?` around the same mode so invalid/omitted modes fail rather than selecting lifecycle semantics by truthiness.

## Review follow-up (code-shaper re-review)

- [x] Split retry-policy resolution so limiter fields needed by `give-up-decision`
      (`:auto-retry-total-timeout-ms` / `:auto-retry-max-retries`) are resolved
      before the decision, but delay fields are validated/resolved only when the
      decision will actually schedule a full or truncated sleep. The new
      `resolve-retry-policy!` call is gated only by `retry-eligible?`, so an
      enabled retryable failure at an already-reached explicit count cap (for
      example `:auto-retry-max-retries 0`) throws for an invalid base/max delay
      even though the immediate `:count-cap` terminal path never uses retry
      metadata or sleeps. This regresses the nineteenth-turn scheduling-boundary
      rule and makes the resolver docstring inaccurate. Keep one typed policy
      boundary/data shape while separating limiter resolution from active-delay
      validation, and add a regression test asserting cap 0 + invalid delay
      returns the normal `:retry-exhausted` / `:count-cap` outcome with one
      provider attempt and no scheduled retry.
      → Done: retry policy resolution now has typed limiter and delay phases;
      the existing single decision function runs before active delay validation,
      then runs with metadata only when scheduling remains possible. Cap 0 plus
      an invalid delay terminates normally after one provider attempt with
      `:count-cap` and no retry schedule.

## Review follow-up (code-shaper second re-review)

- [x] Make canonical retry defaults and the resolved typed retry policy the only
      sources used by retry runtime helpers. `retry.clj` currently duplicates
      `session-model/default-config` in private `retry-policy-defaults`, repeats
      timeout/cap literals in `retry-policy-preview`, and has
      `retry-min-clock-advance-ms` re-read/coerce raw base/max config instead of
      consuming the already validated delay policy. This leaves three policy
      representations that can drift and weakens the new typed boundary. Derive
      fallback/preview values from the canonical defaults, and pass the resolved
      delay values (or a derived minimum) to the hot-loop guard so downstream
      retry machinery does not reinterpret raw operator config. Add focused
      coverage proving preview, resolution, and guard derivation agree when
      canonical defaults and explicit overrides are used.
      → Done: retry fallback and preview values now derive from
      `session-model/default-config`; the resolved typed delay policy flows into
      the hot-loop guard, which no longer reinterprets raw config. Focused
      coverage proves preview/resolution agreement and guard-threshold derivation
      for canonical defaults and explicit overrides.

## Review follow-up (code-shaper third re-review)

- [x] Make the production interruptible-sleep deadline overflow-safe.
      `interruptible-sleep-for-retry!` (`turn-runtime/retry.clj`) still computes
      `(+ (System/currentTimeMillis) (long delay-ms))` with checked addition, so
      a valid near-`Long/MAX_VALUE` configured base/max delay passes the typed
      policy boundary and produces saturated retry metadata, but throws
      `ArithmeticException` before the real sleep begins. The existing
      `near-long-delay-metadata-saturates-test` sets
      `:provider-retry-sleep? false`, so it never exercises this boundary. Use
      saturating deadline construction (or remaining-time accounting that cannot
      overflow) in the interruptible sleep and add focused production-sleep-path
      coverage that avoids waiting while proving a near-Long delay does not
      throw and remains cancellable.
      → Done: the production interruptible sleep now reuses the saturating
      `deadline-ms` constructor. Focused real-sleep-path coverage passes
      `Long/MAX_VALUE`, cancels before waiting, and proves the boundary neither
      overflows nor loses cancellation polling.

## Review follow-up (code-shaper fourth re-review)

- [x] Single-source overflow-safe epoch addition. The latest sleep-boundary fix
      reuses `retry/deadline-ms`, but that helper independently duplicates
      `session-state.model/saturating-epoch-add`: both implement the same
      subtraction-guarded addition and saturation at `Long/MAX_VALUE`, and the
      retry namespace already depends on the session model. Expose one clearly
      named retry-time arithmetic helper and use it for retry-window deadlines,
      retry metadata `:resume-at`, and production sleep deadlines so future
      boundary fixes cannot drift between the three paths; retain focused
      boundary coverage for each caller.
      → Done: `session-state.model/saturating-epoch-add` is now the public
      retry-time arithmetic authority. Retry metadata, retry-window deadline
      construction, and production interruptible-sleep deadlines all call it;
      the duplicate `retry/deadline-ms` helper was removed. Existing focused
      model/runtime tests retain overflow-boundary coverage for all three callers.

## Review follow-up (code-shaper fifth re-review)

- [x] Use one injected-clock sample for each failed-attempt retry decision and its
      metadata. `execute-prepared-request!` reads `now` for deadline construction,
      give-up decisions, truncation, and hot-loop tracking, but
      `retry-metadata-for` immediately calls `now-ms` again to construct
      `:resume-at` and parse time-based `Retry-After` headers. One logical decision
      therefore has two time origins; an advancing injected clock (or a slow metadata
      path) can make the emitted full-retry `:resume-at` disagree with the deadline /
      guard instant and can consume test-clock advances unexpectedly. Pass the loop's
      `now` into metadata shaping (or return one attempt-time data shape) so computation
      is locally coherent, and add a focused clock-count / `:resume-at` regression test.
      → Done: `execute-prepared-request!` passes its failed-attempt `now` sample into
      `retry-metadata-for`; metadata no longer reads the injected clock independently.
      A focused runtime test uses an advancing clock and asserts one read plus
      `:resume-at` derived from that same sample.

## Review follow-up (code-shaper sixth re-review)

- [ ] Preserve a terminal provider-event lifecycle and clear canonical retry-window
      state when `assert-test-seam-no-hot-loop!` rejects a retry seam. The guard
      currently runs after the failed attempt has emitted
      `provider_request_finished` with `:final? false`, and throws before either a
      retry schedule or terminal event; unlike the retry-policy validation error
      path, it also skips the `:window-close` clear. A guarded test therefore leaves
      `:retry-deadline-ms` persisted and the provider request stranded non-final.
      Route guard failures through the same terminal-error boundary (or run/catch
      the guard before the ordinary non-final emission), then extend both guard
      tests to assert a final failed event and reset `:retry-attempt` / `:retry` /
      `:retry-deadline-ms` state.
- [ ] Make the hot-loop guard's threshold and clock comparison robust at their
      boundary. `:retry-min-clock-advance-ms` is consumed without validation, so a
      negative override disables the guard and a wrong type leaks a comparison
      exception; `(- now last-retry-now)` is checked arithmetic and can itself
      overflow for a non-monotonic injected clock spanning the long range, both in
      the predicate and again while building ex-data. Validate the override as a
      positive long integer and compare clock samples without overflow (treat a
      backward clock as non-advancing), with focused tests for invalid overrides
      and a backward/extreme injected clock.
