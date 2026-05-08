# 115 — Workflow registration component extraction

## Goal

Extract canonical workflow-definition registration into a lower component so authoritative definition register/remove/list/get semantics no longer live primarily inside `agent-session` workflow orchestration and adjacent loader/runtime seams.

## Why

Recent extractions have clarified a useful decomposition pattern:

- lower components own canonical registration/query semantics
- adjacent lower components may own compilation or definition shaping
- higher layers keep orchestration, dispatch entrypoints, and side effects

Workflow-related ownership now appears split across several distinct concerns:

- workflow-file discovery/loading from `.psi/workflows/`
- workflow-file parsing/compilation into canonical definitions
- canonical workflow-definition registration/removal/listing/lookup
- workflow-run creation/execution/resume/cancel/progression
- extension-local prompt contribution and delivery behavior in `workflow-loader`

Those are coherent but different boundaries.

The strongest current signal is that `extensions/workflow-loader` is acting as an orchestrator that:

- loads workflow definitions from disk
- detects removed definitions
- calls mutations to register/remove definitions
- keeps extension-local `:loaded-definitions` state for prompt contribution text and command behavior

That is a reasonable orchestrator shape, but it should not also remain the de facto owner of canonical workflow-definition registry semantics.

Likewise, workflow runtime/tool surfaces such as `psi_tool_workflow.clj` currently reach directly into root workflow-definition state for listing and lookup-shaped behavior. A lower authoritative owner would make those reads and updates more explicit and parallel the recently landed registration extractions.

## Problem

Workflow definition ownership is currently mixed across layers:

1. canonical workflow-definition identity and minimal validation
2. workflow-definition registration/replacement/removal in root runtime state
3. workflow-definition lookup/list/query helpers
4. workflow-file discovery/loading orchestration
5. workflow-run execution/runtime behavior

These do not all belong at the same layer.

Representative current surfaces include:

- `components/agent-session/src/psi/agent_session/workflow_runtime.clj`
  - current pure root-state helpers for definition registration/removal and definition lookup alongside run creation/execution support
- `components/agent-session/src/psi/agent_session/mutations/canonical_workflows.clj`
  - higher-level mutation entrypoints such as `psi.workflow/register-definition`, `psi.workflow/remove-definition`, and `psi.workflow/list-definitions`
- `components/agent-session/src/psi/agent_session/resolvers/workflows.clj`
  - definition listing/detail resolvers that currently build ordered definition projections directly from root workflow state
- `components/agent-session/src/psi/agent_session/psi_tool_workflow.clj`
  - list/read/create/execute workflow tool operations, including direct definition listing from root state
- `components/session-state/src/psi/session_state/state.clj`
  - canonical state paths that include workflow definitions under root state
- `extensions/workflow-loader/src/extensions/workflow_loader.clj`
  - definition loading/reload/retirement orchestration that delegates via workflow mutations

Without an explicit extracted owner:

- workflow registration semantics remain entangled with orchestration and runtime concerns
- `workflow-loader` must know too much about how definitions are registered/retired
- tool and resolver surfaces risk reading raw state shape instead of depending on one obvious definition-registry owner
- future workflow work risks widening orchestration namespaces further rather than sharpening the boundary

## Intent

Create one explicit lower-level component for canonical workflow-definition registration semantics.

This component should own:

- workflow-definition identity by canonical normalized `:definition-id`
- minimal validation required at registration time
- canonical definition registration/replacement semantics
- canonical definition removal semantics
- workflow-definition lookup/list/query helpers
- explicit registration/removal result contracts
- tiny root-state update helpers specific to workflow definitions if that proves to be the cleanest first-cut API

This component should not own:

- workflow-file discovery/loading from disk
- workflow-file parsing/compilation
- prompt contribution text advertising available workflows
- workflow-run creation/execution/resume/cancel/progression
- delegated execution orchestration
- session construction or prompt submission for workflow steps
- extension command/UI behavior beyond consuming registered definition queries
- broad workflow authoring redesign

## Proposed boundary

### First-cut boundary decision

The first cut should be a registry-style component over root workflow-definition state.

This is closer in shape to `111-tool-registration-component-extraction` and `113-command-registration-component-extraction` than to the pure session-local collection extractions in `112` and `114`.

That means:

- root runtime/session state remains the owner of stored workflow definitions
- the new component owns workflow-definition registration/removal/query semantics over that state
- `agent-session`, `workflow-loader`, and `psi-tool` remain higher-level orchestration/adaptation seams that delegate downward

### Canonical identity rule

Workflow-definition identity in the first cut should be explicit and preserve the current live contract intentionally.

Live current behavior from `workflow_runtime.clj` is:

- storage identity is the normalized `:definition-id`
- normalization currently goes through `normalize-id`
- non-blank strings are preserved as strings
- keywords normalize to their `name`
- other non-nil values normalize via `str`
- blank or missing ids currently normalize to a generated UUID string at registration/run-creation boundaries
- no aliasing between `:name` and `:definition-id` exists

This task should not silently change that behavior during extraction.

Design decision for the extraction:

- first cut should preserve the current normalization contract exactly unless focused implementation review chooses to tighten it in a follow-on task
- if later tightening is desired, that should be made explicit as a behavior change rather than folded invisibly into this extraction

### First-cut validation rule

The extracted component should preserve current behavior first while making the contract explicit.

Live current registration behavior is stricter about definition shape than about id presence:

- registration requires `workflow-target-ir-compiler/target-authored-workflow-definition?`
- invalid definitions throw structured `ex-info`
- the stored definition is the incoming definition with normalized `:definition-id` associated back onto it
- blank or missing `:definition-id` is not currently rejected; it normalizes to a generated UUID string

Therefore the first-cut extraction should preserve:

- target-authored definition validation at the registry boundary
- structured `ex-info` on invalid definition maps
- id normalization behavior, including generated UUIDs for blank or missing ids
- preserving the definition map otherwise rather than recompiling or broadly reshaping it

If a stricter `:definition-id` requirement is desired, that should be a deliberate later change rather than an accidental side effect of this extraction.

### Canonical stored definition shape

This task should not redesign the canonical workflow-definition data model.

The registry boundary should assume callers provide already-canonical or already-compiled workflow-definition maps and should preserve them as-is except for any tiny normalization step required to preserve live behavior.

That means the new component should not absorb ownership of:

- step compilation
- workflow-file metadata shaping
- run-timechart compilation
- target-authored workflow IR semantics

Those remain adjacent workflow concerns.

Dependency-boundary rule for the first cut:

- the extracted registry may depend on `workflow-target-ir-compiler/target-authored-workflow-definition?` for boundary validation only
- broader compilation, IR construction, or authoring-grammar ownership remains outside this component

### New component responsibility

A new `workflow-registry` component should own canonical workflow-definition registry semantics.

Representative namespace shape:

- `psi.workflow-registry.registry`
- optionally `psi.workflow-registry.defs` only if focused implementation shows a small separate validation/normalization layer is useful

The first cut should make obvious APIs for:

- `register-definition`
- `remove-definition`
- `workflow-definition` for public normalized lookup by `:definition-id`
- `list-definitions`
- `definition-ids`

First-cut API boundary decisions:

- `register-definition` and `remove-definition` in `workflow-registry` are the authoritative tuple-returning lower helpers in the first cut; higher adapter layers may wrap their results, but the component should not introduce a second competing lower contract
- `definition-count` is not part of the first-cut registry API; higher layers should derive counts from `list-definitions` or `definition-ids` where needed
- the only first-cut workflow-definition-specific root-state helpers owned by this component should be path/query/update helpers directly tied to definition storage, such as `definitions-path`, `definition-path`, and the canonical definition register/remove/get/list helpers

Naming/ownership rule for the first cut:

- the extracted component should expose one explicit minimal public query surface for mutations, resolvers, `psi-tool`, and run creation
- if an exact-map-key helper remains useful internally, it should be clearly narrower than the public normalized lookup helper, for example `workflow-definition-in` as a storage-facing helper and `workflow-definition` as the public normalized helper
- upper layers should not bypass the public query surface and re-read raw root-state definition maps directly

Result contracts should be explicit in code/tests rather than implied.

Registration should make explicit whether it returns:

- the updated state
- normalized `:definition-id`
- the stored definition
- optional convenience result flags such as `:registered?` and `:replaced?` at higher adapter layers if useful

Live current lower-level behavior in `workflow_runtime.clj` is tuple-shaped:

- register returns `[state definition-id stored-definition]`
- remove returns `[state removed-definition]`
- direct lookup helper returns `nil` on miss
- remove throws when the definition is missing, and higher mutation layers translate that into `:removed? false` plus an error string

The first-cut extraction should preserve those effective semantics intentionally.

Chosen first-cut API shape:

- the extracted registry should preserve the current tuple-shaped pure lower API as the authoritative first-cut component API
- higher mutation/tool/resolver adapters may wrap that API into richer result maps where needed, but should not invent competing lower-level contracts

Chosen first-cut lookup/removal contract:

- public registry helpers that accept workflow definition ids should normalize incoming ids before lookup/removal so the component presents one consistent caller-facing id contract
- a narrower storage-facing helper may still exist for exact map-key reads where useful internally, but it should not be the only public query surface
- direct public lookup helpers should remain nil-returning on miss
- `list-definitions` and `definition-ids` should return empty vectors when the registry is empty
- remove-miss behavior must stay explicit, including the distinction between lower helper throwing and higher mutation/tool surfaces reporting `removed? false`

### Responsibilities that should remain outside the new component

#### Workflow loader

`workflow-loader` should remain the owner of:

- discovering workflow files under `.psi/workflows/`
- loading/parsing/compiling those files into canonical definitions
- determining which previously loaded definitions were removed from disk
- extension-local prompt contribution refresh based on loaded definitions
- extension-local UI/command behavior around available workflows

Representative existing owner:

- `extensions/workflow-loader/src/extensions/workflow_loader.clj`

Boundary rule:

- loader decides *what changed on disk*
- workflow-registry decides *how canonical definitions are registered and removed in runtime state*

#### Workflow runtime

Workflow runtime should remain the owner of:

- create-run
- execute-run
- resume-run
- cancel-run
- run summaries/statuses
- progression, statechart, and attempt behavior
- any step execution semantics

Representative existing owners likely include:

- workflow runtime / execution / progression namespaces in `components/agent-session/src/psi/agent_session/`
- `psi_tool_workflow.clj` for high-level tool operations over runs

Boundary rule:

- workflow-registry owns registered definitions
- workflow runtime owns runs built from those definitions

#### Mutation / tool / resolver entrypoints

These should remain higher-level seams in the first cut:

- workflow mutations such as `psi.workflow/register-definition` and `psi.workflow/remove-definition`
- `psi-tool` workflow ops such as `list-definitions`
- any resolver/query surfaces that expose definitions to callers

The first cut should make those surfaces thinner by delegating downward into the extracted component.

## Main design decisions

### 1. Separate registration from loading

This extraction should sharpen the distinction between:

- loading/compilation from files
- registration/storage/query in runtime state

`workflow-loader` should continue to orchestrate reloads, but it should not own the definition registry semantics.

### 2. Separate registration from runs

This extraction must not broaden into workflow-run execution.

A registered definition is not the same thing as a run. The new component should stop at definition registry semantics.

### 3. Preserve definition maps rather than redesign them

The first cut should preserve current canonical definition maps as the stored values.

If a definition has already been compiled and validated elsewhere, the registry should not attempt to reinterpret or reshape it beyond minimal boundary validation.

Replacement semantics should also be explicit:

- registering an existing normalized `:definition-id` fully replaces the previously stored definition map at that key
- no field-wise merge is performed during replacement

### 4. Keep higher-level mutation/tool seams above the boundary

The first cut should keep external-facing entrypoints stable:

- mutation seams remain above the component as adapters
- resolver seams remain above the component as adapters
- `psi-tool` stays a higher-level surface
- loader remains an orchestrator

This task is about ownership, not user-facing API redesign.

Read-path delegation should still sharpen ownership:

- definition resolvers should delegate to extracted registry query helpers rather than rebuilding ordered definition queries from raw root state
- resolver detail lookup should delegate to the public normalized registry lookup helper rather than preserving a separate exact-key read contract
- workflow definition listing mutations should delegate to canonical registry listing helpers rather than reimplementing raw-state ordering locally
- `psi-tool` definition-listing/read paths should also delegate to extracted registry query helpers rather than re-reading raw root state directly

Projection rule:

- the registry owns canonical storage/query behavior and ordering
- mutations, resolvers, and `psi-tool` remain responsible for projecting registry results into their own surface-specific output maps and text
- the extracted component should not grow presentation-specific summary helpers merely to avoid thin higher-level projections

### 5. Make replacement/removal semantics explicit

The first cut should preserve current behavior intentionally.

Expected initial semantics to verify and preserve:

- registering a definition by an existing normalized `:definition-id` replaces the previously stored definition at `[:workflows :definitions definition-id]`
- removing a missing definition yields `:removed? false` at higher mutation surfaces because the lower helper throws and the mutation adapter catches it
- direct definition lookup helper is nil-returning on miss
- current user-facing listing surfaces (`psi-tool`, workflow mutations, and workflow resolvers) all sort by `:definition-id`

Ordering is therefore no longer just an open design point; current live presentation/query behavior is already effectively sorted by `:definition-id`.

First-cut extraction rule:

- preserve sorted-by-`definition-id` behavior for current public listing/query surfaces
- the extracted component should own canonical public listing helpers such as `list-definitions` and `definition-ids`, and those helpers should make sorted ordering explicit
- registry-local raw collection helpers may still expose map values directly if useful internally, but upper layers should no longer each reimplement the public sorting/query policy independently

### 6. Keep workflow authoring/compiler concerns out of scope

This task should not absorb:

- `.psi/workflows` file syntax design
- authoring model semantics from task `077`
- compiler ownership between workflow file compiler / IR compiler / runtime compiler

Those are related but separate workflow boundaries.

## Current likely extraction points

Primary current ownership seams now confirmed by live source review include:

- `components/agent-session/src/psi/agent_session/workflow_runtime.clj`
  - `normalize-id`
  - `definitions-path` / `definition-path`
  - `workflow-definition-in`
  - `register-definition`
  - `remove-definition`
  - the registered-definition lookup used by run creation should delegate to the extracted registry after migration
- `components/agent-session/src/psi/agent_session/mutations/canonical_workflows.clj`
  - mutation adapters for register/remove/list
- `components/agent-session/src/psi/agent_session/resolvers/workflows.clj`
  - ordered definition projections and detail lookup
- direct root-state definition listing in `psi_tool_workflow.clj`

Path-helper ownership rule for the first cut:

- `workflow-registry` should become the authoritative owner of workflow-definition-specific path helpers such as `definitions-path` and `definition-path`
- generic root-state path registries or unrelated session-state path constants remain outside this task
- workflow run creation should consume the public normalized registry lookup helper for registered-definition resolution rather than reaching around the component through storage-facing exact-key helpers

Higher-level orchestration seams that should remain above the boundary:

- `extensions/workflow-loader/src/extensions/workflow_loader.clj`
- `components/agent-session/src/psi/agent_session/psi_tool_workflow.clj`
- mutation and resolver entrypoint namespaces

Likely affected consumer/test surfaces:

- workflow mutation tests proving register/remove/list behavior
- workflow resolver tests proving definition root/detail query behavior
- `psi-tool` workflow tests that list/read definitions
- workflow-loader tests that prove reload retires removed definitions and registers new definitions
- any runtime tests that depend on definition lookup from registered root state during run creation

## Suggested implementation shape

1. create `components/workflow-registry/`
2. define the settled first-cut public API for workflow definitions: `register-definition`, `remove-definition`, `workflow-definition`, `list-definitions`, and `definition-ids`
3. add focused component-local tests first for register/remove/get/list/id-query semantics
4. move or re-express workflow-definition registration/removal/query helpers into the extracted component, including workflow-definition-specific path helpers such as `definitions-path` and `definition-path`
5. make workflow mutation entrypoints delegate downward into the new component
6. make workflow resolver read paths delegate downward into the new component, including normalized detail lookup
7. update `psi-tool` definition-listing/lookup paths to use the extracted registry owner or a very thin delegating seam
8. keep workflow-loader as the orchestrator that computes file-backed adds/removals, but delegate registration/removal downward
9. rewire registered-definition lookup used by run creation to the extracted registry without widening into workflow-run execution ownership
10. keep workflow runtime/run execution ownership otherwise unchanged
11. remove any temporary compatibility wrappers if used during migration
12. record final boundary/ordering/result-contract decisions in `implementation.md`

## Acceptance

- a new lower component exists for canonical workflow-definition registration semantics
- authoritative workflow-definition register/remove/list/get helpers no longer live primarily inside broad `agent-session` workflow orchestration namespaces
- the first cut is a registry-style component over root workflow-definition state, not a workflow-run runtime component
- the first-cut registry contract is explicit and preserved:
  - identity is canonical normalized `:definition-id`
  - current `normalize-id` behavior is explicit and proven
  - definition validation behavior is explicit and proven
  - register replacement behavior is explicit and proven, including full-map replacement rather than merge
  - lower remove-miss behavior and higher-surface remove-miss reporting behavior are both explicit and proven
  - public lookup-miss behavior is explicit and proven
  - listing/query ordering behavior is explicit and proven
  - registration/removal result contracts and lower API shape are explicit and proven
- workflow-loader keeps discovery/loading/reload/prompt-contribution ownership while delegating definition registration/removal downward
- workflow runtime keeps create-run/execute-run/resume-run/cancel-run/progression ownership
- mutation/tool/resolver entrypoints remain stable higher-level seams unless trivial delegation sharpens them naturally
- definition registration/removal/lookup/listing semantics remain unchanged
- workflow run creation behavior remains unchanged insofar as it depends on registered-definition lookup semantics
- task `105-agent-session-component-extraction-map` can reference this as a concrete workflow child extraction

## Related work

- `105-agent-session-component-extraction-map` is the umbrella component map and currently identifies workflow as an early extraction domain
- `077-deterministic-workflow-steps` is the workflow authoring/runtime umbrella and should remain separate from this registration-boundary extraction
- `111-tool-registration-component-extraction` and `113-command-registration-component-extraction` are the closest boundary analogues for registry-style extraction
- `114-prompt-contribution-registration-component-extraction` is a useful contrast because it chooses a pure collection component instead of a root-state registry
- a later follow-on may revisit whether workflow-file compilation or workflow-runtime definition helpers also want lower extracted owners, but that is outside this task