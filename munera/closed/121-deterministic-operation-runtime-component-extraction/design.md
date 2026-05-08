# 121 — Deterministic operation runtime component extraction

## Goal

Extract the remaining below-dispatch deterministic operation runtime namespace out of `agent-session` so canonical operation invocation and result validation/wrapping no longer live under `psi.agent-session.*`.

## Why

Recent workflow-boundary review sharpened a narrow but useful extraction seam:

- `psi.agent-session.deterministic-operations` is entirely below the dispatch/adapter layer
- it does not own Pathom mutations, resolvers, `psi-tool` entrypoints, or session/root-state mutation
- it is one of the few remaining non-workflow `agent-session` dependencies reached directly by the lower workflow runtime, especially `workflow_statechart_runtime.clj`

That makes it a good follow-on extraction candidate:

- small surface area
- low ambiguity
- directly reduces workflow runtime dependence on `agent-session`
- aligns with task `105-agent-session-component-extraction-map`, which identifies workflow as the clearest remaining extraction domain and notes `deterministic_operations.clj` as one of the workflow-adjacent lower seams
- remains well-scoped after landed task `120-rename-psi-turn-to-agent-session-turn`, which resolved surrounding turn naming churn without changing this deterministic-operation runtime boundary

## Problem

The current namespace `components/agent-session/src/psi/agent_session/deterministic_operations.clj` mixes two responsibilities that are both below dispatch but no longer need to live in `agent-session`:

1. canonical deterministic operation invocation and returned-result validation
2. workflow-facing wrapping of canonical operation results into invoke-step runtime result semantics

Current characteristics of this namespace:

- below dispatch/adapter level
- no Pathom mutation ownership
- no Pathom resolver ownership
- no `psi-tool` entrypoint ownership
- no direct root-state mutation
- no direct prompt/session orchestration
- depends only on lower deterministic-operation definition contracts in `psi.deterministic-operation-registry.defs`

Even though task `116-deterministic-operation-registration-component-extraction` extracted operation registration/query ownership into `components/deterministic-operation-registry/`, invoke execution and workflow-facing invoke-step result wrapping still sit under `agent-session`.

That leaves an avoidable dependency path:

- workflow runtime -> `psi.agent-session.deterministic-operations`

instead of:

- workflow runtime -> lower deterministic-operation runtime component

## Intent

Create a lower component for deterministic operation runtime behavior.

This task should:

- move canonical operation invocation out of `psi.agent-session.*`
- move canonical deterministic-operation result validation/error shaping out of `psi.agent-session.*`
- move workflow-facing invoke-step result wrapping into explicit workflow-owned code under `psi.agent-session.workflow-statechart-runtime`
- update `psi.agent-session.workflow-statechart-runtime` to depend downward on the extracted component, plus any incidental consumers discovered during implementation
- keep user-facing workflow behavior unchanged

This task should not:

- redesign deterministic operation registration semantics already handled by task `116`
- redesign workflow `:invoke` authoring or source-resolution semantics
- broaden into general workflow runtime extraction
- change operation definition or result contracts unless a focused implementation review proves a very small compatibility fix is required

## Current authoritative surface

Current namespace:

- `components/agent-session/src/psi/agent_session/deterministic_operations.clj`

Current responsibilities:

- re-export lower deterministic-operation definition/result contracts from `psi.deterministic-operation-registry.defs`
- `malformed-operation-result-ex`
- `invoke-operation`
- `operation-result->invoke-step-result`

Current production consumer:

- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`

Adjacent boundary note after landed task `120` and follow-on cleanup `122`:

- `psi.agent-session.turn` is now the direct higher turn orchestration surface
- this task does not need to resolve turn naming or turn-namespace migration questions before extracting deterministic-operation runtime ownership

Current test consumers include:

- `components/agent-session/test/psi/agent_session/extensions_test.clj`
- `components/agent-session/test/psi/agent_session/deterministic_operation_registry_test.clj`
- `components/agent-session/test/psi/agent_session/workflow_execution_test.clj`

## Proposed boundary

### First-cut boundary decision

Extract a small lower runtime component rather than leaving invoke execution under workflow or `agent-session`.

Representative target shape:

- `components/deterministic-operation-runtime/`
- authoritative namespace `psi.deterministic-operation-runtime.core`

The extracted component should own:

- canonical invoke execution of a normalized deterministic operation
- malformed-result detection and structured `ex-info`
- canonical operation-result validation at the runtime boundary

### Result-wrapping ownership decision

`operation-result->invoke-step-result` should move into explicit workflow-owned code rather than the extracted deterministic-operation runtime component.

Reasoning:

- invoke-step accepted-result/execution-error shaping is workflow-facing adapter logic rather than generic deterministic-operation runtime behavior
- the extracted deterministic-operation runtime component should stay focused on canonical operation invocation plus malformed-result validation/error shaping
- this keeps the lower runtime component generic while making workflow-specific result projection explicit at the workflow boundary

First-cut rule:

- `psi.deterministic-operation-runtime.core` owns canonical invoke execution and malformed-result validation/error shaping
- `psi.agent-session.workflow-statechart-runtime` owns invoke-step result wrapping semantics in the first cut as a pragmatic workflow-local home for this extraction slice
- this task should remove `operation-result->invoke-step-result` from `psi.agent-session.deterministic-operations` rather than relocating it into the extracted runtime component unchanged

## Responsibilities that should remain outside the new component

### Deterministic operation registration/query

This remains in the already extracted component:

- `components/deterministic-operation-registry/`
- `psi.deterministic-operation-registry.registry`
- `psi.deterministic-operation-registry.defs`

This task should consume those contracts, not re-own them.

Re-export policy:

- the extracted runtime component should not become a compatibility re-export surface for definition/result helpers already owned by `psi.deterministic-operation-registry.defs`
- callers that need definition/result contract helpers should depend on `psi.deterministic-operation-registry.defs` directly
- the extracted runtime component should own only invoke execution plus malformed-result validation/error shaping

### Workflow runtime sequencing

This remains outside the extracted deterministic operation runtime component:

- deciding when a workflow `:invoke` step runs
- resolving invoke args from workflow state
- attempt/progression updates
- statechart transitions
- workflow run status changes

Representative current owner:

- `workflow_statechart_runtime.clj`

### Adapter / entrypoint surfaces

These remain above the boundary:

- mutations
- resolvers
- `psi-tool`
- extension API surfaces

## Suggested implementation shape

1. create `components/deterministic-operation-runtime/`
2. move invoke runtime ownership from `psi.agent-session.deterministic-operations` into `psi.deterministic-operation-runtime.core`
3. preserve the current operation invocation and result-validation behavior exactly
4. move `operation-result->invoke-step-result` into `psi.agent-session.workflow-statechart-runtime` and document that first-cut ownership explicitly
5. update `workflow_statechart_runtime.clj` to depend downward on the extracted runtime namespace and use its own workflow-owned invoke-step result wrapper
6. update focused tests, moving deterministic-operation runtime unit proofs into the new component while keeping workflow-owned invoke-step wrapping/integration proofs with workflow code
7. remove the old authoritative `psi.agent-session.deterministic-operations` namespace rather than leaving a compatibility shim
8. record any residual workflow-specific adapter logic that still remains outside the new component

## Acceptance

- a new lower component exists for deterministic operation runtime behavior
- canonical invoke execution no longer lives under `psi.agent-session.*`
- malformed operation-result validation/error shaping no longer lives under `psi.agent-session.*`
- `workflow_statechart_runtime.clj` depends downward on the extracted component
- `psi.agent-session.workflow-statechart-runtime` explicitly owns invoke-step result wrapping semantics in the first cut
- operation definition/result contracts remain unchanged
- workflow `:invoke` behavior remains unchanged
- the extracted runtime component does not become a compatibility re-export surface for defs-level contracts
- the old authoritative `psi.agent-session.deterministic-operations` namespace is removed rather than left as a shim
- task `105-agent-session-component-extraction-map` can reference this as a concrete workflow-adjacent runtime extraction that reduces lower workflow dependence on `agent-session`

## Related work

- `105-agent-session-component-extraction-map` — umbrella ownership map
- `115-workflow-registration-component-extraction` — separate workflow-definition registry seam
- `116-deterministic-operation-registration-component-extraction` — separate deterministic-operation registration/query seam
- `077-deterministic-workflow-steps` — broader workflow authoring/runtime umbrella
