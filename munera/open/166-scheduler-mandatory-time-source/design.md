# 166 — Scheduler mandatory time source

## Intent
Make scheduler time deterministic by requiring scheduler creation and delivery code to receive an explicit time source, and update scheduler tests so they control time through that source instead of relying on wall-clock time.

## Problem
Scheduler behavior currently has wall-clock reads inside scheduler-facing code paths, including psi-tool create-time resolution and scheduled message construction. Tests that exercise these paths can depend on `Instant/now`, which makes assertions less precise and hides where time enters the scheduler domain.

The scheduler should have one obvious time boundary: callers provide the current time through a time source, and scheduler code consumes that value explicitly.

## Scope
- Identify all scheduler-owned wall-clock reads in scheduler create, fire, deliver, drain, projection, psi-tool, timer/effect, lifecycle, and focused scheduler test paths.
- Introduce a small scheduler time-source contract that can provide the current `java.time.Instant`.
- Make the time source a mandatory argument for scheduler code paths that need current time.
- Remove scheduler-owned fallback calls to `Instant/now` from those paths.
- Update production wiring so runtime/dispatch/psi-tool scheduler entrypoints pass the runtime scheduler time source explicitly.
- Update scheduler tests to use a test-controlled time source with fixed and advanceable instants.
- Add or update tests proving scheduler timestamps and `at`/`delay-ms` calculations come from the supplied test time source.

## Out of scope
- Reworking non-scheduler domains that also read wall-clock time.
- Changing scheduler user-facing semantics, limits, status names, or public EQL/psi-tool shapes except where timestamps become deterministic.
- Replacing Java timer execution itself with a fully virtual scheduler.
- Broad runtime clock abstraction outside the scheduler boundary unless a minimal shared helper already exists and is the obvious home.

## Acceptance
1. Scheduler-owned code paths that require current time accept a mandatory time source or mandatory current instant from their caller.
2. Scheduler-owned code no longer calls `java.time.Instant/now` or equivalent wall-clock APIs directly for scheduler timestamps or create-time calculations.
3. Production scheduler entrypoints still use real time by passing an explicit production time source.
4. Focused scheduler tests use a test-controlled time source rather than wall-clock time for scheduler-created timestamps and fire-time calculations.
5. Tests cover both `delay-ms` and absolute `at` scheduling against a fixed test instant.
6. Scheduled message timestamps produced during delivery or queue drain come from the supplied time source.
7. Existing scheduler behavior for create, list, cancel, fire, deliver, drain, background-job projection, resolver projection, and psi-tool reports remains otherwise unchanged.
8. Focused scheduler suites pass.

## Design constraints
- Prefer explicit dependency flow over dynamic vars or implicit globals.
- The time source should be small, local, and easy for tests to construct.
- Mandatory means missing time source should fail early at scheduler boundary tests rather than silently falling back to wall-clock time.
- Keep pure scheduler state transitions pure; pass already-resolved instants into pure functions unless the function's purpose is specifically to resolve time from the source.
- Preserve one-shot scheduler semantics and existing timer effect shapes.

## Candidate implementation shape
- Add a scheduler time helper such as `psi.agent-session.scheduler-time` with:
  - `system-time-source` for production
  - `now` that requires a valid time source and returns an `Instant`
  - a simple test helper or documented shape for fixed/atom-backed time sources
- Thread the time source through:
  - psi-tool scheduler fire-time resolution
  - `:scheduler/create` dispatch handler defaulting of `created-at` if the event data remains permissive
  - scheduled user-message construction in deliver/drain paths
  - any scheduler runtime/timer seam that currently computes current time
- Prefer tightening event data so scheduler create receives explicit `created-at`/`fire-at` from the psi-tool/runtime boundary where possible.
- Update scheduler tests to build a deterministic context/time-source fixture and advance it intentionally.

## Proof requirements
Focused verification should include the scheduler suites touched by this change, at minimum:

```sh
clojure -M:test \
  --focus psi.agent-session.scheduler-test \
  --focus psi.agent-session.scheduler-handlers-test \
  --focus psi.agent-session.psi-tool-scheduler-test \
  --focus psi.agent-session.scheduler-timer-seam-test \
  --focus psi.agent-session.scheduler-effects-test \
  --focus psi.agent-session.scheduler-end-to-end-test \
  --focus psi.agent-session.scheduler-background-jobs-test \
  --focus psi.agent-session.scheduler-resolvers-test \
  --focus psi.agent-session.scheduler-tools-test \
  --focus psi.agent-session.scheduler-lifecycle-test \
  --focus psi.agent-session.scheduler-cancel-job-test \
  --focus psi.agent-session.scheduler-context-shutdown-test \
  --focus psi.agent-session.scheduler-dispatch-test
```

Run the narrower focused subset first while shaping the tests, then the full scheduler proof set before closing.
