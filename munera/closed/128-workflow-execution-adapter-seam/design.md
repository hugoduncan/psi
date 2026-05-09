# 128 — Workflow execution adapter seam

## Goal

Introduce a named workflow execution adapter seam so lower workflow runtime code depends on one explicit higher/session-bound execution contract rather than an informal cluster of workflow-specific ctx callback keys.

## Why

Post tasks `123`, `124`, and `125`, the remaining workflow awkwardness is no longer mainly namespace ownership. The main remaining issue is boundary shape.

`psi.workflow-runtime.*` is now lower-owned, but it still depends on several session-bound effects and reads through ctx-supplied callbacks such as:

- child-session creation
- bounded prompt execution
- judge execution
- parent session reads/context lookup for step shaping
- skill lookup for workflow session config shaping

That callback-based decoupling is better than upward namespace dependencies, but the seam is still implicit and scattered. The boundary exists conceptually, but it does not yet exist as one explicit workflow-facing contract.

## Problem

The current workflow-runtime → higher session boundary is spread across multiple callback keys, including examples such as:

- `:create-workflow-child-session-fn`
- `:workflow-prompt-execution-result-fn`
- `:execute-workflow-judge-fn`
- `:get-session-data-fn`
- `:list-context-sessions-fn`
- `:find-skill-fn`
- and adjacent workflow-specific callback keys supplied from `agent-session.context` / `psi_tool_workflow`

This has several costs:

- the boundary is harder to inspect and reason about
- required higher/session services are not grouped by purpose
- tests stub a loose set of keys rather than one named seam
- `context` becomes the implicit protocol registry for workflow runtime concerns

## Intent

Create one explicit workflow execution adapter seam for the higher/session-bound operations the lower workflow runtime requires.

Meaning of “one seam” for this task:
- one seam means one explicit, cohesive boundary owner or adapter surface through which workflow-runtime accesses its session-bound needs
- that seam may contain multiple operations internally, but workflow-runtime should depend on one named surface rather than a scattered set of workflow-specific callback keys

This task should:

- define a named workflow-facing adapter contract
- move workflow-runtime call sites to that contract
- keep `agent-session` as the canonical implementation owner of session-bound workflow effects
- simplify testing and boundary reasoning by replacing a loose callback bag with one explicit seam

This task should not:

- redesign workflow behavior
- redesign judge semantics
- redesign child-session creation semantics
- redesign dispatcher ownership
- broaden into a general context/protocol framework redesign

## Proposed boundary

Representative names to choose between during implementation:

- `psi.workflow-runtime.execution-adapter`
- `psi.workflow-runtime.session-adapter`

Preferred direction:
- choose the name that best reflects that this seam exists specifically for workflow runtime execution/orchestration, not for every possible session concern

Naming decision rule:
- choose `execution-adapter` if the seam remains limited to workflow execution/orchestration needs
- choose `session-adapter` only if the final responsibilities truly form a broader session-facing substrate and implementation records why that broader scope is justified

Expected initial responsibility inventory for the adapter seam includes operations such as:

- create workflow child session
- execute one bounded workflow prompt turn
- execute workflow judge orchestration
- read parent session data
- list relevant context sessions
- resolve/find project/session skill references needed for workflow step config shaping

Implementation should derive the final operation set from the callback inventory actually consumed by workflow-runtime. If any operation from this expected initial inventory is intentionally left outside the seam, implementation must record why.

Responsibility exclusion rule:
- an expected responsibility may remain outside the seam only if it already belongs to a clearer existing boundary, or including it would make the seam less cohesive

This seam may still be implemented through ctx underneath, but the lower runtime should depend on one named contract rather than many workflow-specific callback keys.

## In scope

- defining the explicit workflow execution adapter seam
- choosing one explicit representation for that seam and recording why it was better than the main rejected alternatives
- comparing the chosen representation against the most plausible alternatives, including:
  - a namespace API surface
  - a named adapter value/map
  - a protocol/record-based seam
  - a thin wrapper over raw ctx callback keys
- updating workflow-runtime owners to consume it
- updating `agent-session` to provide the canonical implementation
- updating `psi_tool_workflow` backfill/compatibility wiring, where it participates in workflow-specific callback provisioning, so it prefers the named seam over raw callback-key provisioning once the seam exists
- updating tests that currently stub workflow-specific callback keys consumed by workflow-runtime so they stub the named seam instead, unless a specific exception is recorded as clearer at a lower layer
- lower-layer testing exceptions are appropriate only when a test is intentionally proving the adapter implementation/assembly layer rather than workflow-runtime’s consumption boundary
- recording the chosen seam name and responsibility boundary in `implementation.md`

## Out of scope

- changing workflow semantics
- moving all session concerns into workflow-runtime
- redesigning `context` globally
- broad callback cleanup unrelated to workflow runtime
- public workflow API changes
- removing raw callback keys from every layer of `agent-session` plumbing if the new seam can wrap them adequately at the runtime boundary

## Design constraints

- keep the seam small and cohesive
- include only operations workflow-runtime must use to cross into session-owned behavior
- exclude unrelated general session/runtime services even if they are technically reachable through ctx today
- prefer operations grouped by coherent workflow-runtime needs rather than one-for-one mechanical wrapping of every existing callback key
- do not hide unrelated services inside a generic “everything adapter” abstraction
- preserve clear ownership: workflow runtime owns runtime semantics; agent-session owns session-bound effects/reads
- prefer explicit required operations over opaque map-passing where possible
- prefer the simplest explicit representation that makes the seam inspectable and testable without introducing a broader protocol framework unless implementation records why a broader mechanism is justified
- a single named adapter value is acceptable if its operation surface is explicit and documented; the task is avoiding scattered ad hoc callback keys, not necessarily all map-backed implementation
- preserve behavior exactly

## Implementation shape

1. inventory the current workflow-specific callback keys consumed by `psi.workflow-runtime.*` (preferably against the post-`127` workflow-runtime shape when available)
2. decide the smallest cohesive set that forms the execution/session adapter seam
3. choose one explicit representation for the seam and record why it was better than the main rejected alternatives
4. define the named seam and route workflow-runtime call sites through it
5. provide the canonical implementation from `agent-session`; `agent-session.context` may remain the assembly site for that implementation
6. rework tests to stub the seam more coherently
7. record the final seam name, responsibilities, representation, residual raw-key plumbing, and excluded concerns in `implementation.md`

## Acceptance

- lower workflow runtime code no longer directly depends on a loose, workflow-specific set of raw ctx callback keys for its main higher/session-bound operations
- a named workflow execution adapter seam exists and is the explicit boundary for those operations
- `agent-session` remains the implementation owner of session-bound workflow effects and reads
- `agent-session.context` may remain the assembly site for the canonical seam implementation, but workflow-runtime consumes the named seam rather than raw workflow-specific callback keys
- tests and wiring reflect the seam more coherently
- behavior remains unchanged
- the final implementation records the chosen seam name, representation, owned responsibilities, intentionally excluded concerns, and any residual raw-key plumbing left behind the seam

## Residual-debt rule

Any remaining direct workflow-runtime dependence on raw workflow-specific callback keys after the seam introduction must be treated as explicit residual debt and recorded in `implementation.md` with the blocking reason and intended cleanup.

Interpretation:
- any direct read of workflow-specific callback keys from `psi.workflow-runtime.*` counts as residual debt after the seam introduction, even if most other calls route through the seam

## Related work

- `123-workflow-judge-routing-component-extraction`
- `124-turn-execution-contract-extraction`
- `125-workflow-runtime-core-component-extraction`
- `126-workflow-execution-facade-narrowing`
- `127-workflow-step-prep-role-split`
