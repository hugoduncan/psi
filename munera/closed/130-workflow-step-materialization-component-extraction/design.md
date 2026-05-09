# 130 — Workflow step materialization component extraction

## Goal

Extract workflow step materialization and source-resolution ownership into its own lower component so workflow step input/materialization logic no longer lives inside the broader `workflow-runtime` component as an authoritative owner.

## Why

After tasks `123` through `129`, the main workflow runtime extraction is largely complete:

- pure judge/routing owns workflow verdict interpretation and routing
- bounded turn execution owns session-backed actor/judge turn execution
- workflow runtime owns execution, progression, attempts, and statechart coordination
- workflow step session-config now has a dedicated extraction task because it is lower workflow-domain policy, but not runtime-core execution semantics

That leaves one remaining lower workflow cluster that still looks conceptually separate from runtime-core ownership:

- workflow step materialization
- source binding resolution
- source-spec application
- workflow-authored template rendering
- child-session conversation materialization for session steps
- prompt/preload splitting from that materialized conversation

This cluster is cohesive, derivation-heavy, and mostly independent of runtime stepping logic. It looks more like a workflow input/materialization engine than a runtime-core execution owner.

A separate component would make the architecture easier to read:

- workflow runtime core executes runs
- workflow step session-config derives child-session config policy
- workflow step materialization derives step inputs and session conversation from workflow state

## Problem

`psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.source-resolution` currently live under the extracted workflow runtime component, but their role is different from runtime-core execution semantics.

The current component surface still blurs two concerns:

- workflow runtime execution/progression semantics
- workflow step input/materialization semantics

That has several costs:

- the workflow runtime component remains broader than its clearest execution/runtime core
- input/materialization behavior can continue to accumulate under runtime ownership by inertia
- workflow-local derivation logic is less discoverable as a first-class boundary than it should be
- a later need to reuse materialization behavior outside runtime execution would be harder to satisfy cleanly

The question is no longer whether this code is lower-owned. It is. The question is whether it belongs in the runtime component specifically.

## Intent

Create a dedicated lower component for workflow step materialization ownership.

This task should:

- move authoritative workflow step materialization ownership out of `components/workflow-runtime/`
- preserve current workflow behavior exactly
- keep workflow runtime and higher entrypoints depending downward on the extracted component
- keep workflow step session-config as a separate lower owner rather than recombining the role split from `127`
- record clearly which responsibilities belong in the extracted component versus remaining in workflow runtime or other lower workflow components

This task should not:

- redesign workflow step semantics
- redesign source-spec semantics
- redesign workflow authoring semantics
- redesign the workflow execution adapter seam
- move session-config shaping with it by default
- move public workflow entrypoints, mutations, resolvers, or `psi-tool`
- broaden into another generic workflow-runtime reshuffle without a clear concept boundary

## In scope

- workflow step input materialization ownership
- source binding resolution for workflow step materialization
- source-spec application used by workflow materialization
- workflow-authored template rendering used for step materialization
- workflow child-session conversation materialization for session steps
- prompt/preload splitting of materialized session conversation
- prompt derivation from materialized step conversation
- extraction of the authoritative owner into a dedicated lower component
- rewiring workflow runtime and higher callers to the extracted owner
- recording the final dependency direction and boundary reasoning in `implementation.md`

## Out of scope

- workflow step session-config extraction or redesign
- workflow runtime execution/progression/statechart ownership
- workflow judge semantics
- bounded turn execution contract redesign
- public workflow API surfaces
- mutations, resolvers, `psi-tool`
- broad `agent-session.context` redesign
- introducing a generic templating or generic source-resolution framework for the whole system

## Current authoritative surface under review

Current authoritative owners under review:

- `components/workflow-runtime/src/psi/workflow_runtime/step_materialization.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/source_resolution.clj`

Current responsibilities in those namespaces include:

- binding/source reference resolution
- source-spec application against workflow run state
- workflow-authored template rendering for materialized step values
- step input materialization
- session contribution materialization
- child-session conversation materialization
- prompt/preload splitting
- prompt derivation from materialized session conversation
- projection helpers used for workflow-authored source specs

Current adjacent lower workflow owners that should be reviewed but not assumed in scope:

- `components/workflow-runtime/src/psi/workflow_runtime/step_session_config.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/execution_adapter.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/core.clj`
- `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime.clj`

## Proposed boundary

Representative target shape:

- `components/workflow-step-materialization/`
- authoritative namespace family `psi.workflow-step-materialization.*`

Representative internal namespace candidates if needed:

- `psi.workflow-step-materialization.core`
- `psi.workflow-step-materialization.source-resolution`

First-cut expectation:

- one authoritative namespace may be sufficient
- a small internal split between materialization and source-resolution is acceptable only if it improves ownership clarity and does not create unnecessary indirection

The extracted component should own:

- workflow step materialization derivation policy
- binding/source reference resolution used for workflow materialization
- source-spec application used by materialization
- workflow-authored template rendering used for materialization
- child-session conversation materialization for session steps
- prompt/preload splitting and prompt derivation from materialized session conversation

The extracted component should not own:

- workflow runtime stepping/progression/statechart execution
- workflow step session-config policy
- workflow judge/routing semantics
- session creation or prompt execution
- public workflow execution entrypoints
- mutations/resolvers/`psi-tool`

## Boundary rules

### Belongs in the new component

Belongs if it can be described as:

- given workflow run plus step id plus authored source/template/session contribution definitions, derive workflow step inputs or session conversation artifacts

Examples:

- resolving a source binding from workflow state
- applying a source spec to a workflow run
- rendering template contributions from workflow-authored vars
- materializing step inputs from source specs
- materializing a child-session conversation for a session step
- splitting the materialized conversation into preloaded messages plus execution prompt
- deriving `step-prompt` results

### Stays in workflow runtime

Stays in `workflow-runtime` if it can be described as:

- execute a workflow run
- decide the next step
- maintain attempts, progression, or working memory
- consume materialized step inputs or session conversation artifacts during runtime execution

### Stays in workflow step session-config

Stays in `workflow step session-config` if it can be described as:

- derive child-session config policy
- choose parent-session fallback/inheritance inputs
- derive model/tool/skill/prompt-mode/thinking-level/prompt-component-selection config

### Stays outside both lower components

Stays outside if it can be described as:

- create child sessions
- execute prompts or judge turns
- expose workflow operations publicly
- own transport, resolver, or mutation boundaries

## Dependency direction

The extracted component should depend downward on:

- workflow model/run state and effective step definitions
- lower workflow-owned helpers needed for source/materialization semantics
- `psi.workflow-judge` projection semantics only if the current source-resolution behavior truly depends on them and implementation records why that dependency remains appropriate

Dependency clarification:

- prefer preserving the current dependency shape first, then record any further cleanup opportunity explicitly rather than broadening this task
- if `source-resolution` currently depends on `psi.workflow-judge/project-messages` for projection semantics, preserve behavior first and classify that dependency explicitly as either legitimate shared lower workflow semantics or residual debt
- the preferred final dependency shape is tree-like rather than graph-like; if the extracted component retains a cross-component dependency on workflow-judge, implementation must record why that edge is architecturally appropriate or why it remains explicit residual debt
- this task should not expand the workflow execution adapter seam unless implementation proves a direct higher/session-bound dependency that truly belongs there

It should not depend upward on:

- public workflow execution façades
- workflow mutations/resolvers/`psi-tool`
- runtime statechart orchestration
- step session-config policy

Workflow runtime should depend downward on the extracted component for step inputs and materialized session conversation behavior.

## Naming and ownership decision rule

Preferred outcome:

- create a dedicated component named specifically for workflow step materialization ownership

Representative preferred names:

- `workflow-step-materialization`
- `workflow-materialization`

Naming rule:

- prefer the narrower name if the final responsibilities remain specifically about workflow step input/session-conversation materialization
- choose the broader name only if implementation proves the extracted ownership surface is truly wider than step-specific materialization
- record the naming decision and rejected alternative in `implementation.md`

## Relationship to adjacent workflow code

This task intentionally does not extract `step-session-config` alongside materialization.

Reason:

- `127` already split those two roles cleanly
- `129` is the corresponding session-config extraction task
- the purpose of this task is to preserve that role distinction while moving only the materialization side into a more conceptually accurate component

This task also intentionally does not fold the logic upward into `agent-session`.

Reason:

- the code is still lower workflow-domain ownership
- the extraction goal is not to reclassify it as general session orchestration
- the goal is to give it a more precise lower component home

This task also does not assume a `workflow-runtime` forwarding façade should remain afterward.

Preferred outcome:

- `psi.workflow-runtime.step-materialization` no longer remains as a forwarding owner after rewiring
- `psi.workflow-runtime.source-resolution` no longer remains as an authoritative owner after rewiring, and any retained seam must be explicitly justified
- unless a blocking reason is recorded, temporary forwarding seams should be removed before the task is considered complete

Allowance:

- a tiny temporary forwarding seam is acceptable only if implementation records why direct rewiring was not the better shape yet, what consumer still requires it, and the intended cleanup
- if such a seam remains at task completion, treat it as explicit residual debt rather than as the preferred finished shape

## Implementation decisions to record explicitly

Implementation must record at least these decisions in `implementation.md`:

### Final component/namespace name

Record:

- the chosen component name
- the chosen namespace family
- whether one namespace or a small internal split was used
- why that shape best matched the final responsibility surface

### Source-resolution ownership status

Record whether `source-resolution` is treated as:

- intrinsic to the extracted workflow step materialization component, or
- co-extracted as the smallest clean current boundary even if later work could reconsider it separately

If the latter, record why co-extraction was still the best current shape.

### Responsibility inventory

Record which responsibilities ended up inside the extracted component, including at least:

- binding/source reference resolution
- source-spec application
- workflow-authored template rendering
- step input materialization
- child-session conversation materialization
- prompt/preload splitting
- prompt derivation

If any current materialization/source-resolution responsibility intentionally remains outside the extracted component, record why.

### Public surface

Record the final remaining public vars of the extracted component and why each remains public.

Unless implementation records a compelling reason otherwise, preserve the current canonical behavior surfaces used by callers, including:

- `binding-source-value`
- `materialize-step-inputs`
- `materialize-step-session-conversation`
- `split-step-session-conversation`
- `step-prompt`

Public-surface classification:

- record which preserved public vars are considered canonical long-term behavior surfaces
- record which preserved public vars are being kept stable as currently consumed surfaces for extraction safety even if they may not be ideal long-term public API
- internal contribution-materialization helpers do not need to remain public unless they are intentionally consumed after rewiring and implementation records why

Call-contract clarification:

- preserve the externally consumed call contracts of those preserved behavior surfaces unless implementation records a justified replacement and rewires all affected consumers within this task

Output-contract clarification:

- preserve the externally consumed output contracts of those behavior surfaces exactly unless implementation records a justified replacement and rewires all affected consumers within this task
- internal helper structure, namespace placement, and local data-flow cleanup may change, but caller-visible keys, argument expectations, value shapes, and semantics should not drift implicitly during extraction

### Dependency/input shape

Record whether the extracted component:

- derives solely from provided workflow-run/effective-definition inputs, or
- retains other direct lower-workflow dependencies beyond those inputs

If the dependency/input shape changes from the current pattern, record why that was better than preserving the current shape first.

### Source-resolution dependency status

Record whether the extracted component retains any direct dependency on `psi.workflow-judge` projection behavior, and if so classify it explicitly as:

- legitimate shared lower workflow semantics, or
- explicit residual debt

Also record:

- why that dependency remains architecturally appropriate for now, or
- why it should become a later follow-on cleanup target
- whether the resulting dependency shape remains acceptably tree-like or still preserves a graph edge that should be revisited later

### Transitional namespace status

Record whether `psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.source-resolution`:

- disappear entirely after rewiring, or
- remain as tiny temporary forwarding seams

Any remaining forwarding seam must be justified with the blocking consumer and intended cleanup.

### Residual dependency status

Record whether any workflow-runtime namespace still directly depends on the old `psi.workflow-runtime.step-materialization` or `psi.workflow-runtime.source-resolution` owners after extraction.

If so, treat that as explicit residual debt and record the blocking reason and intended cleanup.

## Implementation shape

1. review `psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.source-resolution` and confirm the exact workflow step materialization responsibilities they own today
2. choose the narrowest accurate extracted component name for that responsibility surface
3. decide whether one namespace or a small internal split best fits the extracted ownership surface
4. create a dedicated lower component for workflow step materialization ownership
5. move the authoritative materialization and source-resolution logic with minimal semantic change
6. preserve the current canonical public behavior surfaces and their externally consumed call and output contracts unless a justified replacement is recorded
7. rewire workflow runtime, `agent-session.context`, `psi_tool_workflow`, and affected tests to the new owner
8. preserve the current role split with workflow step session-config rather than recombining the concerns
9. record the final boundary, name, public surface classification, source-resolution ownership status, dependency/input shape, source-resolution dependency status, responsibility inventory, transitional namespace status, and any residual debt in `implementation.md`

## Acceptance

- authoritative workflow step materialization ownership no longer lives in `components/workflow-runtime/`
- a dedicated lower component exists for workflow step materialization derivation policy
- workflow runtime depends downward on that component for step inputs and materialized session conversation behavior
- source binding resolution, source-spec application, template rendering, step input materialization, child-session conversation materialization, prompt/preload splitting, and prompt derivation remain lower workflow-domain ownership rather than moving upward into `agent-session`
- the preserved behavior surfaces remain stable unless implementation records justified replacements and rewires all affected consumers within task scope
- the preserved behavior surfaces retain their externally consumed call contracts unless implementation records justified replacements and rewires all affected consumers within task scope
- the externally consumed output contracts of the materialization behavior surfaces remain stable unless implementation records justified replacements and rewires all affected consumers within task scope
- `step-session-config` remains a separate role owner unless implementation records a compelling reason otherwise
- the workflow execution adapter seam is not broadened by this task unless implementation records a compelling reason
- public workflow entrypoints, mutations, resolvers, and `psi-tool` remain outside the extracted component
- lower materialization behavior proofs point at the extracted component rather than remaining under `workflow-runtime` or higher `agent-session` surfaces by inertia
- absent a justified blocking reason, `psi.workflow-runtime.step-materialization` and `psi.workflow-runtime.source-resolution` no longer remain as forwarding/authoritative owners after rewiring
- if a temporary forwarding seam remains at task completion, it is recorded as explicit residual debt rather than treated as the preferred finished shape
- workflow runtime, `agent-session.context`, `psi_tool_workflow`, and affected tests are rewired to the extracted owner as needed within task scope
- workflow behavior remains unchanged
- the final implementation records the chosen component name, public surface classification, source-resolution ownership status, dependency/input shape, source-resolution dependency status, final responsibility inventory, transitional namespace status, and any residual dependency debt explicitly

## Related work

- `105-agent-session-component-extraction-map`
- `123-workflow-judge-routing-component-extraction`
- `125-workflow-runtime-core-component-extraction`
- `127-workflow-step-prep-role-split`
- `128-workflow-execution-adapter-seam`
- `129-workflow-step-session-config-component-extraction`
