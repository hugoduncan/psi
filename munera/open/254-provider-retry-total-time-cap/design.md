# Design — 254 Bound provider auto-retry by total elapsed time instead of attempt count

## Goal

Change the provider auto-retry policy so a retryable provider error (e.g.
Anthropic 529 overloaded) keeps retrying with exponential backoff for **up to a
total of 10 minutes** of wall-clock retrying — a retry window that opens when the
first retry is scheduled and runs for 10 minutes — instead of the current
behaviour of giving up after a fixed number of attempts (default 3 → ~14 s total:
2 s + 4 s + 8 s).

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
   **Disable semantics.** The total-time budget is active whenever the key
   resolves to a positive value: a deadline is computed for each retry window and
   bounds the loop. A value of `nil`/absent or `<= 0` disables the time budget
   entirely — no deadline is computed, the give-up predicate evaluates only the
   count cap, and the existing `:auto-retry-max-retries` becomes the sole
   limiter. This keeps a strict count-only mode expressible (e.g. time budget
   off with a large count cap) and leaves `:auto-retry-enabled` as the
   master on/off for retries as a whole.

2. **Deadline-based termination via a single give-up predicate.** Extend the
   existing count-cap decision (`failure-reason-for` in
   `components/turn-runtime/src/psi/turn_runtime/core.clj`) into one coherent
   give-up predicate that evaluates the count cap and the total-time deadline
   together, per failed attempt — no separate deadline check in the loop body.
   The loop ordering is: classify the failure → ensure the deadline is present
   (window-opening failure: compute and persist it; otherwise read the persisted
   value) → compute the would-be next delay (`retry-metadata-for`, which resolves
   any `Retry-After`) → run the single give-up predicate against the failure
   classification, current retry count, the deadline, and the would-be next
   delay. It decides:
   - `:non-retryable` / `:retry-disabled` → stop (unchanged);
   - count cap reached (`retry-attempt >= max-retries`) → `:retry-exhausted`
     with `:exhausted-reason :count-cap`;
   - deadline reached (`now >= deadline`) → `:retry-exhausted`
     with `:exhausted-reason :deadline`;
   - the next full delay would overshoot (`now + next-delay > deadline`) → sleep
     the remaining portion (`deadline - now`) and then give up with
     `:retry-exhausted` with `:exhausted-reason :deadline`, so the default
     give-up occurs at the deadline;
   - otherwise → retry, sleeping the full `next-delay`.

   **Distinguishing the termination boundary.** Both count-cap and deadline
   exhaustion keep the existing single `:failure-reason :retry-exhausted`
   surface (so existing consumers of `:retry-exhausted` / `:exhausted?` are
   unchanged), but the give-up predicate also exposes which boundary fired via a
   new `:exhausted-reason` field — `:count-cap` or `:deadline` — carried on the
   retry-outcome map and the `provider_request_finished` event payload whenever
   `:failure-reason :retry-exhausted` is present. Telemetry and UI can therefore
   tell whether the window was bounded by time or by an explicit count cap.

   The per-attempt backoff still comes from `exponential-backoff-ms` (2 s, 4 s,
   8 s, 16 s, 32 s, then capped at `:auto-retry-max-delay-ms` 60 s) or from
   `Retry-After` when present; the deadline only bounds the total window and
   truncates the *final* sleep to the remaining time — it does not shorten
   intermediate delays.

   **Canonical deadline state.** Record the deadline in canonical session state,
   not a runtime-local binding: when the retry window opens (the first retryable
   failure), store `:retry-deadline-ms` alongside the existing canonical `:retry`
   metadata (with `:resume-at`) via the established
   `apply-root-state-update-in!` / session-update path, and read it back the same
   way `:retry-attempt` is read at loop entry. This survives loop re-entry and
   follows the canonical-root vs runtime-handles state boundary
   (`doc/architecture.md`); the `agent-session-schema` gains the deadline field.

   **Window-open detection.** The give-up predicate's deadline input is ensured
   once per window, before the predicate runs: if `:retry-deadline-ms` is absent
   from session state at a retryable, retry-enabled failure, that failure is the
   window-opening one — compute `deadline = now + :auto-retry-total-timeout-ms`
   (when the budget is active; see Approach 1 disable semantics) and persist it
   in the same session-update that marks the first retry active; otherwise reuse
   the stored value. Because the window is keyed solely on deadline presence, a
   later turn starts a fresh window only after the previous window's deadline was
   cleared (below).

   **Field placement.** `:retry-deadline-ms` is a **top-level** session field —
   a sibling of `:retry-attempt` — not a key inside the `:retry` map. The
   `:retry` map is replaced each attempt by `mark-active-retry!` (fresh
   per-attempt `:delay-ms`/`:resume-at`), whereas the deadline is a
   window-scoped value that must persist across every attempt in the window and
   be cleared once on window close, so it belongs at session top level next to
   `:retry-attempt`. In `agent-session-schema` it is
   `[:retry-deadline-ms {:optional true} [:maybe :int]]` (absent until a window
   opens).

   **Clearing.** `clear-active-retry!` (and the `retry-clear-needed?` guard that
   decides whether to run it) currently clears `:retry-attempt`, `:retry`, and
   `:provider-retry-abort-requested?` on success/give-up/cancel; add
   `:retry-deadline-ms` to both so the window-scoped deadline is cleared with the
   rest of the retry state and a stale deadline cannot leak into a later turn's
   first retry. Read-back at loop entry uses the same helper pattern as
   `retry-attempt-for` (e.g. a `retry-deadline-for`).

   **Recorded/emitted delay under truncation.** The canonical `:retry` metadata
   `:delay-ms`/`:resume-at` written by `mark-active-retry!`, and the
   `provider_retry_scheduled` event `:delay-ms`/`:resume-at`, reflect the delay
   that will actually be slept: `min(full-next-delay, deadline - now)`, with
   `:resume-at` recomputed as `now + that-slept-delay`. For intermediate (non
   final) attempts the deadline does not bind, so this is the full computed delay
   exactly as today; only the truncated final sleep records a shorter delay, so a
   consumer (progress `:retry-updated`, UI, telemetry) always sees a resume time
   that matches when the retry actually resumes. `:delay-source` keeps reporting
   the source of the underlying computed delay (`:exponential-backoff` or
   `:retry-after`), independent of truncation.

   **Anchor.** The 10-minute budget is the *retry window*: it opens when the
   first retry is scheduled (the first retryable-failure decision) and runs for
   `:auto-retry-total-timeout-ms`. The initial failing attempt's own execution
   time is not part of the budget, so Goal/AC1 ("10 minutes of retrying") and the
   deadline computation agree.

   **In-flight attempts.** The budget bounds only the loop's inter-attempt
   give-up points; it does not abort an in-flight request whose execution runs
   past the deadline. That attempt is allowed to complete — success is returned
   immediately, and a failure past the deadline simply gives up rather than
   scheduling another retry. (Aborting an in-flight request remains the job of
   the existing cancellation paths.)

3. **Time source**: use the injectable clock so tests can drive the window
   without sleeping 10 minutes. `retry-metadata-for` already reads a `:now-fn`
   from ctx; the deadline computation and the give-up predicate use the same
   injected clock (falling back to `System/currentTimeMillis`), and the existing
   interruptible sleep stays the wall-clock wait between attempts.

4. **Count remains a hard cap**: keep `:auto-retry-max-retries` as an explicit
   upper bound on attempt count for callers who want a strict count limit, but it
   must no longer be the *default* give-up point. **Pin the default**: raise
   `:auto-retry-max-retries` from `3` to `20`, a value never reached within the
   default 10-minute window (default backoff reaches ~14 attempts in 10 minutes),
   so the total-time budget is the effective default limiter while operators can
   still set a strict count cap. Behaviour-preserving for any explicit small
   value.

5. **`Retry-After` interaction**: when a `Retry-After` header supplies a
   per-attempt delay, respect it as today. Because `retry-metadata-for` runs
   before the give-up predicate, the predicate sees the actual `Retry-After`
   delay; an oversized `Retry-After` whose delay would push past the deadline is
   truncated to the remaining window and the retry ends with `:retry-exhausted`
   at the deadline. A `Retry-After` delay is provider-supplied and not
   config-shrinkable, so the total-time-with-`Retry-After` acceptance case drives
   the wait through the existing `:provider-retry-sleep-fn` injectable seam
   (already used by current retry tests in `response_mode_test.clj`), combined
   with the injected `:now-fn`, to stay deterministic without real sleeps.

## Constraints

- Preserve cancellation: a 10-minute window must remain immediately
  interruptible via active-turn abort and `:provider-retry-abort-requested?`
  (no change to `provider-retry-cancelled?` / `interruptible-sleep-for-retry!`).
- Behaviour-preserving for explicit count-limit configs: any caller that sets a
  small `:auto-retry-max-retries` must still respect it as a hard cap.
- Backoff schedule unchanged: 2 s, 4 s, 8 s, … capped at 60 s; `Retry-After`
  still preferred per attempt (intermediate delays unchanged; only the final
  sleep is truncated to the deadline).
- Use the injectable clock for the deadline so tests avoid real 10-minute sleeps.
- Follow the ¬mock/¬stub standard; drive retry sequences through a real stub
  provider via the injectable seam where tests already do so.

## Acceptance

- Default config gives up on a retryable provider error (529 overloaded, 429,
  timeout) only after the retry window has elapsed **10 minutes total** of wall
  clock — the final backoff is truncated to the remaining time so give-up occurs
  at the deadline — using backoff 2 s, 4 s, 8 s, 16 s, 32 s, then pinned at 60 s —
  not after 3 attempts (~14 s). The window is anchored at the first retry
  decision; the initial failing attempt's execution time is not part of it, and
  an in-flight attempt running past the deadline is not cut off.
- A new config key controls the total window (default `600000` ms), and the
  existing `:auto-retry-base-delay-ms` / `:auto-retry-max-delay-ms` still shape
  the per-attempt backoff.
- `Retry-After` headers are still respected per attempt, and the total-time
  deadline still bounds the overall window.
- A retry window is cancelled immediately (no waiting out the remaining backoff)
  when the active turn is aborted or `:provider-retry-abort-requested?` is set.
- An explicitly configured small `:auto-retry-max-retries` still acts as a hard
  cap on attempt count; setting `:auto-retry-total-timeout-ms` to `nil`/absent
  or `<= 0` disables the time budget so a strict count-only mode is expressible.
- The deadline field `:retry-deadline-ms` is a top-level session field (sibling
  of `:retry-attempt`) in `agent-session-schema`, cleared by
  `clear-active-retry!` / `retry-clear-needed?` with the rest of the retry state
  so a stale deadline never leaks across turns.
- Under truncation the canonical `:retry` metadata and the
  `provider_retry_scheduled` event record the delay actually slept
  (`min(full-next-delay, deadline - now)` with `:resume-at` recomputed), so the
  reported resume time matches the actual resume; `:delay-source` is unchanged.
- Count-cap and deadline exhaustion both yield `:failure-reason :retry-exhausted`
  with `:exhausted? true`, and additionally carry `:exhausted-reason` —
  `:count-cap` or `:deadline` — in the retry-outcome and
  `provider_request_finished` event so consumers can tell which boundary fired.
- Tests: existing retry tests updated where they assert count-based give-up;
  new tests (using the injected `:now-fn` clock and small config values, and the
  `:provider-retry-sleep-fn` seam for the `Retry-After` path) prove the
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
