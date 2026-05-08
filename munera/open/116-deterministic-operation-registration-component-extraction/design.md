# 116 — Deterministic operation registration component extraction

## Goal

Extract canonical deterministic-operation registration into a lower component so the authoritative owner of workflow `:invoke` operation definition normalization, validation, registration, lookup, listing, and unregister/removal semantics no longer lives primarily inside `agent-session` workflow orchestration and broader extension-runtime seams.

## Why

Recent registry extractions have clarified a useful decomposition pattern:

- lower components own canonical registration/query semantics
- higher layers keep orchestration, dispatch entrypoints, runtime wiring, and side effects

Workflow-related work has now exposed a second workflow-adjacent registry seam beyond workflow-definition registration:

- workflow-definition registration answers: what workflows exist?
- deterministic-operation registration answers: what named operations may workflow `:invoke` steps call?

Those are related but distinct boundaries.

Current deterministic-operation ownership appears mixed across:

- canonical deterministic-operation definition shape and validation
- runtime registry storage and query helpers
- extension-facing registration/removal helpers
- workflow invoke-time execution paths that should consume the registry rather than own it

Without an explicit extracted owner:

- `agent-session` continues to mix workflow invoke registry semantics with broader workflow runtime and extension-runtime concerns
- extension registration helpers in `extensions.clj` remain the de facto owner of a workflow-adjacent registry boundary
- future `:invoke` work risks widening orchestration namespaces rather than sharpening the lower ownership seam

## Problem

Deterministic-operation-related ownership is currently spread across several concerns:

1. canonical deterministic-operation definition shape and validation
2. runtime registration, duplicate-rejection, and bulk-unregister semantics by operation id / extension ownership
3. operation lookup/listing/query helpers
4. invoke-time execution entrypoints that should consume the registry rather than own registration state
5. extension registration/removal orchestration that should remain above the boundary

Representative current surfaces include:

- `components/agent-session/src/psi/agent_session/deterministic_operations.clj`
  - canonical operation id rules
  - operation definition validation
  - invoke-time execution helpers and result normalization
- `components/agent-session/src/psi/agent_session/deterministic_operation_registry.clj`
  - runtime-owned deterministic operation registry helpers
- `components/agent-session/src/psi/agent_session/extensions.clj`
  - extension registration/removal helpers for deterministic operations
  - extension-wide unregister cleanup of runtime-owned operations
- `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
  - invoke-step execution paths that should consume the authoritative lower registry
- workflow invoke tests and extension API tests proving current registration and invoke behavior

Without an explicit extracted owner:

- the workflow `:invoke` boundary is only partially separated from broader workflow execution
- registration and execution concerns risk remaining entangled
- extension-owned operation registration continues to live in a mixed-responsibility namespace instead of an explicit operation registry component

## Intent

Create one explicit lower-level component for canonical deterministic-operation registration semantics.

This component should own:

- canonical deterministic operation id normalization/validation rules
- canonical deterministic operation definition validation
- registration, duplicate-rejection, and bulk-unregister semantics for registered operations
- lookup/listing/query helpers for registered operations
- explicit registry-helper result contracts for registration, lookup, count/list queries, bulk unregister, and missing-operation invoke behavior
- the authoritative runtime operation registry surface consumed by workflow `:invoke` execution

This component should not own:

- workflow-definition registration
- workflow-file loading/parsing/compilation
- workflow-run creation/execution/resume/cancel/progression
- invoke-step source resolution or workflow-step shaping beyond consuming registered operations
- extension API surface as a whole
- extension activation orchestration beyond delegating registration/removal downward
- generic extension registry redesign
- broader workflow authoring redesign from task `077`

## Proposed boundary

### First-cut boundary decision

The first cut should be a registry-style component for deterministic operations.

This is analogous to:

- `111-tool-registration-component-extraction`
- `113-command-registration-component-extraction`
- `115-workflow-registration-component-extraction`

The extracted component should become the obvious lower owner of deterministic-operation registration/query semantics, while:

- workflow invoke runtimes consume it
- extension registration/removal entrypoints delegate to it
- `agent-session` keeps higher-level orchestration and runtime composition

### Canonical identity rule

Deterministic operation identity should remain the canonical operation id used by workflow `:invoke` steps.

Live current contract from `deterministic_operations.clj` is explicit:

- the canonical id field is `:id`
- ids must be strings matching the namespaced kebab-case pattern enforced by `valid-operation-id?`
- representative valid shape is `github/search-issues-by-label`
- registration currently does not normalize alternate id keys such as `:operation-id` into the stored definition shape

First-cut extraction rule:

- preserve the current live `:id` contract intentionally
- preserve the current namespaced kebab-case validation rule exactly in the extracted component
- do not silently widen accepted id inputs or introduce alternate canonical id keys during extraction unless a later task makes that behavior change explicit

### Canonical stored definition shape

The extracted component should preserve the current normalized deterministic-operation definition shape and should not redesign invoke semantics.

Live current definition contract from `deterministic_operations.clj` is explicit:

- required keys are:
  - `:id`
  - `:handler`
- optional keys are:
  - `:description`
  - `:summary`
  - `:ext-path`
  - `:source`
- `:source` is currently constrained to `:extension` or `:runtime`
- registration currently validates against `operation-definition-schema`
- current malli map validation does not permit arbitrary extra keys beyond the declared schema keys, so the first cut should preserve that closed stored shape rather than broadening it
- normalization currently trims `:description` and `:summary` when present and otherwise preserves the operation map shape
- registration currently stores the normalized operation definition map itself rather than projecting into a smaller storage-only shape
- duplicate registration is currently rejected rather than replaced
- `:ext-path` on the stored operation definition is the authoritative ownership marker used by the runtime registry for bulk unregister by extension

First-cut extraction rule:

- preserve the current canonical stored key as `:id`
- preserve the current schema shape and validation boundary
- preserve the current normalization behavior for `:description` and `:summary`
- preserve current duplicate-registration behavior: same `:id` throws rather than replaces
- preserve the current closed stored shape; this task should not broaden the accepted stored shape silently

The component should not absorb ownership of:

- invoke-result projection semantics beyond any minimal result helpers already coupled to the operation contract
- workflow step output mapping rules
- workflow source resolution

### New component responsibility

A new `deterministic-operation-registry` component should own canonical deterministic-operation registry semantics.

Representative namespace shape:

- `psi.deterministic-operation-registry.registry`
- `psi.deterministic-operation-registry.defs`

Chosen namespace split for the first cut:

- `psi.deterministic-operation-registry.registry` should own the registry object, registration/query helpers, bulk unregister, and the thin lookup-plus-invoke seam
- `psi.deterministic-operation-registry.defs` should own operation-definition validation and normalization helpers such as the current operation-id rules and `normalize-operation-def`
- the first cut should not create a separate lower invoke-helper namespace; canonical invoke execution helpers may remain outside the registry component boundary so long as registry lookup ownership is explicit

Expected first-cut public API should make obvious helpers for:

- `register-operation-in!`
- `unregister-operations-by-extension-in!`
- `get-operation-in`
- `all-operations-in`
- `operation-ids-in`
- `operation-count-in`
- `invoke-operation-in` as the thin lower lookup-plus-invoke seam consumed by workflow runtime

Chosen first-cut API contract:

- preserve the current runtime-registry object model: a `DeterministicOperationRegistry` record wrapping an atom-backed state map
- preserve current helper naming in the first cut rather than renaming to a second competing API during extraction; the `-in` suffix should remain the canonical public naming for this component because the substrate is an explicit registry object
- `register-operation-in!` returns the registry object and throws on duplicate `:id`
- `unregister-operations-by-extension-in!` returns the registry object and is nil-tolerant when an extension owns no operations
- `get-operation-in` returns the registered normalized operation map or `nil` on miss
- `operation-ids-in` returns ids in registration order
- `all-operations-in` returns operations in registration order
- `operation-count-in` is part of the canonical first-cut public API and returns the count derived from the registry's current registration order
- `invoke-operation-in` is part of the canonical first-cut public API, not merely a temporary compatibility seam: it remains the authoritative lower lookup-plus-invoke seam, throws structured `ex-info` when the operation id is missing, and then delegates to canonical invoke execution

The first cut should not invent tuple-returning lower contracts because live current registry helpers are mutation-in-place helpers over a registry object, not pure root-state tuple transforms.

### Storage substrate and ownership model

The first cut should preserve the current runtime-owned storage substrate explicitly.

Live current storage model from `deterministic_operation_registry.clj` is:

- deterministic operations are stored in a dedicated runtime-owned registry object, not in root workflow-definition state and not directly in extension-registry state
- the registry state shape is currently:
  - `:operations` → map of operation id to normalized operation definition
  - `:registration-order` → vector of operation ids preserving registration order
- extension registry state records extension-owned operation definitions under each extension for ownership/accounting, but invoke-time resolution remains authoritative through the runtime-owned deterministic-operation registry

First-cut extraction rule:

- preserve the dedicated runtime-owned registry object model and state shape
- do not redesign this task into a root-state registry extraction
- keep extension ownership recording above the boundary in extension runtime, while preserving the lower registry helper that removes all operations owned by a given `ext-path`
- keep registration-order preservation as part of the live contract unless focused implementation review proves that no public consumer depends on it

### Responsibilities that should remain outside the new component

#### Workflow runtime

Workflow runtime should remain the owner of:

- invoke-step execution sequencing within a workflow run
- source-resolution of invoke args from workflow run/session data
- workflow step output routing and progression
- broader run lifecycle behavior

Boundary rule:

- deterministic-operation-registry owns what operations are registered and how they are looked up
- workflow runtime owns when/how a registered operation is invoked during workflow execution

#### Extension entrypoints

These should remain higher-level seams in the first cut:

- extension API registration/removal entrypoints for deterministic operations
- extension activation/unregister orchestration that decides when an extension's operations are installed or removed
- extension uninstall cleanup that delegates operation removal downward

Boundary rule:

- the extracted component owns canonical operation registry semantics
- extension runtime remains the orchestrator for extension lifecycle events
- extension runtime remains the owner of per-extension bookkeeping under extension-registry state
- the deterministic-operation registry remains the invoke-time authority and preserves the current bulk cleanup helper `unregister-operations-by-extension-in!` so extension unload/reload can remove all runtime-owned operations for one extension without reimplementing registry scans above the boundary

#### Deterministic operation execution semantics

This task is primarily about registration ownership.

Live current split is already visible:

- `deterministic_operation_registry.clj` owns registry object state, registration order, registration, extension-owned bulk unregister, lookup, listing, and lookup-plus-invoke delegation
- `deterministic_operations.clj` owns operation-definition validation, operation-id rules, canonical invoke execution, operation-result validation, and invoke-step result wrapping helpers

Chosen first-cut post-extraction split:

- the extracted component should own the registry object plus operation-definition validation/normalization rules needed for canonical registration
- concretely, `normalize-operation-def`, `valid-operation-id?`, and related operation-definition validation helpers should move under `psi.deterministic-operation-registry.defs`
- invoke execution helpers should remain outside the registry namespace family except for the existing thin `invoke-operation-in` seam that performs lookup then delegates to invoke execution
- `operation-result->invoke-step-result` should remain outside the registry extraction because it is workflow-runtime-facing result wrapping, not registration ownership
- `invoke-operation` should remain alongside canonical operation execution helpers rather than being absorbed into the registry namespace itself, provided the registry remains the authoritative lookup owner

That means this task should avoid broadening into a full deterministic-invoke runtime extraction.

## Main design decisions

### 1. Separate registration from invocation orchestration

This extraction should sharpen the distinction between:

- what operations are registered
- how workflow runtime invokes them during step execution

The registry should not become the owner of workflow runtime sequencing.

### 2. Separate registration from extension lifecycle orchestration

Extension runtime should continue to decide when extension-owned operations are registered or removed.

The extracted component should own how those operations are stored, validated, duplicate-rejected, bulk-unregistered, and queried.

### 3. Preserve live registration behavior first

The first cut should preserve current semantics intentionally and make them explicit in tests.

Expected semantics to inspect and preserve include:

- operation-id validation behavior
- invalid registration error behavior
- duplicate registration behavior for existing operation ids: current live behavior throws rather than replaces
- missing-operation lookup behavior
- extension bulk-unregister behavior by `ext-path`
- registration-order behavior for `operation-ids-in` and `all-operations-in`
- extension cleanup behavior when an extension unregisters all owned operations

### 4. Keep workflow-definition registration separate

This task must remain distinct from `115-workflow-registration-component-extraction`.

- `115` is about workflow-definition registry semantics
- `116` is about deterministic-operation registry semantics used by workflow `:invoke`

The two may both sit under the broader workflow domain, but they are separate registry seams and should remain separate tasks.

### 5. Keep broader workflow authoring/runtime work out of scope

This task should not absorb:

- authored workflow syntax/IR changes
- invoke-step authoring design changes
- run execution redesign
- judge/routing/progression work
- broader runtime ownership changes beyond replacing direct registry ownership with delegation

## Current likely extraction points

Primary current ownership seams likely include:

- `components/agent-session/src/psi/agent_session/deterministic_operation_registry.clj`
  - canonical registration/removal/query helpers over the runtime registry
- `components/agent-session/src/psi/agent_session/deterministic_operations.clj`
  - deterministic operation definition validation and operation-id rules
- `components/agent-session/src/psi/agent_session/extensions.clj`
  - extension-facing register/remove/unregister-all orchestration that should delegate downward
- workflow invoke runtime consumers such as:
  - `components/agent-session/src/psi/agent_session/workflow_statechart_runtime.clj`
  - any other invoke-time runtime helpers

Likely affected consumer/test surfaces:

- deterministic operation registry tests, if absent then new focused component-local tests
- extension registration/unregister tests proving deterministic operation ownership and cleanup
- workflow invoke runtime tests proving registered operations are still resolved and invoked correctly
- API tests proving `:register-operation` still delegates correctly
- focused tests proving duplicate-registration rejection and registration-order preservation explicitly

## Suggested implementation shape

1. create `components/deterministic-operation-registry/`
2. inspect and document the current canonical deterministic-operation id and definition shape precisely
3. define the settled first-cut public API for deterministic operations around the live registry object shape: `register-operation-in!`, `unregister-operations-by-extension-in!`, `get-operation-in`, `all-operations-in`, `operation-ids-in`, `operation-count-in`, and the thin lower `invoke-operation-in` seam
4. add focused component-local tests first for register/get/list/id-query semantics, invalid-definition behavior, duplicate-registration rejection, extension bulk-unregister behavior, and registration-order preservation
5. move or re-express deterministic-operation definition validation and registration/query helpers into the extracted component while keeping workflow-facing invoke-result wrapping outside the registry boundary
6. keep invoke-time workflow runtime logic above the boundary, but delegate registered-operation lookup to the extracted component
7. make extension registration/removal entrypoints delegate downward into the new component
8. preserve extension unregister-all cleanup behavior for extension-owned operations while making the lower owner explicit
9. update affected higher-level tests for extension API, extension cleanup, and workflow invoke runtime lookup
10. remove temporary compatibility wrappers if used during migration
11. record final boundary/result-contract/ordering decisions in `implementation.md`

## Acceptance

- a new lower component exists for canonical deterministic-operation registration semantics
- authoritative deterministic-operation register/remove/list/get helpers no longer live primarily inside broad `agent-session` workflow or extension orchestration namespaces
- workflow `:invoke` runtime paths consume the extracted registry owner rather than owning registration/query semantics directly
- extension registration/removal surfaces remain higher-level adapters/orchestrators while delegating canonical operation registry work downward
- the first-cut registry contract is explicit and proven:
  - canonical id field is `:id`
  - operation-id validation behavior
  - invalid registration behavior
  - duplicate-registration behavior is explicit and preserved as rejection rather than replacement
  - lookup-miss behavior for `get-operation-in`
  - missing-operation invoke behavior for `invoke-operation-in`
  - extension bulk-unregister behavior by `ext-path`
  - registration-order behavior for public id/list queries
  - registration helper return contracts are explicit and preserved as registry-object mutation helpers
- workflow invoke behavior remains unchanged insofar as it depends on registered-operation lookup semantics
- extension unregister cleanup behavior remains unchanged
- task `105-agent-session-component-extraction-map` can reference this as the separate workflow-adjacent invoke-operation registry seam
- task `115-workflow-registration-component-extraction` remains separate and does not silently absorb deterministic-operation registration scope

## Related work

- `105-agent-session-component-extraction-map` is the umbrella component map and now records this workflow-adjacent invoke-operation registry seam explicitly
- `115-workflow-registration-component-extraction` covers workflow-definition registration, not deterministic-operation registration
- `077-deterministic-workflow-steps` remains the broader workflow authoring/runtime umbrella and should stay separate from this registration-boundary extraction
- `111-tool-registration-component-extraction` and `113-command-registration-component-extraction` are the closest registry-style extraction analogues
- a later follow-on may revisit whether deterministic invoke execution helpers themselves want a lower extracted owner, but that is outside this task
