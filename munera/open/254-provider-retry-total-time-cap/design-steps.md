# Design steps / follow-ups — 254 provider retry total time cap

## Architecture review 2026-08-18

- [x] Keep the retry **termination decision single-sourced**: extend the existing
  count-cap decision (`failure-reason-for` in
  `components/turn-runtime/src/psi/turn_runtime/core.clj`) into one coherent
  give-up predicate that also evaluates the total-time deadline (count-cap and
  time-cap together), instead of adding a separate deadline check in the loop
  body before each retry sleep. Do not distribute the retry give-up rule across
  the loop and a separate decision site.
- [x] Record the retry **deadline in canonical session state**, not only as a
  runtime-local binding. Store it alongside the existing canonical `:retry`
  metadata (e.g. with `:resume-at`) via the established
  `apply-root-state-update-in!` / session-update path, so it survives loop
  re-entry (the loop already re-reads `:retry-attempt` from session data) and
  follows the "canonical root vs runtime handles" state boundary
  (`doc/architecture.md`). Read it back the same way `retry-attempt` is read at
  loop entry.

## Ambiguity review 2026-08-18

- [x] **Pin the default count-cap value/semantics.** Approach 4 defers the decision
  ("Decide in plan whether the default should be raised … or dropped"), but
  Acceptance 1 requires the *default* config to give up only after 10 minutes.
  If `:auto-retry-max-retries` stays 3, the count cap fires at ~14 s and AC1
  fails; the design must state the default-path count-cap value (or that the
  count cap is non-limiting by default) for AC1 to hold.
- [x] **State whether the total-time budget bounds an in-flight attempt.** The
  deadline check is specified only "before each retry sleep", so a single
  attempt whose request execution itself runs past the deadline is not cut off.
  Clarify whether "up to a total of 10 minutes" bounds an individual in-flight
  attempt, or only the loop's inter-attempt give-up points.
- [x] **Define the loop ordering of the deadline decision relative to delay
  computation.** An oversized `Retry-After` delay is only known after
  `retry-metadata-for` runs (which is after the failure decision), yet the
  design's "next scheduled sleep would push past it" check and its
  `:retry-exhausted` mapping for an oversized `Retry-After` (Approach 5) imply
  the check sees that delay. Specify where the total-time check sits relative to
  `failure-reason-for` / `retry-metadata-for` so the single-source termination
  predicate (architecture step 1) can account for the actual next delay.
- [x] **Specify the sleep seam for `Retry-After` tests.** A `Retry-After` delay is
  provider-supplied, not shrinkable via config, so "injected clock and small
  config values" cannot shorten the wait for that path. Drive the wait through
  the existing `:provider-retry-sleep-fn` injectable seam (used by current retry
  tests) so the total-time-with-`Retry-After` acceptance case stays deterministic
  without real sleeps.

## Inconsistency review 2026-08-18

- [x] **Reconcile the 10-minute window anchor with the "retrying" framing.** Goal
  and AC1 frame the budget as "10 minutes of retrying / retry window", but
  Approach 2 anchors the deadline at "first-attempt start". That includes the
  failing request's own execution time (so a slow-failing attempt shortens the
  retry budget below 10 min) and is ambiguous between the *initial* attempt and
  the *first retry* attempt. Define the anchor precisely (initial-attempt start
  vs first-retry-decision time) and make Goal/AC1/Approach agree on whether the
  window is 10 min of retrying or 10 min from the initial attempt start.
- [x] **Reconcile stop-before-deadline with AC1's "only after 10 minutes".**
  Approach 2 stops with `:retry-exhausted` when the next scheduled sleep would
  push past the deadline (so give-up can occur *before* 10 min elapses, e.g.
  ~9:40 with the 60 s pin), while AC1 states the default "gives up ... only after
  the retry window has elapsed 10 minutes total". State which holds: sleep only
  the remaining portion to reach the deadline exactly and give up at 10 min, or
  stop when the next full sleep overshoots (and reword AC1 accordingly).

## Ambiguity re-review 2026-08-18 (post-follow-up)

- [ ] **Define the deadline lifecycle: window-open detection and clearing.** The
  design stores `:retry-deadline-ms` in canonical session state "when the retry
  window opens (the first retryable failure)" and reads it back at loop entry,
  but never specifies how the single give-up predicate distinguishes the
  *window-opening* failure (compute + store the deadline) from *subsequent*
  failures in the same window (reuse the stored deadline). Also specify where
  the deadline is cleared: `clear-active-retry!` currently clears
  `:retry-attempt`, `:retry`, and `:provider-retry-abort-requested?` (all
  session-scoped canonical state) on success/give-up/cancel — if
  `:retry-deadline-ms` is not cleared with them, a stale deadline persists in
  session state and a later turn's first retry could inherit a truncated window
  (the retry loop is per `execute-prepared-request!`, but the state it reads is
  session-scoped). State whether the deadline is cleared by `clear-active-retry!`
  and how a fresh window is anchored on a later turn.
- [ ] **Specify whether the retry outcome/telemetry distinguishes count-cap vs
  deadline exhaustion.** Both "deadline reached" and "count cap reached" map to
  `:failure-reason :retry-exhausted` with `:exhausted? true` (Approach 2), so a
  consumer of the retry-outcome / `provider_request_finished` event cannot tell
  which boundary terminated the window. Clarify whether the single
  `:retry-exhausted` reason is intended to suffice for both, or whether a
  distinguishing field (e.g. `:exhausted-reason :count-cap | :deadline`) is
  added to the outcome/event payload.
- [ ] **State the disable semantics of the total-time budget (or confirm it is
  always active).** The design adds `:auto-retry-total-timeout-ms` (default
  600000) as the primary default limiter and keeps `:auto-retry-max-retries` as a
  secondary hard cap, but does not specify whether a value of the new key can
  disable the time budget (0? negative? nil?), nor whether the budget is simply
  always active once the key exists. Confirm the config contract: is a
  strict count-only mode (time budget off, count cap on, for large count values)
  expressible, or is the total-time window unconditionally active by default?

## Inconsistency re-review 2026-08-18 (post-follow-up)

- [ ] **Reconcile the truncated final sleep with the recorded/emitted delay.** The
  design truncates the final sleep to the remaining window ("sleep the remaining
  portion (deadline - now)") for both the exponential and oversized-`Retry-After`
  paths (Approach 2/5), but never says whether the truncated sleep amount is
  reflected in the canonical `:retry` metadata (`:delay-ms`/`:resume-at`) stored
  by `mark-active-retry!` or in the `provider_retry_scheduled` event payload
  (`:delay-ms`/`:resume-at`, core.clj:623-636). Today both are written from the
  full `retry-metadata` (full exponential or `Retry-After` delay) while
  `sleep-for-retry!` sleeps that full `:delay-ms` (core.clj:641). Under
  truncation the actual sleep would be shorter than the recorded/emitted
  `:delay-ms`/`:resume-at`, so a consumer (progress `:retry-updated`, UI,
  telemetry) would see a resume time that does not match when the retry actually
  resumes. Specify whether the recorded/emitted delay is the full computed delay
  or the truncated remaining-window delay, and how `:resume-at` is adjusted.
- [ ] **Pin the schema placement of the deadline field.** Approach 2 says store
  `:retry-deadline-ms` "alongside the existing canonical `:retry` metadata (with
  `:resume-at`)" and "the `agent-session-schema` gains the deadline field", but
  does not state whether `:retry-deadline-ms` is a **top-level** session field
  (sibling of `:retry`) or a key **inside** the `:retry` map next to `:resume-at`.
  The placement determines the `agent-session-schema` shape, the
  `clear-active-retry!` / `retry-clear-needed?` clearing logic (which must include
  it, per ambiguity step 1), and the loop read-back — pin it explicitly so the
  schema edit, the clearing path, and the predicate's read agree.
