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
- decide explicitly whether workflow-facing invoke-step result wrapping belongs in the same extracted runtime namespace or in a workflow-owned adapter namespace
- update workflow runtime consumers to depend downward on the extracted component
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

Current known consumers:

- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
- tests under `components/agent-session/test/psi/agent_session/`

## Proposed boundary

### First-cut boundary decision

Extract a small lower runtime component rather than leaving invoke execution under workflow or `agent-session`.

Representative target shape:

- `components/deterministic-operation-runtime/`
- authoritative namespace such as `psi.deterministic-operation-runtime.core`

The extracted component should own:

- canonical invoke execution of a normalized deterministic operation
- malformed-result detection and structured `ex-info`
- canonical operation-result validation at the runtime boundary

### Result-wrapping decision to settle

The main open boundary decision is `operation-result->invoke-step-result`.

There are two plausible homes:

#### Option A — keep it in the extracted runtime component

Pros:

- simplest migration
- smallest call-site churn
- keeps all deterministic-operation execution/result logic together initially

Cons:

- leaves one workflow-facing adapter concern in the generic runtime namespace

#### Option B — move it into workflow-owned code

Pros:

- cleaner ownership split
- deterministic-operation runtime stays generic
- workflow remains the owner of invoke-step accepted-result/execution-error wrapping semantics

Cons:

- slightly larger refactor for a small immediate gain

Preferred first-cut decision:

- allow either option, but require the implementation to make the ownership decision explicit in `implementation.md`
- if ambiguity remains during implementation, prefer Option A for the first cut and record Option B as a later shaping follow-on rather than blocking the extraction

## Responsibilities that should remain outside the new component

### Deterministic operation registration/query

This remains in the already extracted component:

- `components/deterministic-operation-registry/`
- `psi.deterministic-operation-registry.registry`
- `psi.deterministic-operation-registry.defs`

This task should consume those contracts, not re-own them.

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
2. move or re-express the canonical runtime namespace from `psi.agent-session.deterministic-operations` to the new component namespace
3. preserve the current operation invocation and result-validation behavior exactly
4. decide and document the ownership of `operation-result->invoke-step-result`
5. update `workflow_statechart_runtime.clj` and any other lower runtime consumers to depend on the extracted namespace
6. update focused tests
7. remove temporary compatibility wrappers if used during migration
8. record any residual workflow-specific adapter logic that still remains outside the new component

## Acceptance

- a new lower component exists for deterministic operation runtime behavior
- canonical invoke execution no longer lives under `psi.agent-session.*`
- malformed operation-result validation/error shaping no longer lives under `psi.agent-session.*`
- workflow runtime consumers depend downward on the extracted component
- operation definition/result contracts remain unchanged
- workflow `:invoke` behavior remains unchanged
- the ownership decision for `operation-result->invoke-step-result` is explicit and recorded
- task `105-agent-session-component-extraction-map` can reference this as a concrete workflow-adjacent runtime extraction that reduces lower workflow dependence on `agent-session`

## Related work

- `105-agent-session-component-extraction-map` — umbrella ownership map
- `115-workflow-registration-component-extraction` — separate workflow-definition registry seam
- `116-deterministic-operation-registration-component-extraction` — separate deterministic-operation registration/query seam
- `077-deterministic-workflow-steps` — broader workflow authoring/runtime umbrella
