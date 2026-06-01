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

## Plan/steps inconsistency follow-up execution (2026-06-01)

Executed both newly added plan/steps inconsistency follow-ups (from the
preceding inconsistency-review pass). Both resolvable from artifact facts; no
blockers. No scheduler source/doc/test code touched (plan/steps/impl only).

1. Slice↔Scope-area↔findings-section mapping → chose **option (a)** (keep the
   fixed 7-section findings skeleton; correct the plan claim). plan.md
   "Slice order" now states the deliberate 3:1 mapping for design Scope area
   #3 "Live execution path" (= Slices 2 message-kind / 3 busy-drain / 4
   session-kind, all → one shared findings section) and the 1:1 mappings for
   the rest. steps.md Slices 2/3/4 "Record …" items now explicitly record into
   the single shared "Live execution path" `findings.md` section; the Slice 0
   skeleton item now states exactly 7 sections with that one section holding all
   three live-execution entries (no separate per-slice sections). No design.md
   change (the 7-section Scope/skeleton is preserved, not split).
2. Slice 10 allowlist → broadened the close-out touched-path allowlist to permit
   test files matching `scheduler_*` **or** `psi_tool_scheduler_test.clj`
   (the psi-tool-surface file is named explicitly because it does not match the
   `scheduler_*` glob, and Slices 0/5 inventory/extend it). `src/**` and
   `doc/scheduler.md` changes still fail the gate.

## Slice 0 execution — Baseline (2026-06-01)

Ran the 13 scheduler test namespaces via a focused `clojure.test/run-tests`
script under the `:test-paths` alias (the `:test` alias pins kaocha main;
`--focus` did substring-matching and ran the whole suite, so used a script).
Result: **35 tests, 338 assertions, 0 failures, 0 errors** — clean baseline, no
pre-existing scheduler failures.

Note: timbre DEBUG logging from the statecharts engine is very noisy in test
output; suppress with `(timbre/set-min-level! :warn)` for readable runs. Test
correctness is unaffected.

Seam availability confirmed: `test_support/make-session-ctx` wires
`:scheduler-run-after-delay-fn`, `:scheduler-cancel-delay-fn`,
`:scheduler-timers*`, `:daemon-thread-fn` (exercised by
`scheduler_timer_seam_test.clj` and `scheduler_effects_test.clj`).

Created `findings.md` with the fixed 7-section skeleton; Baseline recorded
`verified-correct` with the full inventory.

## Slice 1 execution — Pure model (2026-06-01)

Audited `scheduler_test.clj`. Existing 7 deftests cover transitions, delay
bounds, kind-required, fire(idle/busy), deliver, cancel(queued), drain-one
FIFO + busy no-op. Per the sufficient-coverage criterion, four design-named
behaviours were **insufficiently** covered:

- duplicate `schedule-id` create guard ("schedule-id already exists")
- `fire-schedule` non-pending guard ("only pending schedules can fire") for
  delivered/queued source statuses
- `cancel-schedule` terminal-status guard ("schedule is not cancellable")
- `drain-one` ordering by `[fire-at created-at schedule-id]` — the existing
  `drain-one-test` queues in fire-at order, so it can't distinguish
  sort-by-fire-at from FIFO-by-insertion.

Added 4 green deftests asserting current behaviour. The drain-ordering test
deliberately fires the **later**-firing schedule first so queue-insertion order
(`[late early]`) disagrees with fire-at order, then asserts `drain-one` delivers
the earliest fire-at (`sch-early`) — confirming the sort, not insertion order.

`scheduler_test` now: 11 tests / 44 assertions / 0 fail / 0 error. clj-kondo
clean. No scheduler source touched.

## Slice 2 execution — Live execution: message kind (2026-06-01)

Audit: the existing `scheduler-fired-end-to-end-delivers-when-idle` test asserts
the scheduled-provenance delivered message but dispatches `:scheduler/fired`
**directly** — it never crosses the timer seam. The seam test
`scheduler-start-timer-uses-injected-time-source-and-delay-runner` crosses the
timer (captures + invokes the callback) but only asserts status `:delivered`,
not the delivered prompt/provenance. So per the sufficient-coverage criterion
(clause 1 named outputs AND clause 2 drive via timer seam), the message-kind
live path was insufficiently covered by any single test.

Added `scheduler-message-kind-fires-via-timer-seam-and-delivers-to-origin`:
fixed scheduler time source; override `:scheduler-run-after-delay-fn` to capture
the timer callback; assert pending before fire; invoke the callback (no
`Thread/sleep`); assert the origin-session journal carries a `user` message with
`:source :scheduled` + matching `:schedule-id`, schedule `:delivered`, queue
empty. Green (2 tests / 9 assertions in `scheduler_end_to_end_test`).

## Slice 3 execution — Busy-session queue + drain-on-idle (2026-06-01)

Audit found **sufficient** existing coverage (cite, do not add):

- `scheduler-lifecycle-test/busy-session-fire-queues-then-idle-drains-fifo`:
  sets `:is-streaming true`, fires two schedules → both `:queued`, sets idle,
  drives `:scheduler/drain-queue` (handler) → delivers `sch-q-1` then `sch-q-2`
  FIFO, asserts queue mutation + `:delivered`/`:queued` statuses + scheduled
  user-message timestamp from the runtime scheduler time source.
- `scheduler-dispatch-test/scheduler-drain-queue-delivers-oldest-queued-schedule`:
  drives the real `dispatch-in! :scheduler/drain-queue` with queue
  `["sch-1" "sch-2" "missing"]` where `sch-2` has the **earlier** fire-at →
  delivers `sch-2` (oldest by fire-at, not queue position), drops the missing id,
  no effects on the no-op path.
- `scheduler-dispatch-test/scheduler-fired-queues-while-session-busy`: fire while
  `:is-streaming true` → `:queued` + queue membership.

Per the sufficient-coverage criterion these jointly satisfy (1) named queue/
delivered outputs, (2) drive the real drain via the `:scheduler/drain-queue`
dispatch event (the design's stated drain trigger — drain is dispatch-driven,
not timer-driven, so no timer seam is required here), and (3) assert state/
outputs not interactions. Both cited tests verified green. No new test added.

## Slice 4 execution — Live execution: session kind (2026-06-01)

Audit: `scheduler-session-kind-fires-without-origin-idle` and
`scheduler-session-deliver-creates-top-level-session-without-switching` invoke
the handler directly, assert `:scheduler/deliver` emission + stored kind/config,
but stop **before** running `:scheduler/deliver` — so they never create the
session nor assert `:created-session-id`/`:delivery-phase :prompt-submit`, and
never cross the timer seam. Insufficient for the live round trip.

Added `scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session`
on the full `create-context` (real effect executor). Origin deliberately set
`:is-streaming true` (session-kind must deliver regardless of origin idle).
Capture the timer callback via `:scheduler-run-after-delay-fn`; snapshot the
pre-fire session set; invoke the callback (no sleep). Asserts: schedule
`:delivered`, `:delivery-phase :prompt-submit`, `:created-session-id` present and
**not** in the pre-fire set (fresh), present in the post-fire set, distinct from
origin; created session carries `:scheduled-origin-session-id` /
`:scheduled-from-schedule-id` / `:scheduled-from-label`; origin still present
(not switched away). `with-redefs` stubs `execute-prepared-request!` (no live
model) as the lifecycle test does. Green: `scheduler_end_to_end_test` now
3 tests / 20 assertions.

## Slice 5 execution — psi-tool surface (2026-06-01)

Audit: create/list/cancel, `:delay-ms` valid + below-min (10ms) + cap, future
`:at` (+5000ms), kind validation (session requires session-config, message
forbids it, unsupported keys), missing/invalid time-source — all already
covered. Missing: the three `:at` matrix corners the design names.

Added 3 testing blocks to `psi-tool-scheduler-create-list-cancel`:
- **past `:at`** (now−60s) → accepted, timer scheduled with `delay 0`; drive the
  delay-0 timer via the captured `:scheduler-run-after-delay-fn` seam (invoke
  callback, no sleep) → schedule `:delivered`. Confirms past `:at` fires
  immediately, grounded in `resolve-fire-time!` (`delay = max(0, between)`,
  `validate-delay-ms!` only when `(pos? delay)`).
- **near-future `:at`** (now+500ms) → positive delay <1000 → rejected (error).
- **far-future `:at`** (now+max+1ms) → rejected (error).

The past-allowed / near-future-rejected **asymmetry** is recorded
verified-correct (NOT a defect): it matches `doc/scheduler.md` "past absolute
instants fire immediately" — no doc/behaviour drift. `psi_tool_scheduler_test`:
1 test / 107 assertions / green. clj-kondo clean.
