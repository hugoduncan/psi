# 125 — Workflow runtime core component extraction

## Goal

Extract the below-public-entrypoint workflow runtime core into its own component so workflow run stepping, progression, attempts, and statechart execution no longer live under `psi.agent-session.*` as their authoritative owner.

## Why

This is the main workflow-runtime extraction target.

The workflow runtime cluster is already large and cohesive:

- statechart runtime coordination
- run stepping
- progression updates
- blocked/completed/failed transitions
- attempt bookkeeping
- runtime working memory
- integration with judging and deterministic operations
- integration with bounded turn execution

This looks like historical placement inside `agent-session`, not true session-core ownership.

A clean extraction would reduce lower workflow dependence on `agent-session` while preserving `agent-session` as the higher session orchestration layer.

## Problem

Workflow runtime logic is currently mixed with a higher ownership layer.

This obscures the real architecture:

- lower workflow runtime semantics
- higher workflow entrypoints and session/public orchestration

Without an extraction, `agent-session` continues to own too much workflow-domain runtime behavior even when that behavior is:

- below public adapter surfaces
- not adapter-facing
- not inherently session-core logic

At the same time, the current candidate cluster is not perfectly uniform. Some adjacent namespaces may be:

- core runtime owners
- sibling lower workflow helpers/state owners
- above-boundary workflow shaping owners

This task needs to make that distinction explicit rather than assuming every nearby workflow namespace belongs in one extracted runtime component.

## Intent

Create a dedicated lower workflow runtime-core component.

This task should:

- move below-public-entrypoint workflow runtime-core ownership out of `psi.agent-session.*`
- preserve current workflow behavior exactly
- make higher workflow entrypoints depend downward on the extracted runtime core
- consume lower seams for judge/routing and bounded step execution where available
- explicitly record which adjacent workflow namespaces belong inside the runtime core and which remain sibling or above-boundary owners

This task should not:

- move mutations/resolvers/`psi-tool`
- redesign workflow public APIs
- redesign workflow authoring semantics
- redesign dispatcher ownership
- broaden into unrelated session-core extraction
- silently re-absorb workflow-specific shaping that earlier extraction tasks intentionally kept outside lower execution seams

## In scope

- run stepping
- current-step execution loop
- blocked/completed/failed progression
- attempt bookkeeping
- workflow runtime working memory/context
- statechart runtime coordination
- runtime coordination with lower bounded execution seams
- deterministic operation runtime integration
- judge/routing integration
- recording the final membership of the runtime-core surface

## Out of scope

- mutations
- resolvers
- `psi-tool` workflow ops
- adapter/public reporting surfaces
- workflow file discovery policy if that remains adapter-owned
- general session orchestration outside workflow runtime
- dispatcher redesign
- automatic assumption that every adjacent workflow helper namespace belongs in the runtime core

## Current authoritative surface under review

Representative current workflow-adjacent owners include:

- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
- `components/agent-session/src/psi/agent_session/workflow_runtime.clj`
- `components/agent-session/src/psi/agent_session/workflow_progression_recording.clj`
- `components/agent-session/src/psi/agent_session/workflow_attempts.clj`
- `components/agent-session/src/psi/agent_session/workflow_step_prep.clj`
- `components/agent-session/src/psi/agent_session/workflow_terminal_contract.clj`

These do not all need to be assumed members of the extracted runtime core up front.

First-cut expectation:

- `workflow_statechart_runtime.clj`, `workflow_progression_recording.clj`, and `workflow_attempts.clj` are strong runtime-core candidates
- `workflow_runtime.clj`, `workflow_step_prep.clj`, and `workflow_terminal_contract.clj` are explicit review points whose final ownership should be decided during implementation and recorded in `implementation.md`

## Proposed boundary

Representative target shape:

- `components/workflow-runtime/`
- authoritative namespace family `psi.workflow-runtime.*`

The extracted runtime core should own:

- workflow execution/progression runtime semantics
- statechart runtime coordination
- attempt/runtime context ownership
- runtime use of lower judge/routing and deterministic operation seams
- runtime use of a lower bounded step-execution contract

The extracted runtime core should not own:

- mutations/resolvers
- `psi-tool`
- public workflow API surfaces
- session-wide orchestration not specific to workflow runtime
- workflow-specific session-config derivation or conversation shaping unless implementation explicitly justifies that those belong inside runtime-core ownership

## Dependency direction

The extracted runtime core should depend downward on:

- workflow definition/model/compiler outputs
- workflow judge/routing component
- lower bounded step-execution contract
- deterministic operation runtime
- workflow/deterministic-operation registry contracts as needed

It should not depend upward on:

- higher adapter/public entrypoints
- mutation/resolver/`psi-tool` ownership
- session-publication-specific logic

### Dependency clarification for related extraction tasks

The intended target shape assumes the lower seams from tasks `123` and `124` are available.

If this task is implemented before either of those seams is fully landed, any temporary direct dependency on mixed higher judge or turn/session orchestration surfaces must be treated as residual debt and recorded explicitly in `implementation.md`, not normalized as part of the extracted runtime-core design.

## Boundary rules

### Belongs in workflow runtime core

Belongs here if it can be described as:

- given workflow definition plus runtime state and lower execution results, determine and enact the next workflow runtime step

Examples:

- step advancement
- blocked/completed projection
- attempt bookkeeping
- working-memory coordination
- statechart runtime actions and projections
- integration of lower execution results into workflow-run progression

### May be a sibling lower component or helper rather than runtime core

Review case-by-case if it can be described as:

- pure workflow run state creation/update helpers
- workflow-specific step/session shaping
- terminal result contract shaping
- compile-time or authored-definition adaptation

These may still be lower workflow-owned code without necessarily belonging to the extracted runtime core itself.

### Stays outside

Stays outside if it can be described as:

- expose workflow functionality publicly
- answer public graph queries
- accept transport/tool commands
- own session-wide non-workflow orchestration

## Meaning of “below-dispatch” in this task

For this task, “below-dispatch” means below public workflow entrypoint/adapter ownership such as mutations, resolvers, and `psi-tool`.

It does not imply that the extracted runtime core must be purely functional or free of canonical state/runtime orchestration. The extracted component may still own:

- runtime working-memory coordination
- statechart event/action processing
- canonical root-state/runtime orchestration needed for workflow execution

The boundary being extracted is about ownership layer, not about forcing all runtime behavior into pure helper functions.

## Namespace migration decision

This task does not require every workflow-related namespace to disappear from `psi.agent-session.*`.

The required outcome is:

- authoritative workflow runtime-core ownership no longer lives there
- any remaining workflow-related namespaces under `psi.agent-session.*` must be either:
  - clearly above-boundary orchestration/entrypoint owners, or
  - temporary residual seams explicitly recorded during implementation

If implementation leaves some workflow namespaces outside the extracted runtime core for legitimate boundary reasons, that is acceptable and should not be treated as a compatibility shim.

## Statechart runtime decomposition note

`workflow_statechart_runtime.clj` currently contains multiple kinds of runtime logic in one place.

This task may require decomposing that file into smaller runtime-owned namespaces before or during component extraction.
Whole-file movement is not required if it would preserve poor ownership boundaries.

## Implementation decisions to record explicitly

Implementation must record the final decision for at least these review points:

### Runtime-core membership

Record whether each of these remains inside the extracted runtime core, moves to a sibling lower workflow owner, or stays above the boundary for justified reasons:

- `workflow_runtime.clj`
- `workflow_step_prep.clj`
- `workflow_terminal_contract.clj`

### Lower-seam consumption status

Record whether the extracted runtime core consumes:

- the lower judge/routing seam expected from task `123`
- the lower bounded step-execution seam expected from task `124`

If either seam is not yet available, record the temporary direct dependency that remains and why.

### Statechart-runtime decomposition

Record whether `workflow_statechart_runtime.clj` moved as:

- a mostly intact runtime-core namespace,
- a decomposed set of smaller runtime-core namespaces, or
- a split across runtime-core and sibling helpers

and why that final shape best matched ownership.

## Implementation shape

1. review the current workflow-adjacent namespace cluster and confirm the exact runtime-core ownership surface to preserve
2. identify which adjacent namespaces are true runtime-core owners versus sibling lower workflow helpers or above-boundary shaping owners
3. create a dedicated lower workflow runtime-core component
4. move runtime-core-owned namespaces and logic into that component
5. preserve current behavior and contracts first
6. update higher workflow entrypoints to depend downward on the new runtime-core component
7. consume lower seams for judge/routing and bounded step execution when available, or record temporary residual dependencies explicitly
8. remove misplaced authoritative runtime-core ownership from `psi.agent-session.*`
9. record the final runtime-core membership, residual above-boundary logic, and follow-on cleanup candidates in `implementation.md`

## Acceptance

- authoritative below-public-entrypoint workflow runtime-core logic no longer lives under `psi.agent-session.*`
- a dedicated lower workflow runtime-core component exists
- the new component owns execution, progression, statechart runtime, and attempt/runtime coordination
- higher workflow entrypoints depend downward on the extracted runtime core
- mutations/resolvers/`psi-tool` remain above the boundary
- workflow behavior remains unchanged
- the final implementation records which adjacent workflow namespaces are part of runtime core versus sibling or above-boundary owners
- the final implementation records whether lower judge/routing and bounded step-execution seams were consumed directly or temporarily deferred with explicit residual debt
- no compatibility shim is left behind unless implementation proves a very small temporary seam is necessary; legitimate above-boundary workflow namespaces may remain under `psi.agent-session.*` when they are not runtime-core owners

## Related work

- `105-agent-session-component-extraction-map`
- `121-deterministic-operation-runtime-component-extraction`
- `123-workflow-judge-routing-component-extraction`
- `124-turn-execution-contract-extraction`
