# 129 — Workflow step session-config component extraction

## Goal

Extract workflow child-session configuration policy into its own lower component so workflow step session-config shaping no longer lives inside the broader `workflow-runtime` component as an authoritative owner.

## Why

Tasks `123` through `128` cleaned up the main workflow ownership seams:

- pure judge/routing now lives in `psi.workflow-judge`
- bounded turn execution now lives in `psi.workflow-runtime.turn-execution-contract`
- runtime/statechart/progression ownership now lives in `psi.workflow-runtime.*`
- workflow execution now crosses into session-owned behavior through the named `psi.workflow-runtime.execution-adapter` seam
- step prep was split into:
  - `psi.workflow-runtime.step-materialization`
  - `psi.workflow-runtime.step-session-config`

That split made the next boundary clearer.

`step-session-config` is coherent, but it is not quite the same kind of ownership as workflow runtime execution semantics. It primarily owns workflow child-session configuration policy:

- parent session lookup
- context-session fallback
- model/tool/skill inheritance
- workflow meta merge rules
- child-session developer-prompt/config shaping

Those responsibilities are workflow-domain policy, but they are not statechart stepping, progression, attempt bookkeeping, or runtime control flow.

A separate component would make the current architecture easier to read:

- workflow runtime core executes runs
- workflow judge evaluates routing
- workflow step session-config shapes child-session config policy

## Problem

`psi.workflow-runtime.step-session-config` currently lives under the extracted workflow runtime component, but its role is different from the rest of that component.

The current mixed component surface makes it easy to blur two concerns:

- workflow runtime semantics
- workflow child-session configuration policy

That has several costs:

- the workflow runtime component remains broader than its most coherent execution/runtime core
- session-policy behavior can continue to accumulate under runtime ownership by inertia
- future extraction work has less obvious boundaries because config shaping looks like runtime support rather than a first-class workflow policy surface

The main question is no longer whether the code is lower-owned. It is. The question is whether it belongs in the runtime component specifically.

## Intent

Create a dedicated lower component for workflow step session-config ownership.

This task should:

- move authoritative workflow child-session config shaping ownership out of `components/workflow-runtime/`
- preserve current workflow behavior exactly
- keep the existing named runtime → session adapter seam as the higher/session-bound dependency crossing
- make workflow runtime and higher session entrypoints depend downward on the extracted component
- record clearly which responsibilities belong in the extracted component versus remaining in workflow runtime or higher `agent-session` assembly

This task should not:

- redesign workflow step semantics
- redesign inheritance semantics for model/tools/skills
- redesign the execution adapter seam
- move materialization logic with it by default
- move public workflow entrypoints, mutations, resolvers, or `psi-tool`
- broaden into a general session-policy framework
- broaden into another workflow-runtime reshuffle without a clear concept boundary

## In scope

- workflow child-session config shaping ownership
- parent session lookup/fallback used specifically for workflow step config shaping
- inherited model/tool/skill shaping for workflow child sessions
- workflow file meta merge rules used for step child-session config
- child-session developer-prompt/config derivation for workflow steps
- extraction of the authoritative owner into a dedicated lower component
- rewiring workflow runtime and higher callers to the extracted owner
- recording the final dependency direction and boundary reasoning in `implementation.md`

## Out of scope

- workflow step materialization and source-resolution extraction
- workflow runtime execution/progression/statechart ownership
- workflow judge semantics
- bounded turn execution contract redesign
- public workflow API surfaces
- mutations, resolvers, `psi-tool`
- broad `agent-session.context` redesign
- introducing a generic cross-domain session-policy component

## Current authoritative surface under review

Current authoritative owner:

- `components/workflow-runtime/src/psi/workflow_runtime/step_session_config.clj`

Current responsibilities in that namespace include:

- parent session reads via `psi.workflow-runtime.execution-adapter`
- context-session fallback lookup
- skill resolution/inheritance
- tool definition resolution/inheritance
- model inheritance
- prompt-mode derivation/inheritance
- thinking-level derivation/inheritance
- prompt-component-selection derivation
- workflow meta merge rules
- child-session prompt/config shaping
- `resolve-step-session-config`

Current adjacent lower workflow owners that should be reviewed but not assumed in scope:

- `components/workflow-runtime/src/psi/workflow_runtime/step_materialization.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/source_resolution.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/execution_adapter.clj`

## Proposed boundary

Representative target shape:

- `components/workflow-step-session-config/`
- authoritative namespace family `psi.workflow-step-session-config.*`

First-cut expectation:

- one authoritative namespace is likely sufficient unless implementation reveals a clear internal split that improves ownership clarity

The extracted component should own:

- workflow step child-session config derivation policy
- parent-session lookup/fallback rules used for that policy
- model/tool/skill inheritance rules used for workflow child sessions
- prompt-mode, thinking-level, and prompt-component-selection derivation for workflow child sessions
- workflow meta merge behavior used to shape workflow child-session config
- final workflow child-session session-config derivation

The extracted component should not own:

- workflow runtime stepping/progression/statechart execution
- workflow judge/routing semantics
- workflow step conversation materialization
- public workflow execution entrypoints
- session creation or prompt execution
- mutations/resolvers/`psi-tool`

## Boundary rules

### Belongs in the new component

Belongs if it can be described as:

- given workflow run plus step id plus access to parent-session/session-reference data, derive the child-session config for that workflow step

Examples:

- choosing the parent session to inherit from
- resolving inherited skills and tools for a workflow step
- deriving prompt mode, thinking level, and prompt-component-selection for a workflow step child session
- composing workflow-authored prompt/config policy into the child-session config
- producing `resolve-step-session-config` results

Clarification:

- for this task, parent-session selection/fallback is part of workflow step child-session config derivation policy and should move with the extracted component while preserving current behavior exactly

### Stays in workflow runtime

Stays in `workflow-runtime` if it can be described as:

- execute a workflow run
- decide the next step
- maintain attempts, progression, or working memory
- consume a derived step session config during runtime execution

### Stays outside both lower components

Stays outside if it can be described as:

- create child sessions
- execute prompts or judge turns
- expose workflow operations publicly
- own transport, resolver, or mutation boundaries

## Dependency direction

The extracted component should depend downward on:

- the workflow execution adapter seam for session-bound reads such as session lookup, context-session listing, and skill lookup
- workflow registry/model data as needed for workflow file meta and effective step inspection

Dependency clarification:

- preserve the current adapter-vs-local ownership split unless implementation records a compelling reason to change it
- adapter-provided reads may include session data, context-session listing, and skill lookup
- tool normalization and workflow-local config shaping should remain local ownership unless implementation proves a cleaner boundary
- preserve the current direct dependency pattern first; implementation must record whether the extracted component reads workflow registry state directly or only derives from provided workflow-run/effective-definition inputs

It should not depend upward on:

- public workflow execution façades
- workflow mutations/resolvers/`psi-tool`
- runtime statechart orchestration

Workflow runtime should depend downward on the extracted component for step session-config derivation.

## Naming and ownership decision rule

Preferred outcome:

- create a dedicated component named specifically for workflow step session-config ownership

Representative preferred names:

- `workflow-step-session-config`
- `workflow-session-config`

Naming rule:

- prefer the narrower name if the final responsibilities remain specifically about workflow step child-session configuration
- choose the broader name only if implementation proves the extracted ownership surface is truly wider than step-specific child-session config shaping
- record the naming decision and rejected alternative in `implementation.md`

## Relationship to adjacent workflow code

This task intentionally does not extract `step-materialization` alongside session-config by default.

Reason:

- `127` already split those two roles cleanly
- the purpose of this task is to preserve that role distinction while moving only the session-config side into a more conceptually accurate component
- `step-materialization` may become a separate component later, but that is a separate boundary decision

This task also intentionally does not fold the logic upward into `agent-session`.

Reason:

- the code is still lower workflow-domain ownership
- the extraction goal is not to reclassify it as general session orchestration
- the goal is to give it a more precise lower component home

This task also does not assume a `workflow-runtime` forwarding façade should remain afterward.

Preferred outcome:

- `psi.workflow-runtime.step-session-config` no longer remains as a forwarding owner after rewiring

Allowance:

- a tiny temporary forwarding seam is acceptable only if implementation records why direct rewiring was not the better shape yet, what consumer still requires it, and the intended cleanup

## Implementation decisions to record explicitly

Implementation must record at least these decisions in `implementation.md`:

### Final component/namespace name

Record:

- the chosen component name
- the chosen namespace family
- why that name best matched the final responsibility surface

### Responsibility inventory

Record which responsibilities ended up inside the extracted component, including at least:

- parent session selection/fallback
- tool inheritance
- skill inheritance
- model inheritance
- prompt-mode derivation/inheritance
- thinking-level derivation/inheritance
- prompt-component-selection derivation
- workflow meta merge rules
- child-session prompt/config derivation

If any current `step-session-config` responsibility intentionally remains outside the extracted component, record why.

### Public surface

Record the final remaining public vars of the extracted component and why each remains public.

Unless implementation records a compelling reason otherwise, preserve `resolve-step-session-config` as the canonical behavior surface and public entrypoint for this lower owner.

Output-contract clarification:

- preserve the externally consumed output contract of `resolve-step-session-config` exactly unless implementation records a justified replacement and rewires all affected consumers within this task
- internal helper structure, namespace placement, and local data-flow cleanup may change, but caller-visible config keys and their behavior should not drift implicitly during extraction

### Dependency/input shape

Record whether the extracted component:

- reads workflow registry state directly, or
- derives only from provided workflow-run/effective-definition inputs

If the dependency/input shape changes from the current pattern, record why that was better than preserving the current shape first.

### Transitional namespace status

Record whether `psi.workflow-runtime.step-session-config`:

- disappears entirely after rewiring, or
- remains as a tiny temporary forwarding seam

Any remaining forwarding seam must be justified with the blocking consumer and intended cleanup.

### Residual dependency status

Record whether any workflow-runtime namespace still directly depends on the old `psi.workflow-runtime.step-session-config` owner after extraction.

If so, treat that as explicit residual debt and record the blocking reason and intended cleanup.

## Implementation shape

1. review `psi.workflow-runtime.step-session-config` and confirm the exact workflow child-session config responsibilities it owns today
2. choose the narrowest accurate extracted component name for that responsibility surface
3. create a dedicated lower component for workflow step session-config ownership
4. move the authoritative session-config shaping logic with minimal semantic change
5. rewire workflow runtime, `agent-session.context`, `psi_tool_workflow`, and affected tests to the new owner
6. preserve the current execution-adapter seam rather than redesigning the runtime → session crossing
7. record the final boundary, name, public surface, dependency/input shape, responsibility inventory, transitional namespace status, and any residual debt in `implementation.md`

## Acceptance

- authoritative workflow step child-session config ownership no longer lives in `components/workflow-runtime/`
- a dedicated lower component exists for workflow step child-session config derivation policy
- workflow runtime depends downward on that component for step session-config derivation
- parent-session lookup/fallback, inheritance rules, prompt-mode/thinking-level/prompt-component-selection derivation, and child-session config shaping remain lower workflow-domain ownership rather than moving upward into `agent-session`
- `resolve-step-session-config` remains the canonical behavior surface unless implementation records a justified replacement
- the externally consumed output contract of `resolve-step-session-config` remains stable unless implementation records a justified replacement and rewires all affected consumers within task scope
- `step-materialization` remains a separate role owner unless implementation records a compelling reason otherwise
- the workflow execution adapter seam remains the canonical higher/session-bound crossing for this logic
- public workflow entrypoints, mutations, resolvers, and `psi-tool` remain outside the extracted component
- lower session-config behavior proofs point at the extracted component rather than remaining under `workflow-runtime` or higher `agent-session` surfaces by inertia
- absent a justified temporary forwarding seam, `psi.workflow-runtime.step-session-config` no longer remains as a forwarding owner after rewiring
- workflow runtime, `agent-session.context`, `psi_tool_workflow`, and affected tests are rewired to the extracted owner as needed within task scope
- workflow behavior remains unchanged
- the final implementation records the chosen component name, public surface, dependency/input shape, final responsibility inventory, and any residual dependency debt explicitly

## Related work

- `105-agent-session-component-extraction-map`
- `123-workflow-judge-routing-component-extraction`
- `124-turn-execution-contract-extraction`
- `125-workflow-runtime-core-component-extraction`
- `127-workflow-step-prep-role-split`
- `128-workflow-execution-adapter-seam`
- possible later follow-on for workflow step materialization/source-resolution extraction
