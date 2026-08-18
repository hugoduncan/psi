# Design — 254 Bound provider auto-retry by total elapsed time instead of attempt count

## Goal

Change the provider auto-retry policy so a retryable provider error (e.g. an
Anthropic 529 response whose payload says "overloaded") keeps retrying with
exponential backoff for **up to a
total of 10 minutes** of wall-clock retrying — a retry window that opens when the
first retry is scheduled and runs for 10 minutes — instead of the current
behaviour of giving up after a fixed number of attempts (default 3 → ~14 s total:
2 s + 4 s + 8 s).

## Context

The turn runtime retries retryable provider errors (`:rate-limit`, `:timeout`,
`:overloaded`, `:provider-unavailable`, `:transport`) inside
`execute-prepared-request!`
(`components/turn-runtime/src/psi/turn_runtime/core.clj`).

**Error classification of 529.** A bare HTTP 529 with no "overloaded" message
classifies as `:provider-unavailable`, not `:overloaded`: in `provider-error-kind`
(`components/session-state/src/psi/session_state/model.clj`) the message-based
`:overloaded` branch (`overloaded-error-patterns` = `#"(?i)overloaded"`) runs
*before* the status-based provider-unavailable branch, which itself matches
`(contains? #{500 502 503 529} http-status)`. So `:overloaded` is produced only
when the error message contains "overloaded"; a bare 529 falls through to
`:provider-unavailable`. Anthropic's real-world 529 overloaded responses do carry
an "overloaded" message, so the Goal/Acceptance "529 overloaded" examples mean
"a 529 whose payload says 'overloaded'" and classify as `:overloaded`. Both
`:overloaded` and `:provider-unavailable` are in the retryable set, so retry
behaviour is unchanged either way.

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
   (window-opening failure: compute it — persisting it with the first retry's
   `mark-active-retry!` when the retry path runs, per "Persistence at window
   open" below; otherwise read the persisted value) → compute the would-be next
   delay (`retry-metadata-for`, which resolves
   any `Retry-After`) → run the single give-up predicate against the failure
   classification, current retry count, the deadline, and the would-be next
   delay. The predicate returns a **structured outcome** (not a bare
   failure-reason), so the loop can tell "finalize now (no sleep)" apart from
   "sleep the truncated remainder, record/emit it, then finalize". It decides:
   - `:non-retryable` / `:retry-disabled` → stop (unchanged, immediate);
   - count cap reached (`retry-attempt >= max-retries`) —
     `{:failure-reason :retry-exhausted :exhausted-reason :count-cap}` —
     finalize now, no sleep. The count-cap branch is gated as in Approach 4: it
     fires only when the operator has explicitly configured
     `:auto-retry-max-retries`, or when the total-time budget is disabled; with
     the budget active and no explicit override, the count-cap does not gate
     (see Approach 4).
   - deadline reached (`now >= deadline`) →
     `{:failure-reason :retry-exhausted :exhausted-reason :deadline}` —
     finalize now, no sleep;
   - the next full delay would overshoot (`now + next-delay > deadline`) → a
     distinct **final-sleep outcome**
     `{:failure-reason :retry-exhausted :exhausted-reason :deadline
       :final-sleep-ms (- deadline now)}` — the loop runs one retry-path
     iteration with the truncated delay, then finalizes at the deadline;
   - otherwise → retry, sleeping the full `next-delay`.

   **Scheduling the truncated final sleep.** The overshoot case is not the
   uniform immediate-final signal: because the current loop only records/emits
   a retry on the **non-final** path (`mark-active-retry!` +
   `provider_retry_scheduled` run only before a sleep, while a `:retry-exhausted`
   final goes straight to `clear-active-retry!` + `execution-result`, per
   turn-runtime/core.clj:614-641), the overshoot final-sleep outcome routes the
   loop through the retry path exactly once so the truncated delay is recorded
   and emitted: compute `retry-metadata-for` with
   `min(full-next-delay, deadline - now)`, write/emit that truncated
   `:delay-ms`/`:resume-at` (`mark-active-retry!` + `provider_retry_scheduled`),
   sleep the truncated delay via the interruptible seam, then finalize with
   `:retry-exhausted :exhausted-reason :deadline` (clear + execution-result)
   instead of recursing. The presence of `:final-sleep-ms` in the predicate
   outcome is what distinguishes "finalize now (no sleep)" (absent) from "sleep
   remainder, record truncated delay, then finalize" (present).

   **Event/state semantics of the truncated final.** Reusing the non-final path
   for the truncated final is intended: the truncated delay is genuinely slept
   (the remaining window), so `provider_retry_scheduled` / progress
   `:retry-updated` reporting the truncated `:delay-ms`/`:resume-at` is accurate —
   the retry is scheduled to resume at the deadline, at which point the window is
   exhausted. The authoritative terminal signal is the subsequent
   `provider_request_finished` (`:final? true`, `:failure-reason :retry-exhausted`,
   `:exhausted-reason :deadline`), which consumers must treat as superseding the
   interim "scheduled" signal; a consumer should not assert a retry resumes from a
   `provider_retry_scheduled` whose session later emits a `:retry-exhausted`
   final. The transient canonical bump (`:retry-attempt` → `next-attempt`,
   `:retry :active? true`) is exactly the existing per-attempt lifecycle and is
   cleared by the final-path `clear-active-retry!` immediately after the sleep,
   before the turn's outcome is produced.

   **Truncated final under test mode (`:provider-retry-sleep? false`).** The
   truncated final sleep routes through the same sleep seam as any retry sleep
   (`sleep-for-retry!`), so under `:provider-retry-sleep? false` it is a no-op
   (no real wait, as in existing retry tests). The truncated delay is **still
   recorded and emitted** (`mark-active-retry!` + `provider_retry_scheduled` with
   the truncated `:delay-ms`/`:resume-at`) even when sleeping is disabled, so
   deadline-window tests observe the truncation deterministically without real
   sleeps. On finalize, `clear-active-retry!` **still runs unconditionally**: the
   final-path clear is independent of the `:provider-retry-sleep?` skip that
   applies only to the inter-attempt per-sleep clear (core.clj:642-643), so no
   stale `:retry`/`:retry-attempt`/`:retry-deadline-ms` leaks in test mode.

   **Cancellation during the truncated final sleep.** The truncated final sleep
   uses the same interruptible seam as any other retry sleep
   (`interruptible-sleep-for-retry!` / `:provider-retry-sleep-fn`,
   `sleep-for-retry!` → cancellable), so cancellation takes precedence over the
   deadline give-up: if an active-turn abort or `:provider-retry-abort-requested?`
   arrives during the truncated final sleep, the sleep is aborted and the
   outcome becomes `:retry-cancelled` via the existing cancelled path
   (`provider_request_cancelled` event + cancelled retry-outcome), exactly as
   for any pending backoff. The `:retry-exhausted :deadline` give-up is emitted
   only when the truncated sleep runs to completion uninterrupted (reaches the
   deadline). This preserves the "cancelled immediately" constraint for the
   whole window, including its final truncated sleep.

   **Distinguishing the termination boundary.** Both count-cap and deadline
   exhaustion keep the existing single `:failure-reason :retry-exhausted`
   surface (so existing consumers of `:retry-exhausted` / `:exhausted?` are
   unchanged), but the give-up predicate also exposes which boundary fired via a
   new `:exhausted-reason` field — `:count-cap` or `:deadline` — carried on the
   retry-outcome map and the `provider_request_finished` event payload whenever
   `:failure-reason :retry-exhausted` is present. Telemetry and UI can therefore
   tell whether the window was bounded by time or by an explicit count cap.

   **Precedence when both boundaries hold.** The give-up predicate's ordered
   branches evaluate the count cap before the deadline (mirroring the existing
   `failure-reason-for` order: non-retryable → retry-disabled → count-cap →
   deadline). When a single failed attempt reaches both the count cap
   (`retry-attempt >= max-retries`) and the deadline (`now >= deadline`), the
   count-cap branch wins and `:exhausted-reason :count-cap` is reported. This is
   deterministic and is what a consumer observes on the retry-outcome /
   `provider_request_finished` event.

   The per-attempt backoff still comes from `exponential-backoff-ms` (2 s, 4 s,
   8 s, 16 s, 32 s, then capped at `:auto-retry-max-delay-ms` 60 s) or from
   `Retry-After` when present; the deadline only bounds the total window and
   truncates the *final* sleep to the remaining time — it does not shorten
   intermediate delays.

   **Canonical deadline state.** Record the deadline in canonical session state,
   not a runtime-local binding: when the retry window opens (the first retryable
   failure that schedules a retry), store `:retry-deadline-ms` as a **top-level
   session field** in the same `mark-active-retry!` session-update that marks the
   first retry active (via the established `apply-root-state-update-in!` /
   session-update path), and read it back the same way `:retry-attempt` is read
   at loop entry. This survives loop re-entry and follows the canonical-root vs
   runtime-handles state boundary (`doc/architecture.md`); the
   `agent-session-schema` gains the deadline field.

   **Threading the deadline across inter-attempt cleanup.** `clear-active-retry!`
   fires after *every* non-final retry sleep, not only at window close
   (turn-runtime/core.clj inter-attempt call at line ~643, inside
   `(when-not (= false (:provider-retry-sleep? ctx)) ...)`; final-path calls at
   ~571 success and ~611 give-up). The retry loop already carries `:retry-attempt`
   through its `recur` binding precisely because this mid-window clear resets
   canonical `:retry-attempt` to 0 each iteration — the deadline must survive the
   same inter-attempt cleanup, or the window-open detection would open a **fresh
   10-minute window on every attempt**, defeating the single-window budget. The
   deadline is therefore **threaded through the loop binding alongside
   `:retry-attempt`**: seeded once at loop entry from the canonical
   `:retry-deadline-ms` (`retry-deadline-for`, mirroring `retry-attempt-for`),
   computed on the window-opening failure, and carried across attempts via
   `recur`. The loop-bound value is the authoritative in-window deadline, so it
   cannot be recomputed or lost by the per-sleep `clear-active-retry!`. The
   canonical `:retry-deadline-ms` is still written once at window open (via the
   `mark-active-retry!` session-update) so a read-back at loop entry / re-entry
   sees it, but it is cleared **only on true window close** (see Clearing), not by
   the inter-attempt cleanup.

   **Window-open detection.** The give-up predicate's deadline input is ensured
   once per window, before the predicate runs: the loop-bound deadline is `nil`
   at the first retryable, retry-enabled failure (seeded from a canonical
   `:retry-deadline-ms` that is absent until a window opens), so that failure is
   the window-opening one — compute `deadline = now + :auto-retry-total-timeout-ms`
   (when the budget is active; see Approach 1 disable semantics) and thread it
   through the loop binding; otherwise reuse the threaded value. Because the
   window is keyed solely on the presence of the loop-bound deadline, a later
   turn starts a fresh window only after the previous window's canonical deadline
   was cleared on close (below).

   **Persistence at window open.** The deadline is computed **in memory** (using
   the injected clock) for the give-up predicate on every window-opening failure,
   but it is persisted to canonical session state only when the retry path
   actually runs — i.e. in the same `mark-active-retry!` session-update that
   schedules the first retry. A window-opening failure that immediately gives up
   on the count cap (a final, no retry scheduled, so `mark-active-retry!` never
   runs) computes the deadline in memory for the predicate's decision but writes
   nothing to session state; `clear-active-retry!` then runs as usual with no
   deadline to clear. This avoids a redundant canonical write that would be
   immediately cleared, and keeps the persistence colocated with the only
   consumer that needs it across attempts (the retry path's later predicate
   reads).

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
   `:provider-retry-abort-requested?`. The window-scoped deadline must be cleared
   **only on true window close** — the success clear (core.clj ~571), the
   final-give-up clear (~611), and the cancellation path (a cancelled retry ends
   the turn) — and must be **preserved by the inter-attempt (per-sleep) clear**
   (core.clj ~643), otherwise the first sleep would wipe it and the next failure
   would open a fresh window. To express this, `clear-active-retry!` /
   `retry-clear-needed?` gain a window-scoped distinction (e.g. a
   `:clear-deadline?` parameter, or a separate final-clear step): the per-sleep
   call preserves `:retry-deadline-ms` while the success / final-give-up / cancel
   calls clear it. A stale deadline is thereby never leaked into a later turn's
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

4. **Count remains an operator-set hard cap, not a default give-up source.** Keep
   `:auto-retry-max-retries` as an explicit upper bound on attempt count for
   callers who want a strict count limit, but it must no longer be the *default*
   give-up point. **The total-time budget is the effective default limiter for ALL
   per-attempt delays** — including provider-supplied `Retry-After` delays, which
   fully override the exponential backoff and are not bounded by
   `:auto-retry-max-delay-ms`. A fast `Retry-After` (e.g. 1 s) would otherwise let
   the attempt count reach a fixed default cap (say 20) at ~20 s — well inside the
   10-minute window — giving up with `:exhausted-reason :count-cap` instead of at
   the deadline, contradicting Goal/AC1 ("up to 10 minutes") regardless of
   provider delay. Consequently the count-cap branch fires only when the operator
   has **explicitly configured** `:auto-retry-max-retries` (a strict,
   behaviour-preserving hard cap for explicit small values) or when the total-time
   budget is disabled (count-only mode); with the budget active and no explicit
   override, the count-cap does not gate and the deadline alone bounds the window.
   The declared default value of `:auto-retry-max-retries` is raised from `3` to
   `20` as a nominal safety value, but it is **non-limiting while the total-time
   budget is active** — the earlier "never reached within the default window"
   rationale holds only for the exponential schedule and is superseded by the
   explicit-cap semantics above. This makes Goal/AC1 ("retries for up to a total
   of 10 minutes") hold regardless of the per-attempt delay source.

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

- Default config gives up on a retryable provider error (e.g. a 529 whose
  payload says "overloaded", 429, timeout)
  only after the retry window has elapsed **10 minutes total** of wall
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
  `clear-active-retry!` / `retry-clear-needed?` on true window close (success /
  final give-up / cancel) while being preserved by the inter-attempt (per-sleep)
  clear, so a stale deadline never leaks across turns and the window is not
  reset mid-window.
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
