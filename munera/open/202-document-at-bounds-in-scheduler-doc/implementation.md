# Implementation notes

## Design ambiguity review — 2026-06-02

Reviewed `design.md` against `doc/scheduler.md`, `resolve-fire-time!`, `validate-delay-ms!`, task 201 findings, and `psi-tool-scheduler-at-resolution-matrix-test`. No new actionable design ambiguities found. The design clearly scopes a doc-only update for absolute `:at` bound behaviour: past/now fires immediately; positive future delays below `min-delay-ms` or above `max-delay-ms` are rejected.

## Design ambiguity review — 2026-06-02 (sub-ms `:at` precision)

Reviewed `design.md` against `resolve-fire-time!`, `millis-until`, `doc/scheduler.md`, task 201 findings, and the `:at` resolution matrix. Found one new actionable ambiguity: the design sometimes says any future `:at` below `min-delay-ms` is rejected, but the implementation validates only a positive **millisecond** delay; sub-millisecond future instants truncate to delay 0 and fire immediately. The doc wording should be clarified around positive resolved millisecond delays (1–999ms) so it does not overclaim all future instants below the minimum are rejected.

## Design ambiguity follow-up — 2026-06-02

Completed the new ambiguity follow-up. Updated `design.md` so absolute `:at` bounds are described in terms of the resolved millisecond delay: delay 0 fires immediately (past/now and sub-millisecond future truncation), positive 1–999ms delays are rejected by the minimum bound, and positive delays above 24h are rejected by the maximum bound. Updated `doc/scheduler.md` with the same wording and checked the item in `design-steps.md`.

## Design inconsistency review — 2026-06-02

Reviewed `design.md` against `doc/scheduler.md`, `resolve-fire-time!`/`millis-until`, `validate-delay-ms!`, task 201 findings, and `psi-tool-scheduler-at-resolution-matrix-test`. No new actionable inconsistencies found. The design, current scheduler doc, implementation, and verification tests consistently describe absolute `:at` bounds in terms of the resolved millisecond delay: delay 0 fires immediately; positive 1–999ms is rejected by the minimum; positive delay above `max-delay-ms` is rejected by the maximum.

## Plan ambiguity review — 2026-06-02

Reviewed `plan.md` and `steps.md` against `design.md`, `doc/scheduler.md`, `resolve-fire-time!`/`millis-until`, `validate-delay-ms!`, and `psi-tool-scheduler-at-resolution-matrix-test`. No new actionable plan/steps ambiguities found. The plan and steps clearly describe a documentation-only slice, the absolute-`:at` bounds in terms of resolved millisecond delay, and the optional verification choice to either run the focused matrix test or record reliance on task 201 proof.

## Plan inconsistency review — 2026-06-02

Reviewed `plan.md` and `steps.md` against `implementation.md`, `design.md`, updated `doc/scheduler.md`, `resolve-fire-time!`/`millis-until`, and `psi-tool-scheduler-at-resolution-matrix-test`. Found one new actionable inconsistency: `implementation.md` records that the design ambiguity follow-up already updated `doc/scheduler.md`, and the doc now contains the required absolute-`:at` bounds wording, but `steps.md` still leaves the Slice 2 documentation-update items and related verification/task-record items unchecked as if the doc update has not happened. Reconcile the task steps with the already-applied doc update/evidence before doing further execution so the task record does not imply duplicate pending documentation work.

## Plan inconsistency follow-up — 2026-06-02

Completed the newly added reconciliation follow-up. Re-read `design.md`, `plan.md`, `steps.md`, `implementation.md`, current `doc/scheduler.md`, `resolve-fire-time!`/`millis-until`, `validate-delay-ms!`, task 201 findings, and `psi-tool-scheduler-at-resolution-matrix-test`. Confirmed this remains documentation-only and that the already-applied scheduler doc wording matches the acceptance criteria: absolute `:at` is resolved to millisecond delay; delay 0 fires immediately; positive 1–999ms is rejected by the minimum; positive delay beyond 24h is rejected by the maximum; relative `:delay-ms 0` remains rejected by `validate-delay-ms!`. Existing task 201 proof is sufficient for this doc-only reconciliation, so no additional test run was needed. Reconciled `steps.md` by checking the already-completed grounding, documentation, verification, task-record, and review-follow-up items so the task record no longer implies duplicate pending doc work.

## Independent implementation verification — 2026-06-02

Re-read the task artifacts, current `doc/scheduler.md`, `resolve-fire-time!`/`millis-until`, `validate-delay-ms!`, and the task 201 `psi-tool-scheduler-at-resolution-matrix-test`. Confirmed all task checklist items are already complete and no further documentation or code change is needed. Ran focused verification: `clojure -M:test --focus psi.agent-session.psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix-test` → 1 test, 16 assertions, 0 failures.

## Implementation review — 2026-06-02

Applied `task-implementation-review`: re-read task artifacts, `doc/scheduler.md`, `resolve-fire-time!`/`millis-until`, `validate-delay-ms!`, and the task 201 `psi-tool-scheduler-at-resolution-matrix-test`. The doc-only implementation matches the design and runtime behaviour: absolute `:at` is documented by resolved millisecond delay; delay `0` fires immediately; positive `1–999ms` rejects by the minimum; positive delay beyond `24h` rejects by the maximum; relative `:delay-ms 0` remains rejected. Re-ran focused verification: `clojure -M:test --focus psi.agent-session.psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix-test` → 1 test, 16 assertions, 0 failures. No new actionable implementation issues found.

## Test review — 2026-06-02

Applied `task-test-review`: re-read task artifacts, `doc/scheduler.md`, `resolve-fire-time!`/`millis-until`, `validate-delay-ms!`, and `psi-tool-scheduler-at-resolution-matrix-test`. Re-ran focused verification: `clojure -M:test --focus psi.agent-session.psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix-test` → 1 test, 16 assertions, 0 failures. Found one actionable test coverage gap: the doc/design now explicitly include the sub-millisecond future `:at` truncation case (`Duration.toMillis` → resolved delay `0` → accepted/immediate), but the existing matrix covers past delay-0, 500ms below-minimum, 5000ms accepted, and >24h rejected; it does not prove the sub-millisecond future boundary that motivated the wording refinement. Add or identify executable coverage for that case.
