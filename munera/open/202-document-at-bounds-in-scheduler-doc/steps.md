# Steps — Document absolute-`:at` delay bounds in `doc/scheduler.md`

## Slice 1 — Behaviour grounding

- [x] Read `munera/open/202-document-at-bounds-in-scheduler-doc/design.md` and confirm the task is documentation-only.
- [x] Read `doc/scheduler.md` "Create validation rules" and identify the current absolute-`:at` bounds wording.
- [x] Read `resolve-fire-time!`, `millis-until`, and `validate-delay-ms!` in `components/agent-session/src/psi/agent_session/psi_tool_scheduler.clj` to confirm absolute `:at` is validated by resolved millisecond delay.
- [x] Read the existing task 201 scheduler verification coverage for the absolute-`:at` resolution matrix and note the covered boundary cases.

## Slice 2 — Documentation update

- [x] Update `doc/scheduler.md` "Create validation rules" to state that absolute `:at` values are first resolved to a millisecond delay.
- [x] Document that absolute `:at` values resolving to delay `0` fire immediately, including past/now and sub-millisecond future instants that truncate to `0ms`.
- [x] Document that future absolute `:at` values resolving to positive `1–999ms` delays are rejected by the minimum bound.
- [x] Document that future absolute `:at` values resolving beyond `24h` are rejected by the maximum bound.
- [x] Ensure the wording does not imply relative `:delay-ms 0` is accepted.

## Slice 3 — Coherence verification

- [x] Re-read `doc/scheduler.md` and compare the updated wording against `design.md` acceptance criteria.
- [x] Compare the updated wording against `resolve-fire-time!` / `validate-delay-ms!` to ensure no behaviour change is implied.
- [x] Optionally run the focused scheduler verification test covering the absolute-`:at` resolution matrix, or record why existing task 201 proof is sufficient for this doc-only change.

## Slice 4 — Task record update

- [x] Append an implementation note summarizing the documentation update and verification evidence.
- [x] Check off completed `steps.md` items after executing the corresponding actions.

## Review follow-ups

- [x] Reconcile `steps.md` with the already-applied `doc/scheduler.md` absolute-`:at` wording recorded in `implementation.md`: check completed documentation/verification/task-record items or adjust them so the task no longer implies the doc update is still pending.
