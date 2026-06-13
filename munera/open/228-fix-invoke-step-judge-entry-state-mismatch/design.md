# 228 — Fix deterministic-op entry `:handler-entry-state-mismatch` on invoke-step + invoke-judge

## Status

Design — mechanism confirmed by spike; fix shape decided (option **a**, below).
Ready for planning.

## Intent

Fix the workflow-runtime fault where an `:invoke` step that carries **both** an
`:operation` and an invoke `:judge` aborts at the judge with
`:stop-reason :handler-entry-state-mismatch` ("Workflow execution stopped before
deterministic operation start"). This breaks `task-lifecycle` and
`review-task-design` at the `clarity-status` step, so **no task can pass design
review through the lifecycle**.

## Problem

The deterministic-operation entry (`deterministic-operation-runtime/core`) is a
cancellation-safe phase machine (task 225) that drives a step's **latest
attempt** through shared phase keys via
`workflow-coordination.ordinary-entry/transition-latest-attempt!`:

`:operation-start-state` → `:operation-call-state` →
`:operation-handler-entry-state` (`reserve → commit-start → begin-call →
commit-call → prepare-handler-entry → enter-handler`).

Two phases interact incorrectly when the attempt has already been driven once:

- `prepare-workflow-operation-handler-entry!` sets
  `:operation-handler-entry-state :pending` but declares
  `:ok-states #{:pending :entered}`. So if the key is **already `:entered`**,
  `phase-already-ok?` short-circuits to success **without** resetting to
  `:pending`.
- `enter-workflow-operation-handler!` then **requires**
  `:operation-handler-entry-state = :pending` (mismatch reason
  `:handler-entry-state-mismatch`).

An `:invoke` step that has a `:judge` runs **two** deterministic operations
against the **same step attempt**, sharing those keys:

1. the step `:operation` runs first and leaves
   `:operation-handler-entry-state :entered`;
2. the `:judge` operation runs next; its invocation carries the **explicit**
   latest `:workflow-attempt-id` (`(-> step-runs <step-id> :attempts last
   :attempt-id)` in `execute-invoke-judge!`), the **same** attempt the step
   `:operation` already drove. The entry uses `:attempt-id-required? false`, but
   because a real attempt id is supplied, `ordinary-entry` still asserts equality
   with the latest attempt (`(or attempt-id-required? workflow-attempt-id)` is
   truthy), so the judge's transitions target that same attempt and its shared
   `:operation-*` phase keys. `prepare` sees the residual `:entered` (∈ ok-states)
   and skips the reset to `:pending`; `enter` then fails the `:pending`
   requirement → `:handler-entry-state-mismatch` → the operation is reported
   stopped, the step `:failed`, the delegated workflow failed, and the lifecycle
   failed.

`clarity-status` is the one step in `review-task-design.edn` that is
`:type :invoke` **with** an invoke `:judge` (`pass-feedback-routing`), so it is
uniquely affected. Session steps with invoke judges are safe because the actor
turn uses a *different* key namespace (`:turn-start-state`/`:turn-call-state`),
so the judge's operation keys start clean.

### Evidence

- `task-lifecycle-1781378241581` and `…375999896` both failed identically:
  delegated `review-task-design` → step `clarity-status` →
  `{:reason :workflow-stopped :details {:operation-id
  "workflow/pass-feedback-routing" :stop-reason
  :handler-entry-state-mismatch}}`, after ~18 min of real review work each.
- Same symptom is reachable for any invoke-op + invoke-judge step.

## Scope

In scope:

- A failing **characterization test** reproducing the abort: an invoke step (or
  the minimal equivalent) whose `:operation` and invoke `:judge` both run
  deterministic operations against one attempt drives the judge entry to
  `:handler-entry-state-mismatch`.
- The structural fix so the judge operation enters and runs correctly, in
  `deterministic-operation-runtime` and/or `workflow-coordination.ordinary-entry`.
- Regression coverage proving 225 cancellation behaviour is preserved (a genuine
  stop between/within these operations still stops; a normal sequential
  operation+judge no longer false-stops).
- `review-task-design` (and the `task-lifecycle` chain through it) reaches
  `clarity-status` routing without the spurious stop.

Out of scope:

- Task 226/227 design content (independent).
- Any broader redesign of the cancellation entry-lock beyond what the fix
  requires.
- Changing workflow-grammar semantics for invoke steps / judges.

## Acceptance criteria

1. A focused test reproduces the pre-fix `:handler-entry-state-mismatch` abort
   for an invoke-operation + invoke-judge step and passes after the fix.
2. An invoke step with both an `:operation` and an invoke `:judge` runs both
   deterministic operations to completion and routes on the judge outcome (no
   spurious `:workflow-stopped` / `:handler-entry-state-mismatch`).
3. Task-225 cancellation semantics are preserved: a real stop signal arriving
   before/within either operation still yields a clean `:workflow-stopped`
   terminal; existing workflow-coordination/deterministic-op cancellation tests
   stay green.
4. `review-task-design` completes a full review pass through `clarity-status`
   routing (REPEAT/DONE) without the abort; the `task-lifecycle` design-review
   stage is unblocked.
5. clj-kondo clean; relevant Scry suites green.

## Architecture alignment

- The fault is in S3 coordination / S1 runtime: `workflow-coordination`
  (ordinary-entry phase machine, the 225 cancellation-safety primitives) and
  `deterministic-operation-runtime` (operation entry phases). The fix must keep
  the `∀change → event → log → replayable` invariant and the 225 cooperative
  cancellation checkpoints intact.
- `λ fix(bug). cause(structural) → redesign > patch`: prefer a structural
  correction over a local guard hack. The structural defect is that two distinct
  deterministic operations (a step's `:operation` and its `:judge`) share one
  attempt's operation-phase keys, and the `prepare` `:ok-states`/`enter`
  required-phase pair is not re-entrancy-safe.

## Spike outcome (confirmed)

A throwaway spike test drove `deterministic-operation-runtime/invoke-operation`
twice against one attempt (step `:operation` then `:judge` operation, same
`:workflow-run-id`/`:step-id`/`:workflow-attempt-id`) and **reproduced the abort
exactly**:

- op 1 → `:ok`, leaves `:operation-handler-entry-state :entered`;
- op 2 → `{:status :error :reason :workflow-stopped :details {:operation-id
  "workflow/pass-feedback-routing" :stop-reason :handler-entry-state-mismatch}}`.

This confirms the mechanism and that both the step operation and the judge
operation target the **same attempt** with the **same** `:operation-*` phase
keys (verified: both `invoke-step-runtime-result` and `execute-invoke-judge!`
pass `:workflow-attempt-id` = the step-run's latest attempt id). The spike test
was reverted (it asserted buggy behaviour); the real characterization test is
written during build to assert the **fixed** behaviour (judge succeeds).

## Fix shape — decided: (a) per-operation phase namespacing

Give the judge's deterministic-operation entry a **distinct phase-key namespace**
from the step's deterministic-operation entry, so the two operations on one
attempt no longer share `:operation-start-state`/`:operation-call-state`/
`:operation-handler-entry-state`. This mirrors the existing separation whereby a
session step's actor turn uses `:turn-*-state` keys while its judge operation
uses `:operation-*-state` keys (which is exactly why session+invoke-judge steps
never collide).

Approach (to be finalized in plan):

- Thread an explicit **operation role / phase namespace** through the invocation:
  the judge invocation in `execute-invoke-judge!` carries `:operation-role
  :judge`; the step `:operation` invocation omits the key and uses the default
  (`:step`) role.
- In `deterministic-operation-runtime/core`, derive the phase-opts key set from
  that role at the **single** `transition-workflow-operation-phase!` chokepoint
  (every phase helper already routes through it), rewriting the supplied
  `phase-opts` keys via a `role-phase-key` helper. The six phase helpers
  (`reserve`, `commit-start`, `begin-call`, `commit-call`,
  `prepare-handler-entry`, `enter-handler`) are left unchanged. Default/`:step`
  keys are byte-identical for back-compat with single-operation steps; the judge
  role uses a `:judge`-scoped key set.
- Keep the 225 cancellation primitives (`ordinary-entry`,
  `cancellation-entry/with-run-read-lock`, stop-signal) unchanged — only the
  key namespace is parameterized, so cancellation guards still apply per
  operation.

## Build discovery — a second, distinct defect on REPEAT (ψ)

The spike only exercised a *single* clarity-status pass. During build the
phase-key fix let the **first** clarity-status pass route REPEAT, exposing a
**second** defect from the same task-225 lineage: the *re-executed* clarity-status
step's `:operation` aborted with `:stop-reason :attempt-mismatch`.
`invoke-step-runtime-result` derived `:workflow-attempt-id` from the
`workflow-run` snapshot captured in the `:step/enter` action **before** the new
attempt was appended. First attempt: that snapshot had no attempts → `nil` →
the task-225 attempt-equality guard was skipped (so it happened to work). On
REPEAT the stale snapshot's latest id was the *previous* attempt, which no
longer equalled the live latest attempt → `:attempt-mismatch`. Fix: thread the
authoritative just-started `attempt-id` from `:step/enter` into
`invoke-step-runtime-result` and use it for `:workflow-attempt-id`, instead of
re-deriving from the stale snapshot. Both fixes are required to satisfy
acceptance criteria #2 and #4 (full REPEAT/DONE routing). See implementation.md.

Rejected alternatives: **(b)** a fresh attempt/record for the judge (adds
attempt bookkeeping and changes the attempt model); **(c)** making
`prepare`/`enter` re-entrancy-safe by dropping `:entered` from `prepare`'s
`:ok-states` (smallest change, but papers over the real "two operations share one
key set" coupling and risks weakening the cancellation guard the `:ok-states`
idempotency was added for). (a) removes the coupling at its source
(`cause(structural) → redesign > patch`).
