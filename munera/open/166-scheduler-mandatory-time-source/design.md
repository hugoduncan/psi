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

## Boundary contract
Scheduler-owned code has two explicit time boundaries:

1. **Runtime context ownership**: the agent-session runtime context owns the production scheduler time source. Context creation installs a production `:scheduler-time-source` using real time. Tests may replace this key with a deterministic source. Existing generic `:now-fn` may remain for non-scheduler domains, but scheduler code should not use it once this task is implemented.
2. **Dispatch event data**: pure scheduler creation requires explicit instants. `:scheduler/create` event data must include both `:created-at` and `:fire-at`; the handler validates their presence and never derives either from wall-clock time or a time source. Psi-tool/runtime entrypoints resolve `delay-ms`/`at` to these explicit instants before dispatching `:scheduler/create`.

Mandatory therefore means:
- any scheduler boundary that resolves current time must receive or read an explicit scheduler time source from context and fail early if it is absent or invalid; and
- scheduler state-transition handlers that accept already-resolved instants must reject missing instants instead of silently falling back to real time.

## Production time-source ownership and injection
- Add `psi.agent-session.scheduler-time` as the small scheduler time contract.
- `scheduler-time/system-time-source` returns a production source backed by `java.time.Instant/now`.
- `scheduler-time/now` validates the source and returns a `java.time.Instant`; invalid or absent sources throw an `ex-info` error naming the scheduler time-source boundary.
- Runtime context creation (`psi.agent-session.context`) owns production injection by adding `:scheduler-time-source (scheduler-time/system-time-source)` to the context. Test contexts may override this key.
- Psi-tool scheduler execution (`psi.agent-session.psi-tool-scheduler`) obtains current time only through `(:scheduler-time-source ctx)` when resolving `delay-ms` or absolute `at`.
- Scheduler timer effect execution (`psi.agent-session.dispatch_effects` `:scheduler/start-timer`) computes Java timer delay from `(:scheduler-time-source ctx)` and `:fire-at`. The Java timer/thread mechanism itself remains real-time infrastructure and is not virtualized by this task.
- Scheduler delivery and drain handlers obtain message timestamps through `(:scheduler-time-source ctx)` unless the delivery/drain event carries an explicit timestamp as described below.
- Scheduler lifecycle/context shutdown keeps real-time timestamps for lifecycle messages outside this scheduler timestamp boundary unless they are scheduled user-message timestamps.

## Delivery and drain timestamps
- Scheduled user-message construction accepts an explicit `delivered-at` instant. It must not call wall-clock APIs.
- `:scheduler/deliver` accepts optional `:delivered-at`; when absent, the handler resolves it from `(:scheduler-time-source ctx)` before constructing the scheduled user message.
- `:scheduler/drain-queue` accepts optional `:delivered-at`; when absent and a schedule is actually drained, the handler resolves it from `(:scheduler-time-source ctx)` before constructing the scheduled user message.
- Effects that enqueue delivery from `:scheduler/fired` may omit `:delivered-at`; this keeps timer firing as a control-flow signal while the delivery handler owns the delivery timestamp.
- Tests may dispatch explicit `:delivered-at` where they want handler purity at the event boundary, or install a deterministic context time source where they want to verify runtime boundary injection.

## Scheduler-owned wall-clock search boundary
The implementation must remove direct `java.time.Instant/now` or equivalent wall-clock reads from these scheduler-owned production files for scheduler timestamps and fire-time calculations:

- `components/agent-session/src/psi/agent_session/psi_tool_scheduler.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/scheduler.clj`
- `components/agent-session/src/psi/agent_session/dispatch_effects.clj` for the `:scheduler/start-timer` delay calculation
- `components/agent-session/src/psi/agent_session/context.clj` for scheduler context wiring
- `components/agent-session/src/psi/agent_session/scheduler_time.clj`, except inside `scheduler-time/system-time-source` itself
- `components/agent-session/src/psi/agent_session/scheduler_runtime.clj` and `components/agent-session/src/psi/agent_session/resolvers/scheduler.clj` if touched; these should remain projection-only and not read current time

The following wall-clock uses are explicitly outside this task unless needed by a touched scheduler test:

- Non-scheduler domains such as turns, prompt recording, compaction, background jobs, extension workflow runtime, metrics, telemetry, and session close lifecycle timestamps.
- Java timer/thread sleeping infrastructure. The timer may still wait using real elapsed time; only the calculation of delay from “now” to `fire-at` must use the scheduler time source.
- Scheduler tests may retain real short sleeps only when proving timer execution/cancellation behavior; scheduler-created timestamps and `delay-ms`/`at` calculations must use deterministic test time.

## Test time-source helper
- Test support should provide a scheduler-focused helper in `components/agent-session/test/psi/agent_session/test_support.clj` unless a narrower scheduler test-support namespace is introduced by the implementation.
- The helper shape should be simple and explicit, for example:
  - `fixed-scheduler-time-source instant` for immutable time; and
  - `atom-scheduler-time-source instant` returning `{ :time-source source :instant* instant-atom }` plus a small advance/set helper if useful.
- The helper is test support, not production API. Production code should depend only on `scheduler-time/now` and `scheduler-time/system-time-source`.
- The underlying accepted source contract should be documented in `psi.agent-session.scheduler-time`; a zero-arity function returning an `Instant` is sufficient unless implementation discovers an existing better local convention.

## Candidate implementation shape
- Add `psi.agent-session.scheduler-time` with:
  - `system-time-source` for production
  - `now` that requires a valid time source and returns an `Instant`
- Thread the scheduler time source through:
  - psi-tool scheduler fire-time resolution
  - runtime context creation
  - `:scheduler/start-timer` delay calculation
  - scheduled user-message construction in deliver/drain paths
- Tighten `:scheduler/create` so it receives explicit `created-at` and `fire-at` from the psi-tool/runtime boundary.
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
