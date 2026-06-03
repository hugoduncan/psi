# 202 — Document absolute-`:at` delay bounds in doc/scheduler.md

## Goal

Close a documentation gap discovered by the verification-only scheduler audit
(task `201-verify-scheduler-execution`): `doc/scheduler.md` "Create validation
rules" documents only the *relative*-delay bounds and "past absolute instants
fire immediately", but is **silent** on how a future absolute `:at` interacts
with the same min/max bounds.

## Context

Source of truth: `components/agent-session/src/psi/agent_session/psi_tool_scheduler.clj`
`resolve-fire-time!` resolves an absolute `:at` to a relative `delay`, then runs
`validate-delay-ms!` **only when the delay is strictly positive**
(`(when (pos? delay) (validate-delay-ms! (int delay)))`). Consequently:

- past/now `:at` → resolved `delay` ≤ 0 → no min-delay check → created and
  fires immediately (delay 0).
- future `:at` resolving to a positive millisecond delay below `min-delay-ms`
  (1–999ms) → positive delay below the minimum → **rejected** (below-minimum
  bound).
- future `:at` less than 1ms ahead resolves to delay 0 by millisecond
  truncation, so it follows the delay-0 path and fires immediately rather than
  being rejected by the minimum bound.
- `:at` resolving to a positive millisecond delay above `max-delay-ms` (>24h)
  → positive delay above the maximum → **rejected** (exceeds-maximum bound).

The doc currently states only the past-instant case, so the near-future and
>24h rejection behaviour is undocumented doc↔behaviour drift.

This task is referenced from the corrected `findings.md` "psi-tool surface"
`:at`-asymmetry row in task 201.

## Scope

- Extend `doc/scheduler.md` "Create validation rules" to document the
  absolute-`:at` bound behaviour:
  - `:at` values resolving to delay 0 fire immediately (past/now, plus any
    sub-millisecond future instant that truncates to 0ms; no min check);
  - future `:at` values resolving to a positive delay below `min-delay-ms`
    (1–999ms) are rejected (below-minimum bound);
  - `:at` values resolving to a positive delay above `max-delay-ms` are
    rejected (exceeds-maximum bound).

## Out of scope

- Changing scheduler behaviour. This is a documentation-only fix; the behaviour
  is already proven correct by task 201's verification tests
  (`psi-tool-scheduler-test/psi-tool-scheduler-at-resolution-matrix`).

## Acceptance criteria

- `doc/scheduler.md` "Create validation rules" accurately describes the
  absolute-`:at` min/max bound behaviour (delay-0 fires immediately, positive
  near-future 1–999ms rejected, >max rejected).
- Doc matches the behaviour grounded in `resolve-fire-time!` and the existing
  201 verification tests.
