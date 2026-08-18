# Design steps / follow-ups — 254 provider retry total time cap

## Architecture review 2026-08-18

- [ ] Keep the retry **termination decision single-sourced**: extend the existing
  count-cap decision (`failure-reason-for` in
  `components/turn-runtime/src/psi/turn_runtime/core.clj`) into one coherent
  give-up predicate that also evaluates the total-time deadline (count-cap and
  time-cap together), instead of adding a separate deadline check in the loop
  body before each retry sleep. Do not distribute the retry give-up rule across
  the loop and a separate decision site.
- [ ] Record the retry **deadline in canonical session state**, not only as a
  runtime-local binding. Store it alongside the existing canonical `:retry`
  metadata (e.g. with `:resume-at`) via the established
  `apply-root-state-update-in!` / session-update path, so it survives loop
  re-entry (the loop already re-reads `:retry-attempt` from session data) and
  follows the "canonical root vs runtime handles" state boundary
  (`doc/architecture.md`). Read it back the same way `retry-attempt` is read at
  loop entry.

## Ambiguity review 2026-08-18

- [ ] **Pin the default count-cap value/semantics.** Approach 4 defers the decision
  ("Decide in plan whether the default should be raised … or dropped"), but
  Acceptance 1 requires the *default* config to give up only after 10 minutes.
  If `:auto-retry-max-retries` stays 3, the count cap fires at ~14 s and AC1
  fails; the design must state the default-path count-cap value (or that the
  count cap is non-limiting by default) for AC1 to hold.
- [ ] **State whether the total-time budget bounds an in-flight attempt.** The
  deadline check is specified only "before each retry sleep", so a single
  attempt whose request execution itself runs past the deadline is not cut off.
  Clarify whether "up to a total of 10 minutes" bounds an individual in-flight
  attempt, or only the loop's inter-attempt give-up points.
- [ ] **Define the loop ordering of the deadline decision relative to delay
  computation.** An oversized `Retry-After` delay is only known after
  `retry-metadata-for` runs (which is after the failure decision), yet the
  design's "next scheduled sleep would push past it" check and its
  `:retry-exhausted` mapping for an oversized `Retry-After` (Approach 5) imply
  the check sees that delay. Specify where the total-time check sits relative to
  `failure-reason-for` / `retry-metadata-for` so the single-source termination
  predicate (architecture step 1) can account for the actual next delay.
- [ ] **Specify the sleep seam for `Retry-After` tests.** A `Retry-After` delay is
  provider-supplied, not shrinkable via config, so "injected clock and small
  config values" cannot shorten the wait for that path. Drive the wait through
  the existing `:provider-retry-sleep-fn` injectable seam (used by current retry
  tests) so the total-time-with-`Retry-After` acceptance case stays deterministic
  without real sleeps.
