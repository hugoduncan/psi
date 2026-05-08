# 123 — Workflow judge/routing component extraction

## Goal

Extract the pure workflow judge and routing logic into a lower component so canonical projection, verdict interpretation, and next-step resolution no longer live under `psi.agent-session.*`.

## Why

Workflow judge/routing is one of the cleanest remaining below-dispatch seams:

- it is primarily domain logic
- it has relatively low entanglement with mutations, resolvers, and adapter surfaces
- it reduces the eventual size and ambiguity of a larger workflow runtime extraction
- it provides a concrete proof that workflow behavior can move downward cleanly when the ownership line is drawn correctly

This extraction should improve structural clarity without changing workflow behavior.

## Problem

The current namespace `components/agent-session/src/psi/agent_session/workflow_judge.clj` is a mixed-purity owner. It currently contains both:

- pure judge/routing logic
  - judge-input projection
  - transcript/message projection for judging
  - verdict normalization
  - routing-table evaluation
  - goto target resolution
  - iteration-limit evaluation
  - next-step decision shaping
- impure judge execution/orchestration
  - persistence reads
  - child-session creation
  - prompt submission
  - retry-loop execution

These concerns do not belong at the same layer.

The pure subset is workflow-domain logic, not dispatch or public entrypoint ownership. Keeping it under `psi.agent-session.*` obscures the real layering:

- public entrypoints above
- workflow runtime orchestration in the middle
- lower workflow decision logic below

## Intent

Create a lower workflow judge/routing component for the pure subset only.

This task should:

- move canonical pure judge/routing logic out of `psi.agent-session.*`
- preserve current workflow behavior exactly
- keep judge-session execution and retry orchestration outside the new component
- make workflow runtime depend downward on the extracted component
- make the mixed ownership in the current `workflow_judge.clj` explicit and reduce it

This task should not:

- redesign workflow judging semantics
- redesign workflow routing semantics
- take ownership of judge-session execution
- move persistence reads into the new component
- move mutations, resolvers, or `psi-tool` surfaces
- broaden into full workflow runtime extraction

## In scope

- judge input projection from an already-loaded message sequence
- transcript/message projection for judge evaluation
- verdict normalization into canonical routing-decision shapes
- routing-table evaluation
- goto target resolution
- iteration-limit checks
- workflow-local routing decision shaping

## Out of scope

- reading actor messages from persistence
- creating judge sessions
- submitting judge prompts
- retry-loop execution and feedback injection
- turn/session orchestration
- mutations
- resolvers
- `psi-tool`
- adapter/public delivery surfaces
- workflow run lifecycle as a whole

## Current authoritative surface

Current mixed authoritative owner:

- `components/agent-session/src/psi/agent_session/workflow_judge.clj`

Current pure responsibilities in that namespace include:

- `project-messages`
- `match-signal`
- `resolve-goto-target`
- `check-iteration-limit`
- `evaluate-routing`

Current impure responsibilities that should remain outside the extracted component include:

- `execute-judge!`
- persistence reads used to gather actor messages
- child-session creation
- prompt submission
- retry behavior for `:no-match`

## Proposed boundary

Representative target shape:

- `components/workflow-judge/`
- authoritative namespace family `psi.workflow-judge.*`

First-cut expectation:

- one lower namespace is sufficient unless implementation shows a clear need to split projection and routing further

The extracted component should own:

- workflow judge projection logic
- verdict normalization
- routing evaluation
- goto/iteration resolution decisions

The extracted component should not own:

- persistence reads
- session execution
- prompt submission
- retry orchestration
- root-state mutation ownership
- public entrypoints

## Boundary rules

### What belongs in the new component

Belongs if it can be described as:

- given workflow state plus judge inputs, produce a normalized decision or routing result
- given an already-loaded message sequence, project the judge-visible message sequence

Examples:

- projecting candidate messages for judge evaluation from message data already provided by the caller
- interpreting judge output into canonical workflow verdict/routing results
- resolving whether to continue, goto, block, or fail
- enforcing iteration limits as workflow-domain rules

### What stays outside

Stays outside if it can be described as:

- read messages from persistence
- create a session
- submit a prompt
- retry after a no-match result
- update public/root application state
- expose a transport/tool/mutation/resolver interface

## Contract clarifications

### Input boundary

The extracted component should consume:

- already-loaded message sequences
- routing tables
- current-step id
- step order
- step-run iteration data
- judge output text or signal text already obtained by the caller

The extracted component should not consume runtime context merely to read persistence or create sessions.

### Projection semantics to preserve

Unless a later task intentionally redesigns them, the extraction should preserve the current projection semantics:

- `:none` projects to `[]`
- `:full` or `nil` projects to all messages
- `{:type :tail :turns N}` projects the last `N` conversation turns
- `{:type :tail :turns N :tool-output false}` strips non-text tool blocks from those turns
- turn segmentation is based on user-message boundaries
- messages whose content becomes empty after tool-block stripping are dropped

### Verdict normalization semantics to preserve

For this task, verdict normalization means turning judge output text into the canonical routing-decision domain result shape, including outcomes such as:

- `{:action :goto ...}`
- `{:action :complete}`
- `{:action :fail ...}`
- `{:action :no-match}`

Retry behavior after `:no-match` is not part of the extracted component; it remains with judge execution/orchestration.

### Step-id contract

This task should preserve the current canonical step-id contract used by the existing routing functions. If implementation reveals ambiguity between string and keyword step ids, that decision must be made explicitly and recorded in `implementation.md` rather than changed implicitly during extraction.

## Namespace migration decision

This task does not require the entire current `psi.agent-session.workflow-judge` namespace to disappear.

The required outcome is:

- pure judge/routing ownership no longer lives there
- any remaining higher impure execution/orchestration logic may remain above the boundary, either in a thinner `psi.agent-session.workflow-judge` namespace or in a renamed higher namespace if implementation makes that cleaner

If implementation keeps a thinner higher namespace, that is acceptable and should not be treated as a compatibility shim. The thing to remove is mixed authoritative ownership of the pure logic, not necessarily the existence of every higher workflow-judge-related namespace under `psi.agent-session.*`.

## Implementation shape

1. create a new lower component for pure workflow judge/routing logic
2. move canonical pure judge/routing logic from `psi.agent-session.workflow-judge`
3. preserve current behavior and contracts first
4. keep `execute-judge!` and other impure execution/orchestration responsibilities outside the extracted component
5. update workflow runtime consumers to depend downward on the extracted component
6. move lower focused tests with the new component while keeping execution/orchestration integration tests above the boundary
7. remove mixed authoritative ownership of the pure logic from `psi.agent-session.*`
8. record the final namespace split and any remaining higher execution owner in `implementation.md`

## Acceptance

- canonical pure workflow judge/routing logic no longer lives under `psi.agent-session.*`
- the extracted component owns projection, normalization, and routing evaluation logic
- the extracted component consumes already-loaded message sequences rather than reading persistence itself
- judge-session execution, prompt submission, persistence reads, and retry orchestration remain outside the extracted component
- workflow runtime depends downward on the extracted component
- the extracted component does not own mutations, resolvers, `psi-tool`, or session execution
- workflow behavior remains unchanged
- the final implementation records whether a thinner higher `psi.agent-session.*` execution namespace remains after extraction

## Related work

- `105-agent-session-component-extraction-map`
- `121-deterministic-operation-runtime-component-extraction`
- proposed follow-on `turn-execution-contract-extraction`
- proposed follow-on `workflow-runtime-core-component-extraction`
