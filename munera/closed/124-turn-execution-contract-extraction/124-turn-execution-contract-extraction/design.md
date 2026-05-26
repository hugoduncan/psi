# 124 — Turn execution contract extraction

## Goal

Introduce and extract a lower bounded turn-execution contract for workflow session-backed actor steps and workflow judge steps so workflow runtime no longer depends directly on high-level `psi.agent-session.turn` orchestration details.

## Why

This is the key enabling seam for a clean workflow runtime extraction.

The workflow runtime currently depends too directly on higher session/turn orchestration concerns, such as:

- child session creation mechanics
- prompt submission mechanics
- execution-result access paths
- agent-session-owned turn placement

That coupling makes a below-dispatch workflow runtime extraction harder to do cleanly.

A lower contract should let workflow runtime say:

- execute one session-backed actor turn
- execute one judge turn

and receive canonical bounded execution results, without needing to know how session and turn orchestration are internally wired.

## Problem

Workflow execution currently crosses an unstable ownership line:

- workflow runtime needs bounded step-execution results
- session-backed actor execution and judge execution currently flow through higher session/turn orchestration
- historical implementations have sometimes recovered semantic results from transcript/journal state rather than from a canonical bounded execution result

This causes two architectural problems:

1. workflow runtime is coupled upward into session orchestration details
2. execution semantics and persistence/audit semantics are too easy to blur

Bounded callers should consume canonical execution results directly.
Journals and transcripts should remain audit/history surfaces, not semantic recovery surfaces.

## Intent

Define and extract a narrow bounded turn-execution contract for workflow use.

This task should:

- introduce a lower boundary for executing one workflow session-backed actor turn or one workflow judge turn
- return canonical success/failure execution results directly to workflow runtime
- isolate execution-session creation/binding mechanics behind that boundary where needed
- preserve current workflow behavior

This task should not:

- redesign workflow routing/progression
- redesign public transcript publication
- redesign persistence or journaling broadly
- move mutations/resolvers/`psi-tool`
- broaden into a full turn-component redesign
- broaden into a unified contract for non-turn workflow forms such as deterministic `:invoke` or delegated `:delegate` execution

## In scope

- define a narrow execution contract for workflow session-backed actor-step execution
- define a narrow execution contract for workflow judge-step execution
- canonical bounded actor-turn result return
- canonical bounded judge-turn result return
- explicit failure/success normalization at the turn-execution boundary
- isolating execution-session creation/binding mechanics behind the contract where needed
- making journal/transcript reread unnecessary as the semantic result contract for bounded callers

## Out of scope

- deterministic `:invoke` execution
- delegated `:delegate` execution
- workflow progression/routing logic
- workflow-specific session-config derivation
- workflow-specific session conversation materialization/shaping
- public publication or UI delivery semantics
- persistence redesign
- full turn runtime redesign
- full workflow runtime extraction
- mutations, resolvers, and `psi-tool`

## Current mixed ownership

Current mixed surfaces likely include:

- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
- `components/agent-session/src/psi/agent_session/turn.clj`
- lower turn runtime / recording seams already present elsewhere

The current coupling is mixed in two different ways:

1. workflow runtime reaches into higher turn/session orchestration for bounded execution
2. workflow-specific session shaping and generic turn execution mechanics are not yet explicitly separated

This task should clarify which logic is:

- workflow-owned
- lower turn-execution-contract owned
- still above-boundary orchestration

## Proposed boundary

This task should produce either:

- an expanded lower turn-runtime component with a workflow-facing bounded execution contract, or
- a small lower execution-boundary component specifically for workflow session-backed actor/judge turn execution

Default preference:

- expand an existing lower turn-runtime boundary if that yields a clean fit
- create a new lower execution-boundary component only if expanding turn-runtime would distort its ownership

Representative contract semantics:

- execute bounded session-backed actor turn -> canonical execution result
- execute bounded judge turn -> canonical execution result

Workflow runtime should not need to know:

- how the execution session was created internally
- how prompt submission is wired internally
- how journaling is recorded internally
- how transcript/publication layers consume the result afterward

Workflow runtime may still remain responsible for deciding:

- the workflow-specific step/session configuration
- the workflow-specific conversation/preloaded-message shape
- when actor or judge execution should happen

## Boundary rules

### The new contract should own

- bounded turn execution request -> canonical execution result
- canonical success/error normalization for bounded turn execution
- lower execution boundary semantics
- isolation of session/turn internal mechanics from workflow runtime
- execution-session creation/binding mechanics when required by that bounded execution

### The new contract should not own

- workflow routing decisions
- workflow progression
- deterministic `:invoke` execution
- delegated `:delegate` execution
- workflow-specific session-config derivation
- workflow-specific conversation shaping
- mutations/resolvers/`psi-tool`
- transcript/UI publication
- app-wide root-state adapter contracts

## Contract clarifications

### Scope clarification

This task is about the workflow execution forms that actually require bounded session/turn execution:

- session-backed actor steps
- judge steps

It is not a general workflow execution unification task.
Deterministic `:invoke` and delegated `:delegate` execution remain outside this task.

### Boundary start

Workflow-specific shaping remains outside the contract.
That includes responsibilities such as:

- resolving step session config
- materializing workflow-authored step conversation
- splitting preloaded messages from prompt text
- deciding which judge prompt/spec to run

The contract begins once workflow runtime has already decided the execution inputs for one bounded actor or judge turn.

### Result-shape clarification

The lower contract should return a canonical bounded turn execution result, not workflow progression or routing outcomes.

That result shape should preserve these invariants:

- bounded callers receive a direct execution result
- success and failure are both explicit
- execution metadata needed by workflow runtime is present
- persistence/journal state is not required to reconstruct the result meaningfully

Workflow-local interpretation remains outside the contract, for example:

- turning actor execution into workflow pending success/failure payloads
- turning judge execution output into routing decisions
- applying retry policy

### Judge retry ownership

Judge retry orchestration is not part of the lower execution contract.
The contract should execute one bounded judge turn and return its canonical result.
Any no-match retry loop should remain above the contract and compose repeated bounded executions as needed.

### Dependency-success criteria

After this task, workflow runtime may depend on:

- the extracted lower bounded execution contract
- lower turn-runtime helpers/substrates that are part of that boundary
- execution-result contracts returned by that boundary

After this task, workflow runtime should not depend on:

- high-level `psi.agent-session.turn` convenience/orchestration APIs as the primary bounded execution seam
- journal/transcript reread as the semantic result source for bounded execution
- direct knowledge of turn/session orchestration internals that the lower contract can encapsulate

## Implementation decisions to record explicitly

The task is intentionally not freezing every final shape up front, but implementation must make the following decisions explicitly and record them in `implementation.md`.

### Shared contract vs shared substrate

Implementation must decide whether the final shape is:

- one shared bounded execution contract used directly by both session-backed actor and judge callers, or
- one lower bounded execution substrate with thin actor-specific and judge-specific adapters above it

Either outcome is acceptable if the lower boundary remains clean and workflow-local interpretation stays outside it.

### Canonical execution-result shape

Implementation must record the minimum canonical result shape returned to workflow runtime, including:

- the keys required on success
- the keys required on failure
- any required execution/session metadata
- whether the boundary exposes only success/failure or a slightly richer bounded outcome taxonomy

That decision must not be left implicit in the moved code.

### Execution-session creation mode

Implementation must record whether the contract:

- always creates/binds execution sessions internally from standardized execution inputs,
- always expects a pre-created execution session identifier from the caller, or
- supports both modes with explicitly documented behavior

The chosen mode must preserve the boundary rule that workflow runtime should not need direct knowledge of internal creation mechanics.

### Actor-step boundary start

Implementation must record the exact point at which the lower boundary begins for session-backed actor execution, for example whether the contract receives:

- a fully prepared execution request,
- a session identifier plus prompt inputs, or
- another clearly defined bounded request shape

Workflow-specific session shaping must remain outside this boundary.

### Judge-session reuse across retries

Because judge retry orchestration remains above the contract, implementation must record how repeated bounded judge executions can reuse the same execution session when needed without re-exposing high-level orchestration internals.

### Lower-boundary home

Implementation must record whether the contract:

- expanded an existing `turn-runtime` boundary, or
- introduced a new lower execution-boundary component

and why that choice best matched the ownership line.

## Implementation shape

1. identify the current workflow session-backed actor/judge execution path
2. isolate the minimal bounded execution boundary for those turn-backed workflow forms
3. define the canonical bounded execution result contract for workflow callers
4. route workflow runtime through that contract
5. preserve existing persistence/audit behavior without making it the semantic result source
6. leave workflow routing/progression and non-turn workflow forms outside this task
7. record the resulting dependency direction clearly for the follow-on workflow runtime extraction
8. record all implementation-shaping decisions listed above in `implementation.md`

## Acceptance

- workflow runtime no longer depends directly on high-level turn orchestration details for session-backed actor/judge execution
- a lower bounded execution contract exists for session-backed actor turns and judge turns
- bounded callers consume canonical execution results directly
- journal/transcript reread is no longer the semantic result contract for bounded workflow execution
- workflow-specific session-config derivation and conversation shaping remain outside the extracted contract
- deterministic `:invoke` and delegated `:delegate` execution remain outside this task
- workflow behavior remains unchanged
- the new boundary materially improves the cleanliness of a later workflow runtime extraction
- the final implementation records whether the contract expanded an existing turn-runtime boundary or introduced a new lower execution-boundary component
- the final implementation records the canonical result shape and other implementation-shaping boundary decisions explicitly

## Related work

- `105-agent-session-component-extraction-map`
- workflow notes around canonical execution-result return vs journal reread
- proposed follow-on `workflow-runtime-core-component-extraction`
