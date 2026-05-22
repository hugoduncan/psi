# 171 deterministic-operation-registry shared-storage migration

## Intent

Migrate `deterministic-operation-registry` onto the common shared registry storage substrate, using the semantic split clarified in task `170`.

This task should adopt shared storage without collapsing the current deterministic-operation boundary responsibilities that still need to remain adapter/runtime-owned.

In particular, the migration should:

- use shared lower storage for canonical operation registration ownership
- use explicit lower duplicate-rejecting insertion semantics from `root-registry`
- preserve deterministic-operation public/runtime behaviour where it is still intentionally boundary-owned
- keep invoke semantics outside the shared lower contract

## Context

Task `164-registry-semantics-unification-audit` classified `deterministic-operation-registry` as a strong shared-substrate candidate, but not a straightforward first-wave adopter like command, tool, or workflow registries.

Task `170-root-registry-semantic-alignment-for-future-adopters` then clarified the semantic target needed before this migration could be designed safely, and task `172-deterministic-operation-registration-order-removal` removes the remaining non-essential ordering contract that would otherwise complicate the storage move:

- `root-registry` now has an explicit semantic split between duplicate-rejecting insert and replace-capable registration
- lower shared operations are result-oriented rather than exception-oriented
- duplicate rejection should be treated as a lower result contract, not a lower exception contract
- owner-scoped cleanup should continue to target shared `clear-by-extension`
- deterministic-operation listing no longer preserves registration order
- runtime invoke-miss throwing remains adapter/runtime-owned rather than part of shared storage semantics

This task is the first migration that should fully exercise that alignment after ordering has been removed as a separate prerequisite.

## Problem

`deterministic-operation-registry` currently owns several concerns in one runtime-object component:

- canonical operation id registration
- duplicate rejection by thrown exception
- lookup/list/count helpers
- bulk unregister by `ext-path`
- thin lookup-plus-invoke seam

Shared storage can absorb some of that cleanly, but not all of it.

If this task migrates storage ownership without preserving the correct boundary split, we risk either:

- leaking raw shared-storage result shapes into workflow/runtime callers, or
- preserving too much current behaviour as compatibility glue and failing to gain a coherent lower owner

The task therefore needs to migrate only the right layer:

- shared storage should own canonical keyed registration and owner-indexed cleanup
- the deterministic-operation adapter should continue to own runtime object shape and invoke behaviour

## Scope

This task includes:

- refactoring `deterministic-operation-registry` so canonical operation storage is backed by `root-registry`
- preserving the current registry-object public boundary where still useful to callers
- translating duplicate registration from lower duplicate-result semantics into the current deterministic-operation public behaviour deliberately
- preserving bulk cleanup by extension path through shared owner-scoped lower operations
- keeping `invoke-operation-in` as the authoritative lower lookup-plus-invoke seam for runtime callers
- updating focused tests to prove the new lower-owner split and preserved public behaviour
- auditing higher runtime/extension seams so no stale direct local storage reads survive the migration

This task should assume task `172` is complete and should not reintroduce adapter-owned ordering state.

## Out of scope

This task does not include:

- changing workflow invoke runtime semantics
- removing the runtime registry-object API unless a caller audit proves it unnecessary and the design is updated first
- moving invoke execution into `root-registry`
- adding shared lower ordering semantics to `root-registry`
- redesigning deterministic-operation result validation ownership in `defs`
- broad extension lifecycle redesign beyond the cleanup paths needed for migration coherence

## Desired outcome

At the end of this task:

- canonical deterministic-operation registration ownership lives in shared storage
- duplicate rejection is powered by lower `root-registry/insert` semantics
- bulk cleanup by extension path is powered by shared owner-scoped clear semantics
- runtime callers still use the deterministic-operation registry object and invoke seam without seeing raw shared-storage contracts
- focused tests prove both the shared-storage adoption and the preserved adapter-owned behaviour
- the internal storage split is explicit: shared storage is authoritative for operation entries, while the adapter keeps no parallel canonical entry or ordering store

## Concrete migration shape

The intended first-cut implementation shape is:

- keep `DeterministicOperationRegistry` as the public runtime object
- change its atom-backed state from a canonical local store
  - from: `{:operations {...} :registration-order [...]}` before task `172`
  - to: `{:root-state ...}` after task `172` and this migration
- declare one dedicated shared registry id for deterministic operations
- treat each normalized operation as a shared canonical entry keyed by operation `:id`
- remove adapter-local ordering state entirely

A likely first-cut state shape inside the registry object is:

- `:root-state` — shared storage host containing the declared deterministic-operation registry

The important rule is the authority split:

- operation entry maps are authoritative only in shared storage
- the adapter keeps no extra ordering projection state
- the adapter should not retain a second canonical `:operations` map after migration

## Authoritative ownership after migration

### Shared lower owner: `root-registry`

`root-registry` should own:

- canonical entry storage by operation id
- duplicate-rejecting insert semantics
- owner-indexed removal by extension id / `ext-path`
- canonical lookup of stored operation entries
- explicit lower result contracts for insert, lookup-adjacent mutation outcomes, and clear-by-extension

### Adapter owner: `deterministic-operation-registry`

`deterministic-operation-registry` should continue to own:

- the runtime registry-object boundary
- operation-definition validation and normalization before lower insertion
- translation from lower duplicate-id results to current public duplicate-registration behaviour
- invoke lookup-plus-execution seam
- public throw-on-missing invoke behaviour
- unordered projection of shared registered ids/operations/count through the existing public query helpers

## Storage model direction

The registry object should continue to exist, but it should stop treating a local `:operations` map as the canonical store.

Post-migration direction:

- canonical operation entries live in a declared `root-registry` registry for deterministic operations
- deterministic-operation adapter state preserves no extra ordering metadata beyond shared storage
- adapter-local state should not duplicate operation entries except transiently inside one mutation step

### Canonical shared entry shape

The adapter should project normalized deterministic operations into shared entries with the standard shared fields:

- `:id` — canonical operation id
- `:extension-id` — extension owner, using the current operation `:ext-path`
- `:value` — the normalized deterministic operation map as exposed by `get-operation-in`

If the adapter needs additional shared-entry-local metadata for clarity, it should justify that metadata explicitly. The default expectation is that the normalized operation map already contains the fields needed by deterministic-operation callers, including `:id`, `:ext-path`, `:source`, and invoke handler data.

### Registration algorithm target

`register-operation-in!` should conceptually do this:

1. normalize and validate the incoming operation through `defs/normalize-operation-def`
2. ensure the shared deterministic-operation registry is declared in the local `:root-state`
3. call lower duplicate-rejecting insert with the canonical shared entry
4. if lower insert succeeds:
   - update shared `:root-state`
   - return the registry object with no additional ordering repair work
5. if lower insert fails with duplicate id:
   - preserve current public duplicate-registration throw behaviour by throwing from the adapter boundary using the lower failure result as structured cause data
6. for any other lower failure:
   - translate deliberately and explicitly; do not rely on accidental lower exception flow

The membership invariant after registration is:

- successful first registration makes the id available through shared lookup and public listing helpers
- failed duplicate registration does not change registered membership or count

### Cleanup algorithm target

`unregister-operations-by-extension-in!` should conceptually do this:

1. ensure the shared deterministic-operation registry is declared in local `:root-state`
2. call shared `clear-by-extension` using the extension path as owner id
3. preserve nil-tolerant/no-op public behaviour when nothing matched
4. return the registry object with no ordering repair step

The membership invariant after cleanup is:

- removed ids are no longer available through shared lookup or public listing helpers
- surviving membership remains coherent with shared canonical lookup

The design should prefer minimizing parallel authoritative stores and removing obsolete adapter-local state rather than translating it forward.

## Required behavioural preservation

### Preserve

- strict operation-id validation and normalization
- duplicate registration remains rejected at the deterministic-operation public boundary
- `get-operation-in` returns the normalized stored operation or `nil`
- `operation-ids-in` returns exactly the registered ids with no ordering guarantee
- `all-operations-in` returns exactly the registered operations with no ordering guarantee
- `operation-count-in` remains coherent with registered membership
- `unregister-operations-by-extension-in!` remains nil-tolerant/no-op when nothing matches
- `invoke-operation-in` throws structured `ex-info` when the operation id is missing

### Intentionally change internally

- duplicate rejection should no longer depend on lower thrown exceptions
- canonical operation entry ownership should no longer live in a separate adapter-local map if shared storage is authoritative
- extension cleanup should use shared owner-indexed removal rather than adapter-local scans where practical

## Seam inventory requirements

This migration must follow the seam-audit rule established in `164` and exercised again in `169`:

### Write seams to inventory

- direct lower `register-operation-in!`
- extension runtime registration via `components/agent-session/src/psi/agent_session/extensions/runtime_fns.clj` `:register-deterministic-operation-fn`
- extension unload cleanup via `components/agent-session/src/psi/agent_session/extensions.clj` `unregister-extension-in!`
- extension reload/unregister-all cleanup via `components/agent-session/src/psi/agent_session/extensions.clj` `unregister-all-in!`
- any direct test/helper setup that currently assumes local `:operations` ownership

### Read/projection seams to inventory

- `get-operation-in`
- `all-operations-in`
- `operation-ids-in`
- `operation-count-in`
- `invoke-operation-in`
- workflow invoke runtime callers in `components/workflow-runtime/src/psi/workflow_runtime/statechart_runtime/step_execution.clj`
- extension/runtime reload and cleanup proofs in `components/agent-session/test/psi/agent_session/extensions_test.clj`
- extension introspection/projection seams in `components/agent-session/src/psi/agent_session/extensions.clj`, especially `operation-ids-in`, `extension-detail-in`, `extension-details-in`, and `summary-in`
- any integration tests that still assume adapter-local canonical storage shape rather than public registry behaviour

### Adapter-local state classification to record

For every remaining local field in the registry object, record:

- whether it is authoritative or derived
- whether it is canonical or merely projection/cache
- whether it can be rebuilt from shared storage

The expected answer after migration is:

- shared `root-state` entries are canonical for operations
- local registry-object state is limited to shared storage hosting and no longer carries ordering metadata
- no local canonical operation-entry map remains

### Higher-seam migration guards to require

Add at least one focused proof for each of these seams:

- extension runtime registration still makes the operation invokable through the registry object
- extension reload/unregister cleanup still removes stale operation ids so invoke lookup cannot outlive extension ownership
- extension introspection remains coherent after migration, with operation-facing projection surfaces reading an intentionally owned projection rather than a stale parallel canonical store

A migration is not complete just because lower component tests pass; higher seams must prove no stale local canonical reads survive.

## Extension introspection ownership after migration

Extension-facing introspection in `components/agent-session/src/psi/agent_session/extensions.clj` remains extension-registry-owned projection state rather than the canonical owner of deterministic operations.

After migration:

- shared `root-registry` inside `deterministic-operation-registry` is the only canonical owner for runtime deterministic-operation entries
- extension-registry `:extensions ... :operations` remains an upper projection used for extension-facing introspection such as `operation-ids-in`, `extension-detail-in`, `extension-details-in`, and `summary-in`
- that extension-local `:operations` map must stay derivable/coherent with runtime registration and cleanup flows, but it must not be treated as the invoke-time or duplicate-detection source of truth
- tests for extension registration and extension unload/reload must therefore prove both sides of the split: invoke/runtime behaviour comes from the migrated deterministic-operation registry, while extension introspection stays coherent as an explicitly synchronized projection

This task does not migrate extension introspection onto shared `root-registry`; it makes the ownership split explicit so the migration cannot accidentally leave extension-local `:operations` reads pretending to be canonical runtime storage.

## Design constraints

- Assume task `172` has already removed registration-order preservation; do not reintroduce it here.
- Do not expand `root-registry` with shared ordering semantics in this task.
- Do not preserve parallel canonical stores just to avoid adapter refactoring.
- Prefer lower result inspection to exception translation inside the migration.
- Preserve current deterministic-operation public behaviour unless a task artifact is updated first to make a deliberate behaviour change explicit.
- Keep the runtime-object API locally comprehensible; callers should not need to know about shared storage internals.

## Acceptance

This task is complete when:

- `deterministic-operation-registry` canonical entry storage is backed by `root-registry`
- the registry object no longer treats a local `:operations` map as canonical storage
- the registry object no longer carries adapter-local ordering state
- duplicate registration is powered by shared lower duplicate-result semantics
- duplicate registration still throws at the deterministic-operation public boundary, with the throw now deliberately translated from the lower duplicate result
- bulk cleanup by extension path is powered by shared lower owner cleanup semantics
- public listing helpers remain correct under the unordered contract introduced by task `172`
- invoke behaviour remains correctly adapter/runtime-owned and explicitly tested
- at least one extension-runtime registration seam and one extension reload/unregister seam prove migration coherence beyond the lower registry tests
- no stale direct reads of legacy canonical local storage remain above the adapter boundary
- task artifacts record the final authoritative-vs-derived state split inside the migrated registry object

## Non-goals

This task is not asking for:

- making deterministic-operation public APIs identical to workflow/tool/command registries
- moving ordering semantics into shared storage
- moving invoke execution into shared storage
- redesigning the workflow runtime around deterministic operations
- broad registry abstraction beyond the migration needed here
