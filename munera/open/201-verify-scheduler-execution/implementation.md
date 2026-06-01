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

## Design review — inconsistencies (2026-06-01)

Reviewed design.md for internal consistency and against referenced source
(scheduler.clj, dispatch_handlers/scheduler.clj, dispatch_effects.clj,
dispatch_handlers/statechart_actions.clj, context.clj, psi_tool_scheduler.clj,
scheduler_time.clj, scheduler_runtime.clj) + doc/scheduler.md. Verified:

- Quoted error strings exact ("below/exceeds the … bound", "not cancellable",
  "only pending schedules can fire", "only pending schedules can fire").
- Timer/cancel seams (`:scheduler-run-after-delay-fn`,
  `:scheduler-cancel-delay-fn`, `:scheduler-timers*`, `:daemon-thread-fn`),
  handle mirroring (start assoc / cancel dissoc / fire `finally` dissoc), and
  `scheduler-timer-handle-count`/`cancel-all-scheduler-timers!` match
  dispatch_effects.clj.
- Shutdown surface matches `context/shutdown-context!` (cancel-all per session +
  `cancel-all-scheduler-timers!` + reset `:scheduler-timers*`).
- Drain via explicit `:scheduler/drain-queue` dispatch (no idle detector);
  drain ordering `[fire-at created-at schedule-id]` matches `drain-one`.
- `:session` always delivers; `:message` queues when ¬idle (idle = ¬streaming ∧
  ¬compacting) — matches `fire-schedule`/`idle-session?`.
- `:at` bounds: past/now→delay 0 unvalidated (fires immediately, matches doc);
  future <1000ms→below-min throw; >24h→exceeds-max — matches `resolve-fire-time!`.
- Cancel-race outcomes (non-pending stale fire throw surfaces; `:queued`→cancel
  dequeues; terminal cancel throws) match `fire-schedule`/`cancel-schedule`.
- Failure path status guard `{:pending :queued :delivered}` and `:session`
  deliver-then-fail flow match `fail-schedule`/`:scheduler/deliver`.
- EQL `:psi.scheduler/*` attrs match scheduler_runtime.clj.

No new actionable inconsistency found. Minor non-actionable note (not a
contradiction): design's drain-emitter list ("session-turn termination /
on-abort") is non-exhaustive — `:on-compact-done` also emits
`:scheduler/drain-queue` — but tests drive drain via the dispatch event
directly, so the omission does not affect the design's verification approach.
No new design-steps items added.

## Plan/steps review — ambiguities (2026-06-01)

Reviewed plan.md + steps.md (design ambiguities already resolved earlier; this
pass targets the execution plan/checklist). Grounded against the actual
`scheduler_*_test.clj` files and the munera task-id rule. Found 5 actionable
ambiguities; added unchecked follow-ups to steps.md:

1. No operational "sufficient coverage" criterion. Plan step 4 / "Reuse before
   adding" gate adding a new test on coverage being "missing or insufficient" /
   "not already demonstrably covered", and Slices 2/3/4/6/7/8 say "Ensure a
   test exists … Add if missing" — but neither defines what makes existing
   coverage *sufficient* for an acceptance area (must assert state/outputs the
   area names? must drive via the seam? must be a single test?). Without a rule,
   the add-vs-cite decision (and thus duplicate-test risk) is per-judgement.

2. steps.md audit-location pointers are partly wrong. Slice 2 audits
   `scheduler_end_to_end_test.clj`/`scheduler_lifecycle_test.clj`; Slice 3/4
   name deftests but not files. Verified actual locations:
   `busy-session-fire-queues-then-idle-drains-fifo-test` and
   `cancel-pending-and-queued-schedules-test` → `scheduler_lifecycle_test.clj`;
   `scheduler-drain-queue-delivers-oldest-queued-schedule-test` +
   `scheduler-cancel-marks-pending-or-queued-schedule-cancelled-test` →
   `scheduler_dispatch_test.clj`; the three session-kind / failure deftests
   (`scheduler-session-kind-fires-without-origin-idle-test`,
   `…-creates-top-level-session-without-switching-test`,
   `…-records-failed-status-on-prompt-submit-error-test`) →
   `scheduler_handlers_test.clj` (Slice 4/7 cite no file). The mismatched
   pointers will send the executor to the wrong file during audit.

3. Slice 5 `:at` past/now: "created + fires immediately (delay 0)". Ambiguous
   whether the psi-tool-surface test must *assert the schedule actually fires*
   (drive the delay-0 timer via the seam) or only assert it is *created /
   accepted* (no min-delay rejection). The two readings produce materially
   different tests.

4. Slice 9 "alloc next NNN" is unqualified. Munera rule is
   `max(open ∪ closed) + 1`; current max across both is 201 (this task) →
   next = 202. But "next NNN" alone admits scanning only `open/` or only
   `closed/`, and plan.md's own reconciliation log documents prior NNN
   collisions — so the allocation scope must be stated explicitly to avoid a
   colliding remediation-task id.

5. Slice 10 "no scheduler source/doc/behaviour modified" coherence check has no
   stated verification mechanism. Ambiguous how the executor *proves* it — e.g.
   `git diff --stat` restricted to non-`src`/non-`doc/scheduler.md` paths, an
   explicit touched-path allowlist (new test files + `findings.md` only), or
   manual inspection. Without a mechanism the close-out gate is unfalsifiable.

## Plan/steps ambiguity follow-up execution (2026-06-01)

Executed all 5 newly added plan/steps ambiguity follow-ups (from the preceding
ambiguity-review pass). All resolvable from current behaviour / artifact facts;
no blockers. No scheduler source/doc/test code touched (plan/steps/impl only).

1. Sufficient-coverage criterion → added "Sufficient-coverage criterion" section
   to plan.md (3 clauses: asserts named state/outputs; drives real path via timer
   seam for *live* areas; asserts state/outputs not interactions). steps.md
   convention header now points at it as the audit→cite-vs-add governor.
2. Audit-location pointers → verified all 7 deftest locations by grep:
   busy-fifo + cancel-pending-and-queued → scheduler_lifecycle_test.clj;
   drain-queue-delivers-oldest + cancel-marks-pending-or-queued →
   scheduler_dispatch_test.clj; session-kind-fires / creates-top-level /
   records-failed → scheduler_handlers_test.clj. Corrected Slices 2/3/4/6/7.
3. Slice 5 `:at` past/now contract → chose *fires* (drive delay-0 timer via seam,
   assert fired/delivered), grounded in resolve-fire-time! (delay = max(0,until);
   validate-delay-ms! only when delay>0). Slice 5 item updated.
4. Slice 9 NNN allocation → replaced "alloc next NNN" with explicit munera rule
   max(open ∪ closed)+1; verified current max=201 → next=202; scan-both note added.
5. Slice 10 coherence proof → specified git diff --stat touched-path allowlist
   (scheduler_* test files + task dir + any Slice-9 remediation dir); any src/**
   or doc/scheduler.md change fails the gate.

## Plan/steps review — inconsistencies (2026-06-01)

Reviewed plan.md + steps.md for internal consistency, against design.md Scope
areas and the actual `scheduler_*`/`psi_tool_scheduler_test.clj` files. Verified
consistent: all 7 cited deftest→file pointers (grep-confirmed), `findings.md`
section list identical in design.md and steps.md, slice statuses, NNN rule.
Found 2 actionable inconsistencies; added unchecked follow-ups to steps.md.

1. Slice↔Scope-area↔findings-section mapping contradicts plan.md's own claim.
   plan.md "Slice order" asserts "Slices map to design Scope areas", but
   design.md defines 7 Scope areas with a *single* "Live execution path" area
   (#3, covering message-kind + busy/drain + session-kind), and the
   `findings.md` skeleton (design.md + steps.md Slice 0) has one
   "Live execution path" section. plan.md/steps.md instead split that area into
   THREE slices (2 message-kind, 3 busy/drain, 4 session-kind), and each of
   steps Slices 2/3/4 says "Record …-live-path finding" without stating they
   all write into the one shared "Live execution path" findings section. So the
   3-slices→1-area→1-section relationship is left implicit/contradictory: the
   "Slices map to design Scope areas" claim is false as written (3:1), and an
   executor could create three separate findings sections, diverging from the
   fixed 7-section skeleton.

2. Slice 10 coherence-gate allowlist excludes a file Slices 0/5 use. Slice 10's
   touched-path allowlist permits changed test files only under
   `components/agent-session/test/psi/agent_session/scheduler_*`, but Slice 0
   inventories and Slice 5 audits/"Add tests for any uncovered case" against
   `psi_tool_scheduler_test.clj`, which does NOT match the `scheduler_*` glob
   (it is `psi_tool_scheduler_test.clj`). If Slice 5 adds psi-tool-surface
   coverage there, the Slice 10 gate fails a legitimately-changed test file —
   an internal contradiction between the work the plan authorises and the gate
   that validates it. (`scheduler_tools_test.clj`, the other Slice 5 file, does
   match.)
