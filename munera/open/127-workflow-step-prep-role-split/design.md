# 127 — Workflow step prep role split

## Goal

Split the current workflow step-preparation ownership into smaller, role-focused lower namespaces so workflow step materialization and session-config shaping are no longer mixed in one boundary-sensitive namespace.

## Why

Post tasks `123`, `124`, and `125`, `psi.workflow-runtime.step-prep` is the most boundary-sensitive namespace in the extracted workflow runtime component.

It currently mixes two different kinds of work:

- lower workflow-runtime shaping/materialization semantics
- parent-session/config inheritance and child-session configuration shaping

Both may remain inside the lower workflow component family, but they are not the same role. Keeping them together makes it harder to reason about whether workflow runtime is cleanly lower-owned or quietly accumulating session-policy behavior again.

## Problem

`psi.workflow-runtime.step-prep` currently combines:

### Materialization-oriented workflow shaping

- source binding exposure
- step input materialization
- template rendering
- materialized step conversation building
- prompt/preload split
- step prompt derivation

### Session-policy/config shaping

- parent session lookup and fallback selection
- parent model/tool/skill inheritance
- workflow file meta merge rules
- child-session developer-prompt/config shaping

Those roles are related, but they are different enough that they should not remain structurally fused if we want the runtime component to stay locally comprehensible.

## Intent

Separate workflow step-preparation responsibilities by role while preserving current behavior exactly.

Meaning of preserved contracts for this task:
- preserve workflow behavior exactly
- preserve intentionally consumed internal contracts where needed during the split
- allow lower-namespace public-surface cleanup when all affected consumers are rewired within this task

This task should:

- create a clear lower owner for workflow step materialization behavior
- create a distinct lower owner for workflow child-session config shaping behavior
- rewire runtime/context/tests to the split owners
- make future ownership decisions easier if config shaping later needs to move again

This task should not:

- move the split code back to `agent-session` by default
- redesign workflow step semantics
- redesign tool/skill/model inheritance semantics
- introduce the named workflow execution adapter seam yet
- change public workflow APIs
- broadly preserve every current lower-namespace public as a constraint if doing so would keep the mixed ownership shape unnecessarily

## Proposed boundary

Expected target namespaces:
- `psi.workflow-runtime.step-materialization`
- `psi.workflow-runtime.step-session-config`

These names are the expected outcome. A different final naming choice is acceptable only if implementation records the variation explicitly and shows that the same role split remains clear.

### `psi.workflow-runtime.step-materialization`

Owns:

- binding/source resolution exposure for workflow-step use
- `materialize-step-inputs`
- template rendering for step contributions
- `materialize-step-session-conversation`
- `split-step-session-conversation`
- `step-prompt`

This is lower workflow-runtime shaping/materialization behavior.

Public-surface expectation:
- implementation must record the final remaining public vars of this namespace in `implementation.md`
- helpers that exist only for internal support and are not intentionally consumed after the split should be made private where that improves ownership clarity

### `psi.workflow-runtime.step-session-config`

Owns:

- parent session reads/fallback selection
- tool/skill/model inheritance
- workflow meta merge rules
- child-session prompt/config shaping
- `resolve-step-session-config`

This is lower workflow-domain session-config shaping behavior.

Public-surface expectation:
- implementation must record the final remaining public vars of this namespace in `implementation.md`
- helpers that exist only for internal support and are not intentionally consumed after the split should be made private where that improves ownership clarity

Tie-break rule for borderline helpers:
- place a helper with the role whose higher-level behavior it primarily serves
- in ambiguous cases, prefer the ownership choice that minimizes dependency-direction surprises and, where reasonable, keeps session-policy concerns out of the materialization owner
- if a helper is genuinely cross-role and extracting it would reduce coupling more than it adds indirection, a tiny third helper owner is acceptable, but only if implementation records why that shape is better than forcing ownership into one of the two main roles
- a third helper owner is acceptable only for narrowly shared utility behavior and must not introduce a third conceptual top-level role alongside materialization and session-config shaping

### Transitional allowance

Preferred outcome:
- `psi.workflow-runtime.step-prep` no longer remains as an authoritative mixed owner after the split
- remove it entirely if the split owners can be consumed directly without unnecessary churn

Allowance:
- a very small façade namespace may remain only if implementation proves it helps preserve a stable internal call surface during the split
- any remaining façade must be a thin forwarding surface only and must not keep substantive mixed ownership
- any remaining façade must not continue mixing both roles opaquely
- it must not remain the preferred import surface for new callers when direct use of the split owners is practical
- if kept, implementation must record why direct consumption of the split owners was not the better shape yet

## In scope

- split of the current `psi.workflow-runtime.step-prep`
- rewiring of `context`, workflow runtime callers, `psi_tool_workflow`, and tests to the split owners
- direct rewiring of callback/backfill wiring to the split owners when no façade is justified; if a façade remains, implementation must record why direct wiring was not the better shape yet
- proof shaping so tests reflect one clear role owner each
- recording the final split decision in `implementation.md`

## Out of scope

- step-prep behavior redesign
- new adapter/protocol introduction for workflow-runtime effects
- moving session-config shaping above the workflow runtime component unless implementation proves that is required
- mutations/resolvers/`psi-tool` redesign
- consolidating or formalizing the current workflow-runtime ↔ session-owned callback/read seam beyond rewiring existing consumers to the split owners

## Implementation shape

1. review the current `step-prep` namespace and classify each public/private helper as materialization-oriented or session-config-oriented
2. use code search to identify all current references to `psi.workflow-runtime.step-prep` publics across runtime, context, tool, and test consumers
3. create separate lower namespaces for those two roles
4. move code with minimal semantic change
5. rewire runtime/context/tool/test consumers to the new owners
6. leave at most a tiny explicit façade if needed, or remove the old mixed namespace entirely
7. record the final ownership decision and any residual ambiguity in `implementation.md`

Meaning of “minimal semantic change” for this task:
- preserve runtime behavior and externally consumed contracts exactly
- internal helper extraction, private helper renaming, require reshaping, and small local data-flow cleanup are allowed when they make the split clearer without changing behavior

## Acceptance

- workflow step materialization logic and workflow step session-config shaping no longer live as one mixed lower namespace
- materialization behavior has a clear lower owner
- session-config shaping behavior has a clear lower owner
- runtime/context/tests depend on the split owners directly or through a tiny explicit façade only if justified
- callback/backfill wiring points directly at the split owners when no façade is justified; if a façade remains, implementation records why direct wiring was not the better shape yet
- materialization behavior proofs point at the materialization owner, while session-config shaping proofs point at the session-config owner; higher integration tests remain with higher/runtime consumers as appropriate
- tests are moved/renamed when needed so proof ownership reflects the new role topology rather than preserving the old mixed topology by inertia
- behavior remains unchanged
- the final implementation records why each side of the split belongs where it ended up
- the final implementation records the remaining public vars of the split owners and why any non-obvious public remained public
- if `psi.workflow-runtime.step-prep` remains as a façade, implementation records why that was better than direct use of the split owners

## Related work

- `125-workflow-runtime-core-component-extraction`
- `126-workflow-execution-facade-narrowing`
- `128-workflow-execution-adapter-seam`
