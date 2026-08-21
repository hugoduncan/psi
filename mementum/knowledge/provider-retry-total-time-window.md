---
title: Provider-retry total-time window (deadline-bounded, not count-bounded)
status: done
category: turn-runtime
tags: [retry, provider, deadline, backoff, config, test-seams]
related:
  - mementum/memories/wall-clock-timeout-cannot-unwind-blocking-dispatch.md
depends-on: []
---

# Provider-retry total-time window

The psi turn-runtime provider auto-retry (`psi.turn-runtime.core/execute-prepared-request!`,
machinery in `psi.turn-runtime.retry`) bounds the retry loop by **total elapsed
wall-clock time** (a deadline) by default, not by attempt count. The deadline is the
primary give-up condition; attempt count is an operator-set hard cap.

## Config semantics (sentinel-`nil` explicitness)

- `:auto-retry-total-timeout-ms` (default 600000) bounds the window. Active when it
  resolves to a positive value; `nil`/absent/`<= 0` disables the budget → count-only.
- `:auto-retry-max-retries` defaults to sentinel `nil` ("unset"). Because effective
  config is `(merge default-config caller-config)`, a **non-`nil` effective value
  ⇔ operator/caller explicitly set it**. So key presence can't detect explicitness,
  but the sentinel `nil` can.
- Effective count cap (`count-cap`) = explicit cap if non-`nil`; else count-only
  fallback `3` when the budget is disabled; else `nil` (no count limiter — the
  deadline alone bounds). `:max-retries` on retry-outcome reports this `count-cap`.
- **NPE trap**: never `(long sentinel-nil)` — a sentinel default must be treated as
  "no cap", not coerced.
- **Delay validation (17th/18th-turn follow-ups)**: when auto-retry is enabled,
  `execute-prepared-request!` rejects non-positive `:auto-retry-base-delay-ms` /
  `:auto-retry-max-delay-ms` before the first provider request. Disabled auto-retry
  ignores these inactive settings and still executes the provider request once.
  The prior 1 ms fallback prevented a literal zero-delay loop but still permitted
  roughly 1000 requests/second for the default 10-minute cap-free window, so it
  was not a safe production bound.

## Deadline lifecycle

- Deadline is a **top-level canonical session field** (`:retry-deadline-ms`, optional
  `[:maybe :int]` in `agent-session-schema`), written by `mark-active-retry!` when the
  window opens, threaded through the loop `recur` binding. Deadline construction is
  saturating: a positive timeout that would overflow `now + timeout` yields
  `Long/MAX_VALUE` instead of throwing `ArithmeticException`.
- The inter-attempt (per-sleep) `clear-active-retry!` **preserves** the deadline
  (`:clear-deadline? false`); the true-window-close clears (success / final-give-up /
  cancel) **clear** it (`:clear-deadline? true`). The cancel path has its OWN
  unconditional clear, independent of the `:provider-retry-sleep?` skip.
- A persisted deadline already in the past at loop entry (`retry-deadline-for`) is
  **stale**: cleared and treated as absent so the first retryable failure opens a
  fresh window (never an instant give-up). A leftover FUTURE deadline under a
  budget-disabled config is also cleared at entry (count-only mode is never
  deadline-bound), alongside the stale `:retry-attempt`/`:retry` residue of the prior
  window.

## Observability surfaces (post-closure)

- `:retry-deadline-ms` is exposed on the **`session/updated` event payload**
  (`components/rpc/src/psi/rpc/events.clj`) and the **EQL/diagnostics session
  surface** (`:psi.agent-session/retry-deadline-ms` in
  `components/agent-session/src/psi/agent_session/resolvers/session.clj`) — nil when
  no window is open, so observers can tell when the retry window closes.
- The EQL `provider-retries` projection (resolvers in
  `components/agent-session/src/psi/agent_session/resolvers/provider_retries.clj`)
  exposes per-provider-request `:psi.provider-request/final-status`,
  `:psi.provider-request/exhausted-reason` (`:count-cap` | `:deadline`), retry count,
  and per-attempt schedules with `:psi.provider-retry/final?`. The **final marker
  keys on the LAST schedule** (max retry-attempt), not the schedule whose attempt
  number matches the terminal event's — the truncated-final deadline give-up reports
  the pre-sleep failed attempt N while the superseded truncated schedule carries N+1.
- `:exhausted-reason` (`:count-cap` | `:deadline`) is also carried on the retry
  outcome map (`:execution-result/retry-outcome`) and the terminal
  `provider_request_finished` event; `:max-retries` reports the effective count
  limiter (nil on the budget-active default path).

## Retry-After floor fixes (post-closure)

- A non-positive integer `Retry-After` (0/negative) and an oversized/unparsable one
  (e.g. 20 digits, outside Long range) yield nil and floor to the exponential
  backoff (`retry-after-delay-ms` parses integers through the same `parse-long-safe`
  try/catch as the RFC-date branch — no `NumberFormatException`).
- A PARSEABLE near-Long/MAX integer (16 digits, seconds ≥ `Long/MAX_VALUE / 1000`)
  is capped below the `* 1000` conversion AND the `:resume-at` `(+ now-ms delay-ms)`
  addition overflow boundaries (previously an uncaught `ArithmeticException`), and
  the loop's deadline-overshoot comparison is subtraction-based
  (`(> next-delay-ms (- deadline-ms now))`) so it cannot overflow either. The
  largest fitting value is still honored (no spurious floor).

## Test-seam hot-loop guard (post-closure)

- `assert-test-seam-no-hot-loop!` fails fast when the sleep-disabled test seam
  (`:provider-retry-sleep? false` or an injected `:provider-retry-sleep-fn`), an
  active total-time budget, a nil count cap (sentinel default), and a clock that did
  not advance between consecutive scheduled retries would spin until the REAL
  wall-clock deadline. The seam requires an **ADVANCING `:now-fn`** whenever the
  budget is active.
- The clock-advance threshold is **derived** — `(max 1 (min :auto-retry-base-delay-ms
  :auto-retry-max-delay-ms))` — so sub-second base delays don't false-positive, and
  **overridable** per-test via `:retry-min-clock-advance-ms` on the ctx (e.g. for a
  cap-free budget-active test whose smallest delay is a provider Retry-After below
  the configured base).

## Seam-key propagation through `create-context*` (post-closure)

All five retry seam keys flow through `create-session-context` opts (propagated onto
the ctx by `create-context*` in `components/agent-session/src/psi/agent_session/context.clj`):
`:provider-retry-sleep?`, `:provider-retry-sleep-fn`, `:provider-retry-cancelled?`,
`:now-fn`, `:retry-min-clock-advance-ms` — replacing the pre-fix direct-assoc-after-
creation workaround (the silent-drop trap). Inert outside the retry seam; production
behavior unchanged.

## Deterministic time-window testing (no real sleeps)

Drive the window with the two existing injected seams on ctx:

- `:now-fn` (returns an `Instant`) — used by the deadline computation and
  `retry-metadata-for`; return `(Instant/ofEpochMilli @clock)` from an atom and
  advance the atom in the sleep fn.
- `:provider-retry-sleep-fn` — advance the clock atom by the slept delay, and/or
  `:provider-retry-sleep? false` to no-op sleeps entirely.

This lets deadline-window tests assert exact backoff/truncation/resume times without
real 10-minute waits. See `response_mode_retry_test.clj` deadline-window tests.

## Truncated final sleep

When the next full delay would overshoot the deadline, the loop sleeps only the
remaining window: the truncated `:delay-ms`/`:resume-at` (deadline) is recorded and
emitted via the non-final retry path, then a final `provider_request_finished`
(`:final? true`, `:retry-exhausted`, `:exhausted-reason :deadline`) is emitted after
the sleep — consumers treat that final as authoritative, superseding the interim
"scheduled" signal. Cancellation during the truncated sleep → `:retry-cancelled`
(cancel precedence).
