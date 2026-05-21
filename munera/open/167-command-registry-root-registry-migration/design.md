# 167 command registry root registry migration

## Intent

Migrate `command-registry` to use the new shared `root-registry` component as its lower storage owner, while preserving the current command-registry public behavior.

This task should be the first concrete adoption of the new shared registry substrate built in task `166-root-registry-component-build-task`.

## Context

Task `164-registry-semantics-unification-audit` concluded that `command-registry` is one of the strongest direct-adoption candidates for shared registry infrastructure.

Task `165-root-registry-component-target-architecture` defined the normalized target registry semantics.

Task `166-root-registry-component-build-task` implemented the new standalone `root-registry` component as a lower shared owner with:

- explicit registry declaration
- root-state storage
- globally unique ids per registry
- extension ownership for all entries
- nil lookup miss
- targeted unregister, clear-by-extension, and clear-registry
- explicit status/value result contracts
- strict mutation semantics
- built-in support via artificial `:built-in` ownership

`command-registry` is a good first migration target because its core contract is already close to the new substrate:

- exact-name identity
- no invoke behavior in the registry itself
- nil lookup miss
- built-ins and extension commands both project through one visible surface

However, `command-registry` still has current public behavior that must be preserved during migration, including:

- current public API names and caller expectations
- current visible merged built-in + extension command surface
- current collision/precedence behavior at the command-registry boundary unless deliberately changed in a later task

## Scope

This task includes:

- refactoring `command-registry` internals to use `root-registry` as the lower storage owner
- adding whatever compatibility adapter logic is needed to preserve current command-registry public behavior
- updating focused command-registry tests to prove preserved boundary semantics where appropriate
- adding or adjusting lower integration tests proving the new internal storage path behaves correctly
- updating docs or task artifacts if needed to reflect the new storage owner

This task does not include:

- intentionally changing command-registry public semantics unless required by the migration and explicitly justified
- migrating `tool-registry` or any other registry
- broad registry API redesign above the lower storage layer
- changing adopter-facing call sites unless needed for internal migration

## Desired outcome

At the end of this task:

- `command-registry` uses `root-registry` internally
- current caller-facing command-registry behavior remains intact
- compatibility behavior that differs from normalized root-registry semantics is isolated at the command-registry layer rather than in shared storage
- tests make clear which semantics belong to shared storage versus command-registry compatibility/public API

## Migration direction

The intended shape is:

- `root-registry` owns canonical storage and lower mutation/query mechanics
- `command-registry` owns command-specific validation, canonicalization, and compatibility projection
- built-ins are represented through artificial built-in ownership in the lower layer, but remain exposed through current command-registry public surfaces
- any required merged-surface or precedence behavior is implemented in the command-registry adapter layer if it no longer falls out naturally from storage structure

## Key design constraints

### Preserve boundary behavior

This migration should preserve current command-registry public behavior, including at minimum:

- exact command-name identity
- nil lookup miss from public lookup APIs
- visible merged command surface for built-ins and extension commands
- command-specific validation behavior

The task should identify which currently tested command-registry behaviors are true public contract and keep those intact.

### Move storage responsibility down

The task should remove command-registry-specific storage responsibility where root-registry now owns it.

In particular, the migration should avoid re-implementing a parallel command-specific storage system once root-registry is in place.

### Keep compatibility at the adapter layer

Any behaviors that remain more permissive or differently shaped than normalized root-registry semantics should be expressed explicitly in command-registry code, not pushed back down into the shared component.

## Acceptance

This task is complete when:

- `command-registry` is backed by `root-registry`
- command-registry public APIs still satisfy current caller-facing expectations
- focused tests prove preserved command-registry behavior after the migration
- the internal layering clearly separates shared root-registry concerns from command-specific compatibility/validation concerns
- no unrelated registry migration is bundled into this task

## Non-goals

This task is not asking for:

- command-registry semantic cleanup beyond what is necessary for a safe migration
- normalization of command-registry public behavior to the raw root-registry contract
- migration of other registries in the same change
- speculative generalization beyond the new shared registry substrate already built
