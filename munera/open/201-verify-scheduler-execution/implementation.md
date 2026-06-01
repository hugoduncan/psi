# Implementation notes

## Design review — ambiguities (2026-06-01)

Reviewed design.md against scheduler.clj, dispatch_handlers/scheduler.clj,
dispatch_effects.clj, statechart_actions.clj, psi_tool_scheduler.clj, and
doc/scheduler.md. Found 8 actionable ambiguities:

1. drain-on-idle trigger undefined — drain is driven by `:scheduler/drain-queue`
   effect emitted from statechart actions (on-abort / workflow-terminal), not an
   idle detector. Design must say what event drives drain in tests.
2. "cancel racing the timer" undefined — ≥2 distinct races (cancel before
   callback dispatch vs `:scheduler/fired` already past `:pending`); expected
   outcome per race unspecified (fire-schedule throws on non-pending).
3. timer seam coverage incomplete — Key concepts name only
   `:scheduler-run-after-delay-fn`, but deterministic cancel needs
   `:scheduler-cancel-delay-fn` / `:scheduler-timers*` / `:daemon-thread-fn`.
4. "context shutdown" surface unspecified — no named entry point
   (`cancel-all-scheduler-timers!`?); ambiguous testable surface.
5. `:at` past/sub-min-delay outcome unspecified — doc says past `:at` fires
   immediately, but `:delay-ms` enforces min 1000ms; expected `:at` behaviour
   below min / in past not stated.
6. "real effect/dispatch round trip" + "no wall-clock sleeps" — unclear whether
   live test drives effects synchronously or via real executor given handler
   purity frontier and runtime-owned deliver effects.
7. findings-inventory artifact unspecified — location (task dir? implementation.md?
   new doc?) and required structure not defined.
8. remediation-task reference: whether defect tasks must be created (munera dirs)
   or only described in this verification-only task is ambiguous.

## Ambiguity follow-up execution (2026-06-01)

All 8 ambiguity-review design-steps resolved against scheduler source
(no blockers; everything decidable from current behaviour). design.md
"Verification mechanics (resolved 2026-06-01)" section + expanded Key-concepts
timer-seam list added:

1. drain trigger → explicit `:scheduler/drain-queue` dispatch event (no idle
   detector); test fires-while-busy → queued → set idle → dispatch drain.
2. cancel/timer races → (a) cancel-before-callback (stale callback hits
   `fire-schedule` non-`:pending` throw; cancel wins), (b) `:queued`→cancel
   deliverable race (`:cancelled`, dequeued); terminal-status cancel throws
   "not cancellable".
3. seams → `:scheduler-run-after-delay-fn`, `:scheduler-cancel-delay-fn`,
   `:scheduler-timers*`, `:daemon-thread-fn` (grounded in dispatch_effects.clj).
4. shutdown surface → `context/shutdown-context!` +
   `dispatch-effects/cancel-all-scheduler-timers!`; assert
   `scheduler-timer-handle-count`=0, `:scheduler-timers*` empty, no post-shutdown fire.
5. `:at` bounds → past/now → delay 0, no min check, fires immediately;
   future <1000ms → `validate-delay-ms!` throws below-min; >24h → exceeds-max
   (grounded in psi_tool_scheduler/resolve-fire-time!). Asymmetry recorded as
   current behaviour / potential finding.
6. round trip → real dispatch+effect executor run synchronously; only
   time/delay boundary replaced via seams; handlers/deliver effects unchanged.
   "No wall-clock sleeps" = invoke captured callback directly.
7. findings artifact → `findings.md` in task dir, one section per Scope area,
   entries {status, summary, covering test ns+deftest, repro+task-ref for defects}.
8. remediation policy → describe in findings.md; create `munera/open/NNN-slug`
   only when a defect is actually found (failing repro stays in that new task).
