# 131 — Workflow loader component extraction

## Goal

Extract workflow authored-definition loading ownership into its own lower component so workflow file discovery, authored-definition ingestion, and load-to-definition preparation no longer live in mixed workflow or higher session-owned surfaces as their authoritative owner.

## Why

After tasks `123` through `130`, the workflow runtime-oriented extractions are largely covered:

- workflow registry owns registered definition storage/query concerns
- deterministic-operation registry/runtime own workflow `:invoke` registry/runtime concerns
- workflow judge owns routing/judge semantics
- workflow runtime owns execution, progression, attempts, and statechart coordination
- workflow step session-config and workflow step materialization now have dedicated extraction tasks for lower non-runtime workflow derivation seams

That leaves one remaining workflow-shaped area that is conceptually distinct from both registry and runtime:

- workflow file discovery/loading
- authored-definition ingestion
- load-time normalization/preparation
- definition acquisition for later registry/runtime use
- workflow-file metadata loading context where applicable

This is not runtime execution, and it is not registry ownership. It is the workflow authored-definition loading boundary.

A separate component would make the workflow architecture easier to read:

- workflow loader acquires authored definitions
- workflow registry stores/queries known definitions
- workflow runtime executes runs derived from definitions

## Problem

Workflow loading concerns can easily end up mixed across higher surfaces such as:

- `psi_tool_workflow`
- `agent-session` workflow entrypoints
- registry-facing mutation or adapter code
- runtime-adjacent workflow orchestration

Without a dedicated loader owner, authored-definition acquisition risks being spread across multiple layers:

- discovery policy in one place
- file reading in another
- authored-definition normalization in another
- registry/runtime handoff in another

That makes it harder to reason about:

- where workflow definitions enter the system
- which logic is load-time vs registry-time vs runtime-time
- what later cleanup is really loader work rather than adapter work

## Intent

Create a dedicated lower component for workflow loader ownership.

This task should:

- move authoritative workflow authored-definition loading ownership into a dedicated lower component
- preserve current workflow behavior exactly
- keep loader ownership distinct from workflow registry ownership and workflow runtime ownership
- keep higher workflow entrypoints depending downward on the extracted component
- record clearly which responsibilities belong in the extracted loader component versus remaining in registry, runtime, or higher `agent-session`/tool surfaces

This task should not:

- redesign workflow authoring semantics
- redesign workflow runtime semantics
- redesign workflow registry contracts
- redesign public workflow API surfaces
- broaden into generic file-loader infrastructure for the whole system
- silently absorb runtime, registry, or step-derivation responsibilities just because they are nearby

## In scope

- workflow file discovery/loading ownership
- authored-definition acquisition from workflow files or equivalent loader inputs
- load-time preparation/normalization needed to hand definitions to lower registry/runtime consumers
- workflow-file metadata loading context where it belongs to authored-definition loading
- loader-owned handoff boundaries to workflow registry/runtime consumers
- extraction of the authoritative owner into a dedicated lower component
- rewiring higher callers to the extracted owner
- recording the final dependency direction and boundary reasoning in `implementation.md`

## Out of scope

- workflow registry storage/query ownership
- workflow runtime execution/progression/statechart ownership
- workflow judge semantics
- workflow step session-config ownership
- workflow step materialization ownership
- public workflow API redesign
- mutations, resolvers, `psi-tool` redesign
- generic file discovery framework work outside workflow loading

## Current authoritative surface under review

This task intentionally starts from conceptual ownership rather than assuming the final surface is one current namespace.

Expected review targets include whichever current workflow-adjacent namespaces own or participate materially in:

- workflow file discovery
- authored-definition file reading/loading
- workflow definition preparation before registry/runtime use
- workflow-file metadata acquisition used during loading
- workflow loading orchestration currently performed above a lower workflow boundary

Implementation must identify and record the exact current authoritative surfaces reviewed.

Likely adjacent higher or mixed surfaces to inspect include workflow-facing entrypoint and adapter owners such as:

- `psi.agent-session.psi-tool-workflow`
- workflow-facing mutation/resolver entrypoints where they participate in loading rather than only calling a lower loader
- existing workflow registry/definition helper namespaces if they currently mix loading with storage/query concerns
- existing workflow compiler/authoring helpers if they currently serve primarily as loader-owned ingestion steps
- workflow-file authoring compilation helpers that normalize author-facing source, preload, and routing forms into canonical prepared definitions

Current review is expected to at least consider these current lower/mixed workflow-file owners explicitly:

- `psi.agent-session.workflow-file-loader`
- `psi.agent-session.workflow-file-parser`
- `psi.agent-session.workflow-file-compiler`
- `psi.agent-session.workflow-file-authoring-errors`
- `psi.agent-session.workflow-file-authoring-session`
- `psi.agent-session.workflow-file-authoring-preload`
- `psi.agent-session.workflow-file-authoring-routing`
- `psi.agent-session.workflow-file-authoring-resolution`

First-cut expectation:

- `workflow-file-loader`, `workflow-file-parser`, and `workflow-file-compiler` are expected to move unless review discovers a stronger lower owner
- `workflow-file-authoring-errors`, `workflow-file-authoring-session`, `workflow-file-authoring-preload`, and `workflow-file-authoring-routing` are expected to move when they remain load-time preparation of canonical prepared definitions
- `workflow-file-authoring-resolution` is expected to be treated as a compatibility façade rather than a preferred final authoritative owner

This task should not assume up front that every reviewed namespace belongs in the extracted component. Implementation must distinguish:

- authoritative loader ownership
- loader-adjacent helpers that should move with it
- registry/runtime ownership that should remain outside
- higher adapter/entrypoint orchestration that should remain above

## Proposed boundary

Representative target shape:

- `components/workflow-loader/`
- authoritative namespace family `psi.workflow-loader.*`

Representative internal namespace candidates if needed:

- `psi.workflow-loader.core`
- `psi.workflow-loader.discovery`
- `psi.workflow-loader.parser`
- `psi.workflow-loader.compiler`
- `psi.workflow-loader.authoring.*`

First-cut expectation:

- treat the extraction as one coherent authored-definition loading owner rather than an arbitrary pre-runtime bundle
- a small internal split is acceptable if it improves ownership clarity without creating unnecessary indirection
- prefer the smallest split that keeps discovery, parsing, compilation, and authoring-preparation roles legible

The extracted component should own:

- workflow authored-definition loading policy
- workflow file discovery/loading behavior
- authored-definition ingestion/preparation behavior
- loader-owned workflow-file metadata acquisition/normalization where applicable
- handoff of loaded/prepared definitions to lower registry/runtime consumers
- load-time authoring normalization only insofar as it transforms file-authored forms into canonical prepared definitions during loading

The extracted component should not own:

- registering loaded definitions into canonical state as part of loader authoritative ownership
- workflow registry definition storage/query ownership
- workflow runtime stepping/progression/execution
- workflow judge/routing semantics as runtime decision ownership
- workflow step session-config policy
- workflow step materialization policy
- public workflow execution entrypoints
- mutations/resolvers/`psi-tool`


## Boundary rules

### Belongs in the new component

Belongs if it can be described as:

- given workflow loader inputs such as definition ids, file references, or authored workflow sources, acquire and prepare definitions for downstream workflow consumers

Examples:

- discovering workflow files
- reading or loading authored workflow definitions
- parsing unified workflow definition files
- compiling parsed workflow files into canonical target-authored workflow definitions
- preparing/normalizing loaded authored definitions
- attaching workflow-file metadata used as part of loading
- returning canonical prepared workflow definition data and load diagnostics to registry/runtime or higher callers
- compiling workflow-file authoring helpers such as source, preload, and routing forms when they are part of load-time preparation

Inclusion rule clarification:

- workflow-file authoring helpers belong in the loader component only when they participate directly in transforming file-authored forms into canonical prepared definitions during loading
- those helpers do not thereby become owners of runtime execution semantics, workflow judging, step session-config policy, or step materialization policy after loading

### Stays in workflow registry

Stays in workflow registry if it can be described as:

- register/remove/query known workflow definitions
- store canonical known definitions
- answer registry queries over already known definitions

### Stays in workflow runtime

Stays in workflow runtime if it can be described as:

- execute workflow runs
- decide the next step
- maintain attempts, progression, or working memory
- consume already loaded/registered definitions during execution

### Stays outside both lower components

Stays outside if it can be described as:

- expose workflow operations publicly
- own transport, resolver, or mutation boundaries
- orchestrate UI/tool/session-facing command handling above the lower loader boundary

## Dependency direction

The extracted component should depend downward on:

- lower workflow authoring/compiler helpers needed for authored-definition loading
- lower file/path/config helpers where loader behavior already depends on them
- workflow registry/runtime contracts only as downstream consumers of loaded definition data, not as owners of loader policy

Dependency clarification:

- prefer a tree-like dependency shape rather than a graph-like one
- preserve the current dependency shape first where needed for behavior preservation, then record any remaining awkward edges explicitly rather than broadening this task
- if existing workflow loader behavior currently mixes discovery, reading, normalization, and registration too tightly to separate in one pass, preserve behavior first but record the residual mixed edge explicitly as debt rather than normalizing it

It should not depend upward on:

- public workflow entrypoint façades
- workflow mutations/resolvers/`psi-tool`
- workflow runtime execution orchestration

Higher workflow entrypoints should depend downward on the extracted loader component for authored-definition loading behavior.

Expected rewiring targets likely include:

- extension workflow-loading entrypoints that currently call mixed `psi.agent-session.workflow-file-*` owners
- direct lower-proof tests that currently exercise parser/compiler/loader namespaces under `agent-session`
- higher integration callers that should keep proving orchestration but depend downward on the extracted component

## Naming and ownership decision rule

Preferred outcome:

- create a dedicated component named specifically for workflow loader ownership

Representative preferred names:

- `workflow-loader`
- `workflow-authoring-loader`
- `workflow-definition-loader`

Naming rule:

- prefer `workflow-loader` if the final responsibilities remain centered on authored-definition loading/discovery/preparation
- reject broader or more specific alternatives such as `workflow-authoring-loader` or `workflow-definition-loader` unless implementation proves the final responsibility surface is materially wider or narrower than `workflow-loader` suggests
- prefer the name that best describes the smallest coherent owner actually extracted, not the widest set of adjacent pre-runtime concerns
- record the naming decision and rejected alternatives in `implementation.md`

## Relationship to adjacent workflow code

This task intentionally does not fold loader ownership into workflow registry.

Reason:

- registry owns known-definition storage/query concerns
- loader owns authored-definition acquisition/preparation concerns
- collapsing them would blur load-time and registry-time ownership again

This task intentionally does not fold loader ownership into workflow runtime.

Reason:

- runtime consumes definitions to execute runs
- loader acquires/prepares definitions before execution
- combining them would blur authoring ingestion and execution semantics

This task also does not assume higher workflow entrypoints should remain mixed with loader ownership afterward.

Preferred outcome:

- higher adapter/entrypoint owners become thinner and depend downward on the extracted loader component

Allowance:

- a tiny temporary forwarding seam is acceptable only if implementation records why direct rewiring was not the better shape yet, what consumer still requires it, and the intended cleanup
- unless a blocking reason is recorded, temporary forwarding seams should be removed before the task is considered complete
- if such a seam remains at task completion, treat it as explicit residual debt rather than as the preferred finished shape

## Implementation decisions to record explicitly

Implementation must record at least these decisions in `implementation.md`:

### Current surface review

Record:

- which current namespaces/files were reviewed as possible loader owners
- which of those actually moved with the extracted component
- which remained outside and why

### Final component/namespace name

Record:

- the chosen component name
- the chosen namespace family
- whether one namespace or a small internal split was used
- why that shape best matched the final responsibility surface

### Loader responsibility shape

Record whether the extracted component is best understood as:

- one coherent authored-definition loading owner, or
- a small combined discovery + ingestion boundary chosen as the smallest clean current extraction

If the latter, record why that still preserves the best current refactor boundary rather than extracting an arbitrary pre-runtime bundle.

### Responsibility inventory

Record which responsibilities ended up inside the extracted component, including at least:

- workflow file discovery/loading
- authored-definition ingestion
- parsing unified workflow definition files
- compiling parsed workflow files into canonical prepared definitions
- load-time preparation/normalization
- workflow-file metadata loading context where applicable
- workflow-file authoring helper compilation when it remains part of load-time preparation
- downstream handoff contract to registry/runtime consumers

If any current loader-owned responsibility intentionally remains outside the extracted component, record why.

### Public surface

Record the final remaining public vars of the extracted component and why each remains public.

Unless implementation records a compelling reason otherwise, preserve the current canonical loader behavior surfaces used by callers, or if no such stable lower surface exists yet, record the chosen canonical loader entrypoints introduced by the extraction and why they are the right boundary.

Public-surface clarification:

- prefer one small canonical lower loader API if the extraction needs to introduce a clearer lower surface
- final canonical public API should be minimal
- `scan-directory`, parser, compiler, and validation seams such as `validate-step-references`, `validate-no-name-collisions`, and `validate-judge-routing` may remain public where direct extension callers or lower proofs intentionally rely on them
- if such seams remain public, record them as intentional lower APIs rather than accidental leftovers
- record whether multiple remaining public vars are architecturally necessary or are being temporarily preserved for caller safety during the extraction

Call/output-contract clarification:

- preserve externally consumed call and output contracts of preserved loader behavior surfaces unless implementation records a justified replacement and rewires all affected consumers within this task
- preservation applies at least to the `load-workflow-definitions` result shape, the `scan-directory` result shape, parser/compiler call and output contracts that existing direct callers rely on, directory precedence behavior, duplicate-name resolution semantics, source-path attachment behavior, and current load-time error/warning shaping unless a justified replacement is recorded and all affected callers are rewired within this task
- if the extraction introduces a clearer lower canonical loader API where one was not explicit before, record which prior mixed caller surfaces were rewired and why the new lower surface is the better long-term boundary

### Registry/runtime boundary status

Record how the extracted loader component hands off to workflow registry/runtime consumers and whether any remaining mixed load-and-register or load-and-run behavior still crosses the boundary awkwardly.

Also record:

- whether the loader hands downstream consumers raw authored definitions, normalized definitions, canonical prepared definitions, or a load-result envelope including metadata/context
- preferred handoff is canonical prepared workflow definitions, optionally accompanied by loader-owned metadata and diagnostics, rather than raw authored sources or registry-owned state
- the extracted loader component should not itself become the authoritative owner of load-and-register combined operations; registration remains outside in registry consumers or higher orchestration
- whether the resulting loader -> registry/runtime dependency shape is acceptably tree-like, or
- whether it still preserves mixed graph edges that should become later cleanup

### Transitional namespace status

Record whether any previous workflow-runtime or higher workflow-loading owners:

- disappear entirely after rewiring, or
- remain as tiny temporary forwarding seams

Expected final state:

- `psi.agent-session.workflow-file-authoring-resolution` should not remain the preferred final authoritative owner
- if retained during implementation, it should exist only as a short-lived compatibility façade for move sequencing and should be removed before task completion unless a blocking reason is recorded

Any remaining forwarding seam must be justified with the blocking consumer and intended cleanup.

### Residual dependency status

Record whether any higher or lower workflow namespace still directly depends on old mixed workflow-loading owners after extraction.

If so, treat that as explicit residual debt and record the blocking reason and intended cleanup.

## Implementation shape

1. review current workflow loading/discovery/authored-definition preparation surfaces and identify the true loader ownership boundary
2. choose the narrowest accurate extracted component name for that responsibility surface
3. decide whether one namespace or a small internal split best fits the extracted ownership surface
4. create a dedicated lower component for workflow loader ownership
5. move the authoritative workflow loading/authored-definition preparation logic with minimal semantic change
6. preserve existing caller-visible loader behavior call/output contracts unless a justified replacement is recorded
7. rewire higher workflow entrypoints, adapter surfaces, and affected tests to the new owner
8. keep workflow registry, workflow runtime, workflow step session-config, and workflow step materialization ownership separate rather than recombining concerns
9. record the final boundary, reviewed current surfaces, name, loader responsibility shape, public surface, responsibility inventory, registry/runtime boundary status including the downstream handoff artifact, transitional namespace status, and any residual debt in `implementation.md`

## Acceptance

- authoritative workflow authored-definition loading ownership no longer lives in mixed workflow-runtime, registry, or higher entrypoint surfaces
- moved loader-owned lower namespaces have their authoritative owner under `components/workflow-loader/`; any remaining `psi.agent-session.workflow-file-*` namespace is only a documented temporary forwarding seam
- a dedicated lower workflow loader component exists
- higher workflow entrypoints depend downward on that component for authored-definition loading behavior
- workflow registry remains the owner of definition storage/query concerns
- workflow runtime remains the owner of workflow execution/progression concerns
- workflow step session-config and workflow step materialization remain separate lower owners rather than being recombined with loader ownership
- preserved loader behavior surfaces retain their externally consumed call and output contracts unless implementation records justified replacements and rewires all affected consumers within task scope
- public workflow entrypoints, mutations, resolvers, and `psi-tool` remain outside the extracted component
- lower loader behavior proofs point at the extracted component rather than remaining under mixed higher surfaces by inertia
- lower parser/compiler/loader/authoring-helper unit proofs move to the new component where practical, while higher integration tests may remain in higher components when they are proving extension or session orchestration rather than lower ownership
- absent a justified blocking reason, temporary forwarding seams introduced or retained for workflow loading are removed before task completion
- if a temporary forwarding seam remains at task completion, it is recorded as explicit residual debt rather than treated as the preferred finished shape
- workflow behavior remains unchanged, including preserved `load-workflow-definitions` and `scan-directory` result shapes, existing direct parser/compiler caller contracts, directory precedence semantics, duplicate-resolution behavior, source-path attachment behavior, and load-time error/warning shaping unless justified replacements are explicitly recorded and all affected callers are rewired within task scope
- the final implementation records the chosen component name, reviewed current surfaces, loader responsibility shape, public surface, responsibility inventory, registry/runtime boundary status including the downstream handoff artifact, transitional namespace status, and any residual dependency debt explicitly

## Related work

- `105-agent-session-component-extraction-map`
- `115-workflow-registration-component-extraction`
- `123-workflow-judge-routing-component-extraction`
- `125-workflow-runtime-core-component-extraction`
- `127-workflow-step-prep-role-split`
- `129-workflow-step-session-config-component-extraction`
- `130-workflow-step-materialization-component-extraction`
