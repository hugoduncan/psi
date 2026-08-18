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
