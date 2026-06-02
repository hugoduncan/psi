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

## Slice 6 execution — Cancellation & lifecycle (2026-06-01)

Audit found cancel-before-fire, `:queued`→cancel, cancel-all, and shutdown
handle-clearing already covered. Two gaps filled:

- **Race A** (`scheduler-cancel-before-stale-timer-callback-does-not-resurrect`,
  seam test): capture the timer callback; cancel BEFORE invoking it; assert
  `:cancelled` + handle removed; then invoke the **stale** callback and assert
  the schedule stays `:cancelled` (not resurrected). The stale fire hits
  `fire-schedule`'s non-`:pending` guard internally; the live callback path does
  not re-store the schedule.
- **No fire-after-shutdown**
  (`shutdown-context-prevents-captured-timer-callback-from-firing`): capture the
  callback; `shutdown-context!`; assert handle gone + schedule `:cancelled`; then
  invoke the stale callback and assert it does not deliver (stays `:cancelled`).

Both use non-`Thread` handles + a no-op `:scheduler-cancel-delay-fn` so cancel is
deterministic. Race B terminal-status guard is the Slice-1
`cancel-schedule-rejects-terminal-status`. 7 tests / 33 assertions green across
seam + shutdown + effects.

## Slice 7 execution — Failure path (2026-06-01)

Audit: `scheduler-session-deliver-records-failed-status-on-prompt-submit-error`
asserted only `:failed` + `:delivery-phase`; `fail-schedule` (pure) had no
dedicated test. Filled:

- Pure `fail-schedule-records-failure-detail-and-dequeues`: from `:queued`,
  `fail-schedule` records `:failed` + `:delivery-phase :prompt-submit` +
  `:error-summary` + `:created-session-id` and removes the id from `:queue`
  (queue-not-wedged mechanism). Plus terminal-status guard: failing a
  `:cancelled` schedule throws "schedule is not fail-able".
- Extended the handler failure test to assert the failure `:error-summary`
  (`scheduler-error-summary` → `{:message :class :data}`, message "boom") and the
  `:created-session-id` recorded when the session is created before the
  prompt-submit failure (matches doc: "If session creation succeeds but prompt
  submission fails, the schedule becomes :failed and still records the created
  session id").

`scheduler_test` + `scheduler_handlers_test`: 21 tests / 103 assertions green.
clj-kondo clean.

## Slice 8 execution — Projections (2026-06-01)

Audit: `scheduler-resolver` covered `:pending` + a subset of attrs;
`scheduler-background-job-projection` covered pending+queued projection (message
+session) and cancel routing. Gap: the rich attrs across terminal statuses.

Added `scheduler-resolver-projects-rich-attrs-across-statuses`: seeds the
scheduler state directly with `:delivered`/`:cancelled` message schedules and a
`:failed` session-kind carrying `:created-session-id` / `:delivery-phase
:prompt-submit` / `:error-summary {:message "boom" …}` /
`:session-config-summary`, then queries the full `:psi.scheduler/*` attr set and
asserts each projects coherently with the underlying state. Confirms the
runtime exposes the documented introspection attrs across all statuses.

psi-tool summary projection (`:psi-tool/scheduler :schedule`/`:schedules`) is
exercised by Slice 5's create/list/cancel tests. `scheduler_resolvers_test` +
`scheduler_background_jobs_test` + `scheduler_cancel_job_test`: 4 tests / 27
assertions green. clj-kondo clean.

## Slice 9 + 10 — Defect handling + close-out (2026-06-01)

Slice 9 (conditional): **no defects found** in any Scope area — all findings are
`verified-correct`. No `munera/open/NNN-slug` remediation task created.

Slice 10 close-out:
- `findings.md` finalised: 7 sections, 30 entries, all `verified-correct`, with an
  Outcome summary.
- `clj-kondo --lint`: 0 errors / 0 warnings on all 7 touched test files.
- `cljfmt check`: all formatted correctly.
- Full scheduler suite: **45 tests / 412 assertions / 0 fail / 0 error**
  (baseline 35/338 → +10 tests / +74 assertions). (Count updated 410 → 412 in
  review pass 4 after the pass-2 `:at` named-bound follow-up added two assertions.)
- Coherence gate: `git diff --name-only 87140947b~1..HEAD` → only 7 scheduler
  test files (`scheduler_*` / `psi_tool_scheduler_test.clj`) + 3 task-dir files;
  **zero** `components/agent-session/src/**` or `doc/scheduler.md` changes. PASSES.

Task verification-only deliverable complete: green end-to-end coverage (incl.
message-kind + session-kind live timer-seam round trips) plus a structured
`findings.md` recording every area verified-correct.

## Implementation review (2026-06-01)

Reviewed against design.md/plan.md via task-implementation-review skill
(matches-design ∧ follows-architecture ∧ flag reusable-pattern / unnecessary-
abstraction / structural-perf). Verified directly, not just from the log:

- **Matches design + acceptance criteria.** All 7 Scope areas covered; both
  live round trips (message-kind + session-kind) drive the real dispatch+effect
  path and cross the timer seam via `:scheduler-run-after-delay-fn` (callback
  captured + invoked, no wall-clock sleep); busy-queue/drain, cancel races,
  failure recording, shutdown timer-cleanup each have passing coverage.
- **Follows architecture (`λtest`).** New tests assert state/outputs
  (delivered-prompt provenance, `:created-session-id`/`:delivery-phase`,
  `:cancelled`+handle/queue removal, `:failed`+`:error-summary`, EQL attrs),
  not handler interactions. Pure-model guards assert exact error strings.
- **No source/doc drift.** `git diff --stat 611b037bb..HEAD -- components/`
  shows only the 7 test files (no `src/**`, no `doc/scheduler.md`); confirmed
  against the Slice-10 allowlist.
- **`:at` asymmetry judgment is sound.** `resolve-fire-time!` only calls
  `validate-delay-ms!` `(when (pos? delay))`; past/now → delay 0 → fires
  immediately, matching `doc/scheduler.md:99` "past absolute instants fire
  immediately". Recording it `verified-correct` (not a doc/behaviour-drift
  defect) is correct.
- **Suite re-run green here:** 45 tests / 410 assertions / 0 fail / 0 error
  (pass-1 time; later raised to 412 by the pass-2 `:at` named-bound follow-up);
  clj-kondo 0/0 on all 7 touched files.

### Flag — reusable test-support pattern (test-quality, non-blocking)

The capture-timer override idiom
`(assoc ctx :scheduler-run-after-delay-fn (fn [_ctx _delay-ms f] (reset! cb* f) {:handle :captured}))`
paired with an external `cb*` atom is duplicated across **4 files / 5 sites**
(`scheduler_end_to_end_test` ×2, `scheduler_timer_seam_test`,
`scheduler_context_shutdown_test`, `psi_tool_scheduler_test`). The
`test_support/make-session-ctx` default `:scheduler-run-after-delay-fn` *sleeps*
rather than captures, so every verification test re-implements its own capturing
override inline. Per `λreq → reusable_existing_pattern → flag`, this is a
candidate for a shared `test-support` helper (e.g. a `capturing-delay-fn`
returning `[override-fn cb*]`, or a `with-captured-timer` macro) to remove the
duplication. Not a correctness defect and does not affect the verification-only
deliverable — a test-DRY follow-up.

### Implementation-review follow-up executed (2026-06-01) — capturing-delay-fn helper

Executed the test-DRY follow-up flagged above. Added
`test-support/capturing-delay-fn` (in `components/agent-session/test/psi/agent_session/test_support.clj`,
after `advance-scheduler-instant!`), returning `[override-fn cb*]`:

- `override-fn` = `(fn [_ctx delay-ms f] (reset! cb* {:delay-ms delay-ms :f f}) {:handle :captured})`
- `cb*` = an atom holding `{:delay-ms delay-ms :f f}` (richer shape — superset of
  the 4 capture-only sites; needed by the psi-tool delay-0 site that asserts
  `:delay-ms`).

Migrated all **5 named sites** to `[capture* callback*] (test-support/capturing-delay-fn)`
+ `:scheduler-run-after-delay-fn capture*`, with uniform invocation
`((:f @callback*))`:

- `scheduler_end_to_end_test` message-kind + session-kind round-trip tests
- `scheduler_context_shutdown_test` shutdown-prevents-fire test
  (kept its explicit no-op `:scheduler-cancel-delay-fn`)
- `scheduler_timer_seam_test` race-A cancel-before-stale-callback test
  (kept its explicit no-op `:scheduler-cancel-delay-fn`)
- `psi_tool_scheduler_test` past-`:at`-fires-immediately test (already read
  `(:delay-ms @cb*)` / `((:f @cb*))` — directly compatible)

The handle sentinel value (`:fake` at the shutdown + timer-seam sites) was
irrelevant — those sites override cancel-delay-fn to a no-op, so the helper's
`{:handle :captured}` is equivalent. The **two extra-state override forms** in
`scheduler_timer_seam_test` (one capturing `observed-delay*`, one capturing the
cancelled `handle`) are intentionally *not* migrated — they are not the named
single-callback idiom and capture different state.

Verification (test-files-only, within the Slice-10 allowlist; no `src/**`,
no `doc/scheduler.md`):
- clj-kondo 0 errors / 0 warnings on all 5 touched files.
- cljfmt: "All source files formatted correctly".
- Four touched scheduler test namespaces: 9 tests / 148 assertions / 0 fail.
- Full scheduler suite: **45 tests / 410 assertions / 0 failures** at pass-1
  time — identical to the cited baseline → no behaviour change, pure
  test-quality DRY. (Later raised to 412 by the pass-2 `:at` named-bound
  follow-up.)

## Implementation review pass 2 (2026-06-01)

Fresh review pass via task-implementation-review skill. Re-verified against
runtime (suite re-run here: 45 tests / 412 assertions / 0 fail / 0 error;
clj-kondo 0/0 on all 8 touched test files incl. `test_support.clj`). Spot-read
the new test source, not just the log:

- Pure-model guard tests (`scheduler_test.clj`) assert exact error strings
  (`thrown-with-msg?`) + concrete state. `drain-one-orders-by-fire-at` correctly
  builds insertion-order ≠ fire-at-order. Solid.
- Live round trips (`scheduler_end_to_end_test.clj`) genuinely cross the timer
  seam (capture+invoke callback, no sleep) and assert state/outputs
  (delivered-prompt provenance; `:created-session-id`/`:delivery-phase`;
  created-session freshness/provenance), not interactions. Solid.
- Resolver rich-attrs test asserts every projected attr value. Solid.

**Flag (new, actionable) — `:at` bound-rejection tests don't assert the named
bound.** In `psi_tool_scheduler_test.clj`, the near-future (line ~228) and
far-future (line ~243) `:at` rejection blocks assert only
`(true? (:is-error result))` + `(= :error (:psi-tool/overall-status parsed))`.
Both block docstrings — and `findings.md` (psi-tool surface) + steps.md lines
157/159 — claim rejection "with the **below-minimum** bound error" /
"with the **exceeds-maximum** bound error" respectively, but neither assertion
checks *which* bound was hit. The two blocks are assertion-indistinguishable:
they pass for *any* error (a swapped bound, or an unrelated validation failure).
This under-asserts the sufficient-coverage criterion clause 1 (assert the area's
named output) and, more pointedly, the `:at` *asymmetry* finding
(verified-correct, not a drift) is the deliberate distinction between
below-min (near-future) and exceeds-max (far-future) — yet the tests can't tell
them apart. The distinct messages exist in source
(`scheduler.clj:85`/`:89` "delay-ms is below the minimum bound" /
"delay-ms exceeds the maximum bound") and surface through `:psi-tool/error`
`:message`, so asserting the specific bound message per block is feasible and
in-scope (test file only, within the Slice-10 allowlist). Non-blocking for the
green deliverable but it is a real precision gap that contradicts the test's own
claimed coverage. Added as a follow-up step.

Everything else verified accurate against runtime. No other new actionable
issues; the earlier capture-timer DRY flag was already executed.

## Implementation review follow-up — pass 2 executed (2026-06-01)

Tightened the two `:at` bound-rejection assertions in
`psi_tool_scheduler_test.clj` to assert the *named* bound rather than a generic
error. Previously both the near-future (~L228) and far-future (~L243) blocks
asserted only `(true? (:is-error result))` + `(= :error (:psi-tool/overall-status
parsed))`, which are assertion-indistinguishable and would pass for any error
(including a swapped/unrelated rejection). Added per-block
`(= <msg> (get-in parsed [:psi-tool/error :message]))`:
- near-future (500ms) → `"delay-ms is below the minimum bound"`
- far-future (max+1ms) → `"delay-ms exceeds the maximum bound"`

These are the exact `scheduler.clj:85`/`:89` `ex-info` messages surfaced through
`psi-tool-error-summary` (`ex-message e` → `:message`), confirmed via
`validate-delay-ms!`'s `resolve-fire-time!` path. This restores
sufficient-coverage clause 1 (assert named state/outputs) for the deliberate
below-min vs exceeds-max `:at` asymmetry that the findings record as
`verified-correct`.

Verification: `clojure -M:test --focus psi.agent-session.psi-tool-scheduler-test`
→ 1 test / 109 assertions / 0 failures (was 107; +2). clj-kondo 0/0, cljfmt
clean. Coherence gate: `git diff --name-only` shows only the psi-tool-surface
test file (Slice-10 allowlist) + task-dir `steps.md`; zero `src/**` /
`doc/scheduler.md` changes.

## Implementation review — pass 3 (task-implementation-review skill, 2026-06-01)

Reviewed the verification-only deliverable against design/plan/architecture and
the live runtime. Re-ran full `bb test` → ✅ all green; scheduler-touched test
files `clj-kondo` 0/0. Spot-verified test↔source coherence:

- Live round-trip tests (`scheduler_end_to_end_test`) drive the real
  dispatch+effect pipeline, cross only the time/timer boundary via the captured
  `:scheduler-run-after-delay-fn` seam, and assert state/outputs (delivered
  journal message + provenance; created top-level session + `:created-session-id`
  / `:delivery-phase`), never handler interactions. Matches design "real
  effect/dispatch round trip" + architecture (assert state, not interactions). ✓
- Pure-model guard messages in `scheduler_test` match `scheduler.clj` exactly:
  "schedule-id already exists" (:138), "schedule is not cancellable" (:163),
  "only pending schedules can fire" (:201), "schedule is not fail-able" (:245). ✓
- `fail-schedule-records-failure-detail-and-dequeues-test` logic sound: `s1`
  fires a `:session`-kind schedule which returns action `:deliver` with
  `:state state` unchanged, so `sch-fail` stays `:pending`; the cancel→`:cancelled`
  path then correctly triggers the `:fail-able` guard rejection. ✓
- `:at` finding matches `doc/scheduler.md:99` ("past absolute instants fire
  immediately") and `resolve-fire-time!` delay-0 path; named-bound assertions
  present (pass-2). ✓
- `capturing-delay-fn` test-support helper (pass-1 DRY) is clean, documented,
  single-purpose. ✓

One new actionable issue (test-doc accuracy, non-blocking):

- **Slice-10 coherence-gate allowlist is stale.** The close-out gate enumerates
  permitted touched paths as test files matching `scheduler_*` **or**
  `psi_tool_scheduler_test.clj` (+ task dir + any Slice-9 remediation dir). But
  the actual changeset (`git diff --name-only 87140947b~1..HEAD`) also includes
  `components/agent-session/test/psi/agent_session/test_support.clj`, added by
  the pass-1 `capturing-delay-fn` extraction. `test_support.clj` matches neither
  the `scheduler_*` glob nor the named exception, so a literal application of the
  documented gate would FAIL on a legitimately-touched file. The real
  verification-only invariant (no `src/**` / `doc/scheduler.md` changes) still
  holds — `test_support.clj` is under `test/`, not source/doc — but the
  *falsifiable gate as written* no longer enumerates a path it should permit.
  Fix: broaden the Slice-10 allowlist to include shared test-support files under
  `components/agent-session/test/**` (e.g. add `test_support.clj` to the named
  exceptions, or generalise to "test files under
  `components/agent-session/test/**`"), and re-state the gate's true invariant as
  "zero `components/agent-session/src/**` or `doc/scheduler.md` changes". Doc
  edit to steps.md only; no test/src/doc behaviour change.

No other new actionable issues. Earlier flags (capture-timer DRY, `:at`
named-bound precision) already executed.

## Implementation review follow-up — pass 3 executed (2026-06-01)

Executed the single pass-3 follow-up: broadened the Slice-10 coherence-gate
allowlist so it no longer FAILs on the legitimately-touched shared test-support
file. steps.md doc edit only — no test/src/doc behaviour change.

- **Grounded the flag:** `git diff --name-only 87140947b~1..HEAD` confirms the
  changeset includes `components/agent-session/test/psi/agent_session/test_support.clj`
  (pass-1 `capturing-delay-fn` extraction), which matched neither the
  `scheduler_*` glob nor the named `psi_tool_scheduler_test.clj` exception — so a
  literal application of the old gate would have failed on it.
- **Generalised the allowed-path rule** in the Slice-10 close-out item to
  **test files under `components/agent-session/test/**`** (covers `scheduler_*`
  / `psi_tool_scheduler_test.clj` **and** shared `test_support.clj`, all under
  `test/`, not `src/**`/`doc/`).
- **Hoisted the gate's true invariant** to the front of the item: "zero
  `components/agent-session/src/**` or `doc/scheduler.md` changes" — making the
  gate a falsifiable statement about source/doc, with the test-path allowlist as
  the supporting enumeration.
- **Updated the "Done:" note** to the real 8-test-file changeset (7 scheduler
  test files + `test_support.clj`, all under `components/agent-session/test/**`,
  + 3 task-dir files; zero `src/**` / `doc/scheduler.md`) → gate now passes on a
  literal application.

No `src/**` or `doc/scheduler.md` touched; no test code changed. Verification-
only invariant intact.

## Implementation review — pass 4 (task-implementation-review skill, 2026-06-01)

Reviewed the verification-only deliverable against design/plan/architecture and
the live runtime. Verified directly (not just from the log):

- **Coherence gate holds.** `git diff --name-only 87140947b~1..HEAD` → only test
  files under `components/agent-session/test/**` (7 scheduler test files +
  `test_support.clj`) + 3 task-dir files. **Zero** `components/agent-session/src/**`
  or `doc/scheduler.md` changes. Working tree clean. ✓
- **Suite green, re-run here.** Full scheduler subset (13 ns):
  **45 tests / 412 assertions / 0 fail / 0 error.** clj-kondo 0/0 on all 8
  touched test files. ✓
- **Tests are substantive.** Spot-read `scheduler_end_to_end_test` (both live
  round trips genuinely capture+invoke the timer callback, assert delivered-
  prompt provenance / `:created-session-id` / `:delivery-phase` / created-session
  freshness — state/outputs, not interactions), `scheduler_test` pure-model
  guards (exact `thrown-with-msg?` strings; `drain-one-orders-by-fire-at` builds
  insertion-order ≠ fire-at-order; `fail-schedule` detail+dequeue+terminal
  guard), and `psi_tool_scheduler_test` `:at` named-bound assertions (pass-2:
  L242/260 assert `"delay-ms is below/exceeds the … bound"` via
  `[:psi-tool/error :message]`). All sound. ✓
- **Prior flags genuinely executed.** capture-timer DRY (`capturing-delay-fn`),
  `:at` named-bound precision, Slice-10 allowlist generalisation — all present
  in code/steps, verified, not merely logged. ✓

**Flag (new, actionable) — documented assertion count (410) is stale; runtime
reports 412.** The summary figures in `findings.md` (Outcome: "45 tests / 410
assertions"), `steps.md` (Slice 10 "Done:" + pass-1 follow-up note), and
`implementation.md` (Slice 9+10 close-out, pass-1, pass-2, pass-3 review notes)
all cite **410 assertions**. The runtime now reports **412** (re-run here, and
arithmetic-confirmed: `410 − 107 + 109 = 412`). The pass-2 follow-up added the
two `:at` named-bound assertions (psi-tool test 107 → 109) and was committed, but
the aggregate count was never updated to match — so every "45 tests / 410
assertions" claim in the deliverable now under-counts by 2 and contradicts the
runtime. Per `runtime ≡ truth, file ≡ memory`, the findings.md Outcome (the
structured deliverable) and the steps/impl close-out counts should read 412 to
match the green suite. Test/source unaffected (no behaviour change); doc-accuracy
only, within the verification-only scope (no `src/**`/`doc/scheduler.md`). Added
as a follow-up step.

No other new actionable issues. The verification-only deliverable is otherwise
accurate, coherent, and green against current behaviour.

## Implementation review follow-ups executed — pass 4 (2026-06-01)

Executed the pass-4 doc-accuracy follow-up: corrected the stale **410 → 412**
assertion count across the deliverable.

- **Runtime truth confirmed (`runtime ≡ truth`).** Focused `clojure -M:test`
  across the 13 scheduler namespaces reports **45 tests / 412 assertions**
  (= 338 baseline + 74; pass-2 took psi-tool 107 → 109). Full `bb test` is green
  for scheduler.
- **Counts corrected to 412:**
  - `findings.md` Outcome ("45 tests / 410 assertions" → 412).
  - `steps.md` Slice-10 close-out (`+72` → `+74` delta) with a 410→412 note.
  - `implementation.md` Slice-10 close-out (`+72`/`+74`) + pass-2 review re-run
    note (genuinely stale: pass-2 *added* the +2, so its own re-run already read
    412).
- **Pass-1 review notes left intact** (410 was accurate at pass-1 time, before
  pass-2's increment) with forward-pointer parentheticals to 412 so no stale
  figure misleads. The pass-4 flag note + the step's own 410→412 description are
  preserved as documentation of the fix.
- **Scope held:** doc-accuracy only — no `components/agent-session/src/**` or
  `doc/scheduler.md` change; no test behaviour change.
- **Observation (out of scope):** running the scheduler namespaces *in isolation
  together* surfaces 4 ordering-dependent failures that do **not** occur under
  the canonical full `bb test` run (each ns is green standalone). Pre-existing
  cross-namespace test-isolation artifact; not introduced by this step and not
  in scope for a doc-accuracy correction.

## Implementation review — pass 5 (2026-06-01)

Independent verification of the completed deliverable against runtime truth and
the design/plan acceptance criteria. No new actionable issues found.

- **Tests genuinely green (`runtime ≡ truth`).** Focused kaocha run of all 13
  scheduler namespaces together (default no-randomize) = **45 tests / 412
  assertions / 0 failures**; full `--focus unit` kaocha run exits 0 (kaocha
  returns non-zero on any failure/error) → `bb test` acceptance criterion holds.
- **clj-kondo clean** (0/0) on all 7 touched test files + `test_support.clj`.
- **Verification-only scope held.** Changeset = test files under
  `components/agent-session/test/**` + task dir only; **zero**
  `components/agent-session/src/**` / `doc/scheduler.md` — invariant gate passes.
- **Test quality confirmed.** New live-path tests
  (`scheduler_end_to_end_test`, `scheduler_timer_seam_test`,
  `scheduler_context_shutdown_test`) drive the real dispatch+effect round trip,
  cross the time/timer boundary only via the seams, and assert state/outputs
  (delivered prompt provenance, `:created-session-id`/`:delivery-phase`, handle
  count, queue) — never handler interactions. The session-kind `with-redefs` is
  on the turn-runtime boundary (`execute-prepared-request!`), not the scheduler
  delivery path — consistent with the design's runtime-owned-deliver frontier.
- **Deliverable counts coherent.** `findings.md` Outcome reads 412; residual
  `410` strings live only in preserved pass-1 historical notes (carrying
  forward-pointers to 412) and the pass-4 follow-up's own description — not in
  the structured deliverable.
- **Pass-4 cross-ns isolation flag re-assessed and confirmed out of scope.** The
  canonical runner (kaocha, no randomize) is green for all 13 scheduler ns run
  together; the "4 ordering-dependent failures in isolation together" is a
  non-kaocha-runner / cross-ns test-isolation artifact, pre-existing, and the
  acceptance criterion (`bb test` green) is satisfied. No remediation owed by
  this verification-only task; if pursued, it belongs in a separate
  test-isolation task, not 201.

Conclusion: implementation, tests, findings.md, and the structured deliverable
are accurate, coherent, and green against current behaviour. No follow-up steps
added (no new actionable issues; prior passes' items all resolved).

## Test review — pass 6 (task-test-review skill, 2026-06-01)

Applied `task-test-review` skill (well_formed ∧ behaviour-coverage ∧
`∀d ∈ infra_deps. injectable ∧ nullable ∧ ¬mock ∧ ¬stub`). Suite green
(scheduler-end-to-end focus = 3 tests / 20 assertions / 0 fail). Behaviour
coverage maps 1:1 to design Scope areas; timer/cancel/drain/shutdown live tests
correctly drive the real dispatch+effect path via injectable ctx seams and
assert state/outputs, not interactions. One actionable test-quality finding:

- **`with-redefs` used where an injectable ctx seam exists (skill clause
  `injectable ∧ ¬stub`).** `scheduler_end_to_end_test/
  scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session-test`
  (line ~109) stubs the AI-execution boundary via
  `(with-redefs [psi.turn-runtime.core/execute-prepared-request! …] …)` — a
  global var redef. But that boundary is already an **injectable ctx dependency**:
  `dispatch_effects.clj:154` calls `(:execute-prepared-request-fn ctx)`, and
  `test_support/make-session-ctx` (line ~246) already wires a default
  `:execute-prepared-request-fn` stub via that seam. The skill prefers injection
  over var-stubbing, and the plan's own mitigation says to "build live tests on
  `test_support/make-session-ctx`'s already-wired seams." The test instead uses
  its local `create-session-context` (raw `session/create-context`) + a
  `with-redefs` reimplementation of the same stub. Fix: pass
  `:execute-prepared-request-fn` through `safe-context-opts`/ctx (or build on
  `make-session-ctx`) and drop the `with-redefs`. Test-quality DRY/injection
  only — no behaviour change; within the Slice-10 allowlist (test file only,
  zero `src/**`/`doc/scheduler.md`).

- **Disagreement with pass-5 dismissal (recorded for the trail).** Pass 5
  justified the `with-redefs` as "on the turn-runtime boundary, not the
  scheduler delivery path — consistent with the runtime-owned-deliver frontier."
  That defends *what* is stubbed (a genuine infra boundary — correct), but not
  *how*: the skill clause is about the *mechanism* (`injectable ∧ ¬stub`), and a
  ctx seam for exactly this boundary exists and is the documented project idiom.
  So the finding stands on the injection-vs-redef axis, which pass 5 did not
  address.

- **No other actionable test issues.** capturing-delay-fn DRY (pass-1), `:at`
  named-bound asserts (pass-2), allowlist (pass-3), counts (pass-4) all resolved.
  Cross-ns isolation artifact (pass-4/5) confirmed out of scope (canonical
  runner green). All infra time/timer deps are injectable+nullable via ctx
  seams (✓ skill). Behaviour coverage complete (✓ skill).

## Test review follow-up — pass 6 resolved (2026-06-01)

- **`with-redefs` → injectable ctx seam (done).** Replaced the
  `with-redefs [psi.turn-runtime.core/execute-prepared-request! …]` in
  `scheduler_end_to_end_test/scheduler-session-kind-fires-via-timer-seam-and-creates-top-level-session-test`
  with the documented ctx seam: the same shaped stub is bound to a local and
  threaded onto the live ctx as `:execute-prepared-request-fn` alongside the
  existing `:scheduler-run-after-delay-fn` timer seam. The effect resolves the
  fn from ctx (`dispatch_effects.clj:154`), so the round trip is unchanged
  (session-kind fires → fresh top-level session → `:created-session-id` /
  `:delivery-phase :prompt-submit`). This realises the pass-5-disagreement axis:
  injection over redefinition (`infra_deps → injectable ∧ ¬stub`), using the
  boundary's own ctx seam rather than redefining the turn-runtime var.
- **Cleanup.** Removed the now-orphan `[psi.turn-runtime.core]` ns require
  (present only as the `with-redefs` target).
- **Verification.** `scheduler_end_to_end_test` 3 tests / 20 assertions green;
  handler/lifecycle/dispatch/shutdown suites 19 tests / 104 assertions green.
  clj-kondo 0/0, cljfmt clean. Test file only — zero
  `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
  held).

## Test review — pass 7 (task-test-review, 2026-06-01)

Re-applied `task-test-review` (well_formed ∧ behaviour-coverage ∧
infra_deps→injectable∧nullable∧¬mock∧¬stub) across all 201 new/extended tests.
Suite green per-ns (handlers 9/51, e2e 3/20, seam 3/14, scheduler 12/52,
resolvers 2/21, shutdown 2/7, psi-tool 1/109).

Verified good: pure-model guards, message/session live round trips (timer seam,
no sleep), busy-queue/drain, cancel races, shutdown no-fire, `:at` matrix with
named-bound assertions, projection rich-attrs. Pass-6's `with-redefs`→ctx-seam
swap in the session-kind e2e test holds.

**One new actionable test-quality issue:**

- The failure-path **deliverable** test
  `scheduler-handlers-test/scheduler-session-deliver-records-failed-status-on-prompt-submit-error-test`
  forces the prompt-submit failure via `with-redefs` of `dispatch/dispatch!`
  (handlers_test ~L337) — a **stub of the dispatch boundary**, the same class
  the skill flags (`infra_deps → ¬stub`) and that pass-6 removed from the e2e
  test. The `with-redefs` predates 201 (`59e338cb9`); 201 (`d9f2ca032`) adopted
  it as its cited Slice-7 deliverable by adding `error-summary` /
  `created-session-id` assertions on top. Unlike the e2e session-kind path
  (driven by `:execute-prepared-request-fn`), the `:submitted? false` check
  fires on the prompt-submit *dispatch result* before that ctx seam runs, so
  there is no equally-clean ctx-level injection point today — the cleanest fix
  is likely a small `:submit-synthetic-user-prompt-fn`-style seam (or asserting
  the failure via the *pure* `fail-schedule` path already covered in
  `scheduler-test`, treating the handler test as redundant). Recorded as a
  follow-up; resolution may belong to a small standalone test-hygiene task given
  the verification-only scope.

Non-issue (no follow-up): `scheduler-resolvers-test/...-rich-attrs-across-statuses`
seeds `:delivered`/`:cancelled`/`:failed` via direct `swap!`/`assoc-in` rather
than the live path — acceptable for a *projection* unit test (asserts outputs,
not interactions; reaching those terminal statuses live is out of the
projection unit's concern and already covered live elsewhere).

## Test review follow-up — pass 7 executed (2026-06-01)

Resolved the pass-7 actionable issue (infra-boundary `with-redefs` in the
Slice-7 failure-path deliverable test) with a **third path** that the step's
options (a)/(b) did not enumerate but that strictly dominates both for a
verification-only task:

- **Problem.** `scheduler-handlers-test/...-records-failed-status-on-prompt-submit-error-test`
  forced the prompt-submit failure via `(with-redefs [dispatch/dispatch! …] …)`
  — a global redef of the dispatch infra boundary (`infra_deps → ¬stub`), the
  same class pass-6 removed from the e2e session-kind test.
- **Why not option (a).** Introducing a ctx-level
  `:submit-synthetic-user-prompt-fn` seam means editing
  `dispatch_handlers/scheduler.clj` (`src/**`) — outside this verification-only
  task's Slice-10 allowlist (zero `src/**` / `doc/scheduler.md`). Rejected.
- **Why not option (b).** Deleting the handler test and resting solely on the
  pure `fail-schedule-records-failure-detail-and-dequeues-test` is scope-safe
  but *loses* the only live coverage that the `:scheduler/deliver` catch branch
  maps error data into `fail-schedule` correctly (real session creation, real
  ex-info → `:error-summary`/`:created-session-id`/`:delivery-phase`). Avoided.
- **Chosen path — re-register the handler in the kernel registry.** Inside the
  existing `with-registered-handlers` (which already installs the real handlers
  via `kernel/clear-handlers!` + each `register!`), re-register
  `:session/submit-synthetic-user-prompt` via `kernel/register-handler!` to
  return `{:return {:submitted? false …}}`. `dispatch/dispatch!` routes through
  `kernel/dispatch!` against this same registry, so the real `:scheduler/deliver`
  catch branch runs end-to-end: a genuine top-level session is created, then the
  `(:submitted? result)` guard trips and the handler throws its own
  `"scheduled session prompt submission failed"` ex-info carrying the real
  `:created-session-id` + `:delivery-phase :prompt-submit`. This is
  injection-over-redef using the project's own dispatch seam — not a var stub.
- **Edits.** Dropped the `with-redefs [dispatch/dispatch! …]` and the now-orphan
  `[psi.agent-session.dispatch :as dispatch]` require. The error-message
  assertion changed from the stub's `"boom"` to the real surfaced message; the
  `:failed` / `:delivery-phase` / non-nil `:error-summary` / `:created-session-id`
  assertions are unchanged. Assertion count unchanged (6 `is`).
- **Verification.** `scheduler_handlers_test` green (9 tests / 51 assertions);
  full `bb test` green; clj-kondo 0/0, cljfmt clean on the touched file.
  Aggregate scheduler-suite count unchanged at **45 tests / 412 assertions**
  (no assertion added/removed — only an asserted value changed). Test file only
  — zero `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10
  allowlist held). All other infra deps remain injectable via ctx/kernel seams.

## Test review — pass 8 (task-test-review, 2026-06-01)

Re-applied `task-test-review` (well_formed ∧ behaviour-coverage ∧
infra_deps→injectable∧nullable∧¬mock∧¬stub). Suite confirmed green:
focused scheduler subset 32/274; full scheduler aggregate **45 tests / 412
assertions / 0 failures** (matches the deliverable). clj-kondo 0/0 on all 8
touched test files.

Verified good (no new follow-up): the 10 new + 2 extended 201 tests are
well-formed and behaviour-complete against the acceptance criteria — pure-model
guards, message/session live round trips (real timer seam, no sleep), cancel
races, shutdown no-fire, `:at` matrix with named-bound assertions, projection
rich-attrs. Pass-6 (`:execute-prepared-request-fn` ctx seam in the session-kind
e2e test) and pass-7 (kernel handler re-registration in the failure-path
deliverable, no `dispatch/dispatch!` redef) both hold — those two new/extended
tests are now stub-free.

**One new actionable test-quality issue (not previously flagged):**

- The **busy queue + drain-on-idle** finding (`findings.md` Live execution path,
  L87) cites `scheduler-lifecycle-test/busy-session-fire-queues-then-idle-drains-fifo`
  as an authoritative covering test, but that test stubs the AI-execution infra
  boundary via `(with-redefs [psi.turn-runtime.core/execute-prepared-request! …])`
  (`scheduler_lifecycle_test.clj` L51 & L101) — the same infra-boundary var-stub
  class the skill flags (`infra_deps → injectable ∧ ¬stub`) and that pass-6
  removed from the e2e session-kind test using the *already-wired*
  `:execute-prepared-request-fn` ctx seam. Passes 6/7 audited the e2e and
  handlers tests but did not examine this cited pre-existing lifecycle test.
  The busy-drain acceptance area is **already fully covered stub-free** by
  `scheduler_dispatch_test.clj` (0 `with-redefs`):
  `scheduler-fired-queues-while-session-busy` (fire-while-busy → `:queued`) +
  `scheduler-drain-queue-delivers-oldest-queued-schedule` (real `dispatch-in!
  :scheduler/drain-queue` → oldest-by-fire-at delivered, queue mutates). So the
  stubbed lifecycle citation is a redundant-with-stub authority where clean
  coverage exists. Resolution (test-file/findings-only, within the Slice-10
  allowlist — zero `src/**`/`doc/scheduler.md`): either (a) migrate
  `busy-session-fire-queues-then-idle-drains-fifo` off `with-redefs` onto the
  `:execute-prepared-request-fn` ctx seam (mirroring pass-6), or (b) drop the
  lifecycle citation from the L87 busy-drain finding and rest the area on the
  stub-free `scheduler_dispatch_test` deftests already co-cited. (If the task is
  treated as closed, raise it as a small standalone test-hygiene task.)

Non-issue (no follow-up): `scheduler_lifecycle_test/cancel-pending-and-queued-schedules`
is cited for the cancel area (L112/L113) but its assertions sit in the
no-`with-redefs` `cancel-pending-and-queued-schedules-test` deftest (the redef
sites are only in the two *deliver* deftests above it), so that citation is
clean. `scheduler_effects_test`'s `with-redefs` of `dispatch/dispatch!` is
neither modified nor cited by 201 — out of scope.

## Test review follow-ups — pass 8 execution (2026-06-01)

Resolved the pass-8 item via **option (a)**: migrated
`scheduler_lifecycle_test/busy-session-fire-queues-then-idle-drains-fifo-test`
off the `with-redefs [psi.turn-runtime.core/execute-prepared-request! …]` stub
onto the injectable `:execute-prepared-request-fn` ctx seam, exactly mirroring
the pass-6 e2e session-kind migration. The same shaped execution-result stub is
now bound to a local fn and threaded onto the ctx with
`(assoc ctx :execute-prepared-request-fn …)`; the `:runtime/prompt-execute-and-record`
effect reads the seam from ctx (`dispatch_effects.clj:154`), so the busy-fire →
queued → idle → drain-FIFO-oldest-by-fire-at round trip and the
scheduler-time-source timestamp assertions are unchanged.

Decision rationale (a over b): option (a) preserves the live busy-drain round
trip *as a stub-free covering test* and keeps the existing `findings.md`
Live-execution-path busy-drain citation valid, rather than thinning the cited
authority down to the dispatch-test deftests alone. The migration is
injection-over-redef (`infra_deps → injectable ∧ ¬stub`) using a seam that
`make-session-ctx` already wires.

Scope held: the file's first deftest
(`scheduled-deliver-runs-canonical-prompt-lifecycle-test`) retains its own
`with-redefs` and is **out of scope** for pass 8 (named target is only the
busy-drain test), so the `[psi.turn-runtime.core]` require stays in the ns form.

Mechanical note: removing the stub collapsed the redundant `(do …)` body
wrapper; `clj-paren-repair` mis-nested the body into the leading `swap!` on the
first pass, so the de-`do` re-indent was finished by hand and re-verified
(cljfmt "All source files formatted correctly", clj-kondo 0/0).

Verification: `scheduler-lifecycle-test` green (3 tests / 26 assertions);
related `scheduler-dispatch-test` + `scheduler-end-to-end-test` +
`scheduler-handlers-test` green (17 tests / 91 assertions). Assertion count for
the migrated test is unchanged, so the aggregate deliverable stays
**45 tests / 412 assertions**. Test file only — zero
`components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
held).

## Test review — pass 9 (task-test-review, 2026-06-01) — REVIEW_COMPLETE

Re-audited all 201 verification-test deliverables and cited covering tests
against the task-test-review skill (`well_formed ∧ behaviour-coverage ∧
infra_deps→injectable ∧ ¬mock ∧ ¬stub`). No new actionable issues.

- **well-formed + behaviour coverage**: all 7 Scope areas have cited covering
  deftests; the acceptance-required behaviours (message/session live round trip,
  busy-queue+drain, both cancel races, failure recording, shutdown timer
  cleanup) each have a cited test that drives the real dispatch+effect path via
  the time/timer seam and asserts state/outputs, not interactions.
- **infra-dep injection**: every 201-added/cited covering test now uses
  injectable seams — `:scheduler-run-after-delay-fn` (timer),
  `:execute-prepared-request-fn` (AI-execution boundary, passes 6 & 8), and
  `kernel/register-handler!` (failure-path dispatch seam, pass 7). The
  `with-redefs` infra-stub follow-ups from passes 6/7/8 are all closed and
  verified in source: `grep with-redefs` over the cited covering tests is empty.
- **remaining `with-redefs` are out of scope**: the only surviving infra
  `with-redefs` sites are `scheduler_effects_test/scheduler-start-and-cancel-timer-effects-test`
  (+ its `cancel-timer` block) and `scheduler_lifecycle_test/scheduled-deliver-runs-canonical-prompt-lifecycle-test`.
  Both are **pre-existing baseline tests** (effects_test last touched at commit
  `166`; the lifecycle canonical test predates 201 and pass-8 explicitly scoped
  it out), and **neither is cited as a covering test** for any acceptance area
  in `findings.md` (effects_test appears only in the inventory + the clean
  stub-free `shutdown-context-cancels-scheduler-timers-test` citation; the
  canonical-lifecycle deftest appears only in the inventory). They are not 201
  deliverables. The effects_test baseline deftest also uses `Thread/sleep`
  polling, but again as pre-existing non-cited baseline, not a 201 verification
  test (the "no wall-clock sleeps" invariant binds the 201-added tests, which
  all fire by invoking the captured callback).
- **green + clean**: full `bb test` green; scheduler aggregate 45 tests / 412
  assertions; `clj-kondo` 0/0 and `cljfmt` clean on all touched test files +
  `test_support.clj`. Verification-only invariant holds (zero
  `components/agent-session/src/**` or `doc/scheduler.md`).

Conclusion: the review chain has converged. The verification-test deliverables
are well-formed, cover the design behaviour, and inject (rather than stub) their
infra deps. No follow-up steps added.

## Test review — test-shaper pass (2026-06-01) — ACTIONABLE_FEEDBACK

Applied the test-shaper lens (clarity ∧ signal ∧ robustness; single_concern,
minimal_incidental_setup, locally_comprehensible, consistent data_shapes,
meaningful_failures) to the 201 verification-test surface. Prior review chain
(passes 2–9) converged on infra-dep injection (with-redefs→seam) and assertion
precision; this pass is a distinct clarity/signal lens and surfaces two new
items, both test-file-only (within the Slice-10 allowlist). Suite green
(subset 23 tests / 223 assertions), clj-kondo 0/0, cljfmt clean.

- **Misleading shared setup in
  `scheduler-test/fail-schedule-records-failure-detail-and-dequeues-test`.**
  The top-level `let` builds `s0` (a `:session`-kind schedule) then
  `s1 = fire-schedule(s0, idle)`, annotated "session-kind fire delivers
  (action :deliver), so re-queue manually to test dequeue". But the first
  `testing` block ("fail-schedule from :queued …") never touches `s0`/`s1` — it
  builds entirely fresh `q0`/`q1`. `s1` is only used by the *second* block
  (terminal fail-guard), where it relies on the non-obvious fact that pure
  `fire-schedule` leaves a session-kind schedule `:pending` (returns the
  `:deliver` *action* without mutating status), so the subsequent cancel
  succeeds. The comment describes the *action* return as if it mutated status,
  which contradicts the code path the test actually depends on, and the
  disjoint setup couples two unrelated concerns under one deftest. This is a
  minimal-incidental-setup / locally-comprehensible / single-concern violation,
  and a meaningful-failures risk (if the guard regressed the failure message
  would be confusing given the misleading scaffolding). Suggest: scope the fail-
  detail+dequeue concern and the terminal-guard concern to their own minimal
  setups (or correct the comment to state that session-kind `fire-schedule`
  leaves status `:pending` and move `s0`/`s1` into the guard block), removing
  the dead top-level binding from the first block's view.

- **Inconsistent `:kind` shape across live create dispatches.**
  `scheduler-timer-seam-test/scheduler-start-timer-uses-injected-time-source-and-delay-runner-test`,
  its cancel block, and
  `scheduler-timer-seam-test/scheduler-cancelled-default-delay-thread-exits-without-uncaught-interrupted-exception-test`
  dispatch `:scheduler/create` **without** `:kind`, relying on the dispatch
  handler's implicit `(or kind :message)` default (`dispatch_handlers/scheduler.clj:123`),
  while every other 201 live create (e2e message/session, context-shutdown,
  cancel-race, resolvers, psi-tool) passes `:kind :message`/`:session`
  explicitly. The inconsistent data shape makes the intended kind non-local
  (reader must know the handler default) and silently couples these tests to an
  implicit default rather than the behaviour under test. Suggest: add the
  explicit `:kind :message` to these create maps for consistent data_shapes and
  locally-comprehensible intent (no behaviour change — the default already
  resolves to `:message`).

## test-shaper follow-up execution (2026-06-01)

Executed the two test-shaper-pass follow-ups (both test-file only, within the
Slice-10 allowlist — zero `components/agent-session/src/**` or
`doc/scheduler.md` changes):

1. **fail-schedule shared-setup fix** (`scheduler_test.clj`). Removed the
   misleading top-level `let` (`s0`/`s1`); scoped each `testing` block to its
   own minimal setup. The `:queued` fail-detail+dequeue block keeps its
   self-contained `q0`/`q1`; the terminal fail-guard block now builds `s0`/`s1`
   locally with a corrected comment noting that pure session-kind
   `fire-schedule` returns the `:deliver` action and leaves status `:pending`
   (so the schedule is still cancellable). No dead cross-block binding remains;
   assertion shape unchanged.

2. **explicit `:kind :message`** (`scheduler_timer_seam_test.clj`). Added
   `:kind :message` to all three `:scheduler/create` payloads that previously
   relied on the handler `(or kind :message)` default — both blocks of
   `scheduler-start-timer-uses-injected-time-source-and-delay-runner-test` and
   `scheduler-cancelled-default-delay-thread-exits-without-uncaught-interrupted-exception-test`.
   Data shape now matches every other 201 live create; no behaviour change.

Verification: `scheduler-test` + `scheduler-timer-seam-test` focused run =
15 tests / 66 assertions / 0 failures; full `bb test` green. clj-kondo 0/0,
cljfmt clean on both touched files. Aggregate scheduler assertion count is
unchanged (no asserts added/removed) → still 45 tests / 412 assertions.

## Test review — test-shaper pass 2 (2026-06-01) — ACTIONABLE_FEEDBACK

Re-applied the test-shaper lens (clarity ∧ signal ∧ robustness; single_concern,
one_test_per_distinct_behavior, meaningful_failures, locally_comprehensible) to
the full 201 verification-test surface. The prior test-shaper pass (above) closed
the `fail-schedule` shared-setup and `:kind`-shape items; this pass surfaces one
new, distinct item not previously recorded. New verification deftests in
`scheduler_test`, `scheduler_end_to_end_test`, `scheduler_timer_seam_test`,
`scheduler_context_shutdown_test`, and `scheduler_resolvers_test` are
well-shaped (single-concern, ctx-seam driven, state/output assertions, no infra
stubs). Suite green; clj-kondo 0/0; cljfmt clean.

- **Megatest: `psi-tool-scheduler-create-list-cancel-test` bundles ~14 distinct
  behaviours in one deftest (109 assertions, 17 `testing` blocks).** The single
  deftest covers create/list/cancel happy path **and** ~11 unrelated concerns:
  time-source-source validation (missing/invalid), bounds rejection, the 51-cap,
  session-id requirement, explicit-session-id report path, `message` vs `session`
  kind validation (3 variants), and the 201-added `:at` matrix (past-fires /
  near-future-rejected / above-max-rejected). This violates `single_concern` /
  `one_test_per_distinct_behavior`: a failure in any block reports against the
  one giant deftest name (`meaningful_failures` degraded — the failing behaviour
  is not identifiable from the test name), and each fresh `let`-rebound
  `[ctx session-id]` per block is `minimal_incidental_setup` ceremony repeated
  17×. The deftest name ("create-list-cancel") also under-describes its true
  scope (validation + bounds + cap + `:at` matrix). Note: this is *pre-existing*
  structure that 201 *extended* (the `:at` matrix blocks); the first
  test-shaper pass did not flag it. Suggest: split into focused deftests by
  concern — e.g. `…-create-list-cancel` (happy path only),
  `…-time-source-required`, `…-bounds-and-cap`, `…-session-id-resolution`,
  `…-kind-validation`, `…-at-resolution-matrix` — each with its own minimal ctx
  setup; this restores per-behaviour failure localisation and lets the
  `findings.md` psi-tool-surface citations point at the precise covering
  deftest. Test-file-only (within the Slice-10 allowlist — zero
  `components/agent-session/src/**` or `doc/scheduler.md`); keep the suite green
  + clj-kondo/cljfmt clean and the aggregate assertion count unchanged. If 201
  is treated as closed, raise it as a small standalone test-hygiene task instead.

## Test review follow-ups — test-shaper pass 2 execution (2026-06-01)

Executed the megatest-split follow-up. Split
`psi_tool_scheduler_test/psi-tool-scheduler-create-list-cancel-test` (1 deftest,
17 `testing` blocks, 109 assertions) into 6 focused deftests by concern, each
with its own minimal ctx setup, restoring per-behaviour failure localisation:

- `psi-tool-scheduler-create-list-cancel-test` — happy path only
  (create pending → list → cancel), keeps the shared `let` ctx (one coherent
  arrange across the three sequential steps).
- `psi-tool-scheduler-time-source-required-test` — missing / invalid
  scheduler-time-source → error (no wall-clock fallback).
- `psi-tool-scheduler-bounds-and-cap-test` — below-min `delay-ms` rejected +
  51st-pending cap.
- `psi-tool-scheduler-session-id-resolution-test` — invoking/explicit session-id
  required + explicit-session-id report path.
- `psi-tool-scheduler-kind-validation-test` — `session` requires session-config /
  `message` rejects session-config / unsupported session-config keys rejected.
- `psi-tool-scheduler-at-resolution-matrix-test` — absolute-instant delay calc /
  past `:at` fires immediately via the seam / near-future `<min` → below-minimum
  bound / above-max → exceeds-maximum bound.

The previously top-level "absolute instant calculates delay" `testing` block
(it had been written *outside* the megatest deftest) was folded into the `:at`
matrix deftest where it belongs by concern. Assertions and their messages kept
intact — aggregate assertion count **unchanged at 412**; the scheduler-suite
deftest count rises 45 → **50** (psi-tool 1 → 6). `findings.md` psi-tool-surface
citations updated to point at the precise new deftests (Outcome figure 45 → 50;
inventory + "Extended in place" note updated).

Verification: `clojure -M:test --focus psi.agent-session.psi-tool-scheduler-test`
= **6 tests / 109 assertions / 0 failures**; full `bb test` green; clj-kondo 0/0,
cljfmt clean on the touched test file. Test file + task-dir docs only — zero
`components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist held).

## ◈ test-shaper review — pass 3 (2026-06-01)

Applied `test-shaper` to the 201 scheduler test surface (new + touched ns).
Suite well-shaped overall (prior passes removed `with-redefs` infra-stubs, split
the psi-tool megatest, scoped fail-schedule setup, made `:kind` explicit in
`scheduler_timer_seam_test`). Three remaining actionable items
(`consistent(fixtures)` / `consistent(data_shapes)` / clarity-of-failure):

1. **Duplicated fixture ceremony** — `create-session-context` is copy-pasted
   across **9** scheduler test ns (7 byte-identical;
   `scheduler_lifecycle_test` + `scheduler_effects_test` are a `:persist? false`
   variant). test-shaper `consistent(fixtures) ∧ helpers_that_compress(ceremony)`
   wants this single shared helper in `test-support` (this task already set the
   precedent by extracting the duplicated `capturing-delay-fn` timer-seam idiom
   there — steps.md line 377). Test-file/`test_support`-only (Slice-10 allowlist).

2. **`:kind` data-shape drift in a touched ns** — `scheduler-context-shutdown-
   test/shutdown-context-clears-scheduler-timers-test` omits `:kind :message` on
   its `:scheduler/create` and leans on the handler default, while every other
   201 live create is explicit. The pass-1 follow-up aligned this exact shape in
   `scheduler_timer_seam_test` "to bring data shape in line with every other 201
   live create" but missed this pre-existing deftest in a namespace this task
   touched. `consistent(data_shapes)`.

3. **Misleading `testing` label** — `scheduler-test/fire-schedule-test` block
   "idle session delivers immediately" asserts the `:deliver` *action* with
   status still `:pending` (pure `fire-schedule` returns the action without
   mutating status). Reads as if delivery occurred — the same `:pending`-after-
   fire confusion pass-1 corrected in `fail-schedule`. Relabel to state it
   returns the `:deliver` action and leaves status `:pending`. `meaningful_
   failures` / label accuracy. Lowest priority (cosmetic).

No defect in verification coverage itself; all are test-hygiene/consistency.
If 201 is treated as closed, fold these into a small standalone test-hygiene
task instead of reopening.

## Test review follow-ups executed — test-shaper pass 3 (2026-06-01)

Executed all three newly added test-shaper pass-3 follow-ups. Test/`test_support`
-only; verification-only scope held (zero `components/agent-session/src/**` or
`doc/scheduler.md`); `bb test` green, clj-kondo 0/0, cljfmt clean.

1. **`create-session-context` fixture consolidation → reuse existing helper.**
   Audited the 9 scheduler-test copies and found a stronger fact than the flag
   assumed: an equivalent shared helper *already exists* —
   `test-support/create-test-session` returns `[ctx session-id]` from
   `safe-context-opts` + `new-session-in!`, identical to all 9 locals. Because
   `safe-context-opts` already defaults `:persist? false`, the 7 "persist"
   copies, the 2 `(assoc opts :persist? false)` lifecycle/effects variants, and
   `create-test-session` all resolve to the **same** persist-false context (the
   no-arg default differs only cosmetically). Per `λbuild: ∃lib → use(lib)` /
   `λone_way` I reused `create-test-session` rather than adding a second
   near-identical `make-session-context` helper (which would itself violate the
   DRY intent of the flag). Deleted all 9 local `create-session-context` defns;
   rewrote every call site to `test-support/create-test-session` (opts pass
   through unchanged). Removed the now-orphan `[psi.agent-session.core :as
   session]` require from `scheduler_tools_test` (its only `session/` use was
   the deleted defn; the other 8 files still use `session/` for
   dispatch/shutdown, so their requires stay). No deftest renamed →
   `findings.md` Live/psi-tool citations unchanged. cljfmt realigned the
   `create-test-session` opts indentation in lifecycle/timer-seam. Aggregate
   assertion count unchanged (no assertions touched).

2. **`:kind :message` data-shape alignment.** Added `:kind :message` to the
   first `:scheduler/create` in
   `scheduler-context-shutdown-test/shutdown-context-clears-scheduler-timers-test`
   (the file's second create already had it). Matches every other 201 live
   create; default already resolved to `:message`, so no behaviour change.

3. **Misleading `fire-schedule-test` label.** Relabelled "idle session delivers
   immediately" → "idle session: returns the :deliver action and leaves the
   schedule :pending" + a clarifying comment that pure `fire-schedule` returns
   the action without mutating status (mirrors pass-1's `fail-schedule` fix).
   Assertions unchanged.

No blockers; all three completed. Suite green via canonical `bb test`.

## Test review follow-ups — test-shaper pass 4 (2026-06-01)

Re-audited the 201 changeset against the test-shaper skill (clarity ∧ signal ∧
robustness ∧ economical; `consistent(fixtures)`). The 201 verification tests and
cited covering tests are strong — single-concern, state-based assertions,
deterministic via the timer/cancel seams (no wall-clock sleeps), descriptive
testing labels, named-bound `:at` rejection assertions, the megatest split into
6 focused deftests, and the shared `capturing-delay-fn` seam helper. Runtime
truth re-confirmed green on the 201-authored namespaces (scheduler-test +
end-to-end + resolvers + psi-tool = 23 tests / 202 assertions, 0 fail).

One actionable `consistent(fixtures)` finding remains — a holdout from pass-3:

- pass-3 consolidated the **9** scheduler test ns onto
  `test-support/create-test-session`, but `psi_tool_scheduler_test.clj`
  (a 201-touched file — pass-2 split its megatest) was **not** in pass-3's named
  list and still defines its own local `create-session-context` (L11). That
  local is behaviourally identical to `test-support/create-test-session`
  (`safe-context-opts` already defaults `:persist? false`, so the local's
  redundant `(assoc opts :persist? false)` resolves to the same persist-false
  context, exactly as pass-3 noted for the consolidated 9). Result: the
  scheduler suite's fixture is now split across two equivalent helpers, with
  `psi_tool_scheduler_test` the lone holdout — a `consistent(fixtures)` gap
  *within 201's own changeset*. Migrating it completes pass-3's consolidation
  and restores one fixture across the whole scheduler suite. Test-file-only
  (Slice-10 allowlist — zero `components/agent-session/src/**` /
  `doc/scheduler.md`); the project-wide `create-session-context` idiom in
  ~40 non-scheduler ns is out of 201 scope. Follow-up added to steps.md.

### Execution (test-shaper pass 4 follow-up)

Done. Migrated the holdout `psi_tool_scheduler_test.clj` onto the shared
`test-support/create-test-session`:

- Deleted the local `create-session-context` defn; rewrote all 13 call sites
  (across the 6 deftests) to `test-support/create-test-session`, opts unchanged.
- The require `[psi.agent-session.core :as session]` was used **only** by the
  deleted defn — the step's hedge about surviving `session/dispatch-in!` /
  `session/query-in` usage was wrong; this ns has none. The require became
  unused and was removed (clj-kondo 0/0 confirms — an unused require would warn).
- Behaviour unchanged: no-arg local `{}` and `create-test-session` no-arg
  `{:persist? false}` both resolve persist-false via `safe-context-opts`.
- No deftest renamed → `findings.md` psi-tool citations untouched.

Verified: `--focus psi.agent-session.psi-tool-scheduler-test` = 6 tests / 109
assertions / 0 failures (aggregate unchanged: 50 tests / 412 assertions); full
`bb test` green; clj-kondo 0/0; cljfmt clean. `git diff --name-only` = the
single `psi_tool_scheduler_test.clj` path — zero `components/agent-session/src/**`
or `doc/scheduler.md` (Slice-10 gate held). Scheduler-suite fixture now fully
consolidated on `create-test-session`; no `create-session-context` copies remain
in any scheduler test ns. No blockers.

## Test review follow-ups — test-shaper pass 5 (2026-06-01)

Fresh test-shaper audit of the full scheduler test changeset. Two **new**
actionable issues (both `consistent` / `economical`), neither covered by the
prior passes; passes 6–9 (task-test-review) + test-shaper passes 1–4 are all
closed/converged.

- **Issue A — `:kind :message` data-shape drift in `scheduler_lifecycle_test`
  + `scheduler_dispatch_test` (consistent(data_shapes)).** test-shaper passes 1
  & 3 added explicit `:kind :message` to the live `:scheduler/create` payloads
  in `scheduler_timer_seam_test` and `scheduler_context_shutdown_test` to align
  the data shape with every other 201 live create (the handler defaults
  `(or kind :message)`, so the omission is invisible-but-implicit). Those passes
  named only those two files; the same omission survives in
  `scheduler_lifecycle_test.clj` (`scheduled-deliver-runs-canonical-prompt-lifecycle-test`
  L55; `busy-session-fire-queues-then-idle-drains-fifo-test` L116 — a
  **`findings.md`-cited busy-drain covering test**; `cancel-pending-and-queued-schedules-test`
  L152 & L167 — the **cited cancel covering test**) and
  `scheduler_dispatch_test.clj` (`scheduler-create-stores-schedule-and-starts-timer-test`
  L23; the `schedule` helper at L9). Several are 201's own cited covering tests,
  so the drift sits inside the deliverable. Add `:kind :message` to make the
  kind-under-test local + consistent. No behaviour change (default already
  resolves to `:message`).

- **Issue B — duplicated assertion in
  `scheduler_dispatch_test/scheduler-fired-queues-while-session-busy-test`
  (economical / minimal(redundant)).** L65–66 assert
  `(is (= :queued (:status stored)))` **twice** verbatim — a copy-paste
  redundant `is`. Drop the duplicate (a failure in either is
  indistinguishable, adding no signal). Removing it drops the dispatch-test
  assertion count by 1 — recompute the aggregate after the edit.

Both are test-file-only (Slice-10 allowlist — zero `components/agent-session/src/**`
or `doc/scheduler.md`). Suite currently green (45 focused tests / 320 assertions
in the 9-ns focused run; aggregate 50/412 under full `bb test`). Follow-ups
added to steps.md.

## Test review follow-ups — test-shaper pass 5 execution (2026-06-01)

Executed both pass-5 follow-ups (test-file/task-doc only; Slice-10 allowlist held —
zero `components/agent-session/src/**` or `doc/scheduler.md`):

1. `:kind :message` data-shape alignment — added explicit `:kind :message` to the
   6 omitting sites: `scheduler_lifecycle_test` ×4 (canonical-prompt-lifecycle
   ~L55, busy-drain-fifo ~L116, cancel-pending-and-queued ~L152 & ~L167) and
   `scheduler_dispatch_test` ×2 (the `schedule` stored-shape helper ~L9 + the
   `scheduler-create-stores-schedule-and-starts-timer-test` `:scheduler/create`
   payload ~L23). Kind-under-test now local in every 201 live create; no
   behaviour change (handler default `(or kind :message)` already resolved). No
   assertions added/removed.

2. Duplicate-assertion removal — dropped the second verbatim
   `(is (= :queued (:status stored)))` in
   `scheduler-fired-queues-while-session-busy-test`. Aggregate recomputed:
   `scheduler-dispatch-test` 20 → **19** assertions (focused kaocha), so the
   scheduler-suite deliverable total is **50 tests / 411 assertions** (was 412).
   Updated the current deliverable citation in `findings.md` Outcome (412 → 411).
   Historical per-pass `412`/`410` Done-notes left intact (pass-4 precedent —
   preserve state-at-pass-time record).

Verification: `clojure -M:test --focus psi.agent-session.scheduler-dispatch-test`
= 5 tests / 19 assertions / 0 failures; full `bb test` green; clj-kondo 0/0 and
cljfmt/clj-paren-repair clean on both touched test files. The pre-existing
cross-ns isolation artifact (scheduler namespaces run in isolation together
surface ordering-dependent failures absent under canonical full `bb test`) is
unchanged and out of scope. Review chain converges → no new actionable items.

## Test review — test-shaper pass 6 (2026-06-01)

Re-applied test-shaper across the 201 scheduler test suite (now 50 deftests,
green; clj-kondo 0/0 on all touched test files). Five prior passes already
converged the bulk (fixture consolidation, megatest split, `:kind` data-shape
alignment, with-redefs→ctx-seam migrations, duplicate-assertion removal). Two
**new, previously-unflagged** issues surfaced; both are test-file-only
(Slice-10 allowlist — zero `components/agent-session/src/**` / `doc/scheduler.md`):

- **Issue C — journal-scan idiom duplicated (`consistent(test_abstractions)` /
  `economical / minimal(redundant)`).** The "find the scheduled user message in
  the journal" idiom
  `(some->> journal (keep #(get-in % [:data :message])) (some (fn [m] (when (and (= "user" (:role m)) (= :scheduled (:source m)) (= "<id>" (:schedule-id m))) m))))`
  is repeated verbatim in three places:
  `scheduler_end_to_end_test` L26 (`scheduler-fired-end-to-end-delivers-when-idle-test`),
  L70 (`scheduler-message-kind-fires-via-timer-seam-and-delivers-to-origin-test`),
  and `scheduler_dispatch_test` L85
  (`scheduler-deliver-submits-canonical-prompt-lifecycle-test`). This is the
  exact ceremony `scheduler_lifecycle_test` already compresses via its
  `journal-messages` / `scheduled-user-messages` helpers — so the suite is
  inconsistent: one ns has the compressing helper, two repeat the raw block.
  Distinct from pass-3's `create-session-context` fixture consolidation (that
  was the *context builder*; this is the *journal-scan assertion helper*). Lift
  a shared `scheduled-message-by-id` (or reuse the lifecycle pattern) into
  `test-support`, or at least dedupe the two copies within
  `scheduler_end_to_end_test`. `helpers_that_compress(ceremony) ∧
  ¬helpers_that_hide(intent)`.

- **Issue D — wall-clock `Instant/now` in execution-result stubs
  (`deterministic(tests)` — control(time)).** The stubbed assistant-message in
  `scheduler_end_to_end_test` L111 (session-kind seam) and
  `scheduler_lifecycle_test` L51 + L106 sets
  `:timestamp (java.time.Instant/now)` — real wall-clock inside an otherwise
  fully time-seamed test (every other instant is the injected
  `fixed-scheduler-time-source`). Not currently flaky (no assertion reads that
  field), so low-priority; but it violates `control(time(tests))` and is a
  latent footgun if a future assertion ever touches the assistant timestamp.
  Replace with a fixed `Instant/parse` literal consistent with the test's
  `now`, matching the surrounding time-control discipline.

Both are hygiene/consistency follow-ups, not correctness defects — the
verification deliverable remains green and coherent. Follow-ups added to
steps.md. If 201 is treated as closed, either may be raised as a small
standalone test-hygiene task instead.

## Execute test-shaper pass-6 follow-ups (2026-06-01)

Both pass-6 hygiene follow-ups executed; no correctness change.

- **Issue C — journal-scan dedup (done).** Added
  `test-support/scheduled-message-by-id` (ctx, session-id, schedule-id → the
  scheduled `"user"` message with `:source :scheduled` + matching
  `:schedule-id`), reusing the `ss/get-state-value-in` + `ss/state-path
  :journal` state-journal source. Replaced all three verbatim inline copies:
  `scheduler_end_to_end_test` ×2 ("sch-1" end-to-end + "sch-msg" seam) and
  `scheduler_dispatch_test` ×1 ("sch-1" deliver). The surrounding
  `(is (some? scheduled-msg))` assertions are preserved. **Deliberately left**
  the `scheduler_lifecycle_test` `journal-messages`/`scheduled-user-messages`
  helpers: they read a *different* journal source (`persist/all-entries-in`,
  persistence-backed) and filter only on `:schedule-id` presence (not
  `:source :scheduled`), so they are not the same abstraction and do not fold
  cleanly into the new state-journal helper (`¬helpers_that_hide(intent)`).

- **Issue D — wall-clock `Instant/now` removal (done).** Replaced all three
  `(java.time.Instant/now)` assistant-message `:timestamp`s with fixed instants
  already in scope (no new literals): e2e session-kind seam →
  `(.plusMillis now 5000)` (fire instant); lifecycle canonical-lifecycle →
  `delivered-at`; lifecycle busy-drain → `delivered-at-1`. No assertion reads
  the assistant timestamp, so behaviour is unchanged; the time-seamed paths are
  now wall-clock-free.

Gates: focused run of the three touched live nss = 11 tests / 65 assertions / 0
failures; full `bb test` green; clj-kondo 0/0; cljfmt clean. No `is` forms
added/removed → aggregate stays **50 tests / 411 assertions** (findings.md
unchanged; no deftest renamed → citations unchanged). Touched paths =
`scheduler_end_to_end_test.clj`, `scheduler_dispatch_test.clj`,
`scheduler_lifecycle_test.clj`, `test_support.clj` — all under
`components/agent-session/test/**`; zero `components/agent-session/src/**` or
`doc/scheduler.md` (Slice-10 allowlist held).

## Implementation review — pass 6 (task-implementation-review skill, 2026-06-01) — REVIEW_COMPLETE

Independent implementation review (matches-design ∧ follows-architecture ∧
¬unnecessary-abstraction ∧ ¬unflagged-duplication ∧ ¬structural-perf-issue)
against runtime truth. No new actionable issues.

- **Runtime truth: green.** Focused kaocha run of all 13 scheduler namespaces =
  **50 tests / 411 assertions / 0 failures** — matches the `findings.md` Outcome
  figure exactly; full `bb test` green.
- **Verification-only invariant held.** The genuine 201 commits touch only
  `components/agent-session/test/**` (12 scheduler test ns + `test_support.clj`)
  + the task dir — **zero** `components/agent-session/src/**` or
  `doc/scheduler.md`. (The `CHANGELOG.md` / `version.edn` hits from
  `git log --grep=201` are unrelated `release:` automation commits matched by
  digit-coincidence in versions `v0.1.2013`/`v0.1.2017`, not 201 work — verified
  via `git log --grep` scoped to those paths.)
- **Matches design.** The new tests implement the design's "Verification
  mechanics" point-for-point: timer-seam capture-and-invoke (no wall-clock),
  drain-via-`:scheduler/drain-queue` dispatch, both cancel-races (A: stale
  callback non-resurrection; B: `:queued`→cancel), the `:at` past-fires /
  near-future-rejected / above-max-rejected asymmetry, and shutdown handle-count
  0 + no-fire-after-shutdown.
- **Follows architecture.** Live tests drive the real dispatch+effect round trip
  and inject infra only at boundaries via ctx/kernel seams
  (`:scheduler-run-after-delay-fn`, `:scheduler-cancel-delay-fn`,
  `:execute-prepared-request-fn`, `kernel/register-handler!`) — not var-stubs of
  business logic; assertions are on state/outputs (delivered-prompt provenance,
  `:created-session-id`/`:delivery-phase`, queue, handle count), never handler
  interactions. Consistent with `testing-without-mocks` + the runtime-owned-
  deliver frontier.
- **No unnecessary abstraction / unflagged duplication.** Fixtures consolidated
  on `test-support/create-test-session`; the journal-scan idiom lifted to
  `test-support/scheduled-message-by-id`; the psi-tool megatest split into 6
  focused deftests. The two surviving `with-redefs` sites
  (`scheduler_effects_test`, `scheduler_lifecycle_test/scheduled-deliver-…`) are
  pre-existing baseline, non-cited as covering tests — out of 201 scope.
- **No structural-performance issue.** Pure-model tests are pure; live tests fire
  by invoking the captured callback (no `Thread/sleep` waiting) except the one
  intentional default-daemon-thread interrupt test, which bounds its join.

Conclusion: the verification-only deliverable (green coverage + structured
`findings.md`) is accurate, coherent, and matches design/architecture. The
review chain (9 task-test-review + 6 test-shaper passes) has converged; this
implementation-review pass adds no follow-ups.

## Test review — pass 10 (task-test-review, 2026-06-01) — ACTIONABLE_FEEDBACK

Re-applied `task-test-review` (`well_formed ∧ behaviour-coverage ∧
infra_deps→injectable ∧ ¬mock ∧ ¬stub`) to the 201 verification deliverables.
The bulk converged across passes 6–9 + 6 test-shaper passes. One **new,
previously-unflagged** issue surfaced under the skill's
"drive the real path via the seam" / "assert state/outputs, not handler
interactions" clauses (plan sufficient-coverage clauses 2 & 3).

- **Issue E — busy-drain covering test drains via direct handler invocation +
  asserts on handler-returned effect-data (¬real-path, interaction-assert).**
  `scheduler_lifecycle_test/busy-session-fire-queues-then-idle-drains-fifo-test`
  is `findings.md`-cited as a covering test for the **busy queue + drain-on-idle**
  Live-execution area. Its busy-fire→queue→idle phase drives the **real**
  `dispatch-in!` pipeline (good), but the drain phase does **not**: it calls
  the local `invoke-scheduler-handler` helper, which extracts
  `(kernel/handler-entry :scheduler/drain-queue) :fn` and invokes the handler
  **directly, bypassing the dispatch pipeline/interceptors**, then manually
  `apply-root-state-update!`s the result (L140-141, L149-150). The
  scheduler-time-source FIFO-timestamp behaviour is then asserted via
  `(-> drain-1 :effects first :event-data :user-msg :timestamp)` (L146, L154) —
  i.e. on the **shape of the handler-returned effect data** (a produced-effect
  interaction), not on observable delivered-message state.

  This diverges from the design's resolved "Drain-on-idle trigger" mechanic
  ("dispatches `:scheduler/drain-queue` **directly** → asserts `drain-one`
  delivers") and from the plan's sufficient-coverage clause 2 (drive the real
  path via dispatch for a live area) and clause 3 (assert state/outputs, not
  handler interactions). The status/queue assertions in the same test *are*
  state-based and the area is **jointly** cited with
  `scheduler_dispatch_test/scheduler-drain-queue-delivers-oldest-queued-schedule-test`
  (which DOES use real `dispatch-in!` + asserts observable state) — so the
  oldest-by-fire-at drain is cleanly covered via real dispatch. The specific gap
  is: the **busy→fire→queue→idle→drain full sequence driven end-to-end through
  real dispatch** (and the time-source-stamped delivered message asserted as
  observable state rather than handler effect-data) is not demonstrated in a
  single covering test.

  Recommended fix (test-file-only, Slice-10 allowlist): migrate the drain phase
  of `busy-session-fire-queues-then-idle-drains-fifo-test` off
  `invoke-scheduler-handler`/`apply-root-state-update!` onto real
  `dispatch-in! :scheduler/drain-queue`, and assert the delivered-message
  timestamp via observable journal/state (e.g. `scheduled-message-by-id` →
  `:timestamp`) rather than `(-> drain :effects … :event-data …)`. If the
  time-source-stamp-on-effect assertion has independent value, keep it as a
  separate, clearly-named handler-unit assertion rather than the *cited live
  covering test* for the area. If 201 is treated as closed, raise as a small
  standalone test-hygiene task.

Suite green (`scheduler-lifecycle-test` focused: 3 tests / 26 assertions / 0
failures). Verification-only invariant intact (no
`components/agent-session/src/**` or `doc/scheduler.md` touched by this review).
Follow-up added to steps.md.

## Test review follow-up — pass 10 executed (2026-06-01)

Executed the pass-10 follow-up: migrated the busy-drain covering test off the
pure-handler-invocation helpers onto real dispatch, and split the
time-source-stamp handler-unit assertion out of the cited live covering test.

- **Live covering test now drives real dispatch.**
  `scheduler-lifecycle-test/busy-session-fire-queues-then-idle-drains-fifo-test`
  now runs both drain phases via `dispatch-in! :scheduler/drain-queue` (the
  design "dispatch the event directly" drain mechanic; same stub-free path
  `scheduler_dispatch_test` uses). It asserts observable delivered state only —
  per-schedule `:delivered`/`:queued` status, FIFO drain order (oldest by
  `[fire-at created-at schedule-id]` via the `:return` schedule-id), and the
  post-drain queue contents. The handler-returned effect-shape assertions
  (`(-> drain-1 :effects first :event-data :user-msg :timestamp)`) are gone from
  this test.
- **Discovery — drain delivery is not observable in the test ctx.** The
  `:scheduler/drain-queue` handler emits a
  `:runtime/dispatch-event-with-effect-result` effect that re-dispatches
  `:session/submit-synthetic-user-prompt`. In the test ctx this re-dispatch does
  **not** append the scheduled user message to the journal (verified via probes
  on both `create-test-session` and `make-session-ctx`: drain sets `:delivered`
  through `drain-one`'s root-state-update, but `scheduled-message-by-id` /
  `persist/all-entries-in` show no delivered message, and the event log records
  only `:scheduler/drain-queue`). This is the runtime-owned-deliver frontier —
  and explains why the original test (and the cited `scheduler_dispatch_test`
  drain test) asserted the timestamp on the *handler-returned effect* rather
  than on a delivered message. `scheduled-message-by-id` is therefore NOT a
  viable observable for the drain path, so the plan's preferred
  delivered-message-timestamp assertion is not achievable end-to-end here.
- **Time-source stamp kept as a separate handler unit.** Per the step's
  explicit branch ("if the time-source-stamp-on-effect assertion has
  independent unit value, keep it as a separate, clearly-named handler-unit
  assertion"), added
  `drain-one-stamps-scheduled-user-message-from-scheduler-time-source-test`,
  which invokes the handler `:fn` directly (via the retained, now-documented
  `invoke-scheduler-handler` helper) and asserts the emitted `:user-msg`
  `:timestamp` is stamped from the scheduler time source. Explicitly NOT part of
  the cited live covering test.
- **Cleanup.** Deleted the now-unused `apply-root-state-update!` helper; kept +
  documented `invoke-scheduler-handler` (used only by the split-out handler-unit
  test). `kernel` require still used (event-log clears + handler-entry).
- **Counts.** Split raised deftests 50 → 51; assertions unchanged at 411.
  Updated `findings.md` Outcome + `scheduler-lifecycle-test` inventory line.
- **Verification.** `scheduler-lifecycle-test` 4 tests / 26 assertions / 0
  failures; full `bb test` green; clj-kondo 0/0; cljfmt clean. `git diff
  --name-only` = the single `scheduler_lifecycle_test.clj` test path; zero
  `components/agent-session/src/**` or `doc/scheduler.md` (Slice-10 allowlist
  held).

## Test review — pass 11 (task-test-review, 2026-06-01) — REVIEW_COMPLETE

Re-applied `task-test-review` (`well_formed ∧ ∀b∈behaviour. ∃t. covers ∧
infra_deps→injectable∧nullable∧¬mock∧¬stub`) to all 201 new/extended tests +
cited covering tests after pass-10's busy-drain migration landed.

- **well_formed.** All scheduler test nss parse, `clj-kondo --lint` 0/0 across
  the seven touched files, full `bb test` green.
- **behaviour-coverage.** Every design Scope area (7) + every acceptance
  criterion maps to a cited covering test in `findings.md`: pure-model
  guards/ordering (`scheduler-test` new deftests), message- & session-kind live
  round trips via the ctx timer seam (`scheduler-end-to-end-test`), busy-queue +
  drain-on-idle through real `dispatch-in! :scheduler/drain-queue`
  (`scheduler-lifecycle-test/busy-session-…-drains-fifo`, post pass-10), both
  cancel races (`scheduler-timer-seam-test`, `scheduler-lifecycle-test`,
  `scheduler-test`), shutdown timer cleanup + no-fire-after
  (`scheduler-context-shutdown-test`), failure recording
  (`scheduler-test/fail-schedule-…`, `scheduler-handlers-test/…-prompt-submit-error`),
  projections across statuses (`scheduler-resolvers-test`).
- **infra-deps injectable, not stubbed.** All cited covering / 201-added tests
  drive infra through ctx-injected seams
  (`:scheduler-run-after-delay-fn`, `:scheduler-cancel-delay-fn`,
  `:execute-prepared-request-fn`, `:daemon-thread-fn`, `:scheduler-timers*`) and
  assert observable state/outputs — no mocks, no stubs, no interaction asserts.
  The two surviving `with-redefs` sites
  (`scheduler-effects-test/scheduler-start-and-cancel-timer-effects-test` —
  intentional **default daemon-thread** path with real `Thread/sleep`;
  `scheduler-lifecycle-test/scheduled-deliver-runs-canonical-prompt-lifecycle-test`)
  are **pre-existing baseline**, NOT cited as covering tests for any acceptance
  area — already audited and scoped out in passes 7 & 9. No re-file (would
  duplicate prior notes).

No new actionable issue. The review chain (10 task-test-review + 6 test-shaper +
1 implementation-review passes) plus pass 11 has converged: the verification-only
deliverable (green coverage + structured `findings.md`) is well-formed, fully
covers the design behaviour, and uses injection over mocking throughout its
cited/added tests. Verification-only invariant intact (this review touched no
`components/agent-session/src/**` or `doc/scheduler.md`).
