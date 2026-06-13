# 228 — Fix deterministic-op entry `:handler-entry-state-mismatch` on invoke-step + invoke-judge

## Status

Design — draft for refinement. One open question on fix shape (below).

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
2. the `:judge` operation runs next; its invocation carries
   `:workflow-attempt-id nil`, and the entry uses `:attempt-id-required? false`,
   so its transitions target the **same latest attempt**. `prepare` sees the
   residual `:entered` (∈ ok-states) and skips the reset to `:pending`; `enter`
   then fails the `:pending` requirement → `:handler-entry-state-mismatch` →
   the operation is reported stopped, the step `:failed`, the delegated workflow
   failed, and the lifecycle failed.

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

## Open question (fix shape — resolve before plan)

Candidate root-cause fixes (pick after a brief spike confirms the mechanism in a
test):

- **(a) Per-operation phase namespacing.** Distinguish the step `:operation`
  entry from the `:judge` operation entry so they do not share
  `:operation-*-state` keys on one attempt (e.g. a judge-scoped key namespace,
  mirroring how actor turns use `:turn-*` keys). Cleanest separation; localizes
  to the phase-key scheme.
- **(b) Fresh attempt/record for the judge operation.** Give the judge operation
  its own attempt (or its own entry record) so its phase keys start clean.
  Aligns "one deterministic operation = one entry lifecycle."
- **(c) Make `prepare`/`enter` re-entrancy-safe.** Have `prepare` always reset
  `:operation-handler-entry-state` to `:pending` (drop the `:entered` from its
  `:ok-states`, or have `enter` accept the already-`:entered` idempotent case)
  so a second operation on the same attempt re-initializes correctly. Smallest
  change but risks masking the deeper "two operations, one key set" coupling and
  must not weaken cancellation guards.

Recommendation to discuss: lean **(a)** — it matches the existing actor-vs-judge
key separation and removes the shared-state coupling at its source, rather than
papering over it (c) or adding attempt bookkeeping (b). Confirm with the spike.
