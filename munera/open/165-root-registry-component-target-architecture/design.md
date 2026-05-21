# 165 root registry component target architecture

## Intent

Define the target architecture for a standalone registry component that can become the common lower owner for the current domain registries.

This task exists to turn the desired normalized registry semantics into an explicit component design before any migration or implementation work begins.

The design should describe a single reusable registry component whose semantics are intentionally stricter and more uniform than several of the current extracted registries.

## Context

Task `164-registry-semantics-unification-audit` captured the current registry behaviours and showed that many existing differences are likely accidental or compatibility-carried rather than essential.

The desired normalization direction is now clear:

- a single, long-lived, root-state storage supporting different registries
- identity that tracks extension ownership
- registry-specific entry validation
- no duplicates
- globally unique identity rather than per-owner identity
- unordered storage
- built-in support via an artificial `:built-in` extension
- lookup miss returns `nil`
- no invoke operation in the shared component
- targeted unregister by id
- clear by extension id
- global clear
- removal miss returns failure info rather than throwing
- re-register replaces
- operations return status and value
- stored entries include extension ownership and optional provenance
- strict validation and strict semantics

This task should capture that target as the source of truth for later implementation work.

## Scope

This task defines the target architecture of the standalone registry component.

It includes:

- the component's intended responsibilities
- its required state model inside root state
- its identity model
- its normalized operation semantics
- its validation model
- its result contract
- the extension/built-in ownership model
- the boundaries between shared registry behaviour and registry-specific policy
- the compatibility and migration implications for current registries

It may reference current registries only to explain migration constraints or why the new component needs particular semantics.

It does not include:

- implementing the component
- migrating any existing registry to use it
- preserving every current behavioural difference
- designing invoke/execution semantics
- redesigning registry-specific entry schemas beyond what is needed to define the shared contract

## Desired outcome

This task should leave behind a design that is precise enough to guide implementation of a new standalone registry component and later migration tasks.

That design should make clear:

1. what the shared component owns
2. what each consuming registry must supply
3. what the stored root-state shape is
4. how identity and uniqueness work
5. how register, lookup, unregister, clear-by-extension, and global-clear behave
6. what success and failure result shapes look like
7. how built-ins are represented through artificial extension ownership
8. which current registries are plausible adopters of this component
9. which current behaviours are intentionally dropped rather than preserved

## Target component

The target is a standalone registry component with these architectural properties:

- one long-lived root-state store
- multiple named registries hosted within that shared root-state area
- one shared semantic model for storage, identity, replacement, removal, and query
- registry-specific validation and normalization supplied at the registry boundary
- strict enforcement of unique identity within each registry
- no per-owner duplicate coexistence
- no ordering semantics in the shared storage contract
- no invoke or execution concerns in the shared component

## Required semantics

### 1. Storage model

The component stores registry data in root state and is long-lived.

The shared component owns the common storage substrate for multiple registries. Each registry has its own namespace within that root-state storage, but all use the same underlying storage model and operation semantics.

The design must define:

- the root-state location for registry storage
- how multiple registries are keyed within it
- how entries are stored within a registry
- how extension ownership is indexed or derived
- whether auxiliary indexes are stored explicitly or derived on write

### 2. Identity model

Each entry has a globally unique identity within its registry.

Identity must track extension ownership, but identity is not merely per-owner. The registry must not allow duplicate active entries for the same registry identity.

The design must define:

- the canonical identity shape
- how extension ownership participates in stored entry metadata
- whether identity is a single canonical id field or a derived composite
- how built-ins fit the same identity model through the artificial built-in extension id

### 3. Validation model

The shared component is strict, but registry-specific entry validation belongs to the consuming registry layer.

The design must define:

- what shared validation the common component performs
- what registry-specific validation hooks or inputs consumers must provide
- whether canonicalization happens before storage and at which boundary
- how invalid registration attempts fail and what failure info is returned

### 4. Duplicate and replacement semantics

The component does not allow duplicates.

Registering an entry for an existing identity replaces the previous entry for that identity. The result must report whether the operation inserted or replaced.

The design must define:

- what counts as identity equality
- whether replacement requires identity equality only or also extension consistency
- what happens if an entry attempts to change extension ownership for an existing identity
- the status information returned for insert vs replace

### 5. Query semantics

Lookup by id returns the stored entry value on hit and `nil` on miss.

The shared component does not define invoke or execution behaviour.

The design must define:

- supported read operations
- whether bulk read surfaces exist and what they return
- whether read results expose entries only or also metadata/status
- how strictness applies to querying an unknown registry

### 6. Removal semantics

The component supports:

- targeted unregister by id
- clear by extension id
- global clear

Removal miss must return failure info rather than throwing.

The design must define:

- result shape for successful targeted removal
- result shape for targeted removal miss
- result shape for clear-by-extension when nothing matched
- result shape for global clear
- whether clear operations report removed values, counts, identities, or summaries

### 7. Result contract

Operations return status and value.

The design must define a uniform result contract across operations, including:

- success/failure status
- operation kind
- registry id
- affected entry or entries when relevant
- replaced/removed/miss information when relevant
- counts or summaries when relevant

The result contract should be explicit enough that callers do not need to infer outcomes indirectly from mutated state.

### 8. Ownership and provenance

Stored entries must carry extension ownership and may carry provenance.

Built-ins use a distinguished artificial extension id rather than separate built-in storage semantics.

The design must define:

- required ownership field(s)
- optional provenance field(s)
- whether ownership/provenance are part of canonical stored entries or parallel metadata
- what invariants apply to built-in ownership

## Boundaries

The shared component should own:

- root-state storage shape
- identity uniqueness enforcement
- strict shared validation of common fields and operations
- register / lookup / unregister / clear semantics
- result contract semantics
- extension ownership indexing or equivalent support for clear-by-extension

Consuming registries should own:

- entry-specific schema validation
- registry-specific canonicalization
- any registry-specific helper APIs
- any higher-level behaviours such as execution, dispatch, prompt ordering, or workflow semantics

## Adoption intent

This task should identify which current registries are good candidates to move onto this component first, and which should require adapters or remain partially separate.

At minimum, the design should explicitly assess:

- `tool-registry`
- `command-registry`
- `skill-registry`
- `prompt-registry`
- `workflow-registry`
- `deterministic-operation-registry`

The goal is not to force all of them into one identical public API. The goal is to define the shared lower component that could sit beneath some or all of them, with adapters where necessary.

## Target architecture decisions

### Root-state storage model

The shared component owns one root-state area for all hosted registries.

A registry is identified by a registry id supplied by the consuming layer. Root state stores a map of registry id to registry state.

Each registry state stores:

- `:entries-by-id` — canonical id to canonical stored entry
- `:ids-by-extension` — extension id to set of canonical ids owned by that extension
- optional registry-local metadata only when needed by the shared component for invariant maintenance

The shared component does not store ordering. Any caller that needs ordered presentation must derive it outside the shared component.

The `:ids-by-extension` index is stored explicitly rather than derived on clear operations. The component owns keeping `:entries-by-id` and `:ids-by-extension` in sync on every successful mutation.

Unknown registries are not implicitly created by read operations. They become present only through explicit registry initialization by the consuming layer or the first successful registration, depending on implementation choice. Shared semantics must be the same either way: an absent registry is treated as unknown for mutation operations and as empty for lookup-by-id only.

### Canonical stored entry model

Each stored entry is a canonical map containing:

- `:id` — canonical registry identity, unique within one registry
- `:extension-id` — owning extension id, with built-ins stored as `:built-in`
- `:value` — canonical registry-specific payload after consumer-side normalization
- optional `:provenance` — caller-supplied provenance metadata

The shared component may require these fields directly or an equivalent canonical shape, but ownership and identity are part of the stored entry contract rather than detached side metadata.

### Identity and ownership rules

Identity equality is equality of the canonical `:id` within a single registry.

Extension ownership is tracked on every stored entry but is not part of identity equality. This means a registry id names one active entry slot regardless of owner.

Re-register is replacement only when the incoming entry keeps the same owner as the currently stored entry. An attempt to replace an existing id with a different `:extension-id` is rejected by the shared component as an ownership-conflict failure rather than silently transferring ownership.

This rule keeps ownership transitions explicit. If a caller needs to move an id between extensions, it must first unregister the old entry or clear the old owner and then register the new owner entry in a separate step.

Built-ins follow the same rule through the distinguished owner `:built-in`.

### Validation boundary

The consuming registry layer owns registry-specific schema validation and canonicalization before calling the shared component.

The shared component owns only common validation:

- registry id is recognized for mutating operations
- canonical entry contains required shared fields
- `:id` is present and non-nil
- `:extension-id` is present and non-nil
- ownership/index invariants are maintained

If consumer-side validation fails, the consumer should not call into the shared component. If shared validation fails, the shared component returns a failure result with explicit failure kind and does not mutate state.

### Operation set and semantics

The shared component owns these operations:

- register entry
- lookup by id
- list entries for a registry
- unregister by id
- clear by extension id
- clear registry

The shared component does not own invoke, dispatch, ordering, filtering, or execution semantics.

#### Register

Register takes a registry id and one canonical entry.

Outcomes:

- insert success when the id is not present
- replace success when the id is present and the owner matches
- ownership-conflict failure when the id is present and the owner differs
- validation failure when required shared fields are invalid
- unknown-registry failure when the registry id is not recognized for mutation

A successful replace returns both the previous stored entry and the new stored entry.

#### Lookup by id

Lookup takes a registry id and canonical id.

Outcomes:

- hit success with the stored entry as value
- miss success with `nil` value when the id is absent
- unknown registry also returns success with `nil` value

Lookup is the only operation that treats an unknown registry the same as an empty registry. This preserves the desired nil-miss read semantics without allowing silent mutation into an undeclared registry.

#### List entries

List returns the unordered collection of stored entries for one registry.

Outcomes:

- success with an unordered collection of entries for known registries
- unknown-registry failure

Bulk read surfaces beyond full listing are intentionally out of scope for the shared component.

#### Unregister by id

Unregister takes a registry id and canonical id.

Outcomes:

- removed success with the removed stored entry
- miss failure when the id is absent
- unknown-registry failure

The component must update both storage indexes on success.

#### Clear by extension id

Clear by extension takes a registry id and extension id.

Outcomes:

- removed success with removed-count and removed-ids when one or more entries matched
- miss failure with removed-count `0` when no entries matched
- unknown-registry failure

Returning ids rather than full removed entries is the default summary contract for bulk removal. A later implementation may optionally include removed entries as an additional field, but ids and count are the minimum contract.

#### Clear registry

Clear registry removes all entries from one registry.

Outcomes:

- removed success with removed-count and removed-ids when the registry contained entries
- no-op success with removed-count `0` and empty removed-ids when the registry was already empty
- unknown-registry failure

This differs from clear-by-extension: global clear is considered an idempotent administrative operation, so clearing an already empty known registry succeeds rather than fails.

### Uniform result contract

Every operation returns an explicit result map. The exact field set may vary by operation, but the common contract includes:

- `:ok?` — boolean success flag
- `:status` — normalized status keyword
- `:operation` — operation keyword such as `:register` or `:unregister`
- `:registry-id` — target registry id
- `:value` — primary returned value when applicable
- `:failure-kind` — present on failures
- `:message` — terse human-oriented explanation when useful

Operation-specific fields include:

- register: `:change` (`:insert` or `:replace`), `:entry`, optional `:previous-entry`
- lookup: `:value` as stored entry or `nil`, `:change` `:hit` or `:miss`
- list: `:entries`, `:count`
- unregister: `:entry` on success, `:change` `:removed` or `:miss`
- clear-by-extension: `:extension-id`, `:removed-count`, `:removed-ids`
- clear-registry: `:removed-count`, `:removed-ids`

Normalized failure kinds should include at least:

- `:unknown-registry`
- `:invalid-entry`
- `:ownership-conflict`
- `:not-found`

Callers should be able to distinguish insert vs replace, hit vs miss, and removed vs failed directly from the result map rather than by re-reading state.

### Registry adoption assessment

The current registries fit the shared component as follows:

- `tool-registry` — direct adopter. Core semantics are id-keyed registration and lookup; invoke concerns live above the shared storage layer.
- `command-registry` — direct adopter. Command identity and ownership fit the normalized id-keyed storage model.
- `skill-registry` — direct adopter. Skill entries are stable id-keyed definitions with no shared-component ordering needs.
- `prompt-registry` — adopter with adapter. Prompt contribution ordering/composition semantics should stay above the shared component, but prompt definitions themselves can use the shared registry substrate.
- `workflow-registry` — direct adopter for workflow definitions. Any higher-level invocation or execution orchestration remains outside the shared component.
- `deterministic-operation-registry` — adopter with adapter. Operation definitions fit id-keyed storage, but compatibility surfaces around invocation helpers or legacy registration APIs may require an adapter layer during migration.

Anything that fundamentally requires ordered shared storage or invoke-as-registry semantics is intentionally out of scope for direct adoption unless those behaviours are first split into a higher layer.

### Intentionally dropped behaviours

The target architecture intentionally does not preserve:

- per-owner duplicate coexistence for the same registry id
- silent owner transfer on re-register
- unknown-registry writes that create or mutate undeclared registries implicitly
- invoke-oriented behaviour inside the shared component
- ordering as a storage guarantee
- mutable registry-object identity as the primary abstraction
- caller inference of mutation outcome from post-state instead of explicit results

## Acceptance

This task is complete when `design.md` clearly defines:

- the target standalone component and its purpose
- the root-state storage model for multiple registries
- the canonical identity and ownership model
- the shared operation set and semantics
- the strict result contract shape expectations
- the built-in-as-`:built-in` ownership model
- the intended boundary between shared semantics and registry-specific validation
- the expected migration/adoption relationship to the current registries
- the intentionally dropped current behaviours that will not be preserved in the new target architecture

## Non-goals

This task is not asking for:

- compatibility shims by default
- preserving accidental ordering behaviour
- preserving per-owner duplicate coexistence
- preserving invoke-oriented registry APIs
- preserving mutable registry-object APIs where the new architecture does not need them
- implementation planning or code changes yet
