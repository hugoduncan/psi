# Design — 254 Bound provider auto-retry by total elapsed time instead of attempt count

## Goal

Change the provider auto-retry policy so a retryable provider error (e.g.
Anthropic 529 overloaded) keeps retrying with exponential backoff for **up to a
total of 10 minutes** of wall-clock time, instead of the current behaviour of
giving up after a fixed number of attempts (default 3 → ~14 s total: 2 s + 4 s +
8 s).

## Context

The turn runtime retries retryable provider errors (`:rate-limit`, `:timeout`,
`:overloaded`, `:provider-unavailable`, `:transport`; 529 overloaded maps to
`:overloaded`) inside `execute-prepared-request!`
(`components/turn-runtime/src/psi/turn_runtime/core.clj`).

Termination is **count-based** today:

- `max-retries` comes from config `:auto-retry-max-retries`, default `3`
  (`components/session-state/src/psi/session_state/model.clj` default-config).
- The retry loop gives up (`:retry-exhausted`) once `retry-attempt >= max-retries`
  (`failure-reason-for` in turn-runtime/core.clj).
- The backoff delay is `exponential-backoff-ms` = `min(max-ms, base × 2^attempt)`,
  with base `:auto-retry-base-delay-ms` 2000 and max `:auto-retry-max-delay-ms`
  60000. So the default schedule is 2 s, 4 s, 8 s, then capped at 60 s.

Result: with defaults, an overloaded provider that stays overloaded for, say, a
few minutes is abandoned after ~14 s. The user wants the retry window to persist
up to a total of 10 minutes, letting the backoff keep growing (2 s, 4 s, 8 s,
16 s, 32 s, then pinned at 60 s) across the whole window.

Retries are already interruptible: `interruptible-sleep-for-retry!` polls in
short slices and stops when `provider-retry-cancelled?` is true (active-turn
abort or `:provider-retry-abort-requested?`). Any change must preserve this so a
10-minute window can still be cancelled immediately.

## Scope

- `components/session-state/src/psi/session_state/model.clj` — config defaults.
- `components/turn-runtime/src/psi/turn_runtime/core.clj` — the retry loop's
  termination decision and per-attempt delay scheduling.
- Config plumbing for the new total-time budget (session-state config → ctx
  `:config`, following how `:auto-retry-max-retries` / delay keys already flow
  into turn-runtime).
- Tests in `components/turn-runtime/test/` (and any affected
  session-state/agent-session retry tests).

## Approach

Introduce a **total-elapsed-time budget** as the primary give-up condition, while
keeping the existing exponential backoff schedule and the per-attempt
`Retry-After` preference. Keep attempt-count as a secondary, configurable hard
safety cap (not the default limiter).

1. **New config key** `:auto-retry-total-timeout-ms`, default `600000`
   (10 minutes), alongside the existing `:auto-retry-*` keys in default-config.

2. **Deadline-based termination** in the retry loop: record a retry deadline =
   first-attempt start + `:auto-retry-total-timeout-ms`. Before each retry
   sleep, if the current time has reached the deadline (or the next scheduled
   sleep would push past it), stop with `:retry-exhausted` rather than scheduling
   another attempt. The backoff delay for each attempt continues to come from
   `exponential-backoff-ms` (2 s, 4 s, 8 s, 16 s, 32 s, then capped at
   `:auto-retry-max-delay-ms` 60 s) or from `Retry-After` when present; the
   deadline only bounds the total window, it does not shorten individual delays.

3. **Time source**: use the injectable clock so tests can drive the window
   without sleeping 10 minutes. `retry-metadata-for` already reads a `:now-fn`
   from ctx; the deadline check should use the same injected clock (falling back
   to `System/currentTimeMillis`), and the existing interruptible sleep stays the
   wall-clock wait between attempts.

4. **Count remains a hard cap**: keep `:auto-retry-max-retries` as an explicit
   upper bound on attempt count for callers who want a strict count limit, but it
   must no longer be the *default* give-up point. Decide in plan whether the
   default should be raised to a large effective value (so total-time governs) or
   dropped from the default path; the safer option is to keep it present but
   effectively non-limiting under the 10-minute budget so both controls remain
   available to operators.

5. **`Retry-After` interaction**: when a `Retry-After` header supplies a longer
   per-attempt delay, respect it as today; the total-time deadline still applies
   to the overall elapsed window. Document that a single oversized `Retry-After`
   that exceeds the remaining budget ends the retry with `:retry-exhausted`.

## Constraints

- Preserve cancellation: a 10-minute window must remain immediately
  interruptible via active-turn abort and `:provider-retry-abort-requested?`
  (no change to `provider-retry-cancelled?` / `interruptible-sleep-for-retry!`).
- Behaviour-preserving for explicit count-limit configs: any caller that sets a
  small `:auto-retry-max-retries` must still respect it as a hard cap.
- Backoff schedule unchanged: 2 s, 4 s, 8 s, … capped at 60 s; `Retry-After`
  still preferred per attempt.
- Use the injectable clock for the deadline so tests avoid real 10-minute sleeps.
- Follow the ¬mock/¬stub standard; drive retry sequences through a real stub
  provider via the injectable seam where tests already do so.

## Acceptance

- Default config gives up on a retryable provider error (529 overloaded, 429,
  timeout) only after the retry window has elapsed **10 minutes total** of wall
  clock, using backoff 2 s, 4 s, 8 s, 16 s, 32 s, then pinned at 60 s — not after
  3 attempts (~14 s).
- A new config key controls the total window (default `600000` ms), and the
  existing `:auto-retry-base-delay-ms` / `:auto-retry-max-delay-ms` still shape
  the per-attempt backoff.
- `Retry-After` headers are still respected per attempt, and the total-time
  deadline still bounds the overall window.
- A retry window is cancelled immediately (no waiting out the remaining backoff)
  when the active turn is aborted or `:provider-retry-abort-requested?` is set.
- An explicitly configured small `:auto-retry-max-retries` still acts as a hard
  cap on attempt count.
- Tests: existing retry tests updated where they assert count-based give-up;
  new tests (using the injected clock and small config values) prove the
  total-time window governs termination, the cap applies with `Retry-After`
  present, and cancellation interrupts a pending backoff. `bb test --focus
  psi.turn-runtime.response-mode-retry-test` (and the retry-focused subset) is
  green.

## Notes

- Origin: user-reported friction — Anthropic overloaded 529 responses retried at
  2 s/4 s/8 s give up too early for a transiently overloaded provider.
- Design-only: plan.md / steps.md to be written before execution.
- **Decision (user):** the default 10-minute total-time budget is
  provider/model **independent** — a single uniform default
  (`:auto-retry-total-timeout-ms` = 600000) applies to every provider and model
  unless an operator explicitly overrides the session/config key. No per-provider
  or per-model retry-window table is introduced; the design should not add
  provider/model-specific retry-timeout configuration.
