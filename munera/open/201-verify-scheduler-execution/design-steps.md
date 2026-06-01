# Design follow-up steps

## Ambiguity review (2026-06-01)

- [ ] Define the drain-on-idle trigger for message-kind: specify which
      event/effect (`:scheduler/drain-queue` via on-abort / workflow-terminal)
      the verification test uses to drive drain deterministically.
- [ ] Define "cancel racing the timer": enumerate the distinct races (cancel
      before callback dispatch vs schedule already past `:pending`) and the
      expected outcome of each.
- [ ] Enumerate all timer/cancel seams tests may use beyond
      `:scheduler-run-after-delay-fn` (e.g. `:scheduler-cancel-delay-fn`,
      `:scheduler-timers*`, `:daemon-thread-fn`) in Key concepts.
- [ ] Name the context-shutdown surface under test (entry point such as
      `cancel-all-scheduler-timers!`) and the observable assertion
      (timer-handle count / no fire after shutdown).
- [ ] Specify expected behaviour for `:at` instants that are in the past or
      resolve below `min-delay-ms`, and whether `:at` is bounds-validated.
- [ ] Clarify what "real effect/dispatch round trip" means operationally: do
      live tests drive effects synchronously or via the real executor, given the
      handler-purity / runtime-owned-deliver frontier.
- [ ] Specify the findings-inventory artifact: its file location and required
      structure (per-area verified/defect + reproduction notes + task ref).
- [ ] Decide whether defect remediation tasks must be created as munera dirs in
      this task or only described, given the verification-only framing.
