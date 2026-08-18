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

- [x] **Define the deadline lifecycle: window-open detection and clearing.** The
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
- [x] **Specify whether the retry outcome/telemetry distinguishes count-cap vs
  deadline exhaustion.** Both "deadline reached" and "count cap reached" map to
  `:failure-reason :retry-exhausted` with `:exhausted? true` (Approach 2), so a
  consumer of the retry-outcome / `provider_request_finished` event cannot tell
  which boundary terminated the window. Clarify whether the single
  `:retry-exhausted` reason is intended to suffice for both, or whether a
  distinguishing field (e.g. `:exhausted-reason :count-cap | :deadline`) is
  added to the outcome/event payload.
- [x] **State the disable semantics of the total-time budget (or confirm it is
  always active).** The design adds `:auto-retry-total-timeout-ms` (default
  600000) as the primary default limiter and keeps `:auto-retry-max-retries` as a
  secondary hard cap, but does not specify whether a value of the new key can
  disable the time budget (0? negative? nil?), nor whether the budget is simply
  always active once the key exists. Confirm the config contract: is a
  strict count-only mode (time budget off, count cap on, for large count values)
  expressible, or is the total-time window unconditionally active by default?

## Inconsistency re-review 2026-08-18 (post-follow-up)

- [x] **Reconcile the truncated final sleep with the recorded/emitted delay.** The
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
- [x] **Pin the schema placement of the deadline field.** Approach 2 says store
  `:retry-deadline-ms` "alongside the existing canonical `:retry` metadata (with
  `:resume-at`)" and "the `agent-session-schema` gains the deadline field", but
  does not state whether `:retry-deadline-ms` is a **top-level** session field
  (sibling of `:retry`) or a key **inside** the `:retry` map next to `:resume-at`.
  The placement determines the `agent-session-schema` shape, the
  `clear-active-retry!` / `retry-clear-needed?` clearing logic (which must include
  it, per ambiguity step 1), and the loop read-back — pin it explicitly so the
  schema edit, the clearing path, and the predicate's read agree.

## Ambiguity review 2026-08-18 (current session, second turn)

- [x] **Specify how the truncated final sleep is scheduled through the single
  give-up predicate.** Approach 2's overshoot branch ("next full delay would
  overshoot → sleep the remaining portion (deadline − now) and then give up
  `:retry-exhausted` / `:exhausted-reason :deadline`") and the immediate "now ≥
  deadline" branch both produce the same terminal signal
  (`:retry-exhausted` / `:exhausted-reason :deadline`), yet only the overshoot
  case must actually *sleep* the truncated remainder and record/emit the
  truncated delay. But per the loop flow (turn-runtime/core.clj:614-641),
  `mark-active-retry!` and the `provider_retry_scheduled` event run only on the
  **non-final** retry path; a `:retry-exhausted` final goes straight to
  `clear-active-retry!` + `execution-result` with no sleep and no truncated
  recording. The resolved inconsistency step pinned *what* delay is recorded, but
  not *how the loop performs the truncated sleep and records/emits its truncated
  delay* when the predicate returns the same uniform terminal as the immediate
  deadline case. Pin whether the overshoot case is a distinct non-final outcome
  that carries the truncated delay through the retry path, or the final branch
  itself sleeps the remainder before returning — and how the loop distinguishes
  "finalize now (no sleep)" from "sleep remainder, record truncated delay, then
  finalize".
- [x] **State the outcome when cancellation interrupts the truncated final
  sleep.** Approach 2's overshoot branch gives up with
  `:retry-exhausted :deadline` after sleeping the remaining window, and that
  truncated sleep uses the interruptible seam (`interruptible-sleep-for-retry!`
  / `:provider-retry-sleep-fn`), which the current loop treats as cancellable
  (`sleep-for-retry!` → `:retry-cancelled`, turn-runtime/core.clj:637-657). If a
  cancel (active-turn abort or `:provider-retry-abort-requested?`) arrives during
  that truncated final sleep, does the outcome become `:retry-cancelled`
  (consistent with the cancellation constraint and the existing cancelled path)
  or stay `:retry-exhausted :deadline` (as the overshoot branch states)? The
  "cancelled immediately" constraint and the hardcoded `:deadline` give-up can
  conflict here; specify the precedence.
- [x] **Pin whether the window-opening deadline is persisted when the window
  opens on an immediate count-cap give-up.** Approach 2's "ensure the deadline is
  present" step says the window-opening failure "compute[s] and persist[s]" the
  deadline, while also stating it persists "in the same session-update that marks
  the first retry active" — which a window-opening failure that immediately gives
  up on the count cap (never schedules a retry, so `mark-active-retry!` never
  runs) does not do. Specify whether the deadline is (a) computed in memory only
  for the predicate and persisted solely with `mark-active-retry!` (a count-cap
  give-up at window-open writes nothing), or (b) persisted by the ensure-step
  regardless (a redundant canonical write immediately cleared by
  `clear-active-retry!`).

## Inconsistency review 2026-08-18 (current session, third turn)

- [x] **Correct the "529 overloaded maps to `:overloaded`" classification claim.**
  Context states "529 overloaded maps to `:overloaded`", but
  `provider-error-kind` in
  `components/session-state/src/psi/session_state/model.clj` maps an HTTP 529
  status to `:provider-unavailable` (the provider-unavailable branch checks
  `(contains? #{500 502 503 529} http-status)`), while `:overloaded` is produced
  only when the error *message* matches `overloaded-error-patterns`
  (`#"(?i)overloaded"`), which is checked before the status-based
  provider-unavailable branch. So a bare 529 with no "overloaded" message yields
  `:provider-unavailable`, not `:overloaded`. Both kinds are in the retryable
  set, so retry behavior is unaffected, but the blanket "529 overloaded →
  `:overloaded`" framing in Context (echoed in the Goal/Acceptance "529
  overloaded" examples) is inaccurate and could mislead an implementer or a test
  asserting `:error-kind :overloaded` for a 529 stub. Correct the mapping (e.g.
  "529 → `:provider-unavailable`, with `:overloaded` only when the payload says
  'overloaded'") or qualify it so design.md and the code agree.

## Architecture review 2026-08-18 (current session, first turn)

- [ ] **Reconcile the window-scoped deadline with the inter-attempt `clear-active-retry!`.**
  `clear-active-retry!` runs after **every** non-final retry sleep, not only at window
  close (turn-runtime/core.clj non-final path: `(when-not (= false (:provider-retry-sleep? ctx))
  (clear-active-retry! ...))`, line ~643; final-path calls at ~571 and ~611). The design's
  Clearing section says to add `:retry-deadline-ms` to `clear-active-retry!`/`retry-clear-needed?`
  so it is "cleared once on window close" — but if `clear-active-retry!` clears it, the deadline
  is wiped after the first sleep, so on the next failed attempt the give-up predicate's window-open
  detection (`:retry-deadline-ms` absent → compute `now + :auto-retry-total-timeout-ms`) opens a
  **fresh 10-minute window on every attempt**, defeating the single-window budget. Note the loop
  carries `:retry-attempt` via its `recur` binding precisely because the same mid-window clear
  resets it to 0 in canonical state. Decide how the deadline survives inter-attempt cleanup:
  thread it through the loop binding alongside `:retry-attempt` (and clear canonical
  `:retry-deadline-ms` only on true final close / turn end), or split a window-scoped clear out of
  the per-sleep cleanup. The design must state how the deadline is not recomputed at each attempt
  given `clear-active-retry!` fires mid-window.

## Ambiguity review 2026-08-18 (current session, second turn)

- [ ] **Specify the event/state semantics of routing the truncated final sleep through the
  non-final retry path.** Approach 2's final-sleep outcome routes the loop through the retry
  path "exactly once so the truncated delay is recorded and emitted": `mark-active-retry!` +
  `provider_retry_scheduled` run with the truncated `:delay-ms`/`:resume-at`, then the loop
  finalizes `:retry-exhausted :deadline` (clear + execution-result) instead of recursing. Today
  `mark-active-retry!` + `provider_retry_scheduled` run only on the non-final path where a real
  retry will be attempted (turn-runtime/core.clj:633-641). Reusing them for a final means a
  consumer of `provider_retry_scheduled` / progress `:retry-updated` (UI, telemetry) sees
  "retry scheduled / active retry, resuming at :resume-at" when in fact the window is exhausted
  and the turn is about to fail with `:retry-exhausted`. It also transiently sets canonical
  `:retry-attempt` to `next-attempt` (one more than actually attempted) and `:retry :active? true`
  during the final sleep, before clear resets them. State whether reusing the non-final
  emit/mark for the truncated final is intended (and what a consumer should read), or whether the
  truncated final wait should be surfaced distinctly (e.g. the `:exhausted-reason :deadline` /
  final status taking precedence over the "scheduled" signal), so `provider_retry_scheduled` is
  not asserted for a retry that never resumes.
- [ ] **Pin the truncated final sleep's behaviour under `:provider-retry-sleep? false` (test
  mode).** The current non-final path clears retry state after a sleep only when sleeping is not
  disabled: `(when-not (= false (:provider-retry-sleep? ctx)) (clear-active-retry! ...))`
  (turn-runtime/core.clj:642-643), and tests use this flag to skip real sleeps. Approach 2's
  final-sleep outcome "sleeps the truncated remainder ... then finalize[s] with
  `:retry-exhausted :deadline` (clear + execution-result)". With the sleep seam disabled, specify
  whether the truncated delay is still recorded/emitted (`mark-active-retry!` +
  `provider_retry_scheduled`) and whether `clear-active-retry!` still runs on finalize despite
  the existing test-mode skip — so deadline-window tests drive deterministically without real
  sleeps and without leaking stale retry state.
- [ ] **State `:exhausted-reason` precedence when count-cap and deadline fire on the same
  attempt.** The give-up predicate's ordered branches report `:count-cap` when
  `retry-attempt >= max-retries` and `:deadline` when `now >= deadline`. With an operator-set
  small `:auto-retry-max-retries` (count cap reached) and an elapsed window (deadline reached),
  both conditions can hold at one failed attempt. The design does not state which
  `:exhausted-reason` is reported when both are true (branch order implies `:count-cap` wins).
  Pin the precedence so `:exhausted-reason` reporting on the retry-outcome /
  `provider_request_finished` event is deterministic.
