# 166 root registry component build task

## Intent

Implement the standalone shared registry component defined by task `165-root-registry-component-target-architecture`.

This task exists to build the new lower-level registry component as code, with tests that prove the normalized semantics described in task `165`.

## Context

Task `164-registry-semantics-unification-audit` established which current registry differences are essential versus accidental or compatibility-carried.

Task `165-root-registry-component-target-architecture` defines the target shared component:

- one long-lived root-state storage hosting multiple registries
- canonical per-registry globally unique ids
- explicit extension ownership for every stored entry
- strict shared semantics
- registry-specific validation at the consuming boundary
- no duplicate coexistence; re-register replaces
- nil lookup miss
- targeted unregister by id
- clear by extension id
- global clear
- removal miss returns failure info rather than throwing
- operations return explicit status and value
- built-ins represented via the artificial `:built-in` extension id
- no invoke behavior in the shared component
- no ordering semantics in the shared storage contract

This task should implement that component as a standalone lower owner, without simultaneously migrating every current registry.

## Scope

This task includes:

- implementing the standalone shared registry component
- implementing the root-state storage shape and invariants from task `165`
- implementing the shared operation set and result contracts
- implementing strict shared validation for registry existence, canonical ids, ownership fields, and mutation invariants
- implementing focused tests for the component's semantics
- documenting the component's intended use at the code boundary where needed

This task may include minimal integration scaffolding needed to instantiate or exercise the component in realistic project code.

For this first build slice, that scaffolding must not change the public thrown-error or public return-shape contracts of current adopter-facing registries. The new shared component may expose explicit result maps at its own lower boundary, but any adoption of `workflow-registry`, `tool-registry`, or `command-registry` remains a follow-on task and must preserve or adapt current higher-level contracts explicitly rather than changing them implicitly here.

The authoritative implementation home for the new shared component is a new `components/root-registry/` component with the primary runtime namespace `psi.root-registry.registry` and focused lower-component tests under `components/root-registry/test/psi/root_registry/registry_test.clj`.

The shared component must own explicit registry declaration through a lower API that declares a registry id before list or mutation operations are allowed. The authoritative declared-registry state shape for a known empty registry is:

- root state hosts a shared registry area keyed by registry id
- each declared registry state contains `:entries-by-id {}` and `:ids-by-extension {}`
- declaration is idempotent for an already-declared registry and does not implicitly occur during register, unregister, clear-by-extension, clear-registry, or list operations

This task does not include:

- migrating all existing registries to the new component
- forcing compatibility adapters for every current registry
- redesigning higher-level registry-specific APIs
- adding invoke/execution behavior
- preserving accidental ordered behavior from current registries

## Desired outcome

At the end of this task, the repo should contain a new standalone shared registry component that:

- stores multiple registries in one long-lived root-state location
- enforces the target semantics from task `165`
- exposes a coherent operation API with explicit result maps/values
- is covered by focused tests proving storage, identity, replacement, lookup, removal, and clear semantics
- is ready for follow-on migration tasks to adopt directly or through adapters

## Required behavior

The implementation must satisfy the target architecture from task `165`, including at minimum:

### Storage

- shared root-state area for hosted registries
- registry declaration/initialization is explicit
- per-registry storage includes `:entries-by-id` and `:ids-by-extension`
- shared component keeps indexes and entries in sync on every successful mutation
- storage is unordered

### Identity and ownership

- each entry has one canonical id within its registry
- each stored entry has required extension ownership
- built-ins use artificial extension id `:built-in`
- identity is unique within each registry
- re-register with same id replaces only when ownership remains the same
- re-register with same id but different owner fails with ownership-conflict result

### Query

- lookup by id returns stored value on hit
- lookup by id returns `nil` on miss
- lookup by id on unknown registry returns `nil`
- any list/read-bulk surface for unknown registry fails explicitly rather than silently initializing

### Mutation

- register inserts or replaces
- targeted unregister by id succeeds on hit
- targeted unregister by id returns failure info on miss
- clear by extension removes all entries owned by that extension
- clear by extension returns explicit no-op / miss-style status when nothing matched
- global clear removes all entries in one registry
- mutation against unknown registry fails with explicit unknown-registry result

### Result contracts

Operations return explicit status and value.

The implementation must make the result shape concrete and test it. At minimum, results should make explicit:

- operation kind
- success/failure status
- registry id
- canonical id and/or extension id when relevant
- stored / replaced / removed / remaining values when relevant
- count/summary information when relevant
- explicit failure-kind on failed mutations

### Validation and boundaries

- shared component performs strict common validation
- consuming registries remain responsible for registry-specific entry validation/canonicalization before calling into the shared component
- the shared component does not perform registry-specific schema validation beyond shared invariants

## Acceptance

This task is complete when:

- a standalone shared registry component exists in code
- its state model matches the target architecture from task `165`
- focused tests prove the intended shared semantics
- the implementation clearly excludes invoke behavior and ordering semantics from the shared component
- the implementation is ready to serve as the lower substrate for follow-on registry migration tasks

## Non-goals

This task is not asking for:

- migration of `tool-registry`, `command-registry`, `workflow-registry`, `skill-registry`, `prompt-registry`, or `deterministic-operation-registry`
- preservation of current accidental behaviors that conflict with the target architecture
- broad API redesign above the new component
- speculative abstraction beyond the target semantics already defined in task `165`
