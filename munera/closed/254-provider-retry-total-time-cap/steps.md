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

- [ ] `create-context*` (agent-session/context.clj) still silently drops
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
- [ ] `retry-deadline-for` (turn-runtime/retry.clj) budget-disabled branch
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
