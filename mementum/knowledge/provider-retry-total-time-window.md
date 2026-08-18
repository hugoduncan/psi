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

## Deadline lifecycle

- Deadline is a **top-level canonical session field** (`:retry-deadline-ms`, optional
  `[:maybe :int]` in `agent-session-schema`), written by `mark-active-retry!` when the
  window opens, threaded through the loop `recur` binding.
- The inter-attempt (per-sleep) `clear-active-retry!` **preserves** the deadline
  (`:clear-deadline? false`); the true-window-close clears (success / final-give-up /
  cancel) **clear** it (`:clear-deadline? true`). The cancel path has its OWN
  unconditional clear, independent of the `:provider-retry-sleep?` skip.
- A persisted deadline already in the past at loop entry (`retry-deadline-for`) is
  **stale**: cleared and treated as absent so the first retryable failure opens a
  fresh window (never an instant give-up).

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
