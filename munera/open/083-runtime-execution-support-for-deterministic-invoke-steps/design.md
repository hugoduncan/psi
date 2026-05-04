Goal: implement runtime execution support for workflow IR `:type :invoke` steps using the deterministic operation registry and canonical invoke result model.

## Intent

Task `082` defines the operation substrate. This task uses that substrate to make deterministic invoke steps actually execute inside the canonical workflow runtime.

The focus is not just calling an operation. It is making invoke steps participate fully in workflow execution semantics:

- progression
- attempts/history
- result recording
- output surfaces
- yielded value handling
- routing/judge integration
- failure/blocking semantics where applicable

## Problem statement

After IR adoption, the workflow runtime can conceptually distinguish `:type :invoke`, `:type :session`, and `:type :delegate`. But until invoke-step execution exists, deterministic steps remain a schema/compiler feature rather than an actual execution form.

Without this slice:

- invoke steps cannot run end-to-end
- deterministic operation results cannot feed downstream refs and yields through real runtime paths
- invoke-step observability remains undefined in practice
- judge/routing behavior across execution forms remains incompletely proven

## Scope

In scope:

- execute IR `:type :invoke` steps through the deterministic operation registry
- resolve and materialize invoke args from IR source specs before invocation
- record invoke attempts/results in runtime history/attempt surfaces
- expose invoke outputs for downstream `:output` and `:yield` references
- integrate invoke-step success/failure with workflow progression and terminal outcomes
- allow invoke steps to participate in judge/routing behavior through the same normalized control-flow model as other step types
- add focused tests for representative invoke-step execution flows

Out of scope:

- implementing many concrete real-world operations beyond what is needed for proof
- broad migration of built-in workflows to invoke style
- large redesign of workflow attempt/result persistence beyond what invoke-step support requires

## Desired outcome

A normalized workflow containing `:type :invoke` steps can execute end-to-end, produce canonical outputs and yielded values, and route downstream exactly like other workflow step forms.

## Acceptance

- IR `:type :invoke` steps execute through the deterministic operation registry
- invoke args resolve from source refs/projections before invocation
- invoke results are recorded coherently in attempts/history and surfaced for downstream references
- invoke success/failure integrates correctly with workflow progression and terminal outcomes
- invoke steps can participate in judge/routing using the shared control-flow model
- focused tests prove representative invoke-only and invoke-to-session/delegate flows
- runtime behavior matches the design intent from task `077`, `doc/workflow-ir.md`, and the operation contract from task `082`
