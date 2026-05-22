# 172 deterministic-operation registration-order removal

## Intent

Remove registration-order preservation from `deterministic-operation-registry` before migrating it onto shared registry storage.

This task exists to eliminate a non-essential adapter contract that currently complicates task `171-deterministic-operation-registry-shared-storage-migration` without contributing to invoke correctness.

## Problem

Current `deterministic-operation-registry` preserves insertion order through local `:registration-order` state and exposes that order through:

- `operation-ids-in`
- `all-operations-in`
- `operation-count-in` coherence with those listing surfaces

But current evidence indicates that registration order is not used for:

- invoke lookup correctness
- workflow invoke execution semantics
- duplicate detection
- extension ownership enforcement

Instead, ordering is primarily a listing/projection contract and test expectation.

That makes it a poor semantic anchor for the next shared-storage migration:

- `root-registry` intentionally does not promise ordering semantics
- preserving adapter-owned order would require extra local state and lockstep repair logic
- the extra complexity would obscure the more important migration boundary: canonical shared keyed storage plus duplicate-rejecting insert semantics

Removing order preservation first should simplify the later migration by making deterministic-operation listing semantics match the shared substrate more closely.

## Scope

This task includes:

- removing deterministic-operation registration-order preservation as a public contract
- changing `deterministic-operation-registry` internals so operation listing/query surfaces no longer depend on local `:registration-order` state
- updating focused tests to prove the new non-ordered contract clearly
- updating any higher tests or task artifacts that still assume insertion order
- recording the semantic change so task `171` can be simplified around unordered shared storage adoption

This task may include updating `171` afterward so its design no longer treats local ordering metadata as part of the target shape.

## Out of scope

This task does not include:

- migrating deterministic-operation storage onto `root-registry`
- changing duplicate-registration rejection
- changing invoke lookup or invoke-miss throwing behaviour
- changing extension cleanup semantics beyond any order-related assertions
- redesigning workflow invoke runtime

## Desired outcome

At the end of this task:

- `deterministic-operation-registry` no longer preserves registration order
- the registry state no longer requires `:registration-order`
- `operation-ids-in` and `all-operations-in` have a clear non-ordered contract
- tests assert membership/count/coherence semantics rather than insertion order
- task `171` can target shared storage without carrying adapter-owned ordering state

## Concrete simplification target

The intended first-cut simplification is:

- reduce registry state from
  - `{:operations {...} :registration-order [...]}`
  - to `{:operations {...}}`
- make `operation-ids-in` derive its result directly from the currently registered operation map keys
- make `all-operations-in` derive its result directly from the currently registered operation map values or from key-driven lookup without promising order
- make `operation-count-in` count canonical registered operations directly rather than counting an ordering projection

The key simplification rule is:

- one authoritative local concept remains for storage: `:operations`
- there is no separate ordering state to maintain or repair during register or unregister

## Required behaviour changes

### Remove

- insertion-order guarantee for `operation-ids-in`
- insertion-order guarantee for `all-operations-in`
- local `:registration-order` as a required registry-state concept
- survivor-order assertions after extension cleanup or duplicate-rejection tests
- tests whose only purpose is to prove registration-order preservation

### Preserve

- strict operation-id validation and normalization
- duplicate registration remains rejected
- duplicate rejection still leaves the registered-operation set unchanged
- `get-operation-in` still returns the normalized stored operation or `nil`
- `operation-ids-in` still returns exactly the registered ids, without omission or duplication
- `all-operations-in` still returns exactly the registered operations, without omission or duplication
- `operation-count-in` still reflects the number of registered operations
- `unregister-operations-by-extension-in!` remains nil-tolerant/no-op when nothing matches
- `invoke-operation-in` still throws structured `ex-info` when the operation id is missing
- extension cleanup still removes stale operations so invoke lookup cannot outlive extension ownership

### Replace old ordering proofs with these proofs

Focused tests should prove:

- registering N distinct operations yields exactly N ids and N operations
- duplicate registration throws and does not change membership or count
- unregister-by-extension removes exactly the matching operations and preserves surviving membership
- unregistering a missing extension is a no-op on membership and count
- invoke lookup behaviour is unchanged

## Contract direction

After this task, the intended deterministic-operation listing contract is:

- `operation-ids-in` returns the currently registered ids with no ordering guarantee
- `all-operations-in` returns the currently registered operations with no ordering guarantee
- `operation-count-in` returns the current cardinality of the registered operation set
- callers and tests that care about membership should compare sets or sort explicitly at the assertion boundary
- callers and tests should not infer any relation between the order of `operation-ids-in` and the order of prior registrations
- if a future caller genuinely needs a deterministic presentation order, that should be introduced as an explicit projection concern rather than hidden registry storage state

## Known affected proof surfaces

This change should at minimum revisit:

- `components/deterministic-operation-registry/test/psi/deterministic_operation_registry/registry_test.clj`
  - replace order assertions with membership/count/coherence assertions
  - remove or rewrite `registration-order-remains-adapter-owned-test`
- `components/agent-session/test/psi/agent_session/extensions_test.clj`
  - keep cleanup/invoke-staleness assertions
  - relax any remaining ordered `operation-ids-in` expectations to unordered membership assertions
- task `171-deterministic-operation-registry-shared-storage-migration`
  - remove adapter-owned ordering from the intended migration shape once this task is complete

## Design constraints

- Do not replace removed insertion-order guarantees with a new accidental ordering guarantee unless it is named explicitly.
- Prefer simpler state over compatibility scaffolding.
- Update tests to assert the real behavioural contract, not incidental map/vector iteration details.
- Keep invoke behaviour unchanged.
- Keep this task separate from task `171`; this is a simplifying prerequisite, not the storage migration itself.

## Acceptance

This task is complete when:

- `deterministic-operation-registry` no longer maintains `:registration-order` state as part of its contract or implementation
- focused registry tests prove unordered membership/count/cleanup behaviour instead of insertion order
- the dedicated registration-order preservation test is removed or replaced with a non-order contract test
- higher seams that previously asserted order now assert only the preserved behaviour they actually need
- duplicate registration, cleanup, and invoke behaviour remain unchanged except for removed ordering guarantees
- task `171` can be updated to remove adapter-owned ordering from its migration target

## Non-goals

This task is not asking for:

- a shared-storage migration
- a new sorted listing contract
- workflow runtime changes
- changes to duplicate or missing-invoke behaviour
