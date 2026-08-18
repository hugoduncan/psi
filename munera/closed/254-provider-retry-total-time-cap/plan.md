# Plan — 254 Bound provider auto-retry by total elapsed time instead of attempt count

## Approach

Implement the total-elapsed-time retry window per design.md (Approaches 1–5) across the
config layer and the turn-runtime retry loop, then update/extend tests.

### Config & schema (components/session-state/src/psi/session_state/model.clj)

- Add `:auto-retry-total-timeout-ms 600000` to `default-config`.
- Change `:auto-retry-max-retries 3` → `nil` (sentinel "unset"). All current
  explicit-cap callers/tests pass concrete values → they still read as explicit.
- Add top-level optional schema entry `[:retry-deadline-ms {:optional true} [:maybe :int]]`
  (sibling of `:retry-attempt`) in `agent-session-schema`.

### Retry loop (components/turn-runtime/src/psi/turn_runtime/core.clj + retry.clj)

The retry machinery lives in a dedicated `psi.turn-runtime.retry` namespace
(`components/turn-runtime/src/psi/turn_runtime/retry.clj`: `now-ms`, `retry-deadline-for`,
`give-up-decision`, `mark-active-retry!`, `clear-active-retry!`/`retry-clear-needed?`,
`sleep-for-retry!`, `cancelled-retry-outcome`, retry metadata/classification helpers),
required as `retry` by core.clj, which owns the loop orchestration.

Replace the count-only give-up with a single structured give-up predicate that folds
count-cap + deadline + overshoot into one decision (no separate loop-body deadline check).

- `now-ms` helper: `(or (:now-fn ctx) #(java.time.Instant/now))` → epoch ms (mirrors
  `retry-metadata-for`).
- Resolve per-request config once at the top of `execute-prepared-request!`:
  - `budget-active?` ⇔ `(pos? (long (or (get-in ctx [:config :auto-retry-total-timeout-ms]) 0)))`.
  - `explicit-cap` = effective `:auto-retry-max-retries` (sentinel `nil` when default).
  - `count-cap` (the reported/effective count limiter) = `(cond (some? explicit-cap)
    explicit-cap (not budget-active?) 3 :else nil)` — explicit cap wins; budget-disabled
    falls back to 3 (count-only, behavior-preserving); budget-active default → `nil` (no
    count limiter; deadline bounds). **Never `(long nil)`** (NPE trap).
- `retry-deadline-for` (loop-entry read-back, mirroring `retry-attempt-for`): reads
  canonical `:retry-deadline-ms`; if present-but-past (`deadline < (now-ms ctx)`, stale)
  → dissoc the canonical field and yield `nil` (fresh window); else yield the value.
- `give-up-decision` (replaces `failure-reason-for`) returns a structured outcome:
  - non-retryable / retry-disabled → `{:failure-reason ...}` (immediate final).
  - count cap (`some? count-cap` and `retry-attempt >= count-cap`) →
    `{:failure-reason :retry-exhausted :exhausted-reason :count-cap}` (immediate final).
  - deadline reached (`now >= deadline`) →
    `{:failure-reason :retry-exhausted :exhausted-reason :deadline}` (immediate final).
  - overshoot (`deadline > now` and `now + next-delay > deadline`) →
    `{:failure-reason :retry-exhausted :exhausted-reason :deadline
      :final-sleep-ms (- deadline now)}` (sleep truncated remainder, then finalize).
  - else → retry full `next-delay`.
  Branch order mirrors existing `failure-reason-for` (count-cap before deadline →
  `:count-cap` wins when both hold).
- Loop restructure (`execute-prepared-request!`): thread `retry-deadline-ms` through the
  `recur` binding alongside `:retry-attempt`; compute `retry-metadata-for` (the would-be
  next delay, incl. `Retry-After`) before the give-up predicate so it sees `next-delay`.
  Deadline for the window-opening failure: `deadline-ms = (or retry-deadline-ms
  (when (and retryable? retry-enabled? budget-active?) (+ now total-timeout-ms)))`.
  - Immediate-final branch: dispatch `provider_request_finished` (`final? true`,
    `:exhausted-reason` when exhausted), `clear-active-retry!` with `:clear-deadline? true`,
    `execution-result` with retry-outcome (carrying `:exhausted-reason`, `:max-retries`
    = `count-cap`).
  - Final-sleep branch (`:final-sleep-ms` present): the error-branch
    `provider_request_finished` is **non-final** (the attempt is retried with a truncated
    delay); run the non-final path once with truncated metadata
    (`:delay-ms`/`:resume-at` = `final-sleep-ms` / `now + final-sleep-ms`, keeping
    `:delay-source`/`:rate-limit`), `provider_retry_scheduled` + `mark-active-retry!`
    (persist deadline), `sleep-for-retry!` the truncated delay; if cancelled → cancel path;
    else emit the **final** `provider_request_finished` (`final? true`,
    `:retry-exhausted :deadline`), `clear-active-retry!` `:clear-deadline? true`,
    `execution-result`. (Final-sleep uses the non-final emit/mark so the truncated delay is
    recorded; the subsequent final event supersedes the interim "scheduled" signal.)
  - Retry branch: as today (`provider_retry_scheduled`, `mark-active-retry!` persisting
    deadline, full `sleep-for-retry!`), inter-attempt `clear-active-retry!` with
    `:clear-deadline? false` (preserves deadline), `recur next-attempt deadline-ms`.
- `mark-active-retry!` gains a `retry-deadline-ms` arg and writes canonical
  `:retry-deadline-ms` (window-open persistence colocated with the first retry write).
- `clear-active-retry!` / `retry-clear-needed?` gain a window-scoped `:clear-deadline?`
  distinction: per-sleep call (`false`) preserves `:retry-deadline-ms`; success /
  final-give-up / cancel calls (`true`) clear it. `retry-clear-needed?` includes the
  deadline in its guard when `:clear-deadline? true`.
- **Cancel path gets its own unconditional `clear-active-retry!` (`:clear-deadline? true`)**
  inside the `if cancelled?` branch (after the per-sleep clear), independent of the
  `:provider-retry-sleep?` skip, so a cancelled turn never leaks the deadline (and, in
  test mode, `:retry`/`:retry-attempt`).
- `cancelled-retry-outcome` takes the resolved `count-cap` for its `:max-retries`.

### Tests

- `components/turn-runtime/test/psi/turn_runtime/response_mode_retry_test.clj` and
  `response_mode_test.clj`: existing tests set explicit `:auto-retry-max-retries`
  (0/1/2/3) → read as explicit caps, count-capped as before, so they stay green. Verify
  `:max-retries` reporting and add `:exhausted-reason :count-cap` where relevant.
- New tests (inject `:now-fn` clock + `:provider-retry-sleep? false` / `:provider-retry-sleep-fn`):
  1. Default budget (10 min) drives termination: with a tiny injected budget
     (`:auto-retry-total-timeout-ms` small) and advancing `:now-fn`, the window opens on
     the first retryable failure and gives up `:retry-exhausted :exhausted-reason :deadline`
     at the deadline, not at a default count cap; `:max-retries` reports `nil` on the
     outcome in the budget-active default path.
  2. Truncated final sleep: the last `provider_retry_scheduled` carries the truncated
     `:delay-ms`/`:resume-at` (deadline), then a final `provider_request_finished`
     (`final? true`, `:retry-exhausted :deadline`).
  3. Explicit small cap still hard-caps (`:exhausted-reason :count-cap`, immediate, no
     truncated sleep) even with the budget active.
  4. Budget disabled (`nil`/`<= 0` total-timeout) + no explicit cap → count-only fallback 3
     (`:max-retries` 3, `:exhausted-reason :count-cap`).
  5. `Retry-After` respected per attempt and still deadline-bounded (oversized
     `Retry-After` truncated to deadline).
  6. Cancellation interrupts a pending backoff (incl. truncated final sleep) →
     `:retry-cancelled`, and no stale `:retry-deadline-ms` leaks after cancel.
  7. Stale past deadline at loop entry opens a fresh window (no instant `:deadline` give-up).
  8. `:retry-deadline-ms` preserved by inter-attempt clear, cleared on window close.
- `components/session-state/test/psi/session_state/model_test.clj`: `valid-session?`
  accepts the new optional `:retry-deadline-ms`; default-config updated.

## Decisions

- Sentinel `nil` default for `:auto-retry-max-retries`; explicit non-`nil` = hard cap;
  budget-disabled count-only fallback = 3 (unchanged); budget-active default → no count
  limiter (deadline alone bounds). Reported `:max-retries` = effective count limiter.
- Deadline is a top-level canonical session field, threaded through the loop binding;
  persisted via `mark-active-retry!`; preserved by inter-attempt clear; cleared on true
  window close (success / final-give-up / cancel) with `:clear-deadline? true`.
- Truncated final sleep routes through the non-final path once (records/emits truncated
  delay), then emits the authoritative final `provider_request_finished`.
- `:exhausted-reason` (`:count-cap | :deadline`) on retry-outcome + `provider_request_finished`
  event; `:count-cap` wins when both hold (branch order).

## Risks

- NPE from `(long nil)` when the default becomes the sentinel — never coerce the sentinel.
- Reopening a fresh 10-min window each attempt if the inter-attempt clear wipes the
  deadline — threaded loop binding + `:clear-deadline? false` on the per-sleep clear.
- Deadline leaking across a cancelled turn — own unconditional cancel clear.
- Existing count-cap tests silently become budget-driven if they relied on the default cap
  (they don't — all set explicit caps), verified below.
- Event-order consumers of `provider_retry_scheduled` must treat the later final
  `provider_request_finished` as authoritative for a truncated final (documented in design).
