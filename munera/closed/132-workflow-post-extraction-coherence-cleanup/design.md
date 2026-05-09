# 132 — Workflow post-extraction coherence cleanup

## Goal

Refine the workflow implementation after the recent component extractions so the remaining workflow surfaces are coherent, clearly named, and free of unnecessary wrappers, compatibility shims, and duplicated ownership.

## Why

The workflow extraction sequence has successfully created several lower components with clearer ownership, including workflow runtime, workflow loader, workflow judge, workflow step session-config, and workflow step materialization. That work improved structure substantially, but it also likely left behind higher-level residue in `agent-session`:

- compatibility backfills used during migration
- thin wrapper namespaces that no longer add enough policy to justify their own ownership
- duplicated projection/report shaping across multiple entrypoint surfaces
- naming collisions between canonical deterministic workflows and extension workflows
- tests that may still reflect former ownership rather than current component boundaries

Without a cleanup pass, the codebase risks preserving migration-era structure after the conceptual extraction is already complete.

## Problem

The workflow implementation is now split across coherent lower components and higher `agent-session` entrypoint/orchestration surfaces, but some of those higher surfaces still appear mixed or transitional.

Current likely issues include:

- workflow-specific compatibility repair in `psi.agent-session.psi-tool-workflow`
- workflow runtime assembly and callback wiring embedded in `psi.agent-session.context`
- very thin workflow execution façades whose ownership role may be underdefined
- duplicated workflow run/definition summary shaping across psi-tool, Pathom, and execution layers
- confusing naming overlap between canonical deterministic workflow runtime surfaces and the separate extension workflow runtime currently named under `psi.agent-session.workflows`
- possible test duplication where lower-owned behavior is still primarily proved from `agent-session`

These issues do not invalidate the extraction work, but they make the resulting architecture less legible and leave higher-level boundaries less settled than the lower ones.

## Task mode

This task is primarily a workflow-specific review-and-cleanup task.

Expected execution mode:

- first, review and classify the remaining higher workflow surfaces
- then, implement the smallest clearly justified cleanup slices that follow directly from that review
- if review reveals several independent cleanup slices that would make this task too broad, record the cleanup map and create follow-on tasks rather than forcing all cleanup into this one task

Completion rule:

- review/classification is mandatory
- implementation is in scope for the smallest coherent slices whose direction is clear after review
- reviewed surfaces may legitimately remain as-is when the task records why they are coherent final owners rather than transitional leftovers

This task is therefore not complete merely because code moved, and it is not complete merely because review notes exist. It is complete when:

- the targeted workflow surfaces have been reviewed and classified
- the highest-value clearly justified cleanup slices have either been implemented or explicitly deferred with reasons
- the resulting ownership story is clearer than before

## Intent

Create and execute a focused cleanup task for the workflow implementation that sharpens post-extraction ownership without redesigning workflow behavior.

This task should:

- identify the remaining workflow-related wrapper, shim, naming, projection, and ownership residue left after extraction
- remove or isolate compatibility scaffolding that is no longer the preferred final shape
- make higher workflow entrypoint/orchestration namespaces more coherent
- clarify the distinction between canonical deterministic workflows and extension workflows
- align test ownership with the extracted component boundaries where practical
- preserve current workflow behavior and externally consumed contracts unless a justified refinement is explicitly recorded

This task should not:

- redesign the workflow runtime semantics
- redesign workflow authoring semantics
- merge already-separated lower workflow components back together
- broaden into generic `agent-session` cleanup beyond workflow-related surfaces
- silently change public workflow behavior while performing structural cleanup

## Decision rubrics

### Namespace classification meanings

Use these terms consistently when reviewing surfaces:

- `keep` — current owner is coherent as-is and should remain authoritative
- `reshape` — current owner remains, but internal role/surface should be tightened without changing the fundamental owner
- `rename` — ownership remains substantially the same, but the namespace/component name should change for clarity
- `merge` — a surface should stop existing independently and be absorbed into another existing owner
- `extract` — a distinct new or already-lower owner should take over responsibilities currently mixed into the reviewed surface
- `delete` — the surface is compatibility residue or wrapper code that should disappear after rewiring

### Compatibility backfill rubric

Compatibility backfill may remain only if the task identifies a concrete still-supported runtime, reload, or live-context lifecycle that requires it.

If compatibility remains, implementation must record:

- the exact caller or lifecycle that still needs it
- why direct rewiring is not yet sufficient
- why the retained compatibility owner is transitional or final

Absent such a concrete supported need, compatibility repair should be removed rather than retained “just in case”.

### Façade justification rubric

A workflow façade stays only if it owns real higher/session-facing policy such as:

- session-facing validation or precondition checks
- workflow execution/report contract shaping that is intentionally higher-level
- parent-session semantics or other session-owned orchestration policy
- transport-specific or public-entrypoint-specific policy that should not move lower

A façade should be collapsed, merged, or deleted if it does little more than:

- call one lower function
- trivially relay state or arguments
- project data in a way that is duplicated elsewhere without owning meaningful higher-level policy

### Projection duplication rubric

Shared workflow projection logic should move to a shared owner only when multiple surfaces are duplicating the same underlying data-shaping semantics.

Projection logic should remain surface-specific when it exists to satisfy genuinely different contracts, for example:

- psi-tool report shape
- Pathom/EQL attribute shape
- execution-result contract shape

Preferred owner rule:

- if shared workflow projection logic is extracted, prefer a lower workflow-specific projection owner near canonical workflow runtime data, such as `psi.workflow-runtime.*`, unless review finds that the logic is inherently entrypoint-specific
- do not force all surfaces onto one common projection merely to reduce duplication

### Test ownership rubric

Use these rules when reviewing tests:

- lower behavior proof belongs with the lower extracted component that now owns that behavior
- higher integration proof belongs with `agent-session` or another higher owner when it proves cross-component orchestration or public entrypoint behavior
- historical duplication should be narrowed or removed when the same behavior is being proved twice without one proof adding distinct integration value

A higher test is justified when it proves:

- cross-boundary orchestration
- entrypoint contract behavior
- adapter wiring/integration behavior
- session-owned policy above the lower component boundary

## In scope

### 1. Psi-tool workflow compatibility residue

Review `psi.agent-session.psi-tool-workflow` for:

- dynamic callback backfilling
- runtime ctx repair of older workflow callback keys
- on-demand workflow execution adapter construction
- local copies of generic psi-tool helper logic

Task intent for this area:

- determine whether compatibility repair still needs to exist
- if it does, isolate it behind a clearly transitional owner
- if it does not, remove it
- leave `psi-tool-workflow` as a workflow psi-tool handler rather than a runtime-repair namespace

### 2. Workflow runtime assembly ownership

Review workflow-specific assembly still living in `psi.agent-session.context`, including:

- workflow callback default wiring
- workflow execution adapter construction
- workflow-specific ctx override plumbing

Task intent for this area:

- determine whether workflow runtime assembly should remain embedded in `context.clj`
- if not, extract the workflow-specific assembly into a dedicated assembly owner while preserving `agent-session` as the composition root
- make the final raw-callback versus named-adapter story explicit

### 3. Thin workflow façades

Review whether these remain justified as distinct owners or should be absorbed/reframed:

- `psi.agent-session.workflow-execution`
- any other workflow-specific higher wrappers whose behavior is now just pass-through orchestration

Task intent for this area:

- keep façades that own real session-facing policy
- remove or collapse façades that remain only as thin historical wrappers
- if a façade stays, make its responsibility explicit

### 4. Projection/report duplication

Review duplicated workflow shaping logic across:

- `psi.agent-session.psi-tool-workflow`
- `psi.agent-session.resolvers.workflows`
- `psi.agent-session.mutations.canonical-workflows`
- `psi.agent-session.workflow-execution`
- any lower runtime summary/projection helpers that now overlap

Task intent for this area:

- identify which projections are intentionally surface-specific
- extract shared lower projection logic where duplication is accidental
- avoid forcing one projection format onto all surfaces when different consumers genuinely need different shapes

### 5. Naming coherence between workflow systems

Review the current naming of the extension workflow runtime surface under `agent-session`, especially:

- `psi.agent-session.workflows`
- `psi.agent-session.workflow-mutations`

Task intent for this area:

- decide whether these names are now misleading in the presence of `psi.workflow-runtime.*`
- if so, rename them to make the extension-workflow distinction explicit
- preserve behavior while improving architectural readability

Decision rule:

- a pure “keep as-is” decision is acceptable only if the task records why the current names are not materially misleading in the current architecture
- otherwise, prefer renaming to make the extension-workflow distinction explicit

### 6. Test ownership cleanup

Review workflow-related tests still living under `components/agent-session/test/` to distinguish:

- lower-component proof that should live with extracted components
- higher integration proof that should remain with `agent-session`
- historical duplicate proof that should be removed or narrowed

Task intent for this area:

- align proof ownership with current component ownership where practical
- preserve integration coverage at higher layers
- avoid deleting useful integration proofs solely because similar lower tests exist

## Out of scope

- changes to workflow semantics or user-visible behavior except where a refinement is explicitly justified and recorded
- changes to workflow loader, runtime, judge, step session-config, or step materialization ownership boundaries unless needed to remove higher wrapper residue
- redesign of extension runtime semantics beyond naming and ownership clarity needed for workflow coherence
- unrelated `agent-session` cleanup outside workflow-related namespaces
- broad documentation rewrite outside whatever directly reflects the final architecture decision

## Current surface under review

The cleanup should explicitly review at least these namespaces and classify each as keep, reshape, rename, merge, extract, or delete:

- `psi.agent-session.psi-tool-workflow`
- `psi.agent-session.context`
- `psi.agent-session.workflow-execution`
- `psi.agent-session.workflow-judge`
- `psi.agent-session.mutations.canonical-workflows`
- `psi.agent-session.resolvers.workflows`
- `psi.agent-session.workflows`
- `psi.agent-session.workflow-mutations`

`psi.agent-session.workflow-judge` is included primarily to confirm whether it remains a coherent higher impure orchestration owner after the lower extractions. It is not presumed problematic in the same way as `psi-tool-workflow` unless review uncovers a more specific issue.

The cleanup should review workflow-related tests under `components/agent-session/test/psi/agent_session/` by comparing them against lower component proof surfaces as needed to decide whether they remain justified higher integration proofs or historical duplicates.

The cleanup should also consider the current lower workflow component surfaces as the destination/authority context for these decisions:

- `psi.workflow-runtime.*`
- `psi.workflow-loader.*`
- `psi.workflow-judge.*`
- `psi.workflow-step-materialization.*`
- `psi.workflow-step-session-config.*`

## Contract preservation

Unless a justified refinement is explicitly recorded and all affected callers are updated within scope, preserve the externally consumed contracts of the workflow surfaces touched by this cleanup, including at least:

- psi-tool workflow report shapes
- canonical workflow Pathom mutation output shapes
- workflow resolver attribute surfaces
- workflow execution higher-surface result shapes
- extension workflow mutation/result shapes when a reviewed surface is renamed or rewired

Structural cleanup may simplify internal ownership, but it should not silently drift these surfaces.

## Boundary rules

### Belongs in this cleanup task

Belongs if the work is about improving the coherence of workflow structure after extraction, including:

- removing migration-era compatibility scaffolding
- clarifying namespace ownership roles
- moving or consolidating wrapper logic
- improving naming clarity between adjacent workflow domains
- consolidating duplicated summary/projection shaping
- reclassifying workflow tests by current ownership

### Does not belong in this task

Does not belong if the work is primarily about:

- new workflow features
- new workflow behavior
- rethinking canonical workflow semantics
- broad extension-runtime redesign beyond the naming/ownership ambiguity directly implicated here
- new generic infrastructure unrelated to workflow cleanup

## Expected outcomes

A successful result should leave the workflow architecture easier to read in two directions:

1. from lower components upward
   - each lower workflow component has a clear responsibility
   - higher `agent-session` surfaces are thin because they are truly entrypoints, not because migration residue remains

2. from higher entrypoints downward
   - each remaining `agent-session` workflow namespace has a clearly justified role
   - compatibility and wrapper code no longer obscure where the real owner is

## Cleanup map to apply

### Keep as coherent lower owners

Treat these as already-coherent authoritative lower owners unless review finds a concrete contradiction:

- `psi.workflow-runtime.*`
- `psi.workflow-loader.*`
- `psi.workflow-judge.*`
- `psi.workflow-step-materialization.*`
- `psi.workflow-step-session-config.*`

### Highest-priority cleanup targets

1. `psi.agent-session.psi-tool-workflow`
   - likely highest shim density
   - likely should stop owning live ctx repair and compatibility backfill

2. naming of extension workflow runtime surfaces
   - likely highest clarity win
   - likely rename target if the current names remain ambiguous with canonical workflow runtime

3. workflow-specific assembly embedded in `psi.agent-session.context`
   - likely next highest ownership cleanup after compatibility removal

4. duplicated workflow summary/projection logic across entrypoints
   - likely shared extraction or consolidation candidate

5. workflow-related test ownership duplication
   - likely final proof-ownership cleanup after code boundary decisions are settled

### Review target that may stay as-is

`psi.workflow-runtime.statechart-runtime` should not be treated as suspect merely because it is a façade. It currently appears to be a deliberate public orchestration façade over smaller role-focused runtime namespaces, which is coherent.

## Relationship to existing tasks

This task is a follow-on to the workflow extraction sequence, especially:

- `123-workflow-judge-routing-component-extraction`
- `125-workflow-runtime-core-component-extraction`
- `127-workflow-step-prep-role-split`
- `128-workflow-execution-adapter-seam`
- `129-workflow-step-session-config-component-extraction`
- `130-workflow-step-materialization-component-extraction`
- `131-workflow-loader-component-extraction`

It should also be understood as a workflow-specific refinement under the umbrella of:

- `105-agent-session-component-extraction-map`

It may overlap conceptually with:

- `002-compatibility-scaffold-removal`

but this task is specifically about workflow post-extraction coherence, not a generic compatibility-removal sweep across the codebase.

## Acceptance

- a workflow-specific post-extraction cleanup task exists with a clear cleanup map
- the task explicitly classifies the reviewed workflow-related higher surfaces and remaining residue
- compatibility backfill and wrapper ownership are either removed, isolated as transitional, or explicitly justified as final using the recorded compatibility rubric
- naming between canonical deterministic workflows and extension workflows is made coherent or the current naming is explicitly justified
- duplicated workflow projection/report shaping is reduced or intentionally classified by surface, with any shared extracted logic given an explicit owner
- workflow-specific assembly ownership in `agent-session` is made clearer through either implemented cleanup or explicit reviewed justification
- workflow test ownership better reflects current extracted component ownership while preserving needed integration proof
- lower workflow component boundaries remain intact
- workflow behavior and externally consumed contracts remain unchanged unless a justified refinement is explicitly recorded
- reviewed surfaces that remain as-is are recorded as intentional final owners rather than unexamined leftovers
- if review reveals multiple independent cleanup slices too broad for one task, the task records the cleanup map and any follow-on tasks needed rather than forcing an over-broad implementation
