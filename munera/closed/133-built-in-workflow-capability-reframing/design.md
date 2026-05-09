# 133 — Built-in workflow capability reframing

## Goal

Reframe workflow as a built-in core capability rather than an extension-packaged feature, while preserving the already-extracted workflow components as the authoritative owners of workflow behavior.

## Why

The current architecture already shows that workflow is a core product capability in practice:

- canonical workflow runtime ownership lives in lower core components such as `workflow-runtime`, `workflow-loader`, `workflow-registry`, `workflow-judge`, `workflow-step-materialization`, and `workflow-step-session-config`
- `agent-session` and related higher core surfaces already own canonical workflow mutations, resolvers, psi-tool operations, and session-facing orchestration
- workflow definitions under `.psi/workflows/` are part of the product's canonical behavior model rather than an optional add-on
- the canonical user-facing delegation model (`delegate`, `/delegate`, workflow runs, workflow reload) is part of the core agent-session experience rather than an optional extension experience

What remains inconsistent is the framing:

- `extensions/workflow-loader/` still packages workflow bootstrap, registration, command/tool wiring, prompt contribution, and lifecycle behavior as though workflow were an installable extension

That framing no longer matches the architecture. The workflow engine and lifecycle are core; only the packaging still presents them as extension-owned.

## Problem

Keeping workflow framed as an extension creates several architectural mismatches:

- core workflow behavior appears optional when it is actually canonical
- built-in session capabilities still depend on extension-style registration paths for a core feature
- bootstrap and lifecycle wiring for workflow is harder to reason about because ownership is split between core components and extension packaging
- naming and capability modeling remain less clear than they should be: workflow is both a core subsystem and an "extension"
- future cleanup risks targeting the wrong thing, for example by folding extracted workflow components back into `agent-session`, when the real issue is only the remaining extension framing

## Intent

Convert workflow from extension-packaged capability to built-in core capability.

This task should:

- remove extension framing as the canonical ownership model for workflow
- move workflow bootstrap/registration/wiring into built-in core runtime assembly
- preserve the existing extracted workflow component boundaries
- preserve current workflow behavior and current user-facing workflow surfaces unless a justified refinement is explicitly recorded
- make the architecture say one clear thing: workflow is built in

## Built-in capability invariants

This task should treat the following as the preferred success criteria for "workflow is built in":

- canonical workflow behavior is not installed through extension install definitions
- canonical workflow bootstrap does not require extension manifest loading or extension init callbacks
- canonical workflow tool/command/prompt/lifecycle wiring is assembled from built-in core owners rather than from extension packaging
- workflow is not modeled as extension-provided canonical behavior in capability/bootstrap/session assembly
- workflow may still participate in normal capability availability/gating, but its provenance should be built-in core ownership rather than extension ownership

If implementation cannot satisfy one of these invariants in this task, it must record the exact residual exception and why it remains.

This task should not:

- merge extracted workflow components back into `agent-session`
- redesign workflow runtime semantics
- redesign workflow authoring semantics
- redesign workflow registry/runtime/loader/judge/component boundaries
- broaden into a generic extension-runtime redesign beyond what workflow reframing requires
- treat this as a reason to undo prior workflow extractions

## In scope

- removing the extension-packaged framing for canonical workflow capability
- reviewing `extensions/workflow-loader/` and identifying which responsibilities are actually built-in core workflow wiring
- reviewing `extensions/workflow-display/` and deciding whether its display/read-model helpers belong in built-in core ownership or are truly optional under this task's rubric
- moving canonical workflow bootstrap/wiring into built-in core assembly
- rehoming built-in workflow command/tool/prompt-contribution/session-lifecycle registration away from extension packaging where appropriate
- rehoming canonical workflow display/read-model ownership away from extension packaging where it belongs in built-in core
- ensuring workflow capability is modeled and bootstrapped as built-in rather than extension-provided
- preserving the lower extracted workflow components as authoritative owners
- recording the final ownership split between:
  - lower workflow components
  - higher core workflow orchestration surfaces
  - any truly optional workflow-adjacent extension surfaces that remain outside the built-in core

## Out of scope

- recombining workflow loader/runtime/judge/registry/materialization/session-config components
- changing authored workflow file format or `.psi/workflows/` semantics
- redesigning `delegate` behavior beyond what boundary relocation requires
- redesigning extension runtime semantics for unrelated extensions
- broad cleanup of all workflow-adjacent higher surfaces unless directly required by the extension-to-core move
- optional workflow-adjacent features that are genuinely extension-like rather than canonical workflow capability

## Current surface under review

The task should explicitly review at least these surfaces:

### Current extension framing surfaces

- `extensions/workflow-loader/src/extensions/workflow_loader.clj`
- `extensions/workflow-display/src/extensions/workflow_display.clj`
- any workflow-loader or workflow-display extension manifest/resources/config surfaces that participate in extension identity, lifecycle, prompt contribution, or workflow-display framing
- workflow-loader and workflow-display extension tests proving bootstrap/wiring or canonical workflow display behavior rather than lower loader/compiler behavior

### Current lower core workflow owners to preserve

- `components/workflow-loader/`
- `components/workflow-runtime/`
- `components/workflow-registry/`
- `components/workflow-judge/`
- `components/workflow-step-materialization/`
- `components/workflow-step-session-config/`
- `components/deterministic-operation-registry/`
- `components/deterministic-operation-runtime/`

### Current higher core consumers/orchestrators likely affected

- `components/agent-session/` workflow-facing entrypoints and orchestration
- `components/app-runtime/`
- `components/system-bootstrap/`
- any capability/bootstrap/session-assembly owners that currently rely on extension registration for workflow
- capability-catalog / session-capability / extension-install surfaces that currently represent workflow as extension-originated canonical behavior

## Boundary rule

### Built-in core should own

Built-in core should own workflow behavior that is canonical to the product, including:

- workflow definition discovery/loading bootstrap from the canonical project surface
- built-in workflow registration/reload lifecycle
- built-in workflow tool/command registration when those surfaces are part of the canonical product UX
- built-in workflow prompt contributions or other capability surfacing when they describe canonical available workflow capability
- built-in session/runtime lifecycle hooks needed for canonical workflow behavior
- canonical workflow capability modeling in runtime/bootstrap/session assembly

Preferred built-in composition rule:

- prefer `components/system-bootstrap/` as the owner of built-in workflow installation/bootstrap decisions
- prefer `components/app-runtime/` only where process/runtime assembly is the natural place to connect already-built bootstrap pieces
- prefer `components/agent-session/` only for session-scoped orchestration and session-facing behavior after workflow has already been installed as a built-in capability
- if implementation chooses a different placement or a split across these owners, it must record why that split is cleaner than the preferred default

### Lower extracted components should continue to own

The lower extracted components should remain the authoritative owners of their existing responsibilities, including:

- workflow authored-definition loading
- workflow definition registry semantics
- workflow runtime execution/progression/statechart semantics
- workflow judge/routing semantics
- workflow step materialization semantics
- workflow step session-config semantics
- deterministic operation registry/runtime semantics

This task must not reassign those owned responsibilities upward merely because the extension framing is being removed.

### Optional extension surfaces may remain outside core

A workflow-adjacent surface may remain extension-owned only if review shows it is genuinely optional rather than canonical product behavior.

Special review requirement:

- `extensions/workflow-display/` must be reviewed explicitly rather than ignored as a tiny helper
- if its display helpers project canonical workflow state into stable display/read-model forms used by built-in workflow surfaces, they should move into built-in core ownership
- if they remain extension-owned, implementation must record why they are truly optional and why that does not preserve misleading workflow-as-extension framing

If any workflow-adjacent extension surface remains outside built-in core, implementation must record:

- what that surface is
- why it is optional rather than canonical
- why keeping it outside built-in core does not reintroduce the current workflow framing confusion

Optionality rubric:

A workflow-adjacent surface counts as optional only if all of the following are true:

- the canonical workflow product experience still works without it
- no canonical workflow bootstrap/registration/load/run behavior depends on it
- no canonical workflow tool/command capability depends on it
- it is additive rather than identity-defining for workflow as a product feature

## Architectural target

Preferred final shape:

- workflow lower components remain separate core components
- higher built-in runtime/bootstrap/session assembly wires workflow directly as a core capability
- canonical workflow tool/command/prompt/lifecycle behavior no longer depends on extension packaging
- `extensions/workflow-loader/` disappears entirely

Fallback allowed only with explicit justification:

- a tiny temporary compatibility façade may remain only if a specific still-supported bootstrap or lifecycle path requires it
- if that fallback is used, implementation must record the blocking path, why direct rewiring was not sufficient now, and why the residual façade is transitional rather than the preferred end state

The task should prefer the simplest architecture that makes workflow obviously built in.

## Key review questions

Implementation must answer these questions explicitly:

1. Which responsibilities currently inside `extensions/workflow-loader/` are truly canonical built-in workflow behavior?
2. Does `extensions/workflow-display/` represent canonical workflow display/read-model behavior, and if so where should that ownership live in built-in core?
3. Which core owner should assemble and register that canonical behavior?
4. Which parts, if any, are genuinely optional and should remain outside core?
5. How should built-in workflow capability be modeled so it is no longer extension-framed in bootstrap/session/runtime assembly?
6. What is the smallest move that removes the architectural confusion without broadening into unrelated cleanup?

## Implementation decisions to record explicitly

Implementation must record at least these decisions in `implementation.md`:

### Current extension-surface review

Record:

- which workflow-loader and workflow-display extension-owned responsibilities were reviewed
- which were moved into built-in core wiring
- which, if any, remained outside built-in core and why

### Built-in home for workflow framing

Record:

- the chosen built-in owner(s) for workflow bootstrap/wiring
- whether the implementation followed the preferred `system-bootstrap` first composition rule or intentionally chose a different split
- why those owners are the right composition roots
- why the chosen placement does not broaden `agent-session` ownership incorrectly

### Naming rule for higher core workflow namespaces

For higher core workflow namespaces introduced by this task, prefer the nested `workflow.*` family rather than `workflow-*` flat namespace names.

Preferred examples:

- `psi.system-bootstrap.workflow`
- `psi.agent-session.workflow.core`
- `psi.agent-session.workflow.text`
- `psi.agent-session.workflow.display`
- `psi.agent-session.workflow.delivery`
- `psi.agent-session.workflow.orchestration`

Avoid new names such as:

- `psi.agent-session.workflow-core`
- `psi.agent-session.workflow-text`
- `psi.agent-session.workflow-display`

Reason:

- this task is creating a coherent higher core workflow ownership family
- nested `workflow.*` naming makes that family explicit
- it also avoids implying a one-off helper split when the intent is a stable grouped ownership surface

### Preserved lower boundaries

Record explicitly that the task preserves the extracted component boundaries and list the lower workflow components intentionally kept authoritative.

### Public surface preservation

Record which user-facing workflow surfaces were preserved, including at least where applicable:

Must preserve unless a justified refinement is explicitly recorded:

- `delegate` tool availability and behavior
- `/delegate`
- workflow definition loading/reloading from `.psi/workflows/`
- workflow registration/removal behavior that follows definition reloads
- current session-switch reload behavior, even if a later task may revisit whether that lifecycle is ideal
- available-workflow capability surfacing
- workflow tool/runtime operations already exposed through core workflow mutations/resolvers/psi-tool

May change if needed for the reframing, provided implementation records the reason and affected rewiring:

- notification wording
- internal namespace names and file placement
- test placement
- incidental non-contract display text

If any must-preserve surface changes, record the justification and all affected rewiring.

### Extension residue status

Record whether `extensions/workflow-loader/`:

- disappears entirely, or
- remains only as a thin compatibility or optional layer

Also record whether `extensions/workflow-display/`:

- moved into built-in core ownership,
- disappeared as an extension-owned canonical surface, or
- remained outside core under the optionality rubric

If anything remains, record why it is not still the canonical owner of workflow framing.

### Capability-model status

Record how workflow is treated after the change:

- as built-in core capability
- not as extension-provided canonical behavior
- with any related capability/bootstrap/session assembly consequences made explicit
- including whether capability-catalog, session-capability, and extension-install surfaces stopped representing workflow as extension-originated canonical behavior

## Relationship to existing tasks

This task is a workflow-boundary follow-on to the workflow extraction sequence, but it is not a reversal of that extraction work.

It should be understood as compatible with:

- `105-agent-session-component-extraction-map`
- `131-workflow-loader-component-extraction`
- `132-workflow-post-extraction-coherence-cleanup`

It should also be read as clarifying one specific architectural point left open after those tasks:

- workflow is core
- the remaining issue is extension framing, not lower ownership

## Acceptance

- a task exists for removing workflow's extension framing without undoing the extracted workflow components
- canonical workflow capability is bootstrapped and wired as built-in core behavior rather than as extension-packaged behavior
- extracted workflow components remain authoritative lower owners and are not folded back into `agent-session`
- the final implementation clearly distinguishes built-in workflow capability from any truly optional workflow-adjacent extension behavior
- `extensions/workflow-loader/` is deleted entirely, or only a tiny explicitly justified transitional façade remains with the blocking reason recorded
- `extensions/workflow-display/` is either moved into built-in core ownership, deleted as an extension-owned canonical surface, or explicitly justified as optional under the task's optionality rubric
- capability-catalog, session-capability, and extension-install surfaces no longer represent canonical workflow behavior as extension-originated, unless a residual exception is explicitly recorded
- user-facing canonical workflow behavior remains unchanged unless a justified refinement is explicitly recorded
- implementation records the reviewed extension-owned responsibilities, the chosen built-in owners, whether the preferred `system-bootstrap`-first composition rule was followed or intentionally deviated from, the preserved lower boundaries, the public surface preservation decisions, the extension residue status, and the resulting capability-model status explicitly
