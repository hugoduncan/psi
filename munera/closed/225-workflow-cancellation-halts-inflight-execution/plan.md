# Plan — workflow cancellation halts in-flight execution

## Approach

Implement cancellation as an agent-session dispatch/effects feature, not as inline mutation or command-layer side effects. Keep workflow-runtime pure transitions as domain helpers, route public cancel/remove surfaces through canonical state-kernel events, and make all real cancellation/cleanup side effects visible as validated `:runtime/*` effects.

Key decisions from `design.md`:

- Canonical events are `:psi.workflow/cancel-run` and `:psi.workflow/remove-run`; Pathom mutations, `psi-tool`, and `delegate remove` are adapters only. `delegate remove` must not own cancel/remove cleanup side effects, including direct `inflight-runs` mutation or active-background-job terminalization; those must be removed/rerouted through the canonical remove dispatch/effects path (or explicitly documented as a non-cancel/remove precondition if retained).
- `cancel-run` and live `remove-run` share one cancel-transition helper. `remove-run` owns cancel-then-remove: live first pass cancels and emits a re-entrant `:runtime/dispatch-event` for `:psi.workflow/remove-run`; terminal pass drops the existing canonical record and performs runtime-handle cleanup; absent pass returns success/no-op with no canonical record found/removed and emits only the ordered stale-handle cleanup pair (`:runtime/cancel-inflight-run` then `:runtime/drop-inflight-run`).
- Cancellation state is committed in canonical `:state*`; `inflight-runs` remains a runtime handle reached by dispatch effects through ctx injection.
- Cascade cancellation is one multi-run apply-phase `:root-state-update` over the directly cancelled run plus non-terminal descendants discovered by `:delegating-run-id`; per-run terminal guards live inside the update fn so the atom CAS is the atomicity boundary.
- Cancellation effects are canonical dispatch effects: guarded `:runtime/agent-abort`, `:runtime/cancel-inflight-run`, `:runtime/drop-inflight-run`, existing `:runtime/mark-workflow-jobs-terminal`, and existing `:runtime/dispatch-event` for cancel-then-remove.
- Top-level worker cancellation targets only the top-level run's exact `inflight-runs` key. Direct nested sub-run cancel/remove never interrupts the parent/top-level worker; it aborts the in-flight child turn and lets the parent observe a failed delegate step.
- Cooperative stopping is enforced by read-path checkpoints in the workflow execution loop: `:cancelled` status or run absence stops advancement. `future-cancel(true)` is a wake-up mechanism for parked top-level workers, not the authority for the stop decision.
- Public result semantics are idempotent success/no-op shapes for terminal/absent cancel/remove; absence may still emit stale-handle cleanup effects.

## Risks

- **Pre-CAS effect computation:** dispatch computes effects before apply, so sequential no-op requests must be gated in handler-before and true-concurrent duplicate effects must be execution-time idempotent.
- **Apply-before-effects ordering:** live remove cannot cancel and remove in one dispatch without skipping job terminalization; the two-dispatch re-entrant remove ordering must be preserved.
- **Nested sub-run semantics:** direct nested cancel/remove must not interrupt the shared top-level worker; tests need to distinguish top-level and nested effect sets.
- **Guarded abort compatibility:** extending `:runtime/agent-abort` must preserve existing unguarded abort emitters where `:session-id` is injected later by the effects interceptor.
- **Checkpoint placement:** any ordinary step/sub-run/session spawn path that bypasses the cancellation checkpoint can reintroduce post-cancel advancement.
- **Runtime-handle cleanup:** dropping an `inflight-runs` handle before attempting worker cancel can recreate the orphaned-thread failure.
- **Broad test surface:** realistic async/thread tests can be flaky; prefer nullable/controlled harnesses for deterministic checkpoint and effect assertions.

No blocking design ambiguities remain. The design explicitly marks two true-concurrency behaviours as out-of-test-scope: duplicate-effect harmlessness for concurrent cancels, and the bounded direct-sub-run post-enumeration spawn race.

## Slice order

### Slice 1 — Dispatch event skeleton and public routing

Add the canonical workflow terminal event handlers and route all public cancel/remove surfaces through them while preserving current public API names.

### Slice 2 — Runtime cancellation/cleanup effects and ctx reachability

Add ctx access for `inflight-runs`, register effect schemas, and implement `:runtime/cancel-inflight-run`, `:runtime/drop-inflight-run`, and the guarded workflow-cancellation variant of `:runtime/agent-abort`.

### Slice 3 — Shared cancel/remove transition semantics

Implement the shared cancel-transition helper, multi-run cascade update, effect-set construction, D29 public result shapes, and `remove-run` live-vs-terminal/absent branching with re-entrant remove.

### Slice 4 — Cooperative execution stop points

Teach the workflow execution/statechart loop to stop on cancelled or absent runs at safe checkpoints and make blocking waits interrupt-aware so top-level `future-cancel(true)` wakes a parked worker cleanly.

### Slice 5 — Delegate result and nested-run semantics

Make direct nested cancel/remove produce failed delegate-step results without halting the parent, including mapping removed/absent delegate runs to the same cancelled failure result.

### Slice 6 — Background job terminalization and public-surface cleanup

Extend workflow job terminalization to `:cancelled`, remove command-layer `inflight-runs` mutation, and document the `:runtime/dispatch-event` re-entrant sequencing plus the user-visible cancellation fix.

### Slice 7 — Acceptance test net and gates

Add deterministic tests for all guaranteed acceptance criteria, keep true-concurrency exceptions as code-review assertions, then run focused tests, full `bb test`, and clj-kondo.
