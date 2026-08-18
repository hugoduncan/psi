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

- [x] **Reconcile the window-scoped deadline with the inter-attempt `clear-active-retry!`.**
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

- [x] **Specify the event/state semantics of routing the truncated final sleep through the
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
- [x] **Pin the truncated final sleep's behaviour under `:provider-retry-sleep? false` (test
  mode).** The current non-final path clears retry state after a sleep only when sleeping is not
  disabled: `(when-not (= false (:provider-retry-sleep? ctx)) (clear-active-retry! ...))`
  (turn-runtime/core.clj:642-643), and tests use this flag to skip real sleeps. Approach 2's
  final-sleep outcome "sleeps the truncated remainder ... then finalize[s] with
  `:retry-exhausted :deadline` (clear + execution-result)". With the sleep seam disabled, specify
  whether the truncated delay is still recorded/emitted (`mark-active-retry!` +
  `provider_retry_scheduled`) and whether `clear-active-retry!` still runs on finalize despite
  the existing test-mode skip — so deadline-window tests drive deterministically without real
  sleeps and without leaking stale retry state.
- [x] **State `:exhausted-reason` precedence when count-cap and deadline fire on the same
  attempt.** The give-up predicate's ordered branches report `:count-cap` when
  `retry-attempt >= max-retries` and `:deadline` when `now >= deadline`. With an operator-set
  small `:auto-retry-max-retries` (count cap reached) and an elapsed window (deadline reached),
  both conditions can hold at one failed attempt. The design does not state which
  `:exhausted-reason` is reported when both are true (branch order implies `:count-cap` wins).
  Pin the precedence so `:exhausted-reason` reporting on the retry-outcome /
  `provider_request_finished` event is deterministic.

## Inconsistency review 2026-08-18 (current session, third turn)

- [x] **Reconcile the default count-cap rationale with `Retry-After` overriding the backoff.**
  Approach 4 raises `:auto-retry-max-retries` from 3 to 20 because "default backoff reaches ~14
  attempts in 10 minutes" — a value "never reached within the default 10-minute window". That
  rationale assumes the exponential schedule (2,4,8,16,32, then 60 s). But `retry-metadata`
  (session-state/model.clj:571-572) computes `delay-ms (or retry-after-ms exponential-delay-ms)`:
  a `Retry-After` **fully overrides** the exponential delay and is **not** bounded by
  `:auto-retry-max-delay-ms`. Approach 5 confirms `Retry-After` is respected per attempt and
  "provider-supplied, not config-shrinkable". A provider sending short `Retry-After` delays
  (e.g. 1 s) lets the attempt count climb far faster than the exponential schedule, so
  `max-retries=20` can be reached well inside the 10-minute window — giving up with
  `:exhausted-reason :count-cap` at ~20 s instead of at the deadline — contradicting the
  Goal/AC1 "retries for up to a total of 10 minutes" and Approach 4's "never reached within the
  default window" claim. State whether the total-time budget is the effective default limiter for
  ALL per-attempt delays (count-cap must not prematurely fire under fast `Retry-After`), or
  whether the "10 minutes" goal is explicitly qualified for the `Retry-After` case — so the
  default count-cap value and the Goal/AC1 "up to 10 minutes" agree regardless of provider delay.

## Ambiguity review 2026-08-18 (current session, second turn — post-follow-up design state)

- [x] **Pin how the give-up predicate detects "explicitly configured" `:auto-retry-max-retries`.**
  Approach 4 makes the count-cap branch fire "only when the operator has **explicitly configured**
  `:auto-retry-max-retries`", but the config resolution path never preserves that distinction:
  `default-config` always supplies the key (3 today, raised to 20), and turn-runtime reads it via a
  plain `(get-in ctx [:config :auto-retry-max-retries] 3)` (core.clj:546) — so the predicate cannot
  tell a default 20 from an operator-set 20. Specify the mechanism that carries "explicitly set"
  through to the predicate (e.g. key presence in the effective config layer above default-config, a
  `count-cap-set?` / explicitness signal plumbed into ctx `:config`, or a sentinel default that
  reads as "unset"), so behavior-preserving explicit small caps still gate while the default cannot
  prematurely fire under fast `Retry-After` (prior inconsistency step).
- [x] **Pin the count-only-mode default behavior when the total-time budget is disabled.**
  Approach 1's disable semantics make `:auto-retry-max-retries` the sole limiter when
  `:auto-retry-total-timeout-ms` is nil/absent/<= 0. With the default `:auto-retry-max-retries`
  raised from 3 to 20 (Approach 4) as a "nominal safety value" for the budget-active case, an
  operator who disables the budget without setting an explicit cap now gets **20 attempts** instead
  of the prior count-only default of **3** — a silent behavior change to the count-only path that the
  design never acknowledges. State whether count-only mode with defaults should yield 20 attempts
  (the raised default, consistent with the new nominal value) or preserve a smaller count-only
  default, so operators relying on count-only defaults are not surprised.

## Inconsistency review 2026-08-18 (current session, third turn — post-follow-up design state)

- [x] **Reconcile the raise of the default `:auto-retry-max-retries` to 20 with the explicit-only
  count-cap gating.** Approach 4 raises the default from 3 to 20 "as a nominal safety value"
  justified by the budget-active window ("never reached within the default window"). But Approach 4
  also gates the count-cap branch to fire **only when the operator explicitly configures**
  `:auto-retry-max-retries` (or the budget is disabled). Under that gating the default value is
  **never a limiter in the budget-active path** (the deadline alone bounds), so the raise's stated
  purpose is vacuous there. The raise's only real effect lands in count-only mode (budget disabled),
  where the raised default 20 becomes the sole give-up limiter — a silent behavior change from the
  prior count-only default of 3 (also the subject of ambiguity step 2 this session). State the
  raise's actual purpose: is the default 20 intended to bound count-only mode (accept the 3→20
  change), or is it meant only as an unused nominal fallback (and if so, why raise it at all)? The
  "nominal safety value, non-limiting" framing conflicts with the raise's real count-only effect.

## Ambiguity review 2026-08-18 (second turn, current design state)

- [x] **Pin the loop-entry behavior for a persisted `:retry-deadline-ms` that is already in the past.**
  The Clearing section clears the deadline only on the three retry-loop terminal paths — success,
  final-give-up, cancel — and the Window-open detection keys the fresh-window decision solely on
  the **presence** of the loop-bound deadline ("a later turn starts a fresh window only after the
  previous window's canonical deadline was cleared on close"). But the deadline is canonical
  session state (top-level field, threaded through the loop binding, re-seeded at loop entry), so a
  window can be left open by a turn-end path **outside** those three clears: an external abort of an
  in-flight request that isn't the retry-loop cancel path (the budget deliberately does not abort
  in-flight requests), or a session persisted mid-window and rehydrated after the deadline has
  passed (process death / close). In all those cases `:retry-deadline-ms` is present-but-expired at
  the next loop entry. With presence-only detection the loop seeds a non-nil past deadline, so no
  fresh window is computed and the first retryable failure immediately hits `now >= deadline` →
  `:retry-exhausted :deadline` with **zero actual retries** — an instant give-up that contradicts
  the "A stale deadline is thereby never leaked into a later turn's first retry" guarantee. State
  whether a past deadline at loop entry is treated as stale (clear it and open a fresh 10-minute
  window) or as authoritative (immediate `:deadline` give-up), and enumerate which turn-end /
  rehydration paths must also clear the deadline so an expired value cannot strand a later turn's
  first retryable failure.

## Inconsistency review 2026-08-18 (third turn, current design state)

- [x] **Reconcile Approach 4's "fixed default cap at ~20 s" rationale with the sentinel-`nil` default.**
  Approach 4 motivates the explicit-only count-cap gating with: "A fast `Retry-After` (e.g. 1 s)
  would otherwise let the attempt count reach a **fixed default cap at ~20 s** ... giving up with
  `:exhausted-reason :count-cap` instead of at the deadline". That illustration is a vestige of the
  dropped raise-to-20: under the current design the default `:auto-retry-max-retries` is the sentinel
  `nil` (no cap), so there **is no fixed default count cap** to reach in the budget-active path — and
  if a hypothetical fixed default existed it would be the old 3 (reached at ~3 s under 1 s `Retry-After`),
  not 20 (~20 s). The "~20 s / fixed default cap" premise only corresponds to the raised-20 default the
  design explicitly dropped, and a reader of Approach 4 could reasonably conclude a nominal ~20 default
  cap still exists somewhere. Reword the rationale to refer to the counterfactual correctly (e.g. "would
  otherwise reach the old fixed default cap in seconds" or state plainly that there is no default count
  cap under the sentinel, so the count-cap can never prematurely fire), so the illustration matches the
  sentinel-`nil` default-config it is part of.

## Ambiguity review 2026-08-18 (current session, second turn)

- [x] **Pin what `:max-retries` reports in the retry-outcome and `provider_request_finished` event under the sentinel-`nil` default.**
  Both the retry-outcome map (turn-runtime/core.clj:546, assembled at :590-604) and the `provider_request_finished` event (:599-616) carry a `:max-retries` field, today always the value of `(long (get-in ctx [:config :auto-retry-max-retries] 3))` — i.e. always 3 for the default config. Approach 4 changes the default-config value to the sentinel `nil` ("unset"), and the give-up predicate resolves the count cap as `nil` → no cap (budget-active) or `(or explicit-cap count-only-default 3)` (budget-disabled). But the design never states what the **reported** `:max-retries` field holds on those two surfaces when the effective config is `nil` — the budget-active default path, where the count cap does not gate. An implementer must pick: report `nil` (accurate — no count limiter; but a consumer that previously always saw 3 now sees nil), report the count-only fallback `3` (only meaningful when the budget is disabled — misleading in the budget-active case), or omit the field (breaks the existing surface shape). Also note the value reported in budget-disabled count-only mode (no explicit cap) — should it be the count-only fallback 3 (the actual limiter) or nil? Pin the reported value(s) for each mode so the consumer contract (`:max-retries` on retry-outcome / `provider_request_finished`) is deterministic and telemetry that keys on it is not silently changed from 3 to nil without acknowledgment.

## Inconsistency review 2026-08-18 (current session, third turn)

- [x] **Reconcile the cancel path's deadline clearing with the per-sleep clear that it reuses (and the test-mode skip).**
  The Clearing section (design.md:280-290) lists the "cancellation path (a cancelled retry ends the turn)" as one of the three true-window-close calls that clear `:retry-deadline-ms` via `clear-active-retry!` (a `:clear-deadline? true` call), while the per-sleep inter-attempt clear preserves it. But in the code the cancel path (turn-runtime/core.clj:645-657) has **no independent `clear-active-retry!` call** — it relies on the inter-attempt per-sleep clear at core.clj:642-643 (the `(when-not (= false (:provider-retry-sleep? ctx)) (clear-active-retry! ...))` before the `if cancelled?`), which the design re-purposes to **preserve** the deadline (`:clear-deadline? false`) and which is **skipped entirely** under `:provider-retry-sleep? false`. So following the design literally: (a) on a normal (non-test) cancel, the only clear that runs is the per-sleep one preserving the deadline → the deadline is never cleared on cancel, leaking into the next turn's window-open detection; (b) in test mode (`:provider-retry-sleep? false`) the per-sleep clear is skipped outright, so even `:retry`/`:retry-attempt`/`:retry-deadline-ms` all leak. The design explicitly calls out the truncated-final **finalize** clear as running unconditionally and independent of the `:provider-retry-sleep?` skip (design.md:146-156, "Truncated final under test mode"), but does not give the cancel path the same treatment. State that the cancel path must have its **own** unconditional `clear-active-retry!` (`:clear-deadline? true`) call independent of the per-sleep clear / test-mode skip — or otherwise pin how the deadline is cleared on cancel, so the "stale deadline never leaked" guarantee holds for the cancel path in both modes.

## Ambiguity review 2026-08-18 (plan review, first turn)

- [ ] **Pin the minimum per-attempt delay when a provider-sent `Retry-After` is non-positive (integer 0 or negative).**
  Plan.md's `Retry-After` handling (Approach 5 / test 5) covers only an *oversized* delay truncated to the deadline; it does not address a non-positive integer `Retry-After`. In `retry-after-delay-ms` (session-state/model.clj:539-551) the integer branch (`* 1000 (Long/parseLong raw)`) has **no positive floor**, so `Retry-After: 0` yields `delay-ms 0` (and a negative integer yields a negative delay) — `(or 0 exponential-delay-ms)` keeps 0. Under the new budget-active default (count-cap nil) `give-up-decision` returns the retry branch (no deadline/overshoot hit), and `sleep-for-retry!` skips the wait (its `pos?` guard), so the loop retries back-to-back until the 10-minute deadline — a behavior change from the pre-change default count cap 3, which bounded the same 0-delay loop to 3 attempts. Pin whether a non-positive `Retry-After` is floored to the exponential backoff (as the RFC-date branch already does for ≤0) or accepted as immediate-retry-until-deadline, and add a test for the budget-active default with a zero/negative `Retry-After`.
