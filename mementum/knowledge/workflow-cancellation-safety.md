---
title: Workflow cancellation safety
status: active
category: architecture
tags: [workflow, cancellation, concurrency, dispatch, effects, testing]
related: ["munera/open/225-workflow-cancellation-halts-inflight-execution"]
depends-on: []
---

Task 225 showed that workflow cancellation safety is a cross-boundary invariant, not a local `if cancelled?` check.

## Core rule

After the workflow cancel checkpoint (the CAS that commits `:status :cancelled`, or run removal), workflow-owned ordinary work must be either already linearized before that checkpoint or must not start. Check-then-call gates are insufficient whenever cancellation can land between the final read and the ordinary boundary call.

## Use the shared primitives

- Use `psi.workflow-coordination.stop-signal` for canonical stop reads: missing run ⇒ removed, `:cancelled` ⇒ cancelled. Do not duplicate local nil/cancel predicates.
- Use `psi.workflow-coordination.cancellation-entry` to order cancellation against ordinary entry points. Keep read locks narrowed to the entry linearization point; never hold them across full prompt/provider/tool/operation work, or cancellation will wait for natural completion.
- Use `psi.workflow-coordination.ordinary-entry` for the multi-phase ordinary-entry CAS protocol shared by actor/judge turn starts and deterministic-operation starts. Future entry-boundary changes should happen there, not in parallel local phase machines.

## Guard stale work, not just live loops

A stale pure result or already-built effect vector can execute after cancellation. Workflow-owned effects and nested dispatches must carry `:workflow-run-id` and re-check the canonical stop signal at execution/apply time. This applies to prompt lifecycle, memory recovery, provider execution, tool dispatch/recording, response recording, prompt continue/finish, `:on-agent-done`, synthetic follow-ups, journal append/persist IO, context usage, and deterministic operations.

If a child or judge session is created but loses the guarded attach/start race to cancellation, abort/cleanup the just-created session immediately; otherwise it becomes untracked and unaddressable by guarded abort effects.

## Runtime-handle hygiene

Cancellation-entry lock handles are runtime handles. Remove/drop them when workflow runs are removed or evicted by retention, while retaining locks for retained canonical runs.

## Test shape

Add deterministic race-window regressions at the actual boundary: final read→call, reservation/start/call phase gaps, stale handler-result apply/effects after cancellation, and blocked ordinary work proving cancellation commits promptly. Prefer nullable/injectable seams over `with-redefs` so tests exercise the same boundary contracts production uses.
